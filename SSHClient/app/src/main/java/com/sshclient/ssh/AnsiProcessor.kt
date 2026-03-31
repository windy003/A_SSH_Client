package com.sshclient.ssh

/**
 * Stateful ANSI/VT100 processor. Instead of returning a string to append,
 * it maintains a persistent display buffer so that backspace (\b) and cursor
 * movement sequences from one server response can undo text written by a
 * previous response. Call getText() after each process() to get the full
 * current display content.
 */
class AnsiProcessor {

    // Leftover bytes from last chunk that might be an incomplete escape sequence
    private var pending = ""

    // The full accumulated display text — mutated in place by process()
    private val displayBuffer = StringBuilder()

    fun process(raw: String) {
        val input = pending + raw
        pending = ""

        var i = 0
        while (i < input.length) {
            val c = input[i]

            when {
                // ── Escape sequence ──────────────────────────────────────────
                c == '\u001b' -> {
                    val seqStart = i
                    i++
                    if (i >= input.length) { pending = input.substring(seqStart); break }

                    when (input[i]) {
                        // CSI  ESC [ <param bytes> <final byte>
                        '[' -> {
                            i++
                            val paramStart = i
                            while (i < input.length && input[i] in '\u0020'..'\u003f') i++
                            val param = input.substring(paramStart, i)
                            // Intermediate bytes (rare)
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

                        // OSC  ESC ] <text> BEL  or  ST
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

                        // Character set  ESC ( X  /  ESC ) X
                        '(', ')' -> { i++; if (i < input.length) i++ }

                        // ESC M — Reverse Index (cursor up one line)
                        'M' -> { cursorUp("1"); i++ }

                        // Two-char ESC sequences: skip
                        else -> i++
                    }
                }

                // ── Carriage return ──────────────────────────────────────────
                c == '\r' -> {
                    i++
                    if (i < input.length && input[i] == '\n') {
                        displayBuffer.append('\n'); i++
                    } else {
                        goToLineStart()
                    }
                }

                // ── Backspace ────────────────────────────────────────────────
                // Operates on displayBuffer directly, so it can erase characters
                // written by a previous process() call.
                c == '\b' -> {
                    if (displayBuffer.isNotEmpty() && displayBuffer.last() != '\n')
                        displayBuffer.deleteCharAt(displayBuffer.length - 1)
                    i++
                }

                // ── Discard other C0 control chars ───────────────────────────
                c < '\u0020' && c != '\n' && c != '\t' -> i++

                // ── Normal printable character ───────────────────────────────
                else -> { displayBuffer.append(c); i++ }
            }
        }
    }

    /** Returns the current full display text. */
    fun getText(): String = displayBuffer.toString()

    /**
     * Append text directly without ANSI processing (used for local status messages).
     * \r\n is normalised to \n.
     */
    fun appendDirect(text: String) {
        displayBuffer.append(text.replace("\r\n", "\n").replace('\r', '\n'))
    }

    /** Trim the buffer to at most [maxLength] characters from the end. */
    fun trimToLength(maxLength: Int) {
        if (displayBuffer.length > maxLength)
            displayBuffer.delete(0, displayBuffer.length - maxLength)
    }

    // ── Cursor / erase helpers ────────────────────────────────────────────────

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
