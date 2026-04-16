package com.sshclient.ssh

/**
 * 有状态的 ANSI/VT100 处理器。与返回追加字符串的做法不同,
 * 它维护一个持久的显示缓冲区,使得某次服务器响应中的退格(\b)
 * 和光标移动序列可以撤销前一次响应写入的文本。
 * 每次 process() 调用后可通过 getText() 获取当前完整的显示内容。
 */
class AnsiProcessor {

    // 上一段数据残留的字节,可能是一个不完整的转义序列
    private var pending = ""

    // 累积的完整显示文本 —— 由 process() 原地修改
    private val displayBuffer = StringBuilder()

    fun process(raw: String) {
        val input = pending + raw
        pending = ""

        var i = 0
        while (i < input.length) {
            val c = input[i]

            when {
                // ── 转义序列 ─────────────────────────────────────────────────
                c == '\u001b' -> {
                    val seqStart = i
                    i++
                    if (i >= input.length) { pending = input.substring(seqStart); break }

                    when (input[i]) {
                        // CSI  ESC [ <参数字节> <终止字节>
                        '[' -> {
                            i++
                            val paramStart = i
                            while (i < input.length && input[i] in '\u0020'..'\u003f') i++
                            val param = input.substring(paramStart, i)
                            // 中间字节(较少见)
                            while (i < input.length && input[i] in '\u0020'..'\u002f') i++
                            if (i >= input.length) { pending = input.substring(seqStart); break }
                            val final = input[i]
                            if (final in '@'..'~') i++

                            when (final) {
                                'K' -> eraseInLine(param)
                                'J' -> eraseInDisplay(param)
                                'A' -> cursorUp(param)
                                'M' -> cursorUp(param)
                            }
                        }

                        // OSC  ESC ] <文本> BEL  或  ST
                        ']' -> {
                            i++
                            while (i < input.length) {
                                when {
                                    input[i] == '\u0007' -> { i++; break }
                                    input[i] == '\u001b' && i + 1 < input.length && input[i + 1] == '\\' -> {
                                        i += 2; break
                                    }
                                    else -> i++
                                }
                            }
                        }

                        // 字符集切换  ESC ( X  /  ESC ) X
                        '(', ')' -> { i++; if (i < input.length) i++ }

                        // ESC M —— 反向索引(光标上移一行)
                        'M' -> { cursorUp("1"); i++ }

                        // 两字符的 ESC 序列:跳过
                        else -> i++
                    }
                }

                // ── 回车符 ───────────────────────────────────────────────────
                c == '\r' -> {
                    i++
                    if (i < input.length && input[i] == '\n') {
                        displayBuffer.append('\n'); i++
                    } else {
                        goToLineStart()
                    }
                }

                // ── 退格符 ───────────────────────────────────────────────────
                // 直接作用于 displayBuffer,因此可以擦除前一次 process()
                // 调用写入的字符。
                c == '\b' -> {
                    if (displayBuffer.isNotEmpty() && displayBuffer.last() != '\n')
                        displayBuffer.deleteCharAt(displayBuffer.length - 1)
                    i++
                }

                // ── 丢弃其它 C0 控制字符 ──────────────────────────────────────
                c < '\u0020' && c != '\n' && c != '\t' -> i++

                // ── 普通可打印字符 ────────────────────────────────────────────
                else -> { displayBuffer.append(c); i++ }
            }
        }
    }

    /** 返回当前完整的显示文本。 */
    fun getText(): String = displayBuffer.toString()

    /**
     * 直接追加文本而不进行 ANSI 处理(用于本地状态消息)。
     * \r\n 会被规范化为 \n。
     */
    fun appendDirect(text: String) {
        displayBuffer.append(text.replace("\r\n", "\n").replace('\r', '\n'))
    }

    /** 将缓冲区裁剪到不超过 [maxLength] 个字符(保留末尾)。 */
    fun trimToLength(maxLength: Int) {
        if (displayBuffer.length > maxLength)
            displayBuffer.delete(0, displayBuffer.length - maxLength)
    }

    // ── 光标/擦除辅助方法 ────────────────────────────────────────────────────

    private fun goToLineStart() {
        val nl = displayBuffer.lastIndexOf('\n')
        if (nl >= 0) displayBuffer.delete(nl + 1, displayBuffer.length)
        else displayBuffer.clear()
    }

    private fun eraseInLine(param: String) { goToLineStart() }

    private fun eraseInDisplay(param: String) {
        val n = param.trimEnd().toIntOrNull() ?: 0
        if (n == 2 || n == 3) displayBuffer.clear() else goToLineStart()
    }

    private fun cursorUp(param: String) {
        val n = (param.trimEnd().toIntOrNull() ?: 1).coerceAtLeast(1)
        var linesFound = 0
        var pos = displayBuffer.length
        while (pos > 0 && linesFound < n) {
            pos--
            if (displayBuffer[pos] == '\n') linesFound++
        }
        if (linesFound == n) displayBuffer.delete(pos + 1, displayBuffer.length)
        else displayBuffer.clear()
    }

    fun reset() {
        pending = ""
        displayBuffer.clear()
    }
}
