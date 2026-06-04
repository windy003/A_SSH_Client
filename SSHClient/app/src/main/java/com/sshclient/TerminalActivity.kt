package com.sshclient

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.content.Context
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.Toast
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

    // 浏览模式:开启时点击终端不弹出输入法,方便滚动查看内容
    private var browseMode = false

    // 当前 PTY 尺寸(列/行),根据 TextView 实际可显示区域计算
    private var ptyCols = LOGICAL_COLS
    private var ptyRows = 24

    // 调试:保存最近收到的原始字节
    private val rawHistory = StringBuilder()
    private val rawHistoryMaxLen = 4096

    // 闪烁光标
    private var cursorOn = true
    private val cursorHandler = Handler(Looper.getMainLooper())

    private val cursorRunnable = object : Runnable {
        override fun run() {
            cursorOn = !cursorOn
            binding.tvOutput.cursorVisible = cursorOn && ansiProcessor.cursorVisibleByApp
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
        // 固定的逻辑终端列宽:远端始终按此列数排版,与屏幕宽度/缩放无关。
        private const val LOGICAL_COLS = 120
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityTerminalBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        binding.scrollView.setBackgroundColor(Color.WHITE)
        binding.tvOutput.setBackgroundColor(Color.WHITE)

        fontSize = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getFloat(PREF_FONT_SIZE, DEFAULT_FONT_SIZE)

        binding.tvOutput.setFontSizeSp(fontSize)

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
     * 计算 PTY 行数(按可视高度),列数固定为 [LOGICAL_COLS]。
     *
     * 终端列宽与屏幕/缩放解耦:远端始终按固定列数排版,内容完整不被挤压;
     * 超出屏幕宽度的部分通过 HorizontalScrollView 横向滚动查看,音量键缩放字号。
     */
    private fun recomputeTerminalSize() {
        val lineH = binding.tvOutput.lineHeight
        val sv = binding.scrollView
        val h = sv.height - sv.paddingTop - sv.paddingBottom
        if (h <= 0 || lineH <= 0f) return

        val cols = LOGICAL_COLS
        val rows = (h / lineH).toInt().coerceAtLeast(8)

        if (cols == ptyCols && rows == ptyRows) return
        ptyCols = cols
        ptyRows = rows
        ansiProcessor.resize(cols, rows)
        sshManager.resize(cols, rows)
        refreshDisplay()
    }

    // ── 光标 ─────────────────────────────────────────────────────────────────

    private fun refreshDisplay() {
        val ssb = ansiProcessor.getSpannable()
        binding.tvOutput.setContent(ssb, ansiProcessor.lastCursorIndex)
        binding.tvOutput.cursorVisible = cursorOn && ansiProcessor.cursorVisibleByApp
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
        binding.tvOutput.setFontSizeSp(fontSize)

        getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putFloat(PREF_FONT_SIZE, fontSize).apply()

        // 字号变化后重新计算行数并通知远端(列数固定)
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
        binding.scrollView.setOnClickListener { focusTerminal() }
        binding.tvOutput.setOnClickListener   { focusTerminal() }
        binding.terminalInput.onInput = { data -> sendRaw(data) }
    }

    /** 点击终端时唤起输入法;浏览模式下不弹出键盘,仅供滚动查看。 */
    private fun focusTerminal() {
        if (browseMode) return
        binding.terminalInput.showKeyboard()
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

    // ── 选项菜单(右上角三点) ─────────────────────────────────────────────────

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.terminal_menu, menu)
        menu.findItem(R.id.action_browse_mode)?.isChecked = browseMode
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.action_browse_mode -> {
                browseMode = !browseMode
                item.isChecked = browseMode
                if (browseMode) {
                    // 进入浏览模式时收起键盘,让出更多屏幕空间
                    binding.terminalInput.hideKeyboard()
                    Toast.makeText(this, R.string.browse_mode_on, Toast.LENGTH_SHORT).show()
                } else {
                    // 退出浏览模式时立即唤起键盘,便于继续输入
                    binding.terminalInput.showKeyboard()
                    Toast.makeText(this, R.string.browse_mode_off, Toast.LENGTH_SHORT).show()
                }
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
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
