package com.sshclient.ui

import android.content.Context
import android.util.AttributeSet
import android.view.KeyEvent
import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputMethodManager
import android.text.InputType
import android.view.View

/**
 * 一个不可见的视图,用于捕获键盘输入并直接转发到 SSH 通道 ——
 * 没有文本框,没有发送按钮,就像真正的终端一样。
 */
class TerminalInputView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    var onInput: ((String) -> Unit)? = null

    init {
        isFocusable = true
        isFocusableInTouchMode = true
    }

    fun showKeyboard() {
        requestFocus()
        val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        imm.showSoftInput(this, InputMethodManager.SHOW_FORCED)
    }

    fun hideKeyboard() {
        val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        imm.hideSoftInputFromWindow(windowToken, 0)
    }

    override fun onCheckIsTextEditor(): Boolean = true

    override fun onCreateInputConnection(outAttrs: EditorInfo): InputConnection {
        // TYPE_TEXT_VARIATION_VISIBLE_PASSWORD:不做自动纠错,不进行词汇学习,
        // 但输入法依然能通过 deleteSurroundingText 正常处理退格/删除。
        outAttrs.inputType = InputType.TYPE_CLASS_TEXT or
                InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS or
                InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
        outAttrs.imeOptions = EditorInfo.IME_FLAG_NO_EXTRACT_UI or
                EditorInfo.IME_FLAG_NO_FULLSCREEN or
                EditorInfo.IME_ACTION_NONE
        outAttrs.initialSelStart = 0
        outAttrs.initialSelEnd = 0
        return TerminalInputConnection(this)
    }

    // 硬件键盘入口 —— 吞掉所有按键,防止产生副作用
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN) handleKeyDown(event)
        return true
    }

    private fun handleKeyDown(event: KeyEvent) {
        val isCtrl = event.isCtrlPressed
        when (event.keyCode) {
            KeyEvent.KEYCODE_ENTER       -> send("\r")
            KeyEvent.KEYCODE_DEL         -> send("\u007f")
            KeyEvent.KEYCODE_FORWARD_DEL -> send("\u001b[3~")
            KeyEvent.KEYCODE_TAB         -> send("\t")
            KeyEvent.KEYCODE_ESCAPE      -> send("\u001b")
            KeyEvent.KEYCODE_DPAD_UP     -> send(if (isCtrl) "\u001b[1;5A" else "\u001b[A")
            KeyEvent.KEYCODE_DPAD_DOWN   -> send(if (isCtrl) "\u001b[1;5B" else "\u001b[B")
            KeyEvent.KEYCODE_DPAD_RIGHT  -> send(if (isCtrl) "\u001b[1;5C" else "\u001b[C")
            KeyEvent.KEYCODE_DPAD_LEFT   -> send(if (isCtrl) "\u001b[1;5D" else "\u001b[D")
            KeyEvent.KEYCODE_MOVE_HOME   -> send("\u001b[H")
            KeyEvent.KEYCODE_MOVE_END    -> send("\u001b[F")
            KeyEvent.KEYCODE_PAGE_UP     -> send("\u001b[5~")
            KeyEvent.KEYCODE_PAGE_DOWN   -> send("\u001b[6~")
            else -> {
                val ch = if (isCtrl) event.unicodeChar and 0x1f else event.unicodeChar
                if (ch > 0) send(ch.toChar().toString())
            }
        }
    }

    private fun send(s: String) { onInput?.invoke(s) }

    private inner class TerminalInputConnection(view: View) : BaseInputConnection(view, false) {

        // 伪造的缓冲区,使输入法在退格时能够可靠地调用 deleteSurroundingText
        private val fakeBuffer = " ".repeat(16)
        private var lastDeleteSurroundingMs = 0L

        override fun getTextBeforeCursor(n: Int, flags: Int): CharSequence =
            fakeBuffer.takeLast(n.coerceIn(0, fakeBuffer.length))
        override fun getTextAfterCursor(n: Int, flags: Int): CharSequence = ""
        override fun getSelectedText(flags: Int): CharSequence = ""
        override fun getCursorCapsMode(reqModes: Int): Int = 0

        // 来自软键盘的普通字符输入
        override fun commitText(text: CharSequence?, newCursorPosition: Int): Boolean {
            text?.toString()?.let { if (it.isNotEmpty()) send(it) }
            return true
        }

        // 来自软键盘的特殊按键 —— 切勿调用 super(会导致重复分发)
        override fun sendKeyEvent(event: KeyEvent): Boolean {
            if (event.action == KeyEvent.ACTION_DOWN) {
                if (event.keyCode == KeyEvent.KEYCODE_DEL) {
                    if (System.currentTimeMillis() - lastDeleteSurroundingMs > 100) {
                        send("\u007f")
                    }
                } else {
                    handleKeyDown(event)
                }
            }
            return true
        }

        // 来自软键盘的退格键
        override fun deleteSurroundingText(beforeLength: Int, afterLength: Int): Boolean {
            if (beforeLength > 0) {
                // 只有在实际发送了内容时才记录时间戳,
                // 这样多余的 deleteSurroundingText(0,0) 调用
                // 就不会阻塞后续的 sendKeyEvent(KEYCODE_DEL)。
                lastDeleteSurroundingMs = System.currentTimeMillis()
                repeat(beforeLength) { send("\u007f") }
            }
            return true
        }

        override fun finishComposingText(): Boolean = true
        override fun setComposingText(text: CharSequence?, newCursorPosition: Int): Boolean = true
        override fun setComposingRegion(start: Int, end: Int): Boolean = true
    }
}
