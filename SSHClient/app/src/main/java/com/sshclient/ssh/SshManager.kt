package com.sshclient.ssh

import com.jcraft.jsch.ChannelShell
import com.jcraft.jsch.JSch
import com.jcraft.jsch.Session
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.InputStream
import java.io.OutputStream
import java.util.Properties

class SshManager {
    private var session: Session? = null
    private var channel: ChannelShell? = null
    var outputStream: OutputStream? = null
    var inputStream: InputStream? = null

    suspend fun connect(
        host: String,
        port: Int,
        username: String,
        password: String,
        privateKey: String = ""
    ) = withContext(Dispatchers.IO) {
        val jsch = JSch()
        if (privateKey.isNotBlank()) {
            val keyBytes = privateKey.toByteArray()
            jsch.addIdentity("key", keyBytes, null, null)
        }
        session = jsch.getSession(username, host, port).apply {
            if (privateKey.isBlank()) setPassword(password)
            val config = Properties().apply {
                put("StrictHostKeyChecking", "no")
                put("PreferredAuthentications", if (privateKey.isNotBlank()) "publickey" else "password")
            }
            setConfig(config)
            connect(15000)
        }
        channel = (session!!.openChannel("shell") as ChannelShell).apply {
            setPtyType("xterm-256color")
            setPtySize(220, 50, 0, 0)
            connect()
        }
        outputStream = channel!!.outputStream
        inputStream = channel!!.inputStream
    }

    fun resize(cols: Int, rows: Int) {
        channel?.setPtySize(cols, rows, 0, 0)
    }

    fun isConnected(): Boolean = session?.isConnected == true && channel?.isConnected == true

    fun disconnect() {
        try {
            channel?.disconnect()
            session?.disconnect()
        } catch (_: Exception) {}
        channel = null
        session = null
        outputStream = null
        inputStream = null
    }
}
