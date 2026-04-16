package com.sshclient

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
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

    // 闪烁光标
    private var cursorOn = true
    private val cursorHandler = Handler(Looper.getMainLooper())
    private var currentSpannable: SpannableStringBuilder? = null
    // 复用的 span 对象 —— 在可见和透明之间切换,避免文本长度变化
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
        private const val DEFAULT_FONT_SIZE = 13f
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

        // 恢复已保存的字号
        fontSize = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getFloat(PREF_FONT_SIZE, DEFAULT_FONT_SIZE)

        binding.tvOutput.typeface = Typeface.MONOSPACE
        binding.tvOutput.textSize = fontSize

        // 每当 TextView 重新布局时(有新内容加入)滚动到底部
        binding.tvOutput.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
            binding.scrollView.post { binding.scrollView.fullScroll(View.FOCUS_DOWN) }
        }

        setupTerminalInput()
        setupSpecialKeys()

        val connectionId = intent.getLongExtra(EXTRA_CONNECTION_ID, -1L)
        if (connectionId == -1L) { finish(); return }

        lifecycleScope.launch {
            val connection = AppDatabase.getInstance(this@TerminalActivity)
                .connectionDao().getById(connectionId) ?: run { finish(); return@launch }

            supportActionBar?.title = "${connection.username}@${connection.host}"
            appendOutput("Connecting to ${connection.host}:${connection.port}…\r\n", Color.YELLOW)

            try {
                sshManager.connect(
                    host = connection.host,
                    port = connection.port,
                    username = connection.username,
                    password = connection.password,
                    privateKey = connection.privateKey
                )
                ansiProcessor.reset()
                appendOutput("Connected. Tap screen to type.\r\n", Color.GREEN)
                startReading()
                binding.terminalInput.showKeyboard()
            } catch (e: Exception) {
                appendOutput("Connection failed: ${e.message}\r\n", Color.RED)
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

    // ── 光标 ─────────────────────────────────────────────────────────────────

    /**
     * 仅通过在已有的 SpannableStringBuilder 上修改 span 颜色来切换光标可见性。
     * 这样可以避免文本长度变化,否则会触发整次布局计算,导致整个视图抖动。
     */
    private fun refreshCursorOnly() {
        val ssb = currentSpannable ?: return
        val textEnd = ssb.length - CURSOR_CHAR.length
        if (textEnd < 0) return
        ssb.removeSpan(hiddenCursorSpan)
        if (!cursorOn) {
            ssb.setSpan(hiddenCursorSpan, textEnd, ssb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
    }

    /** 重建完整显示(在实际内容变化时调用)。 */
    private fun refreshDisplay() {
        val text = ansiProcessor.getText()
        val ssb = SpannableStringBuilder(text + CURSOR_CHAR)
        currentSpannable = ssb
        if (!cursorOn) {
            ssb.setSpan(hiddenCursorSpan, text.length, ssb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        binding.tvOutput.text = ssb
    }

    // ── 通过音量键控制字号 ────────────────────────────────────────────────────

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN) {
            when (event.keyCode) {
                KeyEvent.KEYCODE_VOLUME_UP -> {
                    adjustFontSize(+FONT_STEP)
                    return true
                }
                KeyEvent.KEYCODE_VOLUME_DOWN -> {
                    adjustFontSize(-FONT_STEP)
                    return true
                }
            }
        }
        return super.dispatchKeyEvent(event)
    }

    private fun adjustFontSize(delta: Float) {
        fontSize = (fontSize + delta).coerceIn(MIN_FONT_SIZE, MAX_FONT_SIZE)
        binding.tvOutput.textSize = fontSize

        // 持久化保存
        getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putFloat(PREF_FONT_SIZE, fontSize).apply()

        showSizeIndicator()
    }

    private fun showSizeIndicator() {
        val label = binding.tvFontSize
        label.text = "${fontSize.toInt()} sp"
        label.alpha = 1f
        label.visibility = View.VISIBLE

        // 取消任何待执行的隐藏任务
        hideSizeIndicatorRunnable?.let { label.removeCallbacks(it) }

        // 1.2 秒后淡出
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
        binding.btnEsc.setOnClickListener   { sendRaw("\u001b") }
        binding.btnTab.setOnClickListener   { sendRaw("\t") }
        binding.btnCtrlC.setOnClickListener { sendRaw("\u0003") }
        binding.btnCtrlD.setOnClickListener { sendRaw("\u0004") }
        binding.btnCtrlZ.setOnClickListener { sendRaw("\u001a") }
        binding.btnUp.setOnClickListener    { sendRaw("\u001b[A") }
        binding.btnDown.setOnClickListener  { sendRaw("\u001b[B") }
        binding.btnLeft.setOnClickListener  { sendRaw("\u001b[D") }
        binding.btnRight.setOnClickListener { sendRaw("\u001b[C") }
        binding.btnHome.setOnClickListener  { sendRaw("\u001b[H") }
        binding.btnEnd.setOnClickListener   { sendRaw("\u001b[F") }
        binding.btnPgUp.setOnClickListener  { sendRaw("\u001b[5~") }
        binding.btnPgDn.setOnClickListener  { sendRaw("\u001b[6~") }
    }

    private fun sendRaw(data: String) {
        if (!sshManager.isConnected()) return
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                sshManager.outputStream?.write(data.toByteArray(Charsets.UTF_8))
                sshManager.outputStream?.flush()
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    appendOutput("\r\nSend error: ${e.message}\r\n", Color.RED)
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
                            withContext(Dispatchers.Main) { appendOutput(text) }
                        }
                    } else {
                        delay(20)
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    appendOutput("\r\nDisconnected: ${e.message}\r\n", Color.RED)
                }
            }
        }
    }

    private fun appendOutput(raw: String, color: Int = 0) {
        // SSH 输出:交由 ANSI 处理器处理(处理 \b、\r、光标移动等)
        // 状态消息(color != 0):直接追加,不经过 ANSI 处理
        if (color == 0) {
            ansiProcessor.process(raw)
        } else {
            ansiProcessor.appendDirect(raw)
        }
        ansiProcessor.trimToLength(MAX_OUTPUT_CHARS)
        // 新内容到来时将光标重置为可见状态,使其在输出后始终显示
        cursorOn = true
        refreshDisplay()
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
