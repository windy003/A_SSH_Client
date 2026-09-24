package com.sshclient.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.Typeface
import android.text.Spanned
import android.text.style.BackgroundColorSpan
import android.util.AttributeSet
import android.util.TypedValue
import android.view.GestureDetector
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import kotlin.math.hypot
import kotlin.math.max

/**
 * 固定网格自绘终端视图。
 *
 * 关键点:不依赖字体的自然字宽,而是把每个字符强制画在等宽的格子里:
 *  - ASCII / box-drawing 字符占 1 格;
 *  - CJK 全角字符占 2 格。
 * 这样无论字体把 box-drawing(─ │ ╭ ╮)画得多宽,框线始终落在网格上,
 * 表格 / TUI 框都能对齐。
 *
 * 视图**不换行**:每个逻辑行画成一整行,视图宽度 = 最长行的实际像素宽度。
 * 外层用 ScrollView(纵向)+ HorizontalScrollView(横向)即可上下左右拖动查看,
 * 超出屏幕的内容通过横向滚动查看。字号(缩放)由 [setFontSizeSp] 控制。
 *
 * 内容(文字 + 背景色)由 [setContent] 传入;前景统一渲染为黑色。
 *
 * 文本选择:长按选中手指下的词(空白处则选中整行),不松手继续拖动可扩展
 * 选区;选区两端有手柄可以微调;轻点别处取消选择。选区文本由 [selectedText]
 * 取出,外层据此弹出“复制”菜单。
 */
class TerminalView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.MONOSPACE
        color = Color.BLACK
    }
    private val bgPaint = Paint()
    private val selPaint = Paint().apply { color = SELECTION_COLOR }
    private val handlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = HANDLE_COLOR }

    /** 单元格宽度(ASCII 步进)与行高,供外部计算 PTY 行数。 */
    var cellWidth = 1f
        private set
    var lineHeight = 1f
        private set
    private var ascent = 0f
    private var lineSpacingExtra = 0f

    private var content: CharSequence = ""

    // 逻辑行(按换行切分)的起始字符下标,长度 = 行数 + 1(末尾哨兵)。
    private var lineStarts = intArrayOf(0, 1)
    // 每个逻辑行的宽度(格子数)。
    private var lineCells = intArrayOf(0)
    private var maxLineCells = 0

    // 背景色段:(start, end, color)
    private var bgStarts = IntArray(0)
    private var bgEnds = IntArray(0)
    private var bgColors = IntArray(0)

    // 光标(末尾)位置
    private var cursorRow = 0
    private var cursorCol = 0
    var cursorVisible: Boolean = true
        set(value) {
            if (field != value) { field = value; invalidate() }
        }

    // ── 选区状态 ──────────────────────────────────────────────────────────────

    private var selStart = -1
    private var selEnd = -1
    /** 拖动过程中固定不动的那一端。 */
    private var selAnchor = -1
    private var draggingHandle = HANDLE_NONE

    private val handleRadius = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP, 8f, resources.displayMetrics
    )
    private val handleTouchRadius = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP, 24f, resources.displayMetrics
    )

    val hasSelection: Boolean get() = selStart in 0 until selEnd

    /** 当前选中的文本;没有选区时为空串。 */
    val selectedText: String
        get() = if (hasSelection) content.subSequence(selStart, selEnd).toString() else ""

    /**
     * 选区变化回调:[active] 为 true 表示现在有选区(新建或改变),false 表示
     * 选区已清除。外层据此显示 / 更新 / 关闭复制菜单。
     */
    var onSelectionChanged: ((active: Boolean) -> Unit)? = null

    private val gestureDetector = GestureDetector(
        context,
        object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent): Boolean = true

            override fun onSingleTapUp(e: MotionEvent): Boolean {
                // 有选区时轻点先取消选择,不唤起键盘
                if (hasSelection) clearSelection() else performClick()
                return true
            }

            override fun onLongPress(e: MotionEvent) {
                startSelectionAt(e.x, e.y)
            }
        }
    )

    init {
        setFontSizeSp(11f)
    }

    fun setFontSizeSp(sp: Float) {
        textPaint.textSize = TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_SP, sp, resources.displayMetrics
        )
        lineSpacingExtra = TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP, 2f, resources.displayMetrics
        )
        recomputeMetrics()
        requestLayout()
        invalidate()
    }

    private fun recomputeMetrics() {
        cellWidth = textPaint.measureText("M").coerceAtLeast(1f)
        val fm = textPaint.fontMetrics
        ascent = -fm.top
        lineHeight = (fm.bottom - fm.top + lineSpacingExtra).coerceAtLeast(1f)
    }

    /**
     * 设置要显示的内容(带可选 BackgroundColorSpan);前景一律黑色。
     * [cursorIndex] 为光标在 [text] 中的字符下标,据此定位光标的行与列。
     */
    fun setContent(text: CharSequence, cursorIndex: Int) {
        content = text

        // 切分逻辑行
        val starts = ArrayList<Int>()
        starts.add(0)
        for (i in text.indices) {
            if (text[i] == '\n') starts.add(i + 1)
        }
        starts.add(text.length + 1) // 哨兵
        lineStarts = starts.toIntArray()

        // 计算每行格子宽度
        val lineCount = lineStarts.size - 1
        lineCells = IntArray(lineCount)
        maxLineCells = 0
        for (i in 0 until lineCount) {
            val s = lineStarts[i]
            val e = (lineStarts[i + 1] - 1).coerceAtMost(text.length)
            val cells = cellsOf(s, e)
            lineCells[i] = cells
            if (cells > maxLineCells) maxLineCells = cells
        }

        // 根据字符下标定位光标的行与列(列按显示宽度计,CJK 占 2 格)
        val ci = cursorIndex.coerceIn(0, text.length)
        var row = 0
        while (row < lineCount - 1 && lineStarts[row + 1] <= ci) row++
        cursorRow = row
        val lineEnd = (lineStarts[row + 1] - 1).coerceAtMost(text.length)
        cursorCol = cellsOf(lineStarts[row], ci.coerceAtMost(lineEnd))

        // 提取背景色段
        if (text is Spanned) {
            val spans = text.getSpans(0, text.length, BackgroundColorSpan::class.java)
            bgStarts = IntArray(spans.size)
            bgEnds = IntArray(spans.size)
            bgColors = IntArray(spans.size)
            for (k in spans.indices) {
                bgStarts[k] = text.getSpanStart(spans[k])
                bgEnds[k] = text.getSpanEnd(spans[k])
                bgColors[k] = spans[k].backgroundColor
            }
        } else {
            bgStarts = IntArray(0); bgEnds = IntArray(0); bgColors = IntArray(0)
        }

        // 内容变短(滚动区被裁剪)时把选区夹回有效范围
        if (selStart >= 0) {
            selStart = selStart.coerceAtMost(text.length)
            selEnd = selEnd.coerceAtMost(text.length)
            if (selEnd <= selStart) clearSelection()
        }

        requestLayout()
        invalidate()
    }

    private fun cellsOf(start: Int, end: Int): Int {
        var col = 0
        var i = start
        while (i < end) {
            val cp = Character.codePointAt(content, i)
            col += if (isWide(cp)) 2 else 1
            i += Character.charCount(cp)
        }
        return col
    }

    // ── 布局 ─────────────────────────────────────────────────────────────────

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val lineCount = lineStarts.size - 1
        val desiredW = (paddingLeft + paddingRight + maxLineCells * cellWidth).toInt()
        val desiredH = (paddingTop + paddingBottom + lineCount * lineHeight).toInt()
        setMeasuredDimension(
            resolve(widthMeasureSpec, desiredW),
            resolve(heightMeasureSpec, desiredH)
        )
    }

    /** 让视图至少占满父容器(配合 fillViewport),但内容更大时用内容尺寸。 */
    private fun resolve(spec: Int, desired: Int): Int {
        val mode = MeasureSpec.getMode(spec)
        val size = MeasureSpec.getSize(spec)
        return when (mode) {
            MeasureSpec.EXACTLY -> max(desired, size)
            // AT_MOST 来自 fillViewport 的重新测量:内容不足一屏时要撑满视口,
            // 否则视图只有内容那么高,下方空白不属于本视图 —— 点击也就唤不起键盘。
            // 内容超过一屏时走 UNSPECIFIED 分支,滚动不受影响。
            MeasureSpec.AT_MOST -> size
            else -> desired
        }
    }

    // ── 绘制 ─────────────────────────────────────────────────────────────────

    override fun onDraw(canvas: Canvas) {
        if (cellWidth <= 0f || lineHeight <= 0f) return

        // 只绘制可见行(ScrollView / HorizontalScrollView 会把 clip 设为可见矩形)
        val clip = canvas.clipBounds
        val firstRow = (((clip.top - paddingTop) / lineHeight).toInt() - 1).coerceAtLeast(0)
        val lastRow = ((clip.bottom - paddingTop) / lineHeight).toInt() + 1

        val lineCount = lineStarts.size - 1
        val from = firstRow.coerceAtMost(lineCount - 1)
        val to = lastRow.coerceAtMost(lineCount - 1)
        for (row in from..to) {
            drawLine(canvas, row)
        }

        // 选择文本期间不画光标,免得和高亮混在一起
        if (cursorVisible && !hasSelection) {
            val x = paddingLeft + cursorCol * cellWidth
            val top = paddingTop + cursorRow * lineHeight
            bgPaint.color = Color.BLACK
            canvas.drawRect(x, top, x + cellWidth * 0.12f + 2f, top + lineHeight, bgPaint)
        }

        if (hasSelection) drawHandles(canvas)
    }

    private fun drawLine(canvas: Canvas, row: Int) {
        val s = lineStarts[row]
        val e = (lineStarts[row + 1] - 1).coerceAtMost(content.length)
        val top = paddingTop + row * lineHeight
        var col = 0
        var i = s
        while (i < e) {
            val cp = Character.codePointAt(content, i)
            val charLen = Character.charCount(cp)
            val w = if (isWide(cp)) 2 else 1
            val x = paddingLeft + col * cellWidth

            val bg = bgColorAt(i)
            if (bg != 0) {
                bgPaint.color = bg
                canvas.drawRect(x, top, x + w * cellWidth, top + lineHeight, bgPaint)
            }
            if (i >= selStart && i < selEnd) {
                canvas.drawRect(x, top, x + w * cellWidth, top + lineHeight, selPaint)
            }
            if (!(charLen == 1 && content[i] == ' ')) {
                canvas.drawText(content, i, i + charLen, x, top + ascent, textPaint)
            }

            col += w
            i += charLen
        }

        // 选区跨到下一行时,把行尾的换行也画成一格高亮,视觉上连成一片
        if (e < content.length && e >= selStart && e < selEnd) {
            val x = paddingLeft + col * cellWidth
            canvas.drawRect(x, top, x + cellWidth, top + lineHeight, selPaint)
        }
    }

    private fun drawHandles(canvas: Canvas) {
        val p = FloatArray(2)
        handleCenter(selStart, p)
        canvas.drawCircle(p[0], p[1], handleRadius, handlePaint)
        handleCenter(selEnd, p)
        canvas.drawCircle(p[0], p[1], handleRadius, handlePaint)
    }

    private fun bgColorAt(index: Int): Int {
        for (k in bgStarts.indices) {
            if (index >= bgStarts[k] && index < bgEnds[k]) return bgColors[k]
        }
        return 0
    }

    // ── 触摸:长按选词 + 手柄拖动 ─────────────────────────────────────────────

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                if (hasSelection) {
                    val h = handleAt(event.x, event.y)
                    if (h != HANDLE_NONE) {
                        draggingHandle = h
                        selAnchor = if (h == HANDLE_START) selEnd else selStart
                        // 拖手柄期间别让外面的 ScrollView 抢走手势
                        parent?.requestDisallowInterceptTouchEvent(true)
                        return true
                    }
                }
            }
            MotionEvent.ACTION_MOVE -> {
                if (draggingHandle != HANDLE_NONE) {
                    extendSelectionTo(event.x, event.y)
                    return true
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (draggingHandle != HANDLE_NONE) {
                    draggingHandle = HANDLE_NONE
                    parent?.requestDisallowInterceptTouchEvent(false)
                    // 手指抬起,让菜单回到选区旁边
                    onSelectionChanged?.invoke(hasSelection)
                    return true
                }
            }
        }
        gestureDetector.onTouchEvent(event)
        return true
    }

    override fun performClick(): Boolean = super.performClick()

    /** 长按:选中手指下的词;落在空白或行尾之外则选中整行。 */
    private fun startSelectionAt(x: Float, y: Float) {
        val lineCount = lineStarts.size - 1
        if (lineCount <= 0 || content.isEmpty()) return
        // 内容下方的空白区域不参与选择
        if (y > paddingTop + lineCount * lineHeight) return

        val row = rowAt(y)
        val s = lineStarts[row]
        val e = (lineStarts[row + 1] - 1).coerceAtMost(content.length)
        val i = charIndexAt(x, row)

        if (i < 0 || content[i].isWhitespace()) {
            selStart = s
            selEnd = e
        } else {
            var b = i
            while (b > s && !content[b - 1].isWhitespace()) b--
            var f = i
            while (f < e && !content[f].isWhitespace()) f++
            selStart = b
            selEnd = f
        }

        if (selEnd <= selStart) {          // 空行,没什么可选
            selStart = -1; selEnd = -1
            return
        }

        // 手指不松开继续拖动 = 从词尾扩展选区
        selAnchor = selStart
        draggingHandle = HANDLE_END
        parent?.requestDisallowInterceptTouchEvent(true)
        performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
        invalidate()
        onSelectionChanged?.invoke(true)
    }

    private fun extendSelectionTo(x: Float, y: Float) {
        var idx = caretIndexAt(x, y)
        if (idx == selAnchor) {
            // 不允许收缩成空选区,至少留一个字符
            idx = if (idx < content.length) idx + 1 else idx - 1
        }
        val newStart = minOf(selAnchor, idx).coerceIn(0, content.length)
        val newEnd = maxOf(selAnchor, idx).coerceIn(0, content.length)
        if (newStart == selStart && newEnd == selEnd) return
        selStart = newStart
        selEnd = newEnd
        // 拖过锚点时两个手柄的身份互换
        draggingHandle = if (idx <= selAnchor) HANDLE_START else HANDLE_END
        invalidate()
        onSelectionChanged?.invoke(true)
    }

    /** 选中全部内容(含滚动区历史)。 */
    fun selectAll() {
        if (content.isEmpty()) return
        selStart = 0
        selEnd = content.length
        selAnchor = 0
        draggingHandle = HANDLE_NONE
        invalidate()
        onSelectionChanged?.invoke(true)
    }

    fun clearSelection() {
        if (selStart < 0 && selEnd < 0) return
        selStart = -1
        selEnd = -1
        selAnchor = -1
        draggingHandle = HANDLE_NONE
        invalidate()
        onSelectionChanged?.invoke(false)
    }

    /** 选区的外接矩形(视图坐标),供悬浮菜单定位。 */
    fun getSelectionBounds(out: Rect) {
        if (!hasSelection) { out.setEmpty(); return }
        val r1 = rowOf(selStart)
        val r2 = rowOf(selEnd)
        val top = (paddingTop + r1 * lineHeight).toInt()
        val bottom = (paddingTop + (r2 + 1) * lineHeight).toInt()
        val left: Int
        val right: Int
        if (r1 == r2) {
            left = (paddingLeft + cellsOf(lineStarts[r1], selStart) * cellWidth).toInt()
            right = (paddingLeft + cellsOf(lineStarts[r2], selEnd) * cellWidth).toInt()
        } else {
            left = paddingLeft
            right = max(width - paddingRight, paddingLeft + 1)
        }
        out.set(minOf(left, right), top, max(left, right), bottom)
    }

    // ── 坐标与字符下标互转 ────────────────────────────────────────────────────

    private fun rowAt(y: Float): Int {
        val lineCount = lineStarts.size - 1
        return ((y - paddingTop) / lineHeight).toInt().coerceIn(0, lineCount - 1)
    }

    private fun rowOf(index: Int): Int {
        val lineCount = lineStarts.size - 1
        var row = 0
        while (row < lineCount - 1 && lineStarts[row + 1] <= index) row++
        return row
    }

    /** [x] 落在哪个格子里,返回那个字符的下标;越过行尾返回 -1。 */
    private fun charIndexAt(x: Float, row: Int): Int {
        val s = lineStarts[row]
        val e = (lineStarts[row + 1] - 1).coerceAtMost(content.length)
        val target = (x - paddingLeft) / cellWidth
        if (target < 0f) return if (s < e) s else -1
        var col = 0
        var i = s
        while (i < e) {
            val cp = Character.codePointAt(content, i)
            val n = Character.charCount(cp)
            val w = if (isWide(cp)) 2 else 1
            if (target < col + w) return i
            col += w
            i += n
        }
        return -1
    }

    /** 离 (x, y) 最近的字符间隙(插入点)下标。 */
    private fun caretIndexAt(x: Float, y: Float): Int {
        val row = rowAt(y)
        val s = lineStarts[row]
        val e = (lineStarts[row + 1] - 1).coerceAtMost(content.length)
        val target = (x - paddingLeft) / cellWidth
        if (target <= 0f) return s
        var col = 0
        var i = s
        while (i < e) {
            val cp = Character.codePointAt(content, i)
            val n = Character.charCount(cp)
            val w = if (isWide(cp)) 2 else 1
            if (target < col + w / 2f) return i
            col += w
            i += n
        }
        return e
    }

    private fun handleCenter(index: Int, out: FloatArray) {
        val row = rowOf(index)
        out[0] = paddingLeft + cellsOf(lineStarts[row], index) * cellWidth
        out[1] = (paddingTop + (row + 1) * lineHeight + handleRadius * 0.7f)
            .coerceAtMost(height - handleRadius)
    }

    /** 手指是不是按在某个手柄上;是则返回该手柄。 */
    private fun handleAt(x: Float, y: Float): Int {
        val p = FloatArray(2)
        handleCenter(selStart, p)
        val dStart = hypot(x - p[0], y - p[1])
        handleCenter(selEnd, p)
        val dEnd = hypot(x - p[0], y - p[1])
        if (dStart > handleTouchRadius && dEnd > handleTouchRadius) return HANDLE_NONE
        return if (dStart <= dEnd) HANDLE_START else HANDLE_END
    }

    // ── 全角(占 2 格)字符判定 ──────────────────────────────────────────────

    private fun isWide(cp: Int): Boolean {
        return (cp in 0x1100..0x115F) ||                 // Hangul Jamo
                (cp in 0x2E80..0x303E) ||                // CJK 部首 / 汉字标点
                (cp in 0x3041..0x33FF) ||                // 假名 / CJK 符号
                (cp in 0x3400..0x4DBF) ||                // CJK 扩展 A
                (cp in 0x4E00..0x9FFF) ||                // CJK 统一表意文字
                (cp in 0xA000..0xA4CF) ||                // 彝文
                (cp in 0xAC00..0xD7A3) ||                // 谚文音节
                (cp in 0xF900..0xFAFF) ||                // CJK 兼容表意文字
                (cp in 0xFE30..0xFE4F) ||                // CJK 兼容形式
                (cp in 0xFF00..0xFF60) ||                // 全角 ASCII
                (cp in 0xFFE0..0xFFE6) ||                // 全角符号
                (cp in 0x1F300..0x1FAFF) ||              // emoji(近似)
                (cp in 0x20000..0x3FFFD)                 // CJK 扩展 B+
    }

    private companion object {
        const val HANDLE_NONE = 0
        const val HANDLE_START = 1
        const val HANDLE_END = 2
        const val SELECTION_COLOR = 0x553F51B5
        val HANDLE_COLOR = 0xFF3F51B5.toInt()
    }
}
