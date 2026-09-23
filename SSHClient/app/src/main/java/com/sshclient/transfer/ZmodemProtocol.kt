package com.sshclient.transfer

import java.io.InputStream
import java.io.OutputStream

/**
 * ZMODEM 协议的常量、CRC、转义与帧编解码。
 *
 * 命名与 lrzsz 保持一致,便于对照原始实现排查问题。
 */
object Zm {
    // 帧前导
    const val ZPAD = 0x2A       // '*'
    const val ZDLE = 0x18       // CAN,转义引导符
    const val ZDLEE = 0x58      // 转义后的 ZDLE ('X' = 0x18 xor 0x40)
    const val ZBIN = 0x41       // 'A' 二进制帧,CRC16
    const val ZHEX = 0x42       // 'B' 十六进制帧,CRC16
    const val ZBIN32 = 0x43     // 'C' 二进制帧,CRC32

    // 帧类型
    const val ZRQINIT = 0       // 发送方请求接收方初始化(远端 sz 的第一帧)
    const val ZRINIT = 1        // 接收方就绪(远端 rz 的第一帧)
    const val ZSINIT = 2
    const val ZACK = 3
    const val ZFILE = 4
    const val ZSKIP = 5
    const val ZNAK = 6
    const val ZABORT = 7
    const val ZFIN = 8
    const val ZRPOS = 9
    const val ZDATA = 10
    const val ZEOF = 11
    const val ZFERR = 12
    const val ZCRC = 13
    const val ZCHALLENGE = 14
    const val ZCOMPL = 15
    const val ZCAN = 16
    const val ZFREECNT = 17
    const val ZCOMMAND = 18
    const val ZSTDERR = 19

    // 数据子包结束符
    const val ZCRCE = 0x68      // 'h' 帧结束,不需应答
    const val ZCRCG = 0x69      // 'i' 帧继续,不需应答
    const val ZCRCQ = 0x6A      // 'j' 帧继续,需要 ZACK
    const val ZCRCW = 0x6B      // 'k' 帧结束,需要 ZACK

    const val ZRUB0 = 0x6C      // 'l' 转义的 0x7F
    const val ZRUB1 = 0x6D      // 'm' 转义的 0xFF

    // ZRINIT 能力位(位于 ZF0)
    const val CANFDX = 0x01     // 全双工
    const val CANOVIO = 0x02    // 可以在磁盘 I/O 期间继续收数据
    const val CANFC32 = 0x20    // 支持 CRC32

    /**
     * 接收方要求把**所有**控制字符都转义。
     *
     * lrzsz 在 PTY 上经常置这个位。漏掉它的话,文本文件也许能侥幸传过去,
     * 二进制文件必然失败 —— 对端会不停地发 ZRPOS 要求重传。
     */
    const val ESCCTL = 0x40

    /** 头部 4 个标志/位置字节里,ZF0 在最高下标。 */
    const val ZF0 = 3
    const val ZF1 = 2

    /** 读取超时(毫秒)后返回的哨兵值。 */
    const val TIMEOUT = -1

    /** 数据子包结束符在 zdlread 里的标记位。 */
    const val GOT_OR = 0x100

    // ── CRC ──────────────────────────────────────────────────────────────────

    /** CRC16/XMODEM:多项式 0x1021,初值 0。 */
    fun crc16(crc: Int, b: Int): Int {
        var c = crc xor ((b and 0xFF) shl 8)
        repeat(8) {
            c = if (c and 0x8000 != 0) (c shl 1) xor 0x1021 else c shl 1
        }
        return c and 0xFFFF
    }

    private val CRC32_TAB = IntArray(256).also { tab ->
        val poly = 0xEDB88320.toInt()
        for (i in 0 until 256) {
            var c = i
            repeat(8) { c = if (c and 1 != 0) (c ushr 1) xor poly else c ushr 1 }
            tab[i] = c
        }
    }

    /** CRC32:zlib 多项式,初值 0xFFFFFFFF。 */
    fun crc32(crc: Int, b: Int): Int =
        CRC32_TAB[(crc xor (b and 0xFF)) and 0xFF] xor (crc ushr 8)

    /** 把 CRC32 也算进收到的 4 个校验字节后,结果应当等于这个魔数。 */
    const val CRC32_MAGIC = 0xDEBB20E3.toInt()

    /** 帧类型的可读名字,用于诊断输出。 */
    fun frameName(type: Int): String = when (type) {
        ZRQINIT -> "ZRQINIT"; ZRINIT -> "ZRINIT"; ZSINIT -> "ZSINIT"
        ZACK -> "ZACK"; ZFILE -> "ZFILE"; ZSKIP -> "ZSKIP"
        ZNAK -> "ZNAK"; ZABORT -> "ZABORT"; ZFIN -> "ZFIN"
        ZRPOS -> "ZRPOS"; ZDATA -> "ZDATA"; ZEOF -> "ZEOF"
        ZFERR -> "ZFERR"; ZCRC -> "ZCRC"; ZCAN -> "ZCAN"
        ZCOMMAND -> "ZCOMMAND"
        else -> "帧#$type"
    }
}

/** 传输被对端取消,或本地主动中止。 */
class ZmodemCancelled(message: String) : Exception(message)

/**
 * 一个已解析的帧头。
 *
 * [crc32] 记录对端用的是 ZBIN32 还是 ZBIN —— 后面挂着的数据子包
 * 必须用同样的校验方式去读。
 */
data class ZmHeader(val type: Int, val bytes: IntArray, val crc32: Boolean = false) {
    /** ZRPOS / ZDATA / ZEOF 等把 4 个字节当小端整数用。 */
    val pos: Long
        get() = (bytes[0].toLong() and 0xFF) or
                ((bytes[1].toLong() and 0xFF) shl 8) or
                ((bytes[2].toLong() and 0xFF) shl 16) or
                ((bytes[3].toLong() and 0xFF) shl 24)

    override fun equals(other: Any?): Boolean =
        other is ZmHeader && type == other.type && bytes.contentEquals(other.bytes)

    override fun hashCode(): Int = 31 * type + bytes.contentHashCode()
}

/** 读到的一个数据子包。 */
class ZmDataPacket(val data: ByteArray, val frameEnd: Int)

/**
 * ZMODEM 的字节通道 —— 在 SSH 的 shell 流之上做带超时的读、带转义的写。
 *
 * [prefetched] 是检测启动序列时已经从流里读出来的字节,必须先还回去。
 */
class ZmodemIo(
    private val ins: InputStream,
    private val out: OutputStream,
    prefetched: ByteArray
) {
    private val buf = ByteArray(8192)
    private var bufLen = 0
    private var bufPos = 0

    /** 多读了一个字节时放回这里 —— 不能依赖 bufPos,因为读取可能已经换过缓冲。 */
    private var pushed = -1

    /**
     * 帧级流水：发出去的记 >，收到的记 <。
     *
     * 出了问题时把它打出来,协议走到哪一步、对端回了什么
     * 一目了然 —— 比靠现象猜可靠得多。
     */
    private val traceItems = mutableListOf<String>()

    private fun addTrace(s: String) {
        if (traceItems.size < 500) traceItems += s
    }

    private fun trace(dir: Char, type: Int, pos: Long) {
        addTrace(dir + Zm.frameName(type) + if (pos != 0L) "($pos)" else "")
    }

    /** 记一笔数据子包的收尾类型(E/G/Q/W),不记内容。 */
    fun traceData(frameEnd: Int) {
        addTrace(
            when (frameEnd) {
                Zm.ZCRCE -> ">e"
                Zm.ZCRCG -> ">g"
                Zm.ZCRCQ -> ">q"
                Zm.ZCRCW -> ">w"
                else -> ">?"
            }
        )
    }

    /**
     * HEX 帧整帧的原始字符 —— 发送方向只有 ZEOF/ZFIN 走 HEX,量很小,
     * 全记下来才好和 lrzsz 的字节逐个对照。
     */
    private fun traceRaw(label: String, bytes: ByteArray) {
        val sb = StringBuilder(label).append('[')
        for (b in bytes) {
            when (val v = b.toInt() and 0xFF) {
                0x0D -> sb.append("\\r")
                0x0A -> sb.append("\\n")
                Zm.ZDLE -> sb.append("<CAN>")
                in 0x20..0x7E -> sb.append(v.toChar())
                else -> sb.append(String.format("\\x%02x", v))
            }
        }
        addTrace(sb.append(']').toString())
    }

    /** 把连续重复的项折叠成 `>g×64`,再按行断开,免得一行长到屏幕外面去。 */
    fun traceDump(): String {
        val folded = mutableListOf<String>()
        var i = 0
        while (i < traceItems.size) {
            var j = i
            while (j < traceItems.size && traceItems[j] == traceItems[i]) j++
            folded += if (j - i > 1) "${traceItems[i]}×${j - i}" else traceItems[i]
            i = j
        }
        return folded.chunked(5).joinToString("\n  ") { it.joinToString(" ") }
    }

    private val outBuf = java.io.ByteArrayOutputStream(4096)

    /** zsendline 需要记住上一个真正发出去的字节,用来决定 CR 是否转义。 */
    private var lastSent = 0

    init {
        if (prefetched.isNotEmpty()) {
            System.arraycopy(prefetched, 0, buf, 0, prefetched.size)
            bufLen = prefetched.size
        }
    }

    // ── 读 ───────────────────────────────────────────────────────────────────

    /** 把多读的一个字节放回去,下次 [read] 先拿到它。 */
    fun unread(b: Int) {
        if (b != Zm.TIMEOUT) pushed = b and 0xFF
    }

    /** 读一个字节;超时或流结束返回 [Zm.TIMEOUT]。 */
    fun read(timeoutMs: Long): Int {
        if (pushed >= 0) {
            val b = pushed
            pushed = -1
            return b
        }
        if (bufPos < bufLen) return buf[bufPos++].toInt() and 0xFF
        val deadline = System.currentTimeMillis() + timeoutMs
        while (true) {
            val avail = ins.available()
            if (avail > 0) {
                bufLen = ins.read(buf, 0, minOf(avail, buf.size))
                bufPos = 0
                if (bufLen <= 0) return Zm.TIMEOUT
                return buf[bufPos++].toInt() and 0xFF
            }
            if (System.currentTimeMillis() >= deadline) return Zm.TIMEOUT
            Thread.sleep(2)
        }
    }

    /** 还有没有立即可读的数据(用于发送过程中顺带查看对端反馈)。 */
    fun hasBuffered(): Boolean = pushed >= 0 || bufPos < bufLen || ins.available() > 0

    /** 瞄一眼下一个字节但不消费;没有立即可读的数据返回 [Zm.TIMEOUT]。 */
    fun peek(): Int {
        if (!hasBuffered()) return Zm.TIMEOUT
        val b = read(50)
        unread(b)
        return b
    }

    /** 丢掉缓冲里已有的字节(对端重发的过期帧、PTY 回显等噪声)。 */
    fun discardBuffered() {
        while (hasBuffered()) {
            if (read(50) == Zm.TIMEOUT) break
        }
    }

    /**
     * 会话结束后缓冲里剩下的、协议没消费掉的字节。
     *
     * 这些通常是紧跟在传输之后的 shell 提示符 —— 必须还给终端,
     * 否则会被一起吞掉,看上去就像丢了一行。
     */
    fun drain(): ByteArray {
        val head = if (pushed >= 0) byteArrayOf(pushed.toByte()) else ByteArray(0)
        pushed = -1
        val rest = if (bufPos < bufLen) buf.copyOfRange(bufPos, bufLen) else ByteArray(0)
        bufPos = bufLen
        return head + rest
    }

    /**
     * 读一个经过 ZDLE 解转义的字节。
     *
     * 返回值带 [Zm.GOT_OR] 标记时,低 8 位是数据子包的结束符(ZCRCE/G/Q/W)。
     */
    fun readEscaped(timeoutMs: Long): Int {
        val c = read(timeoutMs)
        if (c != Zm.ZDLE) return c
        return when (val e = read(timeoutMs)) {
            Zm.ZCRCE, Zm.ZCRCG, Zm.ZCRCQ, Zm.ZCRCW -> e or Zm.GOT_OR
            Zm.ZRUB0 -> 0x7F
            Zm.ZRUB1 -> 0xFF
            Zm.ZDLEE -> Zm.ZDLE
            Zm.TIMEOUT -> Zm.TIMEOUT
            else -> if (e and 0x60 == 0x40) e xor 0x40 else Zm.TIMEOUT
        }
    }

    // ── 写 ───────────────────────────────────────────────────────────────────

    /** 不做任何转义地写一个字节(帧前导等)。 */
    private fun raw(b: Int) {
        outBuf.write(b and 0xFF)
    }

    /**
     * 对端在 ZRINIT 里置了 ESCCTL —— 所有控制字符都得转义。
     *
     * 必须在开始发数据之前按对端声明设好,否则二进制文件传不过去。
     */
    var escapeCtl = false

    /**
     * zsendline:按 ZMODEM 规则转义后写出。
     *
     * 必须转义的是 ZDLE 自身和 XON/XOFF/DLE(含高位版本);
     * CR 只在紧跟 '@' 之后才转义 —— 这是为了躲开 telnet 的 IAC 处理。
     * [escapeCtl] 打开时,再加上所有控制字符。
     */
    fun sendEscaped(value: Int) {
        val c = value and 0xFF
        when (c) {
            Zm.ZDLE -> escape(c)
            0x0D, 0x8D ->
                if (escapeCtl || lastSent and 0x7F == '@'.code) escape(c)
                else { lastSent = c; raw(c) }
            0x10, 0x11, 0x13, 0x90, 0x91, 0x93 -> escape(c)
            // (c and 0x60) == 0 的就是 C0/C1 控制字符
            else ->
                if (escapeCtl && c and 0x60 == 0) escape(c)
                else { lastSent = c; raw(c) }
        }
    }

    private fun escape(c: Int) {
        raw(Zm.ZDLE)
        lastSent = c xor 0x40
        raw(lastSent)
    }

    fun flush() {
        if (outBuf.size() == 0) return
        out.write(outBuf.toByteArray())
        out.flush()
        outBuf.reset()
    }

    // ── 帧 ───────────────────────────────────────────────────────────────────

    /**
     * 找到并解析下一个帧头,跳过途中的垃圾字节。
     *
     * 返回 null 表示超时 —— 调用方决定是重试还是放弃。
     */
    fun readHeader(timeoutMs: Long): ZmHeader? {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            val remain = deadline - System.currentTimeMillis()
            var c = read(remain)
            if (c == Zm.TIMEOUT) return null
            if (c != Zm.ZPAD) continue
            c = read(remain)
            if (c == Zm.ZPAD) c = read(remain)
            if (c != Zm.ZDLE) continue
            val header = when (read(remain)) {
                Zm.ZHEX -> readHexHeader(remain)
                Zm.ZBIN -> readBinHeader(remain, crc32 = false)
                Zm.ZBIN32 -> readBinHeader(remain, crc32 = true)
                else -> null
            }
            if (header != null) {
                trace('<', header.type, header.pos)
                return header
            }
        }
        return null
    }

    private fun hexDigit(timeoutMs: Long): Int {
        val c = read(timeoutMs)
        return when (c) {
            in '0'.code..'9'.code -> c - '0'.code
            in 'a'.code..'f'.code -> c - 'a'.code + 10
            in 'A'.code..'F'.code -> c - 'A'.code + 10
            else -> -1
        }
    }

    private fun hexByte(timeoutMs: Long): Int {
        val hi = hexDigit(timeoutMs)
        val lo = hexDigit(timeoutMs)
        return if (hi < 0 || lo < 0) -1 else (hi shl 4) or lo
    }

    private fun readHexHeader(timeoutMs: Long): ZmHeader? {
        val type = hexByte(timeoutMs)
        if (type < 0) return null
        var crc = Zm.crc16(0, type)
        val bytes = IntArray(4)
        for (i in 0 until 4) {
            val b = hexByte(timeoutMs)
            if (b < 0) return null
            bytes[i] = b
            crc = Zm.crc16(crc, b)
        }
        for (i in 0 until 2) {
            val b = hexByte(timeoutMs)
            if (b < 0) return null
            crc = Zm.crc16(crc, b)
        }
        if (crc and 0xFFFF != 0) return null
        // 吃掉帧尾的 CR / LF / XON;多读的那个字节要还回去,它可能是下一帧的开头
        while (hasBuffered()) {
            val c = read(50)
            if (c != 0x0D && c != 0x0A && c != 0x8A && c != 0x11) {
                unread(c)
                break
            }
        }
        return ZmHeader(type, bytes)
    }

    private fun readBinHeader(timeoutMs: Long, crc32: Boolean): ZmHeader? {
        val type = readEscaped(timeoutMs)
        if (type == Zm.TIMEOUT || type and Zm.GOT_OR != 0) return null
        var c16 = Zm.crc16(0, type)
        var c32 = Zm.crc32(-1, type)
        val bytes = IntArray(4)
        for (i in 0 until 4) {
            val b = readEscaped(timeoutMs)
            if (b == Zm.TIMEOUT || b and Zm.GOT_OR != 0) return null
            bytes[i] = b
            c16 = Zm.crc16(c16, b)
            c32 = Zm.crc32(c32, b)
        }
        val crcLen = if (crc32) 4 else 2
        for (i in 0 until crcLen) {
            val b = readEscaped(timeoutMs)
            if (b == Zm.TIMEOUT || b and Zm.GOT_OR != 0) return null
            c16 = Zm.crc16(c16, b)
            c32 = Zm.crc32(c32, b)
        }
        val ok = if (crc32) c32 == Zm.CRC32_MAGIC else c16 and 0xFFFF == 0
        return if (ok) ZmHeader(type, bytes, crc32) else null
    }

    /** 发一个 HEX 帧(所有控制帧都用它,最稳)。 */
    fun sendHexHeader(type: Int, bytes: IntArray = IntArray(4)) {
        raw(Zm.ZPAD); raw(Zm.ZPAD); raw(Zm.ZDLE); raw(Zm.ZHEX)
        var crc = Zm.crc16(0, type)
        putHex(type)
        for (b in bytes) {
            putHex(b)
            crc = Zm.crc16(crc, b)
        }
        // 注意：lrzsz 的 updcrc 是 shift8(crc)^cp，比标准 CRC-16 慢半拍，
        // 所以它发送前要补两个零字节追回来。这里的 crc16 是标准形式，
        // 本身就没有这半拍 —— 再补零就多算一轮，对端会直接丢帧。
        putHex((crc shr 8) and 0xFF)
        putHex(crc and 0xFF)
        // lrzsz 发的是 CR 和 LF|0x80
        raw(0x0D); raw(0x8A)
        // ZFIN 与 ZACK 之后不发 XON,避免干扰对端
        if (type != Zm.ZFIN && type != Zm.ZACK) raw(0x11)
        lastSent = 0
        trace('>', type, ZmHeader(type, bytes).pos)
        // 发送方向只有 ZEOF/ZFIN 走 HEX,把整帧字节留下来好逐字节核对
        traceRaw(Zm.frameName(type), outBuf.toByteArray())
        flush()
    }

    private fun putHex(b: Int) {
        val digits = "0123456789abcdef"
        raw(digits[(b shr 4) and 0x0F].code)
        raw(digits[b and 0x0F].code)
    }

    /**
     * 发一个二进制帧头(数据帧用,比 HEX 省一半字节)。
     *
     * [crc32] 必须跟对端 ZRINIT 里声明的能力一致 —— 对端没声明 CANFC32
     * 却收到 ZBIN32,会直接把这一帧当垃圾丢掉。
     */
    fun sendBinHeader(type: Int, pos: Long, crc32: Boolean) {
        trace('>', type, pos)
        raw(Zm.ZPAD); raw(Zm.ZDLE); raw(if (crc32) Zm.ZBIN32 else Zm.ZBIN)
        lastSent = 0
        var c32 = Zm.crc32(-1, type)
        var c16 = Zm.crc16(0, type)
        sendEscaped(type)
        for (i in 0 until 4) {
            val b = ((pos shr (8 * i)) and 0xFF).toInt()
            c32 = Zm.crc32(c32, b)
            c16 = Zm.crc16(c16, b)
            sendEscaped(b)
        }
        if (crc32) {
            val c = c32.inv()
            for (i in 0 until 4) sendEscaped((c shr (8 * i)) and 0xFF)
        } else {
            val c = c16      // 同上:标准 crc16 不补零
            sendEscaped((c shr 8) and 0xFF); sendEscaped(c and 0xFF)
        }
    }

    /** 发一个数据子包,[frameEnd] 为 ZCRCE/G/Q/W。 */
    fun sendData(data: ByteArray, len: Int, frameEnd: Int, crc32: Boolean) {
        traceData(frameEnd)
        var c32 = -1
        var c16 = 0
        for (i in 0 until len) {
            val b = data[i].toInt() and 0xFF
            c32 = Zm.crc32(c32, b)
            c16 = Zm.crc16(c16, b)
            sendEscaped(b)
        }
        raw(Zm.ZDLE); raw(frameEnd)
        if (crc32) {
            val c = Zm.crc32(c32, frameEnd).inv()
            for (i in 0 until 4) sendEscaped((c shr (8 * i)) and 0xFF)
        } else {
            val c = Zm.crc16(c16, frameEnd)   // 同上:标准 crc16 不补零
            sendEscaped((c shr 8) and 0xFF); sendEscaped(c and 0xFF)
        }
        flush()
    }

    /**
     * 读一个数据子包。CRC 校验失败返回 null。
     *
     * [crc32] 由对端在帧头里用 ZBIN 还是 ZBIN32 决定。
     */
    fun readData(timeoutMs: Long, crc32: Boolean, max: Int = 8192): ZmDataPacket? {
        val out = ByteArray(max)
        var n = 0
        var c16 = 0
        var c32 = -1
        while (true) {
            val c = readEscaped(timeoutMs)
            if (c == Zm.TIMEOUT) return null
            if (c and Zm.GOT_OR != 0) {
                val frameEnd = c and 0xFF
                c16 = Zm.crc16(c16, frameEnd)
                c32 = Zm.crc32(c32, frameEnd)
                val crcLen = if (crc32) 4 else 2
                for (i in 0 until crcLen) {
                    val b = readEscaped(timeoutMs)
                    if (b == Zm.TIMEOUT || b and Zm.GOT_OR != 0) return null
                    c16 = Zm.crc16(c16, b)
                    c32 = Zm.crc32(c32, b)
                }
                val ok = if (crc32) c32 == Zm.CRC32_MAGIC else c16 and 0xFFFF == 0
                return if (ok) ZmDataPacket(out.copyOf(n), frameEnd) else null
            }
            if (n >= max) return null
            out[n++] = c.toByte()
            c16 = Zm.crc16(c16, c)
            c32 = Zm.crc32(c32, c)
        }
    }

    /** 发 ZMODEM 的取消序列:连续 CAN 会让对端 rz/sz 立刻退出。 */
    fun sendCancel() {
        repeat(8) { raw(Zm.ZDLE) }
        repeat(8) { raw(0x08) }
        lastSent = 0
        flush()
    }
}

/** 把 4 字节位置打包成帧头用的小端数组。 */
fun posBytes(pos: Long): IntArray = IntArray(4) { ((pos shr (8 * it)) and 0xFF).toInt() }
