package com.sshclient.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.text.Spanned
import android.text.style.BackgroundColorSpan
import android.util.AttributeSet
import android.util.TypedValue
import android.view.View
import kotlin.math.max
import kotlin.math.min

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

    /** 单元格宽度(ASCII 步进)与行高,供外部计算 PTY 行数。 */
    var cellWidth = 1f
        private set
    var lineHeight = 1f
        private set
    private var ascent = 0f
    private var lineSpacingExtra = 0f

    private var content: CharSequence = ""

    // 逻辑行(按 '\n' 切分)的起始字符下标,长度 = 行数 + 1(末尾哨兵)。
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
            MeasureSpec.AT_MOST -> min(desired, size)
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

        if (cursorVisible) {
            val x = paddingLeft + cursorCol * cellWidth
            val top = paddingTop + cursorRow * lineHeight
            bgPaint.color = Color.BLACK
            canvas.drawRect(x, top, x + cellWidth * 0.12f + 2f, top + lineHeight, bgPaint)
        }
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
            if (!(charLen == 1 && content[i] == ' ')) {
                canvas.drawText(content, i, i + charLen, x, top + ascent, textPaint)
            }

            col += w
            i += charLen
        }
    }

    private fun bgColorAt(index: Int): Int {
        for (k in bgStarts.indices) {
            if (index >= bgStarts[k] && index < bgEnds[k]) return bgColors[k]
        }
        return 0
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
}
