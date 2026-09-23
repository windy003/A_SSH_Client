package com.sshclient.transfer

import java.io.InputStream
import java.io.OutputStream

/** 一个待上传的本地文件。 */
interface ZmFile {
    val name: String
    val size: Long
    fun open(): InputStream
}

/** 会话与界面之间的往来。选择类方法会阻塞在 IO 线程上等用户操作。 */
interface ZmodemHost {
    /**
     * 远端在跑 sz:为远端送来的文件挑一个保存位置。
     *
     * 等收到 ZFILE、知道文件名和大小之后才调用,这样"另存为"能把名字预填好。
     * 返回 null 表示用户放弃这个文件,会话会告诉远端跳过它。
     */
    fun createOutput(name: String, size: Long): OutputStream?

    /** 远端在跑 rz:让用户选要上传的文件。返回空表示取消传输。 */
    fun chooseFiles(): List<ZmFile>

    fun onStart(sending: Boolean)

    /** 当前处于协议的哪一步 —— 直接显示在进度框上。 */
    fun onStage(text: String)
    fun onProgress(name: String, done: Long, total: Long)
    fun onLog(msg: String, isError: Boolean)
    fun isCancelled(): Boolean
}

/**
 * 一次 ZMODEM 传输会话。
 *
 * 由 [ZmodemIo] 直接读写 SSH shell 流 —— 会话期间终端渲染必须让路,
 * 结束后再把流还给读循环。
 */
class ZmodemSession(
    private val io: ZmodemIo,
    private val host: ZmodemHost
) {

    companion object {
        private const val HEADER_TIMEOUT = 15_000L
        private const val DATA_TIMEOUT = 20_000L
        /** 每个数据子包的大小,1KB 是 lrzsz 的常用值。 */
        private const val BLOCK = 1024

        /**
         * 每发这么多字节就用 ZCRCQ 要一次 ZACK。
         *
         * 纯流式(全程 ZCRCG)在链路正常时最快,但只要远端停止读取,
         * SSH 的窗口就会填满、JSch 的 write 会无限阻塞 —— 界面上就是
         * 进度框卡死且永远等不到超时。定期讨一个 ACK 能把这种死锁
         * 变成一次可报告的超时。
         */
        private const val ACK_INTERVAL = 64 * 1024
    }

    /** 对端在 ZRINIT 里声明支持 CRC32 时才用 ZBIN32 发数据。 */
    private var useCrc32 = false

    /**
     * 跑完整个会话。[first] 是触发进入 ZMODEM 的那一帧:
     * ZRQINIT 说明远端是 sz(我们收),ZRINIT 说明远端是 rz(我们发)。
     */
    fun run(first: ZmHeader) {
        when (first.type) {
            Zm.ZRQINIT -> receive()
            Zm.ZRINIT -> {
                // 必须照对端 ZRINIT 里声明的能力来发,尤其是 ESCCTL ——
                // 漏了它二进制文件一定传不过去
                val flags = first.bytes[Zm.ZF0]
                useCrc32 = flags and Zm.CANFC32 != 0
                io.escapeCtl = flags and Zm.ESCCTL != 0
                host.onLog(
                    "远端能力:CRC32=${if (useCrc32) "是" else "否"} " +
                        "转义控制字符=${if (io.escapeCtl) "是" else "否"}",
                    false
                )
                send()
            }
            else -> throw ZmodemCancelled("不认识的起始帧 ${first.type}")
        }
    }

    private fun checkCancel() {
        if (host.isCancelled()) {
            io.sendCancel()
            throw ZmodemCancelled("已取消")
        }
    }

    // ── 接收(远端 sz) ────────────────────────────────────────────────────────

    private fun receive() {
        host.onStart(sending = false)
        host.onStage("等待远端发来文件信息")
        // 先应答握手 —— 保存位置等收到 ZFILE、知道文件名之后再问用户
        sendZrinit()

        try {
            while (true) {
                checkCancel()
                val h = io.readHeader(HEADER_TIMEOUT)
                if (h == null) {
                    // 对端可能没收到我们的 ZRINIT,再吆喝一次
                    sendZrinit()
                    val retry = io.readHeader(HEADER_TIMEOUT)
                        ?: throw ZmodemCancelled("等待远端响应超时")
                    if (!handle(retry)) break
                    continue
                }
                if (!handle(h)) break
            }
        } finally {
            closeQuietly(current)
        }
        host.onLog("接收完成:共 $received 个文件", false)
    }

    // 接收过程中的可变状态(放在字段里,handle 分支要跨帧共享)
    private var current: OutputStream? = null
    private var currentName = ""
    private var currentTotal = 0L
    private var currentDone = 0L
    private var received = 0

    /** 处理一帧;返回 false 表示会话正常结束。 */
    private fun handle(h: ZmHeader): Boolean {
        when (h.type) {
            Zm.ZRQINIT -> sendZrinit()

            Zm.ZFILE -> {
                val pkt = io.readData(DATA_TIMEOUT, h.crc32)
                if (pkt == null) {
                    io.sendHexHeader(Zm.ZNAK)
                    return true
                }
                parseFileHeader(pkt.data)
                closeQuietly(current)
                val sizeText = if (currentTotal >= 0) "$currentTotal 字节" else "大小未知"
                host.onLog("远端要发送 $currentName（$sizeText）", false)
                host.onStage("选择 $currentName 的保存位置")
                val out = host.createOutput(currentName, currentTotal)
                if (out == null) {
                    // 用户放弃了这个文件 —— 让远端跳过,会话继续
                    current = null
                    host.onLog("已跳过 $currentName", false)
                    io.sendHexHeader(Zm.ZSKIP)
                    return true
                }
                current = out
                currentDone = 0
                host.onStage("接收中：$currentName")
                host.onProgress(currentName, 0, currentTotal)
                io.sendHexHeader(Zm.ZRPOS, posBytes(0))
            }

            Zm.ZDATA -> {
                if (current == null) {
                    // 没有对应的 ZFILE,要求对端从头来
                    io.sendHexHeader(Zm.ZRPOS, posBytes(currentDone))
                    return true
                }
                if (!readDataFrames(h)) {
                    io.sendHexHeader(Zm.ZRPOS, posBytes(currentDone))
                }
            }

            Zm.ZEOF -> {
                closeQuietly(current)
                current = null
                received++
                host.onProgress(currentName, currentDone, currentTotal)
                host.onLog("已保存 $currentName（$currentDone 字节）", false)
                host.onStage("等待下一个文件")
                sendZrinit()
            }

            Zm.ZFIN -> {
                host.onStage("收尾")
                io.sendHexHeader(Zm.ZFIN)
                // 对端最后会发 "OO",读掉免得落进终端
                io.read(1000); io.read(1000)
                return false
            }

            Zm.ZCAN, Zm.ZABORT -> throw ZmodemCancelled("远端中止了传输")
        }
        return true
    }

    /** 读一个 ZDATA 帧后面挂着的全部数据子包。返回 false 表示 CRC 出错需要重传。 */
    private fun readDataFrames(h: ZmHeader): Boolean {
        while (true) {
            checkCancel()
            val pkt = io.readData(DATA_TIMEOUT, h.crc32) ?: return false
            current?.write(pkt.data)
            currentDone += pkt.data.size
            host.onProgress(currentName, currentDone, currentTotal)
            when (pkt.frameEnd) {
                Zm.ZCRCG -> {}                                   // 继续,无需应答
                Zm.ZCRCQ -> io.sendHexHeader(Zm.ZACK, posBytes(currentDone))
                Zm.ZCRCE -> return true                          // 帧结束
                Zm.ZCRCW -> {                                    // 帧结束且要应答
                    io.sendHexHeader(Zm.ZACK, posBytes(currentDone))
                    return true
                }
            }
        }
    }

    /** ZFILE 的子包是 "文件名\0 长度 修改时间 模式 ..."。 */
    private fun parseFileHeader(data: ByteArray) {
        val zero = data.indexOf(0.toByte()).let { if (it < 0) data.size else it }
        val raw = String(data, 0, zero, Charsets.UTF_8)
        // 只取基名 —— 远端给的路径一律不信任
        currentName = raw.substringAfterLast('/').substringAfterLast('\\')
            .ifBlank { "zmodem_file" }
        currentTotal = if (zero + 1 < data.size) {
            String(data, zero + 1, data.size - zero - 1, Charsets.UTF_8)
                .trim().split(' ').firstOrNull()?.toLongOrNull() ?: -1L
        } else -1L
    }

    private fun sendZrinit() {
        val flags = IntArray(4)
        flags[Zm.ZF0] = Zm.CANFDX or Zm.CANOVIO or Zm.CANFC32
        io.sendHexHeader(Zm.ZRINIT, flags)
    }

    // ── 发送(远端 rz) ────────────────────────────────────────────────────────

    private fun send() {
        host.onStart(sending = true)
        val files = host.chooseFiles()
        if (files.isEmpty()) {
            io.sendCancel()
            host.onLog("已取消发送", false)
            return
        }

        // 用户挑文件的这段时间里,远端 rz 会一直重发 ZRINIT。
        // 这些过期帧必须先丢掉,否则后面读 ZFILE 的应答时会先读到它们。
        io.discardBuffered()
        host.onStage("正在与远端 rz 协商")
        host.onLog("已选择 ${files.size} 个文件,开始传输", false)

        var sent = 0
        for (file in files) {
            checkCancel()
            if (sendOne(file)) sent++
        }

        host.onStage("收尾")
        io.sendHexHeader(Zm.ZFIN)
        io.readHeader(5_000)          // 对端的 ZFIN
        io.sendEscaped('O'.code); io.sendEscaped('O'.code); io.flush()
        host.onLog("发送完成:共 $sent 个文件", false)
    }

    /** 发送一个文件;返回 false 表示对端跳过了它。 */
    private fun sendOne(file: ZmFile): Boolean {
        host.onLog("发送 ${file.name}", false)

        // ZFILE + 文件信息子包
        io.sendBinHeader(Zm.ZFILE, 0, useCrc32)
        val info = buildString {
            append(file.name).append('\u0000')
            append(file.size).append(" 0 644 0 1 ").append(file.size).append('\u0000')
        }.toByteArray(Charsets.UTF_8)
        io.sendData(info, info.size, Zm.ZCRCW, useCrc32)

        host.onStage("已发送文件信息，等待远端 ZRPOS")

        // 等对端告诉我们从哪里开始
        var pos = 0L
        var waited = 0
        while (true) {
            val h = io.readHeader(HEADER_TIMEOUT)
                ?: throw ZmodemCancelled("等待远端 ZRPOS 超时(远端 rz 可能已退出)")
            when (h.type) {
                Zm.ZRPOS -> { pos = h.pos; break }
                Zm.ZSKIP -> { host.onLog("远端跳过 ${file.name}", false); return false }
                Zm.ZCAN, Zm.ZABORT -> throw ZmodemCancelled("远端中止了传输")
                else -> {
                    // ZRINIT 等过期帧,读掉继续等;但别无限转下去
                    if (++waited > 32) throw ZmodemCancelled("远端一直没有回应 ZRPOS")
                }
            }
        }

        host.onLog("远端已就绪,开始传 ${file.name}(${file.size} 字节)", false)
        host.onStage("传输中：${file.name}")
        var end = streamFile(file, pos)

        host.onStage("已发完（$end 字节），等待远端确认")
        io.sendHexHeader(Zm.ZEOF, posBytes(end))
        // 等对端 ZRINIT,表示这个文件收妥了
        var rounds = 0
        while (true) {
            val h = io.readHeader(HEADER_TIMEOUT)
            if (h == null) {
                host.onLog("远端在 ZEOF 之后没有回应(已发 $end 字节)", true)
                return true
            }
            when (h.type) {
                Zm.ZRINIT -> return true
                Zm.ZRPOS -> {
                    if (h.pos >= end) {
                        // 远端其实已经收全了,只是没认下这个 ZEOF —— 重发即可,
                        // 再走一遍 streamFile 只会多发一个空的 ZDATA 帧
                        host.onLog("远端未确认 ZEOF($end),重发", true)
                        io.sendHexHeader(Zm.ZEOF, posBytes(end))
                    } else {
                        host.onLog("ZEOF 后远端要求从 ${h.pos} 重传(已发 $end 字节)", true)
                        end = streamFile(file, h.pos)
                        io.sendHexHeader(Zm.ZEOF, posBytes(end))
                    }
                }
                Zm.ZCAN, Zm.ZABORT -> throw ZmodemCancelled("远端中止了传输")
                else -> host.onLog("ZEOF 之后收到意外帧 ${Zm.frameName(h.type)}", true)
            }
            if (++rounds > 4) throw ZmodemCancelled("远端反复要求重传,放弃")
        }
    }


    /**
     * 从 [from] 开始把文件流式发出去,返回实际发送到的位置。
     *
     * 返回值要用来发 ZEOF —— 不能拿 [ZmFile.size] 顶替:SAF 报的长度
     * 未必准(有的 provider 干脆返回 0),长度对不上远端就会一直等下去。
     */
    private fun streamFile(file: ZmFile, from: Long): Long {
        io.sendBinHeader(Zm.ZDATA, from, useCrc32)
        val buf = ByteArray(BLOCK)
        var pos = from
        file.open().use { ins ->
            // SAF 的流 skip() 可能返回 0 而并非到了末尾 —— 那时必须退回用 read 跳,
            // 否则会从文件开头读却当成 from 处的数据,发出去的内容整个错位
            var skipped = 0L
            val waste = ByteArray(8192)
            while (skipped < from) {
                val s = ins.skip(from - skipped)
                if (s > 0) { skipped += s; continue }
                val want = minOf(from - skipped, waste.size.toLong()).toInt()
                val r = ins.read(waste, 0, want)
                if (r <= 0) break
                skipped += r
            }
            if (skipped < from) throw ZmodemCancelled("无法定位到 $from 字节处,文件可能已变动")
            var sinceAck = 0
            while (true) {
                checkCancel()
                val n = ins.read(buf)
                if (n <= 0) break        // 以流的实际结束为准,不相信 file.size
                pos += n

                // 攒够一批就讨个 ZACK,免得远端不读时把我们堵死在 write 上
                sinceAck += n
                val needAck = sinceAck >= ACK_INTERVAL
                if (needAck) sinceAck = 0

                io.sendData(buf, n, if (needAck) Zm.ZCRCQ else Zm.ZCRCG, useCrc32)
                host.onProgress(file.name, pos, file.size)

                if (needAck) awaitAck(file, pos)?.let { return it }

                // 顺带看一眼对端有没有喊停或要求重传。
                // 只有当缓冲里真的以 ZPAD 打头时才去解析帧 —— 否则那是 PTY 回显之类的
                // 噪声,必须快速丢掉:在这里每包等上几百毫秒,大文件就等于卡死。
                while (io.hasBuffered()) {
                    if (io.peek() != Zm.ZPAD) { io.read(10); continue }
                    val h = io.readHeader(200) ?: break
                    when (h.type) {
                        // 链路出错要求回退 —— 重开文件从那里再来
                        Zm.ZRPOS -> if (h.pos != pos) {
                            host.onLog("远端要求从 ${h.pos} 重传(已发到 $pos)", true)
                            return streamFile(file, h.pos)
                        }
                        Zm.ZCAN, Zm.ZABORT -> throw ZmodemCancelled("远端中止了传输")
                        Zm.ZSKIP -> return pos
                        else -> {}
                    }
                }
            }
            // 数据发完了,补一个空的 ZCRCE 收帧,告诉远端这一帧到此为止
            io.sendData(buf, 0, Zm.ZCRCE, useCrc32)
        }
        return pos
    }

    /**
     * 等 ZCRCQ 的应答。
     *
     * 返回 null 表示可以接着往下发;返回非 null 表示这个文件已经在重传路径里
     * 发完了,值就是最终位置,调用方直接把它当作 streamFile 的结果。
     *
     * 远端如果不回应,这里会超时抛错 —— 比阻塞在 write 上无声卡死强得多。
     */
    private fun awaitAck(file: ZmFile, pos: Long): Long? {
        val deadline = System.currentTimeMillis() + HEADER_TIMEOUT
        while (System.currentTimeMillis() < deadline) {
            val h = io.readHeader(deadline - System.currentTimeMillis()) ?: continue
            when (h.type) {
                Zm.ZACK -> return null
                Zm.ZRPOS -> {
                    if (h.pos == pos) return null
                    return streamFile(file, h.pos)   // 要求回退,从那里重发
                }
                Zm.ZSKIP -> return pos
                Zm.ZCAN, Zm.ZABORT -> throw ZmodemCancelled("远端中止了传输")
                else -> {}
            }
        }
        throw ZmodemCancelled("远端在 $pos 字节处停止应答(传输中断)")
    }

    private fun closeQuietly(s: OutputStream?) {
        try { s?.flush(); s?.close() } catch (_: Exception) {}
    }
}
