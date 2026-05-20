package com.sshclient.ssh

import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.BackgroundColorSpan
import android.text.style.ForegroundColorSpan

/**
 * 基于二维网格的 VT100/xterm 屏幕缓冲器,支持:
 *  - 绝对光标定位(ESC[r;cH)及大多数 CSI 命令
 *  - SGR(颜色:基础 16 色 / 256 色 / truecolor)
 *  - 备用屏幕缓冲区(ESC[?1049h/l, ESC[?47h/l, ESC[?1047h/l)
 *  - 动态 resize
 *
 * 渲染输出为 SpannableStringBuilder,以便上层 TextView 显示颜色。
 */
class AnsiProcessor {

    companion object {
        const val DEFAULT_ROWS = 24
        const val DEFAULT_COLS = 80

        /** 表示"使用默认颜色",不附加任何 span。 */
        const val COLOR_DEFAULT = Int.MIN_VALUE

        // xterm 标准 16 色(0..15)
        private val ANSI_16 = intArrayOf(
            0xFF000000.toInt(), 0xFFCD0000.toInt(), 0xFF00CD00.toInt(), 0xFFCDCD00.toInt(),
            0xFF0000EE.toInt(), 0xFFCD00CD.toInt(), 0xFF00CDCD.toInt(), 0xFFE5E5E5.toInt(),
            0xFF7F7F7F.toInt(), 0xFFFF0000.toInt(), 0xFF00FF00.toInt(), 0xFFFFFF00.toInt(),
            0xFF5C5CFF.toInt(), 0xFFFF00FF.toInt(), 0xFF00FFFF.toInt(), 0xFFFFFFFF.toInt()
        )

        /** 把 256 色索引转换为 ARGB。 */
        fun color256(i: Int): Int {
            return when {
                i in 0..15 -> ANSI_16[i]
                i in 16..231 -> {
                    val n = i - 16
                    val r = n / 36
                    val g = (n / 6) % 6
                    val b = n % 6
                    val ramp = intArrayOf(0, 95, 135, 175, 215, 255)
                    (0xFF shl 24) or (ramp[r] shl 16) or (ramp[g] shl 8) or ramp[b]
                }
                i in 232..255 -> {
                    val v = 8 + (i - 232) * 10
                    (0xFF shl 24) or (v shl 16) or (v shl 8) or v
                }
                else -> COLOR_DEFAULT
            }
        }
    }

    private var rows = DEFAULT_ROWS
    private var cols = DEFAULT_COLS

    // 当前屏幕(主屏幕 或 备用屏幕,取决于 inAlternateScreen)
    private var grid = Array(rows) { CharArray(cols) { ' ' } }
    private var fg = Array(rows) { IntArray(cols) { COLOR_DEFAULT } }
    private var bg = Array(rows) { IntArray(cols) { COLOR_DEFAULT } }

    // 备用屏幕切换时,主屏幕的快照
    private var inAlternateScreen = false
    private var mainGrid: Array<CharArray>? = null
    private var mainFg: Array<IntArray>? = null
    private var mainBg: Array<IntArray>? = null
    private var mainCursorRow = 0
    private var mainCursorCol = 0

    // 光标
    private var cursorRow = 0
    private var cursorCol = 0
    private var savedRow = 0
    private var savedCol = 0

    // 当前 SGR 状态
    private var curFg = COLOR_DEFAULT
    private var curBg = COLOR_DEFAULT

    // 滚出屏幕的历史(主屏幕模式下),保留颜色
    private val scrollback = SpannableStringBuilder()

    // 上一段数据残留的不完整转义序列
    private var pending = ""

    // ── 公开接口 ─────────────────────────────────────────────────────────────

    fun resize(newCols: Int, newRows: Int) {
        val nc = newCols.coerceAtLeast(1)
        val nr = newRows.coerceAtLeast(1)
        if (nc == cols && nr == rows) return
        grid = resizeGrid(grid, nr, nc) { ' ' }
        fg = resizeIntGrid(fg, nr, nc) { COLOR_DEFAULT }
        bg = resizeIntGrid(bg, nr, nc) { COLOR_DEFAULT }
        if (mainGrid != null) {
            mainGrid = resizeGrid(mainGrid!!, nr, nc) { ' ' }
            mainFg = resizeIntGrid(mainFg!!, nr, nc) { COLOR_DEFAULT }
            mainBg = resizeIntGrid(mainBg!!, nr, nc) { COLOR_DEFAULT }
            mainCursorRow = mainCursorRow.coerceIn(0, nr - 1)
            mainCursorCol = mainCursorCol.coerceIn(0, nc - 1)
        }
        rows = nr
        cols = nc
        cursorRow = cursorRow.coerceIn(0, rows - 1)
        cursorCol = cursorCol.coerceIn(0, cols - 1)
        savedRow = savedRow.coerceIn(0, rows - 1)
        savedCol = savedCol.coerceIn(0, cols - 1)
    }

    fun reset() {
        pending = ""
        scrollback.clear()
        clearGrid()
        cursorRow = 0; cursorCol = 0
        savedRow = 0; savedCol = 0
        curFg = COLOR_DEFAULT
        curBg = COLOR_DEFAULT
        inAlternateScreen = false
        mainGrid = null; mainFg = null; mainBg = null
    }

    fun process(raw: String) {
        val input = pending + raw
        pending = ""

        var i = 0
        while (i < input.length) {
            val c = input[i]
            when {
                c == '\u001B' -> {
                    val seqStart = i
                    i++
                    if (i >= input.length) { pending = input.substring(seqStart); break }

                    when (input[i]) {
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
                        ']' -> {
                            // OSC ESC ] <文本> BEL | ST  —— 暂不实现,跳过
                            i++
                            while (i < input.length) {
                                when {
                                    input[i] == '\u0007' -> { i++; break }
                                    input[i] == '\u001B' && i + 1 < input.length && input[i + 1] == '\\' -> { i += 2; break }
                                    else -> i++
                                }
                            }
                        }
                        '(', ')' -> { i++; if (i < input.length) i++ }
                        'M' -> { reverseIndex(); i++ }
                        '7' -> { savedRow = cursorRow; savedCol = cursorCol; i++ }
                        '8' -> { cursorRow = savedRow; cursorCol = savedCol; i++ }
                        'D' -> { lineFeed(); i++ }
                        'E' -> { lineFeed(); cursorCol = 0; i++ }
                        '=' , '>' -> i++   // 应用程序键盘模式,忽略
                        else -> i++
                    }
                }
                c == '\r' -> { cursorCol = 0; i++ }
                c == '\n' -> { lineFeed(); i++ }
                c == '\b' -> { if (cursorCol > 0) cursorCol--; i++ }
                c == '\t' -> {
                    cursorCol = (((cursorCol / 8) + 1) * 8).coerceAtMost(cols - 1)
                    i++
                }
                c == '\u0007' -> i++   // BEL
                c < ' ' -> i++         // 其它 C0 控制字符忽略
                else -> { putChar(c); i++ }
            }
        }
    }

    /** 返回当前完整显示(scrollback + 当前屏幕,带颜色)的 Spannable。 */
    fun getSpannable(): SpannableStringBuilder {
        val out = SpannableStringBuilder()
        out.append(scrollback)
        // 找到最后一个有内容的行
        var lastNonEmpty = -1
        for (r in rows - 1 downTo 0) {
            for (c in 0 until cols) {
                if (grid[r][c] != ' ' || fg[r][c] != COLOR_DEFAULT || bg[r][c] != COLOR_DEFAULT) {
                    lastNonEmpty = r; break
                }
            }
            if (lastNonEmpty >= 0) break
        }
        val end = maxOf(lastNonEmpty, cursorRow)
        for (r in 0..end) {
            appendLineWithSpans(out, grid[r], fg[r], bg[r],
                if (r == cursorRow) maxOf(cursorCol, lineContentEnd(r)) else lineContentEnd(r))
            if (r < end) out.append('\n')
        }
        return out
    }

    /** 不经过 ANSI 处理直接追加到滚动区(用于本地状态消息)。 */
    fun appendDirect(text: String) {
        scrollback.append(text.replace("\r\n", "\n").replace('\r', '\n'))
    }

    /** 限制 scrollback 长度。 */
    fun trimToLength(maxLength: Int) {
        if (scrollback.length > maxLength) {
            scrollback.delete(0, scrollback.length - maxLength)
        }
    }

    // ── CSI 分发 ─────────────────────────────────────────────────────────────

    private fun handleCsi(final: Char, param: String) {
        // 私有模式序列 ESC[?...
        if (param.isNotEmpty() && param[0] == '?') {
            val numsStr = param.substring(1)
            val nums = numsStr.split(';').mapNotNull { it.trimEnd().toIntOrNull() }
            when (final) {
                'h' -> for (n in nums) setPrivateMode(n, true)
                'l' -> for (n in nums) setPrivateMode(n, false)
            }
            return
        }
        if (param.isNotEmpty() && param[0] in ">=<") return

        val parts = if (param.isEmpty()) emptyList() else param.split(';')
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
            'G', '`' -> cursorCol = (p(0) - 1).coerceIn(0, cols - 1)
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
            'S' -> repeat(p(0).coerceAtLeast(1)) { scrollOne(toScrollback = true) }
            'T' -> repeat(p(0).coerceAtLeast(1)) { scrollDown() }
            's' -> { savedRow = cursorRow; savedCol = cursorCol }
            'u' -> { cursorRow = savedRow; cursorCol = savedCol }
            'm' -> handleSgr(parts)
            'r' -> { /* 滚动区域(DECSTBM),暂不实现 */ }
            'n' -> { /* 设备状态报告,暂不响应 */ }
            // 其余忽略
        }
    }

    private fun setPrivateMode(mode: Int, on: Boolean) {
        when (mode) {
            // 备用屏幕缓冲区(三种变体)
            47, 1047 -> if (on) enterAlternateScreen(clear = false) else exitAlternateScreen()
            1049 -> if (on) { savedRow = cursorRow; savedCol = cursorCol; enterAlternateScreen(clear = true) }
                    else { exitAlternateScreen(); cursorRow = savedRow.coerceIn(0, rows - 1); cursorCol = savedCol.coerceIn(0, cols - 1) }
            // 其它如光标可见性 (?25) 等暂不实现
        }
    }

    private fun enterAlternateScreen(clear: Boolean) {
        if (inAlternateScreen) {
            if (clear) clearGrid().also { cursorRow = 0; cursorCol = 0 }
            return
        }
        // 备份主屏幕
        mainGrid = Array(rows) { grid[it].copyOf() }
        mainFg = Array(rows) { fg[it].copyOf() }
        mainBg = Array(rows) { bg[it].copyOf() }
        mainCursorRow = cursorRow
        mainCursorCol = cursorCol
        inAlternateScreen = true
        clearGrid()
        cursorRow = 0; cursorCol = 0
    }

    private fun exitAlternateScreen() {
        if (!inAlternateScreen) return
        mainGrid?.let { saved ->
            for (r in 0 until rows) grid[r] = saved.getOrNull(r)?.copyOf(cols) ?: CharArray(cols) { ' ' }
        }
        mainFg?.let { saved ->
            for (r in 0 until rows) fg[r] = saved.getOrNull(r)?.copyOf(cols) ?: IntArray(cols) { COLOR_DEFAULT }
        }
        mainBg?.let { saved ->
            for (r in 0 until rows) bg[r] = saved.getOrNull(r)?.copyOf(cols) ?: IntArray(cols) { COLOR_DEFAULT }
        }
        cursorRow = mainCursorRow.coerceIn(0, rows - 1)
        cursorCol = mainCursorCol.coerceIn(0, cols - 1)
        mainGrid = null; mainFg = null; mainBg = null
        inAlternateScreen = false
    }

    // ── SGR(颜色 / 样式)──────────────────────────────────────────────────────

    private fun handleSgr(parts: List<String>) {
        if (parts.isEmpty()) { curFg = COLOR_DEFAULT; curBg = COLOR_DEFAULT; return }
        var i = 0
        while (i < parts.size) {
            val n = parts[i].trimEnd().toIntOrNull() ?: 0
            when {
                n == 0 -> { curFg = COLOR_DEFAULT; curBg = COLOR_DEFAULT }
                n == 39 -> curFg = COLOR_DEFAULT
                n == 49 -> curBg = COLOR_DEFAULT
                n in 30..37 -> curFg = ANSI_16[n - 30]
                n in 90..97 -> curFg = ANSI_16[n - 90 + 8]
                n in 40..47 -> curBg = ANSI_16[n - 40]
                n in 100..107 -> curBg = ANSI_16[n - 100 + 8]
                n == 38 || n == 48 -> {
                    // 38;5;N  或  38;2;R;G;B
                    val sub = parts.getOrNull(i + 1)?.trimEnd()?.toIntOrNull()
                    if (sub == 5) {
                        val idx = parts.getOrNull(i + 2)?.trimEnd()?.toIntOrNull() ?: 0
                        if (n == 38) curFg = color256(idx) else curBg = color256(idx)
                        i += 2
                    } else if (sub == 2) {
                        val r = parts.getOrNull(i + 2)?.trimEnd()?.toIntOrNull() ?: 0
                        val g = parts.getOrNull(i + 3)?.trimEnd()?.toIntOrNull() ?: 0
                        val b = parts.getOrNull(i + 4)?.trimEnd()?.toIntOrNull() ?: 0
                        val argb = (0xFF shl 24) or ((r and 0xFF) shl 16) or ((g and 0xFF) shl 8) or (b and 0xFF)
                        if (n == 38) curFg = argb else curBg = argb
                        i += 4
                    }
                }
                // 其它属性(粗体/下划线/反色/...)目前忽略
            }
            i++
        }
    }

    // ── 字符放置与滚动 ───────────────────────────────────────────────────────

    private fun putChar(c: Char) {
        if (cursorCol >= cols) {
            cursorCol = 0
            cursorRow++
            if (cursorRow >= rows) { scrollOne(toScrollback = !inAlternateScreen); cursorRow = rows - 1 }
        }
        grid[cursorRow][cursorCol] = c
        fg[cursorRow][cursorCol] = curFg
        bg[cursorRow][cursorCol] = curBg
        cursorCol++
    }

    private fun lineFeed() {
        cursorRow++
        if (cursorRow >= rows) { scrollOne(toScrollback = !inAlternateScreen); cursorRow = rows - 1 }
    }

    private fun reverseIndex() {
        cursorRow--
        if (cursorRow < 0) {
            scrollDown()
            cursorRow = 0
        }
    }

    /** 顶部行上移一行;主屏幕模式下保存到 scrollback,备用屏幕模式下丢弃。 */
    private fun scrollOne(toScrollback: Boolean) {
        if (toScrollback) {
            val ssb = SpannableStringBuilder()
            appendLineWithSpans(ssb, grid[0], fg[0], bg[0], lineContentEndOf(grid[0], fg[0], bg[0]))
            ssb.append('\n')
            scrollback.append(ssb)
        }
        for (r in 0 until rows - 1) {
            grid[r] = grid[r + 1]
            fg[r] = fg[r + 1]
            bg[r] = bg[r + 1]
        }
        grid[rows - 1] = CharArray(cols) { ' ' }
        fg[rows - 1] = IntArray(cols) { COLOR_DEFAULT }
        bg[rows - 1] = IntArray(cols) { COLOR_DEFAULT }
    }

    private fun scrollDown() {
        for (r in rows - 1 downTo 1) {
            grid[r] = grid[r - 1]
            fg[r] = fg[r - 1]
            bg[r] = bg[r - 1]
        }
        grid[0] = CharArray(cols) { ' ' }
        fg[0] = IntArray(cols) { COLOR_DEFAULT }
        bg[0] = IntArray(cols) { COLOR_DEFAULT }
    }

    // ── 擦除 ─────────────────────────────────────────────────────────────────

    private fun eraseInLine(mode: Int) {
        when (mode) {
            0 -> clearCells(cursorRow, cursorCol, cols)
            1 -> clearCells(cursorRow, 0, cursorCol.coerceAtMost(cols - 1) + 1)
            2 -> clearCells(cursorRow, 0, cols)
        }
    }

    private fun eraseInDisplay(mode: Int) {
        when (mode) {
            0 -> {
                clearCells(cursorRow, cursorCol, cols)
                for (r in cursorRow + 1 until rows) clearCells(r, 0, cols)
            }
            1 -> {
                for (r in 0 until cursorRow) clearCells(r, 0, cols)
                clearCells(cursorRow, 0, cursorCol.coerceAtMost(cols - 1) + 1)
            }
            2, 3 -> for (r in 0 until rows) clearCells(r, 0, cols)
        }
    }

    private fun eraseChars(n: Int) {
        val nn = n.coerceAtLeast(1).coerceAtMost(cols - cursorCol)
        clearCells(cursorRow, cursorCol, cursorCol + nn)
    }

    private fun clearCells(row: Int, c0: Int, c1: Int) {
        val a = c0.coerceAtLeast(0)
        val b = c1.coerceAtMost(cols)
        for (c in a until b) {
            grid[row][c] = ' '
            fg[row][c] = curFg            // 擦除时使用当前 SGR(用于背景色填充)
            bg[row][c] = curBg
        }
    }

    // ── 行/字符插入与删除 ────────────────────────────────────────────────────

    private fun insertChars(n: Int) {
        val nn = n.coerceAtLeast(1).coerceAtMost(cols - cursorCol)
        for (c in cols - 1 downTo cursorCol + nn) {
            grid[cursorRow][c] = grid[cursorRow][c - nn]
            fg[cursorRow][c] = fg[cursorRow][c - nn]
            bg[cursorRow][c] = bg[cursorRow][c - nn]
        }
        for (c in cursorCol until cursorCol + nn) {
            grid[cursorRow][c] = ' '
            fg[cursorRow][c] = curFg
            bg[cursorRow][c] = curBg
        }
    }

    private fun deleteChars(n: Int) {
        val nn = n.coerceAtLeast(1).coerceAtMost(cols - cursorCol)
        for (c in cursorCol until cols - nn) {
            grid[cursorRow][c] = grid[cursorRow][c + nn]
            fg[cursorRow][c] = fg[cursorRow][c + nn]
            bg[cursorRow][c] = bg[cursorRow][c + nn]
        }
        for (c in cols - nn until cols) {
            grid[cursorRow][c] = ' '
            fg[cursorRow][c] = curFg
            bg[cursorRow][c] = curBg
        }
    }

    private fun insertLines(n: Int) {
        val nn = n.coerceAtLeast(1).coerceAtMost(rows - cursorRow)
        for (r in rows - 1 downTo cursorRow + nn) {
            grid[r] = grid[r - nn]; fg[r] = fg[r - nn]; bg[r] = bg[r - nn]
        }
        for (r in cursorRow until cursorRow + nn) {
            grid[r] = CharArray(cols) { ' ' }
            fg[r] = IntArray(cols) { COLOR_DEFAULT }
            bg[r] = IntArray(cols) { COLOR_DEFAULT }
        }
    }

    private fun deleteLines(n: Int) {
        val nn = n.coerceAtLeast(1).coerceAtMost(rows - cursorRow)
        for (r in cursorRow until rows - nn) {
            grid[r] = grid[r + nn]; fg[r] = fg[r + nn]; bg[r] = bg[r + nn]
        }
        for (r in rows - nn until rows) {
            grid[r] = CharArray(cols) { ' ' }
            fg[r] = IntArray(cols) { COLOR_DEFAULT }
            bg[r] = IntArray(cols) { COLOR_DEFAULT }
        }
    }

    // ── 辅助 ─────────────────────────────────────────────────────────────────

    private fun clearGrid() {
        for (r in 0 until rows) {
            grid[r] = CharArray(cols) { ' ' }
            fg[r] = IntArray(cols) { COLOR_DEFAULT }
            bg[r] = IntArray(cols) { COLOR_DEFAULT }
        }
    }

    private fun lineContentEnd(r: Int): Int = lineContentEndOf(grid[r], fg[r], bg[r])

    private fun lineContentEndOf(row: CharArray, fgRow: IntArray, bgRow: IntArray): Int {
        for (c in row.size - 1 downTo 0) {
            if (row[c] != ' ' || fgRow[c] != COLOR_DEFAULT || bgRow[c] != COLOR_DEFAULT) return c + 1
        }
        return 0
    }

    /**
     * 把一行写入 ssb,按相同 (fg, bg) 段合并 span。
     * extent:要渲染到的列数(独占)。
     */
    private fun appendLineWithSpans(
        ssb: SpannableStringBuilder,
        row: CharArray,
        fgRow: IntArray,
        bgRow: IntArray,
        extent: Int
    ) {
        if (extent <= 0) return
        var segStart = 0
        var segFg = fgRow[0]
        var segBg = bgRow[0]
        for (c in 1 until extent) {
            if (fgRow[c] != segFg || bgRow[c] != segBg) {
                appendSegment(ssb, row, segStart, c, segFg, segBg)
                segStart = c
                segFg = fgRow[c]
                segBg = bgRow[c]
            }
        }
        appendSegment(ssb, row, segStart, extent, segFg, segBg)
    }

    private fun appendSegment(
        ssb: SpannableStringBuilder,
        row: CharArray,
        from: Int, to: Int,
        segFg: Int, segBg: Int
    ) {
        val start = ssb.length
        ssb.append(String(row, from, to - from))
        val end = ssb.length
        if (segFg != COLOR_DEFAULT) {
            ssb.setSpan(ForegroundColorSpan(segFg), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        if (segBg != COLOR_DEFAULT) {
            ssb.setSpan(BackgroundColorSpan(segBg), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
    }

    private inline fun resizeGrid(
        old: Array<CharArray>,
        newRows: Int, newCols: Int,
        fill: () -> Char
    ): Array<CharArray> {
        val out = Array(newRows) { CharArray(newCols) { fill() } }
        val rs = minOf(old.size, newRows)
        for (r in 0 until rs) {
            val src = old[r]
            val cs = minOf(src.size, newCols)
            for (c in 0 until cs) out[r][c] = src[c]
        }
        return out
    }

    private inline fun resizeIntGrid(
        old: Array<IntArray>,
        newRows: Int, newCols: Int,
        fill: () -> Int
    ): Array<IntArray> {
        val out = Array(newRows) { IntArray(newCols) { fill() } }
        val rs = minOf(old.size, newRows)
        for (r in 0 until rs) {
            val src = old[r]
            val cs = minOf(src.size, newCols)
            for (c in 0 until cs) out[r][c] = src[c]
        }
        return out
    }
}
