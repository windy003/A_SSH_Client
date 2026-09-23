package com.sshclient.transfer

import android.app.AlertDialog
import android.graphics.Color
import android.net.Uri
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.documentfile.provider.DocumentFile
import com.sshclient.R
import com.sshclient.databinding.DialogTransferProgressBinding
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * ZMODEM 传输的界面一侧:系统路径选择器(SAF)、进度框,以及 [ZmodemHost] 的实现。
 *
 * 远端敲 rz/sz 之后,协议会话在 IO 线程上跑;需要用户挑文件或目录时,
 * 这里把 IO 线程挂起,等主线程的选择器回来再放行。
 *
 * 必须在 Activity 的 onCreate 里构造 —— registerForActivityResult 要求
 * 在 Activity 进入 STARTED 之前完成注册。
 */
class ZmodemTransferManager(
    private val activity: AppCompatActivity,
    /** 把一行状态信息写进终端显示区。 */
    private val termLog: (String, Int) -> Unit
) : ZmodemHost {

    private enum class Pick { FILES, SAVE_AS }

    @Volatile private var cancelled = false

    // 选择器与 IO 线程之间的交接
    private var latch: CountDownLatch? = null
    @Volatile private var pickedSave: Uri? = null
    @Volatile private var pickedFiles: List<Uri> = emptyList()
    /** 传给“另存为”的预填文件名。 */
    @Volatile private var saveAsName = "file"

    private var progressDialog: AlertDialog? = null
    private var progressBinding: DialogTransferProgressBinding? = null
    private var lastProgressMs = 0L
    @Volatile private var stage = ""

    private val pickFiles = activity.registerForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments()
    ) { uris ->
        pickedFiles = uris
        latch?.countDown()
    }

    /**
     * “另存为”而不是选目录 —— Android 11 起 OPEN_DOCUMENT_TREE 不允许
     * 授予 Download 根目录的访问权,而 CREATE_DOCUMENT 不受这条限制。
     */
    private val saveAs = activity.registerForActivityResult(
        ActivityResultContracts.CreateDocument("application/octet-stream")
    ) { uri ->
        pickedSave = uri
        latch?.countDown()
    }

    // ── 会话入口(由终端读循环在 IO 线程上调用) ────────────────────────────────

    /**
     * 接管 SSH 流跑完一次 ZMODEM 传输。
     *
     * [prefetched] 是读循环为了识别起始帧而已经读走的字节,要原样还给协议层。
     */
    fun runSession(ins: InputStream, out: OutputStream, prefetched: ByteArray): ByteArray {
        cancelled = false
        lastProgressMs = 0          // 否则新一轮传输的第一次进度会被限流吞掉
        stage = ""
        val io = ZmodemIo(ins, out, prefetched)
        try {
            val header = io.readHeader(15_000)
            if (header == null) {
                log("ZMODEM 握手超时", true)
                io.sendCancel()
                return io.drain()
            }
            ZmodemSession(io, this).run(header)
        } catch (e: ZmodemCancelled) {
            // 一定要把远端的 rz/sz 也停掉,否则它会继续发帧,
            // 读循环又会把那些帧当成新会话的开头
            try { io.sendCancel() } catch (_: Exception) {}
            log(e.message ?: "传输已取消", true)
            dumpTrace(io)
        } catch (e: Exception) {
            try { io.sendCancel() } catch (_: Exception) {}
            log("传输出错:${e.message}", true)
            dumpTrace(io)
        } finally {
            dismissProgress()
        }
        // 协议没吃掉的尾巴(通常是下一个 shell 提示符)交还给终端
        return io.drain()
    }

    // ── ZmodemHost ───────────────────────────────────────────────────────────

    override fun createOutput(name: String, size: Long): OutputStream? {
        saveAsName = name
        val uri = awaitPick(Pick.SAVE_AS) as? Uri ?: return null
        // "wt" 而不是 "w":用户若选了已存在的文件,要把旧内容截断掉
        return activity.contentResolver.openOutputStream(uri, "wt")
            ?: throw IOException("无法写入 $name")
    }

    override fun chooseFiles(): List<ZmFile> {
        @Suppress("UNCHECKED_CAST")
        val uris = awaitPick(Pick.FILES) as? List<Uri> ?: return emptyList()
        return uris.mapNotNull { uri ->
            val doc = DocumentFile.fromSingleUri(activity, uri) ?: return@mapNotNull null
            object : ZmFile {
                override val name: String = doc.name ?: "file"
                override val size: Long = doc.length()
                override fun open(): InputStream =
                    activity.contentResolver.openInputStream(uri)
                        ?: throw IOException("无法读取 $name")
            }
        }
    }

    override fun onStart(sending: Boolean) {
        log(if (sending) "检测到远端 rz —— 请选择要上传的文件" else "检测到远端 sz —— 正在接收", false)
    }

    /** 阶段文字占 tvCurrent,握手期间就把进度框亮出来,卡在哪一步一眼可见。 */
    override fun onStage(text: String) {
        stage = text
        ui {
            ensureProgress().tvCurrent.text = text
        }
    }

    override fun onProgress(name: String, done: Long, total: Long) {
        val now = System.currentTimeMillis()
        if (now - lastProgressMs < 100) return      // 限流,别让 UI 拖慢传输
        lastProgressMs = now
        ui {
            val b = ensureProgress()
            // 万一某条路径忘了报阶段,至少还能看见文件名,不要留一行空白
            b.tvCurrent.text = stage.ifEmpty { name }
            b.progressBar.progress =
                if (total > 0) ((done * 100) / total).toInt().coerceIn(0, 100) else 0
            b.tvDetail.text =
                if (total > 0) "${humanSize(done)} / ${humanSize(total)}" else humanSize(done)
        }
    }

    override fun onLog(msg: String, isError: Boolean) = log(msg, isError)

    override fun isCancelled(): Boolean = cancelled

    // ── 选择器:阻塞 IO 线程等主线程结果 ───────────────────────────────────────

    /** 返回 Uri(SAVE_AS)或 List<Uri>(FILES);取消或超时返回 null。 */
    private fun awaitPick(what: Pick): Any? {
        val l = CountDownLatch(1)
        latch = l
        pickedSave = null
        pickedFiles = emptyList()
        // 选择器要盖在进度框之上,先把进度框收起来
        dismissProgress()
        ui {
            when (what) {
                Pick.FILES -> pickFiles.launch(arrayOf("*/*"))
                Pick.SAVE_AS -> saveAs.launch(saveAsName)
            }
        }
        // 用户在挑文件的时候,远端 rz/sz 会不断重发,不必急着超时
        val done = l.await(5, TimeUnit.MINUTES)
        latch = null
        if (!done || cancelled) return null
        return when (what) {
            Pick.FILES -> pickedFiles.ifEmpty { null }
            Pick.SAVE_AS -> pickedSave
        }
    }

    // ── 进度框 ───────────────────────────────────────────────────────────────

    /** 只在真正开始传数据时才弹进度框(选择器期间不该挡着)。 */
    private fun ensureProgress(): DialogTransferProgressBinding {
        progressBinding?.let { return it }
        val b = DialogTransferProgressBinding.inflate(activity.layoutInflater)
        val dialog = AlertDialog.Builder(activity)
            .setTitle(R.string.transfer_in_progress)
            .setView(b.root)
            .setCancelable(false)
            .setNegativeButton(R.string.cancel) { _, _ -> cancelled = true }
            .create()
        dialog.show()
        progressDialog = dialog
        progressBinding = b
        return b
    }

    private fun dismissProgress() = ui {
        try { progressDialog?.dismiss() } catch (_: Exception) {}
        progressDialog = null
        progressBinding = null
    }

    // ── 杂项 ─────────────────────────────────────────────────────────────────

    /** 出错时把帧流水打到终端,用来判断协议到底走到哪一步。 */
    private fun dumpTrace(io: ZmodemIo) {
        val t = io.traceDump()
        if (t.isNotEmpty()) log("帧流水：$t", true)
    }

    private fun log(msg: String, isError: Boolean) = ui {
        termLog("[ZMODEM] $msg\n", if (isError) COLOR_ERR else COLOR_OK)
        if (isError) Toast.makeText(activity, msg, Toast.LENGTH_SHORT).show()
    }

    private fun ui(block: () -> Unit) = activity.runOnUiThread(block)

    private fun humanSize(bytes: Long): String = when {
        bytes < 0 -> "?"
        bytes < 1024 -> "$bytes B"
        bytes < 1024 * 1024 -> String.format("%.1f KB", bytes / 1024.0)
        bytes < 1024L * 1024 * 1024 -> String.format("%.1f MB", bytes / (1024.0 * 1024))
        else -> String.format("%.2f GB", bytes / (1024.0 * 1024 * 1024))
    }

    companion object {
        private val COLOR_OK = Color.parseColor("#008800")
        private val COLOR_ERR = Color.parseColor("#CC0000")
    }
}
