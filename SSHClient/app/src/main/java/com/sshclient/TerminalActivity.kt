package com.sshclient

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.content.Context
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.util.TypedValue
import android.view.KeyEvent
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.sshclient.data.AppDatabase
import com.sshclient.databinding.ActivityTerminalBinding
import com.sshclient.ssh.AnsiProcessor
import com.sshclient.ssh.SshManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class TerminalActivity : AppCompatActivity() {

    private lateinit var binding: ActivityTerminalBinding
    private val sshManager = SshManager()
    private val ansiProcessor = AnsiProcessor()

    // 字号状态
    private var fontSize = DEFAULT_FONT_SIZE
    private var hideSizeIndicatorRunnable: Runnable? = null

    // 当前 PTY 尺寸(列/行),根据 TextView 实际可显示区域计算
    private var ptyCols = 80
    private var ptyRows = 24

    // 调试:保存最近收到的原始字节
    private val rawHistory = StringBuilder()
    private val rawHistoryMaxLen = 4096

    // 闪烁光标
    private var cursorOn = true
    private val cursorHandler = Handler(Looper.getMainLooper())
    private var currentSpannable: SpannableStringBuilder? = null
    private val hiddenCursorSpan = ForegroundColorSpan(Color.TRANSPARENT)

    private val cursorRunnable = object : Runnable {
        override fun run() {
            cursorOn = !cursorOn
            refreshCursorOnly()
            cursorHandler.postDelayed(this, CURSOR_BLINK_MS)
        }
    }

    companion object {
        const val EXTRA_CONNECTION_ID = "extra_connection_id"
        private const val MAX_OUTPUT_CHARS = 80_000
        private const val DEFAULT_FONT_SIZE = 11f
        private const val MIN_FONT_SIZE = 6f
        private const val MAX_FONT_SIZE = 32f
        private const val FONT_STEP = 1f
        private const val PREFS_NAME = "terminal_prefs"
        private const val PREF_FONT_SIZE = "font_size"
        private const val CURSOR_BLINK_MS = 500L
        private const val CURSOR_CHAR = "▏"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityTerminalBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        binding.scrollView.setBackgroundColor(Color.WHITE)
        binding.tvOutput.setBackgroundColor(Color.WHITE)
        binding.tvOutput.setTextColor(Color.BLACK)

        fontSize = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getFloat(PREF_FONT_SIZE, DEFAULT_FONT_SIZE)

        binding.tvOutput.typeface = Typeface.MONOSPACE
        binding.tvOutput.textSize = fontSize

        // 每当 TextView 重新布局时滚动到底部
        binding.tvOutput.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
            binding.scrollView.post { binding.scrollView.fullScroll(View.FOCUS_DOWN) }
        }

        // 等容器尺寸确定后,根据实际宽高计算 PTY 列/行
        binding.tvOutput.post { recomputeTerminalSize() }
        binding.scrollView.addOnLayoutChangeListener { _, _, _, _, _, oldL, oldT, oldR, oldB ->
            if (oldR - oldL == 0 || oldB - oldT == 0) recomputeTerminalSize()
        }

        setupTerminalInput()
        setupSpecialKeys()

        val connectionId = intent.getLongExtra(EXTRA_CONNECTION_ID, -1L)
        if (connectionId == -1L) { finish(); return }

        lifecycleScope.launch {
            val connection = AppDatabase.getInstance(this@TerminalActivity)
                .connectionDao().getById(connectionId) ?: run { finish(); return@launch }

            supportActionBar?.title = "${connection.username}@${connection.host}"
            appendOutput("Connecting to ${connection.host}:${connection.port}…\r\n", Color.parseColor("#CC8800"))

            // 确保在连接之前已经测量过 PTY 尺寸(否则使用默认 80x24)
            recomputeTerminalSize()

            try {
                sshManager.connect(
                    host = connection.host,
                    port = connection.port,
                    username = connection.username,
                    password = connection.password,
                    privateKey = connection.privateKey,
                    initialCols = ptyCols,
                    initialRows = ptyRows
                )
                ansiProcessor.reset()
                ansiProcessor.resize(ptyCols, ptyRows)
                appendOutput("Connected. Tap screen to type.\r\n", Color.parseColor("#008800"))
                startReading()
                binding.terminalInput.showKeyboard()
            } catch (e: Exception) {
                appendOutput("Connection failed: ${e.message}\r\n", Color.parseColor("#CC0000"))
            }
        }
    }

    override fun onResume() {
        super.onResume()
        cursorHandler.postDelayed(cursorRunnable, CURSOR_BLINK_MS)
    }

    override fun onPause() {
        super.onPause()
        cursorHandler.removeCallbacks(cursorRunnable)
    }

    // ── 终端尺寸 ──────────────────────────────────────────────────────────────

    /**
     * 根据 TextView 实际宽高与当前字体度量,计算可显示的列/行数,
     * 同步给 AnsiProcessor 和远端 PTY。
     */
    private fun recomputeTerminalSize() {
        val tv = binding.tvOutput
        val w = tv.width - tv.paddingLeft - tv.paddingRight
        val h = tv.height - tv.paddingTop - tv.paddingBottom
        if (w <= 0 || h <= 0) return

        val paint = tv.paint
        // 取 ASCII 与 box-drawing 字符的最大宽度作为单元宽度:
        //  - 只用 M 会让 cols 偏大,box-drawing 字符渲染累计起来超出屏幕,
        //    导致行末的 ╮ ╯ | 被 TextView 自动换行,框右边整列消失。
        //  - 只用 ─ 又会让 cols 太小,Claude 画出的框比屏幕窄一大块。
        // 取最大值再不做额外余量是最稳妥的折中。
        val charW = maxOf(
            paint.measureText("M"),
            paint.measureText("─")
        ).coerceAtLeast(1f)
        val fm = paint.fontMetrics
        val lineH = (fm.bottom - fm.top + tv.lineSpacingExtra).coerceAtLeast(1f)

        val cols = (w / charW).toInt().coerceAtLeast(20)
        val rows = (h / lineH).toInt().coerceAtLeast(8)

        if (cols == ptyCols && rows == ptyRows) return
        ptyCols = cols
        ptyRows = rows
        ansiProcessor.resize(cols, rows)
        sshManager.resize(cols, rows)
        refreshDisplay()
    }

    // ── 光标 ─────────────────────────────────────────────────────────────────

    private fun refreshCursorOnly() {
        val ssb = currentSpannable ?: return
        val textEnd = ssb.length - CURSOR_CHAR.length
        if (textEnd < 0) return
        ssb.removeSpan(hiddenCursorSpan)
        if (!cursorOn) {
            ssb.setSpan(hiddenCursorSpan, textEnd, ssb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
    }

    private fun refreshDisplay() {
        val ssb = ansiProcessor.getSpannable()
        ssb.append(CURSOR_CHAR)
        currentSpannable = ssb
        if (!cursorOn) {
            ssb.setSpan(hiddenCursorSpan, ssb.length - CURSOR_CHAR.length, ssb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        binding.tvOutput.text = ssb
    }

    // ── 通过音量键控制字号 ────────────────────────────────────────────────────

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN) {
            when (event.keyCode) {
                KeyEvent.KEYCODE_VOLUME_UP -> { adjustFontSize(+FONT_STEP); return true }
                KeyEvent.KEYCODE_VOLUME_DOWN -> { adjustFontSize(-FONT_STEP); return true }
            }
        }
        return super.dispatchKeyEvent(event)
    }

    private fun adjustFontSize(delta: Float) {
        fontSize = (fontSize + delta).coerceIn(MIN_FONT_SIZE, MAX_FONT_SIZE)
        binding.tvOutput.textSize = fontSize

        getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putFloat(PREF_FONT_SIZE, fontSize).apply()

        // 字号变化后重新计算列/行并通知远端
        binding.tvOutput.post { recomputeTerminalSize() }

        showSizeIndicator()
    }

    private fun showSizeIndicator() {
        val label = binding.tvFontSize
        label.text = "${fontSize.toInt()} sp  ${ptyCols}x${ptyRows}"
        label.alpha = 1f
        label.visibility = View.VISIBLE

        hideSizeIndicatorRunnable?.let { label.removeCallbacks(it) }
        hideSizeIndicatorRunnable = Runnable {
            label.animate()
                .alpha(0f)
                .setDuration(300)
                .setListener(object : AnimatorListenerAdapter() {
                    override fun onAnimationEnd(animation: Animator) {
                        label.visibility = View.GONE
                    }
                })
                .start()
        }
        label.postDelayed(hideSizeIndicatorRunnable!!, 1200)
    }

    // ── 终端输入 ──────────────────────────────────────────────────────────────

    private fun setupTerminalInput() {
        binding.scrollView.setOnClickListener { binding.terminalInput.showKeyboard() }
        binding.tvOutput.setOnClickListener   { binding.terminalInput.showKeyboard() }
        binding.terminalInput.onInput = { data -> sendRaw(data) }
    }

    private fun setupSpecialKeys() {
        binding.btnEsc.setOnClickListener   { sendRaw("\u001B") }
        binding.btnTab.setOnClickListener   { sendRaw("\t") }
        binding.btnTab.setOnLongClickListener { dumpRawHistory(); true }
        binding.btnCtrlC.setOnClickListener { sendRaw("\u0003") }
        binding.btnCtrlD.setOnClickListener { sendRaw("\u0004") }
        binding.btnCtrlZ.setOnClickListener { sendRaw("\u001A") }
        binding.btnUp.setOnClickListener    { sendRaw("\u001B[A") }
        binding.btnDown.setOnClickListener  { sendRaw("\u001B[B") }
        binding.btnLeft.setOnClickListener  { sendRaw("\u001B[D") }
        binding.btnRight.setOnClickListener { sendRaw("\u001B[C") }
        binding.btnHome.setOnClickListener  { sendRaw("\u001B[H") }
        binding.btnEnd.setOnClickListener   { sendRaw("\u001B[F") }
        binding.btnPgUp.setOnClickListener  { sendRaw("\u001B[5~") }
        binding.btnPgDn.setOnClickListener  { sendRaw("\u001B[6~") }
    }

    private fun sendRaw(data: String) {
        if (!sshManager.isConnected()) return
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                sshManager.outputStream?.write(data.toByteArray(Charsets.UTF_8))
                sshManager.outputStream?.flush()
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    appendOutput("\r\nSend error: ${e.message}\r\n", Color.parseColor("#CC0000"))
                }
            }
        }
    }

    private fun startReading() {
        lifecycleScope.launch(Dispatchers.IO) {
            val buffer = ByteArray(4096)
            try {
                while (sshManager.isConnected()) {
                    val inputStream = sshManager.inputStream ?: break
                    val available = inputStream.available()
                    if (available > 0) {
                        val n = inputStream.read(buffer, 0, minOf(available, buffer.size))
                        if (n > 0) {
                            val text = String(buffer, 0, n, Charsets.UTF_8)
                            recordRaw(text)
                            withContext(Dispatchers.Main) { appendOutput(text) }
                        }
                    } else {
                        delay(20)
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    appendOutput("\r\nDisconnected: ${e.message}\r\n", Color.parseColor("#CC0000"))
                }
            }
        }
    }

    private fun appendOutput(raw: String, color: Int = 0) {
        if (color == 0) {
            ansiProcessor.process(raw)
        } else {
            ansiProcessor.appendDirect(raw)
        }
        ansiProcessor.trimToLength(MAX_OUTPUT_CHARS)
        cursorOn = true
        refreshDisplay()
    }

    // ── 调试:原始字节记录与转储 ──────────────────────────────────────────────

    @Synchronized
    private fun recordRaw(text: String) {
        rawHistory.append(text)
        if (rawHistory.length > rawHistoryMaxLen) {
            rawHistory.delete(0, rawHistory.length - rawHistoryMaxLen)
        }
    }

    @Synchronized
    private fun dumpRawHistory() {
        val snapshot = rawHistory.toString()
        rawHistory.clear()
        val sb = StringBuilder("\n[RAW BYTES, len=${snapshot.length}]\n")
        for (c in snapshot) {
            when {
                c == '\u001B' -> sb.append("\\e")
                c == '\r'     -> sb.append("\\r")
                c == '\n'     -> sb.append("\\n\n")
                c == '\t'     -> sb.append("\\t")
                c == '\b'     -> sb.append("\\b")
                c == '\u0007' -> sb.append("\\a")
                c < ' '  -> sb.append(String.format("\\x%02x", c.code))
                c.code > 0x7E -> sb.append(String.format("\\u%04x", c.code))
                else          -> sb.append(c)
            }
        }
        sb.append("\n[/RAW]\n")
        appendOutput(sb.toString(), Color.parseColor("#0066CC"))
    }

    override fun onSupportNavigateUp(): Boolean {
        onBackPressedDispatcher.onBackPressed()
        return true
    }

    override fun onDestroy() {
        super.onDestroy()
        sshManager.disconnect()
    }
}
