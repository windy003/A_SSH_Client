package com.sshclient

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Color
import android.graphics.Rect
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.ActionMode
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
import com.sshclient.transfer.Zm
import com.sshclient.transfer.ZmodemTransferManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.InputStream

class TerminalActivity : AppCompatActivity() {

    private lateinit var binding: ActivityTerminalBinding
    private val sshManager = SshManager()
    private val ansiProcessor = AnsiProcessor()
    private lateinit var transferManager: ZmodemTransferManager

    // 字号状态
    private var fontSize = DEFAULT_FONT_SIZE
    private var hideSizeIndicatorRunnable: Runnable? = null

    // 浏览模式:开启时点击终端不弹出输入法,方便滚动查看内容
    private var browseMode = false

    // 长按选择文本时弹出的悬浮菜单(复制 / 全选)
    private var selectionActionMode: ActionMode? = null

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
        // 选择文本时悬浮菜单的菜单项 id
        private const val MENU_COPY = 1
        private const val MENU_SELECT_ALL = 2
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

        // 每当 TextView 重新布局时滚动到底部(正在选文本时不打扰用户)
        binding.tvOutput.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
            if (binding.tvOutput.hasSelection) return@addOnLayoutChangeListener
            binding.scrollView.post { binding.scrollView.fullScroll(View.FOCUS_DOWN) }
        }

        // 等容器尺寸确定后,根据实际宽高计算 PTY 列/行
        binding.tvOutput.post { recomputeTerminalSize() }
        binding.scrollView.addOnLayoutChangeListener { _, _, _, _, _, oldL, oldT, oldR, oldB ->
            if (oldR - oldL == 0 || oldB - oldT == 0) recomputeTerminalSize()
        }

        // registerForActivityResult 要求在 Activity 进入 STARTED 之前完成注册
        transferManager = ZmodemTransferManager(this) { msg, color ->
            appendOutput(msg, color)
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
                // 有选区时,返回键先取消选择而不是退出终端
                KeyEvent.KEYCODE_BACK -> if (binding.tvOutput.hasSelection) {
                    binding.tvOutput.clearSelection(); return true
                }
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
        binding.tvOutput.onSelectionChanged = { active -> onSelectionChanged(active) }
    }
    /** 点击终端时唤起输入法;浏览模式下不弹出键盘,仅供滚动查看。 */
    private fun focusTerminal() {
        if (browseMode) return
        binding.terminalInput.showKeyboard()
    }

    // ── 长按选择文本 / 复制 ───────────────────────────────────────────────────

    /** 选区出现时弹出悬浮菜单,变化时跟着选区移动,消失时收起菜单。 */
    private fun onSelectionChanged(active: Boolean) {
        val mode = selectionActionMode
        when {
            !active -> { selectionActionMode = null; mode?.finish() }
            mode == null -> startSelectionActionMode()
            else -> mode.invalidateContentRect()
        }
    }

    private fun startSelectionActionMode() {
        val callback = object : ActionMode.Callback2() {
            override fun onCreateActionMode(mode: ActionMode, menu: Menu): Boolean {
                menu.add(0, MENU_COPY, 0, R.string.copy)
                    .setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS)
                menu.add(0, MENU_SELECT_ALL, 1, R.string.select_all)
                    .setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS)
                return true
            }

            override fun onPrepareActionMode(mode: ActionMode, menu: Menu): Boolean = false

            override fun onActionItemClicked(mode: ActionMode, item: MenuItem): Boolean =
                when (item.itemId) {
                    MENU_COPY -> { copySelection(); mode.finish(); true }
                    MENU_SELECT_ALL -> {
                        binding.tvOutput.selectAll()
                        mode.invalidateContentRect()
                        true
                    }
                    else -> false
                }

            override fun onDestroyActionMode(mode: ActionMode) {
                selectionActionMode = null
                binding.tvOutput.clearSelection()
            }

            /** 悬浮菜单贴着选区显示。 */
            override fun onGetContentRect(mode: ActionMode, view: View, outRect: Rect) {
                binding.tvOutput.getSelectionBounds(outRect)
                if (outRect.isEmpty) super.onGetContentRect(mode, view, outRect)
            }
        }
        selectionActionMode =
            binding.tvOutput.startActionMode(callback, ActionMode.TYPE_FLOATING)
    }

    private fun copySelection() {
        val text = binding.tvOutput.selectedText
        if (text.isEmpty()) return
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("terminal", text))
        Toast.makeText(this, R.string.copied, Toast.LENGTH_SHORT).show()
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

    /**
     * 终端读循环。
     *
     * 按字节读而不是直接解码成字符串 —— ZMODEM 传的是二进制帧,
     * 一旦按 UTF-8 解码就毁了。每批数据先扫一遍 ZMODEM 起始序列:
     * 没有就当文本交给 ANSI 处理器,有就把流交给传输会话接管。
     */
    private fun startReading() {
        lifecycleScope.launch(Dispatchers.IO) {
            val buffer = ByteArray(8192)
            // 上一批末尾没能凑齐的字节(半个 UTF-8 字符,或半个 ZMODEM 起始序列)
            val carry = ByteArrayOutputStream()
            try {
                while (sshManager.isConnected()) {
                    val inputStream = sshManager.inputStream ?: break
                    val available = inputStream.available()
                    if (available <= 0) {
                        delay(20)
                        continue
                    }
                    val n = inputStream.read(buffer, 0, minOf(available, buffer.size))
                    if (n <= 0) continue

                    val bytes: ByteArray
                    if (carry.size() > 0) {
                        carry.write(buffer, 0, n)
                        bytes = carry.toByteArray()
                        carry.reset()
                    } else {
                        bytes = buffer.copyOf(n)
                    }

                    val zi = findZmodemStart(bytes)
                    if (zi >= 0) {
                        if (zi > 0) emitText(bytes, 0, zi)
                        runZmodem(inputStream, bytes.copyOfRange(zi, bytes.size))
                        continue
                    }

                    // 末尾可能是半个字符或半个起始序列,留到下一批再处理
                    val hold = holdFrom(bytes, 0, bytes.size)
                    val end = if (hold >= 0) hold else bytes.size
                    if (hold >= 0) carry.write(bytes, hold, bytes.size - hold)
                    emitText(bytes, 0, end)
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    appendOutput("\r\nDisconnected: ${e.message}\r\n", Color.parseColor("#CC0000"))
                }
            }
        }
    }

    private suspend fun emitText(bytes: ByteArray, from: Int, to: Int) {
        if (to <= from) return
        val text = String(bytes, from, to - from, Charsets.UTF_8)
        recordRaw(text)
        withContext(Dispatchers.Main) { appendOutput(text) }
    }

    /** 把流交给 ZMODEM 会话;它跑完之前读循环不碰这个流。 */
    private suspend fun runZmodem(ins: InputStream, prefetched: ByteArray) {
        val out = sshManager.outputStream ?: return
        val leftover = transferManager.runSession(ins, out, prefetched)
        // 会话多读进去的那部分通常是传输结束后的 shell 提示符,补回终端
        if (leftover.isNotEmpty()) emitText(leftover, 0, leftover.size)
    }

    /** 找 ZMODEM 起始序列:`**` ZDLE ZHEX,或 `*` ZDLE ZBIN/ZBIN32。 */
    private fun findZmodemStart(b: ByteArray): Int {
        for (i in 0..b.size - 4) {
            if (b[i].toInt() and 0xFF != Zm.ZPAD) continue
            val c1 = b[i + 1].toInt() and 0xFF
            val c2 = b[i + 2].toInt() and 0xFF
            val c3 = b[i + 3].toInt() and 0xFF
            if (c1 == Zm.ZPAD && c2 == Zm.ZDLE && c3 == Zm.ZHEX) return i
            if (c1 == Zm.ZDLE && (c2 == Zm.ZBIN || c2 == Zm.ZBIN32)) return i
        }
        return -1
    }

    /**
     * 这一批末尾从哪个下标开始需要留到下一批 —— 取“半个 UTF-8 字符”
     * 与“半个 ZMODEM 起始序列”里更靠前的那个;都没有则返回 -1。
     */
    private fun holdFrom(b: ByteArray, from: Int, to: Int): Int {
        val utf8 = incompleteCharStart(b, from, to)
        val zm = partialZmodemStart(b, from, to)
        return when {
            utf8 < 0 -> zm
            zm < 0 -> utf8
            else -> minOf(utf8, zm)
        }
    }

    /** 末尾那个 UTF-8 字符是不是被切断了;是则返回它的首字节下标。 */
    private fun incompleteCharStart(b: ByteArray, from: Int, to: Int): Int {
        var i = to - 1
        while (i >= from && i >= to - 4) {
            val v = b[i].toInt() and 0xFF
            if (v and 0xC0 != 0x80) {          // 找到了序列首字节
                val need = when {
                    v < 0x80 -> 1
                    v and 0xE0 == 0xC0 -> 2
                    v and 0xF0 == 0xE0 -> 3
                    v and 0xF8 == 0xF0 -> 4
                    else -> 1
                }
                return if (i + need > to) i else -1
            }
            i--
        }
        return -1
    }

    /** 末尾是不是一个还没凑齐的 ZMODEM 起始序列(`*`、`**`、`**`+ZDLE)。 */
    private fun partialZmodemStart(b: ByteArray, from: Int, to: Int): Int {
        for (start in maxOf(from, to - 3) until to) {
            var ok = true
            for (i in start until to) {
                val v = b[i].toInt() and 0xFF
                val valid = when (i - start) {
                    0 -> v == Zm.ZPAD
                    1 -> v == Zm.ZPAD || v == Zm.ZDLE
                    else -> v == Zm.ZDLE || v == Zm.ZHEX || v == Zm.ZBIN || v == Zm.ZBIN32
                }
                if (!valid) { ok = false; break }
            }
            if (ok) return start
        }
        return -1
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
