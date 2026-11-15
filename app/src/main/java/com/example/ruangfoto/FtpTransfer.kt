package com.ruangfoto.app

import org.apache.commons.net.ftp.FTP
import org.apache.commons.net.ftp.FTPClient
import org.apache.commons.net.ftp.FTPReply
import org.apache.commons.net.ftp.FTPSClient
import java.io.File
import java.io.IOException
import java.io.InterruptedIOException
import java.time.Duration
import java.util.UUID
import javax.net.ssl.SSLContext

internal data class FtpSettings(
    val host: String, val port: Int, val user: String, val password: String,
    val folder: String, val tls: Boolean
) {
    fun validate() {
        require(host.isNotBlank() && host.none { it.isWhitespace() || it in "/\\\r\n\u0000" }) { "Enter a hostname or IP, without a URL or path" }
        require(port in 1..65535) { "Port must be 1–65535" }
        require(user.isNotBlank()) { "Username is required" }
        require(listOf(user, password, folder).none { s -> s.any { it == '\r' || it == '\n' || it == '\u0000' } }) { "Control characters are not allowed" }
        require(folder.isNotBlank() && '\\' !in folder && folder.split('/').none { it == ".." }) { "Enter a remote folder without '..'" }
    }
    fun destination() = listOf(host.lowercase(), port.toString(), user, folder, tls.toString()).joinToString("\n")
}

internal class FtpFailure(message: String, val permanent: Boolean = false, val configuration: Boolean = false) : IOException(message)

/** One client per transfer. Cancellation closes sockets, including a blocked data transfer. */
internal class FtpTransfer {
    @Volatile private var client: FTPClient? = null
    @Volatile private var cancelled = false

    fun cancel() {
        cancelled = true
        try { client?.disconnect() } catch (_: IOException) { }
    }

    private fun connect(settings: FtpSettings): FTPClient {
        settings.validate()
        checkCancelled()
        val ftp = if (settings.tls) {
            // Commons Net's default trust manager checks expiry only: use the platform CA store.
            FTPSClient(false, SSLContext.getInstance("TLS").apply { init(null, null, null) }).apply {
                isEndpointCheckingEnabled = true
            }
        } else FTPClient()
        client = ftp
        ftp.connectTimeout = 15_000
        ftp.defaultTimeout = 20_000
        ftp.setDataTimeout(Duration.ofSeconds(20))
        ftp.controlEncoding = "UTF-8"
        checkCancelled()
        ftp.connect(settings.host, settings.port)
        checkCancelled()
        if (!FTPReply.isPositiveCompletion(ftp.replyCode)) fail(ftp, "Server refused the connection")
        if (!ftp.login(settings.user, settings.password)) throw FtpFailure("Login rejected; check username and password", true, true)
        if (ftp is FTPSClient) { ftp.execPBSZ(0); ftp.execPROT("P") }
        ftp.enterLocalPassiveMode()
        if (!ftp.setFileType(FTP.BINARY_FILE_TYPE)) fail(ftp, "Server rejected binary transfer")
        if (!ftp.changeWorkingDirectory(settings.folder)) throw FtpFailure("Remote folder unavailable; check path and permissions", true, true)
        return ftp
    }

    fun test(settings: FtpSettings) {
        val name = ".ruangfoto-test-${UUID.randomUUID()}"
        try {
            val ftp = connect(settings)
            if (!ftp.storeFile("$name.part", byteArrayOf(0, 10, 13, -1).inputStream())) fail(ftp, "Remote folder is not writable")
            if (!ftp.rename("$name.part", name)) fail(ftp, "Server cannot finalize uploads (rename required)")
            if (!ftp.deleteFile(name)) fail(ftp, "Server cannot delete the connection-test file")
        } finally {
            try { client?.deleteFile("$name.part") } catch (_: IOException) { }
            try { client?.deleteFile(name) } catch (_: IOException) { }
            close()
        }
    }

    fun upload(settings: FtpSettings, file: File, expectedSize: Long, expectedModified: Long,
               remoteName: String, wasFinalizing: Boolean, beforeRename: () -> Unit,
               progress: (Long) -> Unit) {
        require(remoteName.matches(Regex("[A-Za-z0-9._-]+"))) { "Invalid remote filename" }
        if (!file.isFile || file.length() != expectedSize || file.lastModified() != expectedModified) {
            throw FtpFailure("Local photo changed or disappeared; original upload was not sent", true)
        }
        try {
            val ftp = connect(settings)
            // The UUID name belongs to this durable job. Only a recorded rename attempt may recover it.
            val existing = ftp.mlistFile(remoteName) ?: ftp.listFiles(remoteName).firstOrNull { it.name == remoteName }
            if (existing != null) {
                if (wasFinalizing && existing.isFile && existing.size == expectedSize) return
                throw FtpFailure("Remote filename conflict; refusing to overwrite an existing photo", true)
            }
            var sent = 0L
            val listener = object : org.apache.commons.net.io.CopyStreamAdapter() {
                override fun bytesTransferred(totalBytesTransferred: Long, bytesTransferred: Int, streamSize: Long) {
                    checkCancelled()
                    sent = totalBytesTransferred
                    progress(sent)
                }
            }
            ftp.copyStreamListener = listener
            val stored = file.inputStream().buffered().use { ftp.storeFile("$remoteName.part", it) }
            checkCancelled()
            if (!stored) fail(ftp, "Photo transfer rejected")
            if (sent != expectedSize || file.length() != expectedSize || file.lastModified() != expectedModified) {
                throw FtpFailure("Local photo changed during upload; temporary remote file was not finalized", true)
            }
            beforeRename() // Persist BEFORE RNFR/RNTO: recover a lost reply without creating a duplicate.
            if (!ftp.rename("$remoteName.part", remoteName)) fail(ftp, "Server could not finalize the photo")
        } finally { close() }
    }

    private fun fail(ftp: FTPClient, message: String): Nothing {
        // Never surface untrusted replies, which may echo USER/PASS or filesystem details.
        throw FtpFailure("$message (FTP ${ftp.replyCode})", ftp.replyCode in 500..599, ftp.replyCode in 500..599)
    }
    private fun checkCancelled() { if (cancelled || Thread.currentThread().isInterrupted) throw InterruptedIOException("Transfer paused") }
    private fun close() { try { client?.disconnect() } catch (_: IOException) { }; client = null }
}
