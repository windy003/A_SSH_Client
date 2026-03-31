package com.sshclient

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.os.Handler
import android.os.Looper
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

    // Font size state
    private var fontSize = DEFAULT_FONT_SIZE
    private var hideSizeIndicatorRunnable: Runnable? = null

    // Blinking cursor
    private var cursorOn = true
    private val cursorHandler = Handler(Looper.getMainLooper())
    private val cursorRunnable = object : Runnable {
        override fun run() {
            cursorOn = !cursorOn
            refreshDisplay()
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

        // Restore saved font size
        fontSize = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getFloat(PREF_FONT_SIZE, DEFAULT_FONT_SIZE)

        binding.tvOutput.typeface = Typeface.MONOSPACE
        binding.tvOutput.textSize = fontSize

        // Scroll to bottom whenever the text view is re-laid out (new content added)
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

    // ── Cursor ───────────────────────────────────────────────────────────────

    /** Update the TextView with current buffer + blinking cursor. */
    private fun refreshDisplay() {
        binding.tvOutput.text = if (cursorOn) ansiProcessor.getText() + CURSOR_CHAR
                                else          ansiProcessor.getText()
    }

    // ── Volume key font size control ─────────────────────────────────────────

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

        // Persist
        getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putFloat(PREF_FONT_SIZE, fontSize).apply()

        showSizeIndicator()
    }

    private fun showSizeIndicator() {
        val label = binding.tvFontSize
        label.text = "${fontSize.toInt()} sp"
        label.alpha = 1f
        label.visibility = View.VISIBLE

        // Cancel any pending hide
        hideSizeIndicatorRunnable?.let { label.removeCallbacks(it) }

        // Fade out after 1.2 s
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

    // ── Terminal input ────────────────────────────────────────────────────────

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
        // SSH output: run through ANSI processor (handles \b, \r, cursor movement, etc.)
        // Status messages (color != 0): append directly without ANSI processing
        if (color == 0) {
            ansiProcessor.process(raw)
        } else {
            ansiProcessor.appendDirect(raw)
        }
        ansiProcessor.trimToLength(MAX_OUTPUT_CHARS)
        // Reset cursor to visible state on new content so it's always shown right after output
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
