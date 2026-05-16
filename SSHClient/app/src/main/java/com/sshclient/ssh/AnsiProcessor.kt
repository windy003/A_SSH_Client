package com.sshclient.ssh

/**
 * 基于二维网格的 VT100/xterm 屏幕缓冲器。
 *
 * 维护一个 rows × cols 的字符网格,加上一个用于滚出顶部内容的滚动区。
 * 这是为了能正确处理 Windows ConPTY(以及大多数现代终端程序)使用的
 * 绝对光标定位序列(ESC[r;cH),否则像 cmd.exe 的 Tab 循环这种就地覆盖
 * 候选项的行为会被错误地变成追加。
 */
class AnsiProcessor {

    companion object {
        private const val DEFAULT_ROWS = 50
        private const val DEFAULT_COLS = 220
    }

    private var rows = DEFAULT_ROWS
    private var cols = DEFAULT_COLS

    // rows × cols 的活动屏幕网格,每个格子一个字符(默认空格)
    private var grid = Array(rows) { CharArray(cols) { ' ' } }

    // 光标位置(0 起始)
    private var cursorRow = 0
    private var cursorCol = 0

    // 保存的光标位置(用于 ESC 7 / ESC 8)
    private var savedRow = 0
    private var savedCol = 0

    // 已滚出屏幕顶部的历史内容
    private val scrollback = StringBuilder()

    // 上一段数据残留的不完整转义序列
    private var pending = ""

    fun process(raw: String) {
        val input = pending + raw
        pending = ""

        var i = 0
        while (i < input.length) {
            val c = input[i]
            when {
                // ── 转义序列 ─────────────────────────────────────────────────
                c == '\u001B' -> {
                    val seqStart = i
                    i++
                    if (i >= input.length) { pending = input.substring(seqStart); break }

                    when (input[i]) {
                        // CSI  ESC [ <参数> <终止字节>
                        '[' -> {
                            i++
                            val paramStart = i
                            while (i < input.length && input[i] in ' '..'?') i++
                            val param = input.substring(paramStart, i)
                            while (i < input.length && input[i] in ' '..'/') i++
                            if (i >= input.length) { pending = input.substring(seqStart); break }
                            val final = input[i]
                            if (final in '@'..'~') i++
                            handleCsi(final, param)
                        }

                        // OSC  ESC ] <文本> BEL  或  ST  —— 我们不实现,跳过即可
                        ']' -> {
                            i++
                            while (i < input.length) {
                                when {
                                    input[i] == '\u0007' -> { i++; break }
                                    input[i] == '\u001B' && i + 1 < input.length && input[i + 1] == '\\' -> {
                                        i += 2; break
                                    }
                                    else -> i++
                                }
                            }
                        }

                        // 字符集切换  ESC ( X  /  ESC ) X
                        '(', ')' -> { i++; if (i < input.length) i++ }

                        // ESC M —— 反向索引(光标上移一行,顶部需要下滚)
                        'M' -> { reverseIndex(); i++ }
                        // ESC 7 / ESC 8 —— 保存/恢复光标
                        '7' -> { savedRow = cursorRow; savedCol = cursorCol; i++ }
                        '8' -> { cursorRow = savedRow; cursorCol = savedCol; i++ }
                        // ESC D —— 索引(下移一行,可能滚动)
                        'D' -> { lineFeed(); i++ }
                        // ESC E —— 下一行
                        'E' -> { lineFeed(); cursorCol = 0; i++ }

                        else -> i++
                    }
                }

                // ── 控制字符 ─────────────────────────────────────────────────
                c == '\r' -> { cursorCol = 0; i++ }
                c == '\n' -> { lineFeed(); i++ }
                c == '\b' -> { if (cursorCol > 0) cursorCol--; i++ }
                c == '\t' -> {
                    cursorCol = (((cursorCol / 8) + 1) * 8).coerceAtMost(cols - 1)
                    i++
                }
                c < ' ' -> i++   // 其它 C0 控制字符:忽略

                // ── 普通可打印字符 ────────────────────────────────────────────
                else -> { putChar(c); i++ }
            }
        }
    }

    // ── CSI 分发 ─────────────────────────────────────────────────────────────

    private fun handleCsi(final: Char, param: String) {
        // 私有模式序列(? > = <)目前一律忽略 —— 我们不实现光标可见性、备用屏等
        if (param.isNotEmpty() && param[0] in "?>=<") return

        val parts = param.split(';')
        fun p(idx: Int, default: Int = 1): Int {
            val s = parts.getOrNull(idx)?.trimEnd() ?: return default
            return s.toIntOrNull() ?: default
        }

        when (final) {
            'A' -> cursorRow = (cursorRow - p(0)).coerceAtLeast(0)
            'B' -> cursorRow = (cursorRow + p(0)).coerceAtMost(rows - 1)
            'C' -> cursorCol = (cursorCol + p(0)).coerceAtMost(cols - 1)
            'D' -> cursorCol = (cursorCol - p(0)).coerceAtLeast(0)
            'E' -> { cursorRow = (cursorRow + p(0)).coerceAtMost(rows - 1); cursorCol = 0 }
            'F' -> { cursorRow = (cursorRow - p(0)).coerceAtLeast(0); cursorCol = 0 }
            'G' -> cursorCol = (p(0) - 1).coerceIn(0, cols - 1)
            'd' -> cursorRow = (p(0) - 1).coerceIn(0, rows - 1)
            'H', 'f' -> {
                cursorRow = (p(0, 1) - 1).coerceIn(0, rows - 1)
                cursorCol = (p(1, 1) - 1).coerceIn(0, cols - 1)
            }
            'J' -> eraseInDisplay(p(0, 0))
            'K' -> eraseInLine(p(0, 0))
            'L' -> insertLines(p(0))
            'M' -> deleteLines(p(0))
            '@' -> insertChars(p(0))
            'P' -> deleteChars(p(0))
            'X' -> eraseChars(p(0))
            'S' -> scrollViewportUp(p(0))
            'T' -> scrollViewportDown(p(0))
            's' -> { savedRow = cursorRow; savedCol = cursorCol }
            'u' -> { cursorRow = savedRow; cursorCol = savedCol }
            'm' -> { /* SGR(字体/颜色)—— 暂不实现 */ }
            // 其他:忽略
        }
    }

    // ── 字符放置与滚动 ───────────────────────────────────────────────────────

    private fun putChar(c: Char) {
        if (cursorCol >= cols) {
            cursorCol = 0
            cursorRow++
            if (cursorRow >= rows) { scrollOne(); cursorRow = rows - 1 }
        }
        grid[cursorRow][cursorCol] = c
        cursorCol++
    }

    private fun lineFeed() {
        cursorRow++
        if (cursorRow >= rows) { scrollOne(); cursorRow = rows - 1 }
    }

    private fun reverseIndex() {
        cursorRow--
        if (cursorRow < 0) {
            for (r in rows - 1 downTo 1) grid[r] = grid[r - 1]
            grid[0] = CharArray(cols) { ' ' }
            cursorRow = 0
        }
    }

    // 把顶部行滚入 scrollback,其余行上移一行,底部新建空白行
    private fun scrollOne() {
        val line = String(grid[0]).trimEnd()
        scrollback.append(line).append('\n')
        for (r in 0 until rows - 1) grid[r] = grid[r + 1]
        grid[rows - 1] = CharArray(cols) { ' ' }
    }

    private fun scrollViewportUp(n: Int)   { repeat(n.coerceAtLeast(1)) { scrollOne() } }
    private fun scrollViewportDown(n: Int) {
        repeat(n.coerceAtLeast(1)) {
            for (r in rows - 1 downTo 1) grid[r] = grid[r - 1]
            grid[0] = CharArray(cols) { ' ' }
        }
    }

    // ── 擦除 ─────────────────────────────────────────────────────────────────

    private fun eraseInLine(mode: Int) {
        when (mode) {
            0 -> for (c in cursorCol until cols) grid[cursorRow][c] = ' '
            1 -> for (c in 0..cursorCol.coerceAtMost(cols - 1)) grid[cursorRow][c] = ' '
            2 -> for (c in 0 until cols) grid[cursorRow][c] = ' '
        }
    }

    private fun eraseInDisplay(mode: Int) {
        when (mode) {
            0 -> {
                for (c in cursorCol until cols) grid[cursorRow][c] = ' '
                for (r in cursorRow + 1 until rows) java.util.Arrays.fill(grid[r], ' ')
            }
            1 -> {
                for (r in 0 until cursorRow) java.util.Arrays.fill(grid[r], ' ')
                for (c in 0..cursorCol.coerceAtMost(cols - 1)) grid[cursorRow][c] = ' '
            }
            2, 3 -> for (r in 0 until rows) java.util.Arrays.fill(grid[r], ' ')
        }
    }

    private fun eraseChars(n: Int) {
        val nn = n.coerceAtLeast(1).coerceAtMost(cols - cursorCol)
        for (c in cursorCol until cursorCol + nn) grid[cursorRow][c] = ' '
    }

    // ── 行/字符插入与删除 ────────────────────────────────────────────────────

    private fun insertChars(n: Int) {
        val row = grid[cursorRow]
        val nn = n.coerceAtLeast(1).coerceAtMost(cols - cursorCol)
        for (c in cols - 1 downTo cursorCol + nn) row[c] = row[c - nn]
        for (c in cursorCol until cursorCol + nn) row[c] = ' '
    }

    private fun deleteChars(n: Int) {
        val row = grid[cursorRow]
        val nn = n.coerceAtLeast(1).coerceAtMost(cols - cursorCol)
        for (c in cursorCol until cols - nn) row[c] = row[c + nn]
        for (c in cols - nn until cols) row[c] = ' '
    }

    private fun insertLines(n: Int) {
        val nn = n.coerceAtLeast(1).coerceAtMost(rows - cursorRow)
        for (r in rows - 1 downTo cursorRow + nn) grid[r] = grid[r - nn]
        for (r in cursorRow until cursorRow + nn) grid[r] = CharArray(cols) { ' ' }
    }

    private fun deleteLines(n: Int) {
        val nn = n.coerceAtLeast(1).coerceAtMost(rows - cursorRow)
        for (r in cursorRow until rows - nn) grid[r] = grid[r + nn]
        for (r in rows - nn until rows) grid[r] = CharArray(cols) { ' ' }
    }

    // ── 渲染与外部接口 ───────────────────────────────────────────────────────

    /** 当前完整显示文本 = scrollback + 屏幕(末尾空白行被裁掉)。 */
    fun getText(): String {
        val sb = StringBuilder()
        sb.append(scrollback)
        // 找出最后一个有内容的行
        var lastNonEmpty = -1
        for (r in 0 until rows) {
            for (c in 0 until cols) {
                if (grid[r][c] != ' ') { lastNonEmpty = r; break }
            }
        }
        val end = maxOf(lastNonEmpty, cursorRow)  // 至少渲染到光标所在行
        for (r in 0..end) {
            sb.append(String(grid[r]).trimEnd())
            if (r < end) sb.append('\n')
        }
        return sb.toString()
    }

    /** 不经过 ANSI 处理直接追加到滚动区,用于本地状态消息/调试输出。 */
    fun appendDirect(text: String) {
        scrollback.append(text.replace("\r\n", "\n").replace('\r', '\n'))
    }

    /** 将 scrollback 裁剪到不超过 [maxLength] 个字符(保留末尾)。 */
    fun trimToLength(maxLength: Int) {
        if (scrollback.length > maxLength)
            scrollback.delete(0, scrollback.length - maxLength)
    }

    fun reset() {
        pending = ""
        scrollback.clear()
        for (r in 0 until rows) java.util.Arrays.fill(grid[r], ' ')
        cursorRow = 0
        cursorCol = 0
        savedRow = 0
        savedCol = 0
    }
}
