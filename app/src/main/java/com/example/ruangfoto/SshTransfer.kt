package com.ruangfoto.app

import net.schmizz.sshj.DefaultConfig
import net.schmizz.sshj.SSHClient
import net.schmizz.sshj.common.Buffer
import net.schmizz.sshj.common.StreamCopier
import net.schmizz.sshj.sftp.SFTPClient
import net.schmizz.sshj.transport.verification.HostKeyVerifier
import net.schmizz.sshj.xfer.FileSystemFile
import net.schmizz.sshj.xfer.TransferListener
import java.io.File
import java.io.IOException
import net.schmizz.sshj.userauth.UserAuthException
import java.io.InterruptedIOException
import java.security.MessageDigest
import java.security.PublicKey
import java.util.Base64
import java.util.UUID
import java.util.concurrent.TimeUnit


/** SSH transports use explicit SHA-256 host-key pinning; an unpinned discovery sends no credentials. */
internal class SshTransfer : TransferClient {
    @Volatile private var ssh: SSHClient? = null
    @Volatile private var cancelled = false

    override fun cancel() {
        cancelled = true
        try { ssh?.disconnect() } catch (_: IOException) { }
    }
    override fun listDirectories(settings: TransferSettings, folder: String): List<String> {
        val browsing = settings.copy(folder = folder)
        requireSsh(browsing)
        val connection = connect(browsing)
        return try {
            if (settings.protocol == TransferProtocol.SFTP) {
                connection.newSFTPClient().use { sftp ->
                    sftp.ls(folder).filter { it.isDirectory && it.name !in setOf(".", "..") }.map { it.name }
                }
            } else {
                val (status, output) = runRemote(connection,
                    "find ${shellQuote(folder)} -mindepth 1 -maxdepth 1 -type d -print")
                if (status != 0) throw TransferFailure("This SCP server cannot list folders; enter the remote path manually", true, true)
                val prefix = if (folder == "/") "/" else "${folder.trimEnd('/')}/"
                output.lineSequence().map { it.removePrefix(prefix) }
                    .filter { it.isNotBlank() && '/' !in it && it !in setOf(".", "..") }.toList()
            }
        } finally {
            try { connection.disconnect() } catch (_: IOException) { }
            ssh = null
        }
    }

    override fun test(settings: TransferSettings) {
        requireSsh(settings)
        val file = File.createTempFile("ruangfoto-ssh-test", ".bin")
        file.writeBytes(byteArrayOf(0, 10, 13, -1))
        val name = ".ruangfoto-test-${UUID.randomUUID()}"
        try {
            val connection = connect(settings)
            checkRemoteDirectory(connection, settings)
            put(connection, settings, file, remotePath(settings.folder, "$name.part"), {})
            rename(connection, settings, remotePath(settings.folder, "$name.part"), name)
            if (settings.protocol == TransferProtocol.SFTP) {
                connection.newSFTPClient().use { it.rm(remotePath(settings.folder, name)) }
            } else {
                runRemote(connection, "rm -f -- ${shellQuote(remotePath(settings.folder, name))}")
            }
        } finally {
            try { ssh?.disconnect() } catch (_: IOException) { }
            ssh = null
            file.delete()
        }
    }

    override fun upload(settings: TransferSettings, file: File, expectedSize: Long, expectedModified: Long,
                        remoteName: String, wasFinalizing: Boolean, beforeRename: () -> Unit,
                        progress: (Long) -> Unit) {
        requireSsh(settings)
        require(remoteName.matches(Regex("[A-Za-z0-9._-]+"))) { "Invalid remote filename" }
        if (!file.isFile || file.length() != expectedSize || file.lastModified() != expectedModified) {
            throw TransferFailure("Local photo changed or disappeared; upload was not sent", true)
        }
        val connection = connect(settings)
        try {
            checkRemoteDirectory(connection, settings)
            val finalPath = remotePath(settings.folder, remoteName)
            val partPath = "$finalPath.part"
            val existingSize = remoteSize(connection, settings, finalPath)
            if (existingSize != null) {
                if (wasFinalizing && existingSize == expectedSize) return
                throw TransferFailure("Remote filename conflict; refusing to overwrite an existing photo", true)
            }
            put(connection, settings, file, partPath, progress)
            checkCancelled()
            if (file.length() != expectedSize || file.lastModified() != expectedModified) {
                throw TransferFailure("Local photo changed during upload; remote temporary file was not finalized", true)
            }
            beforeRename()
            rename(connection, settings, partPath, remoteName)
        } finally {
            try { connection.disconnect() } catch (_: IOException) { }
            ssh = null
        }
    }

    private fun requireSsh(settings: TransferSettings) {
        require(settings.protocol == TransferProtocol.SFTP || settings.protocol == TransferProtocol.SCP)
        settings.validate()
        checkCancelled()
    }

    private fun connect(settings: TransferSettings): SSHClient {
        val client = SSHClient(DefaultConfig())
        val expected = settings.hostKeyFingerprint
        var hostKeySeen = false
        var hostKeyAccepted = false
        client.addHostKeyVerifier(object : HostKeyVerifier {
            override fun verify(hostname: String, port: Int, key: PublicKey): Boolean {
                hostKeySeen = true
                hostKeyAccepted = fingerprint(key) == expected
                return hostKeyAccepted
            }
            override fun findExistingAlgorithms(hostname: String, port: Int): List<String> = emptyList()
        })
        client.setConnectTimeout(15_000)
        client.setTimeout(30_000)
        ssh = client
        try {
            client.connect(settings.host, settings.port)
            checkCancelled()
            client.authPassword(settings.user, settings.password.toCharArray())
            checkCancelled()
            return client
        } catch (error: Exception) {
            try { client.disconnect() } catch (_: IOException) { }
            ssh = null
            if (error is InterruptedIOException) throw error
            if (hostKeySeen && !hostKeyAccepted) throw TransferFailure("SSH host fingerprint changed; verify and trust the new key", true, true)
            if (error is UserAuthException) throw TransferFailure("SSH login rejected; check the username and password", true, true)
            throw TransferFailure("SSH connection failed; it will retry when the network is available")
        }
    }

    private fun checkRemoteDirectory(client: SSHClient, settings: TransferSettings) {
        if (settings.protocol == TransferProtocol.SFTP) {
            client.newSFTPClient().use { sftp ->
                val attrs = sftp.statExistence(settings.folder)
                if (attrs == null || attrs.type != net.schmizz.sshj.sftp.FileMode.Type.DIRECTORY) throw TransferFailure("Remote folder does not exist or is not a directory", true, true)
            }
        } else {
            val (status, _) = runRemote(client, "test -d ${shellQuote(settings.folder)}")
            if (status != 0) throw TransferFailure("Remote folder does not exist or is not a directory", true, true)
        }
    }

    private fun put(client: SSHClient, settings: TransferSettings, file: File, remotePath: String, progress: (Long) -> Unit) {
        val listener = object : TransferListener {
            override fun directory(name: String) = this
            override fun file(name: String, size: Long) = object : StreamCopier.Listener {
                override fun reportProgress(transferred: Long) {
                    checkCancelled()
                    progress(transferred)
                }
            }
        }
        val source = RemoteNamedFile(file, remotePath.substringAfterLast('/'))
        if (settings.protocol == TransferProtocol.SFTP) {
            client.newSFTPClient().use { sftp ->
                sftp.getFileTransfer().transferListener = listener
                sftp.put(source, remotePath)
            }
        } else {
            client.newSCPFileTransfer().apply { transferListener = listener }.upload(source, settings.folder)
        }
        checkCancelled()
        progress(file.length())
    }

    private fun rename(client: SSHClient, settings: TransferSettings, partName: String, finalName: String) {
        val partPath = partName
        val finalPath = remotePath(settings.folder, finalName)
        if (settings.protocol == TransferProtocol.SFTP) {
            client.newSFTPClient().use { sftp ->
                if (sftp.statExistence(finalPath) != null) throw TransferFailure("Remote filename conflict; refusing to overwrite an existing photo", true)
                sftp.rename(partPath, finalPath)
            }
        } else {
            // SCP has no remote rename primitive; use the SSH shell after its atomic upload to .part.
            val command = "mv -n -- ${shellQuote(partPath)} ${shellQuote(finalPath)} && test ! -e ${shellQuote(partPath)}"
            val (status, _) = runRemote(client, command)
            if (status != 0) throw TransferFailure("SCP server cannot safely finalize the upload; it must allow remote mv", true, true)
        }
    }

    private fun remoteSize(client: SSHClient, settings: TransferSettings, path: String): Long? {
        if (settings.protocol == TransferProtocol.SFTP) {
            return client.newSFTPClient().use { it.statExistence(path)?.size }
        }
        val (status, output) = runRemote(client, "if [ -f ${shellQuote(path)} ]; then wc -c < ${shellQuote(path)}; else exit 1; fi")
        if (status != 0) return null
        return output.trim().toLongOrNull() ?: throw TransferFailure("Could not verify the existing SCP file size", true)
    }

    private fun runRemote(client: SSHClient, command: String): Pair<Int, String> {
        checkCancelled()
        client.startSession().use { session ->
            val process = session.exec("$command 2>&1")
            val output = process.inputStream.bufferedReader().use { it.readText() }
            process.join(35, TimeUnit.SECONDS)
            checkCancelled()
            return (process.exitStatus ?: -1) to output
        }
    }

    private fun fingerprint(key: PublicKey): String {
        val keyBytes = Buffer.PlainBuffer().putPublicKey(key).compactData
        val digest = MessageDigest.getInstance("SHA-256").digest(keyBytes)
        return "SHA256:" + Base64.getEncoder().encodeToString(digest).trimEnd('=')
    }

    private fun checkCancelled() {
        if (cancelled || Thread.currentThread().isInterrupted) throw InterruptedIOException("Transfer paused")
    }

    private fun remotePath(folder: String, name: String) = if (folder == "/") "/$name" else "${folder.trimEnd('/')}/$name"

    private fun shellQuote(value: String) = "'${value.replace("'", "'\\''")}'"

    private fun discoverClient(settings: TransferSettings): String {
        settings.validate(allowUnpinnedHost = true)
        val observed = arrayOfNulls<String>(1)
        val client = SSHClient(DefaultConfig())
        client.addHostKeyVerifier(object : HostKeyVerifier {
            override fun verify(hostname: String, port: Int, key: PublicKey): Boolean {
                val actual = fingerprint(key)
                observed[0] = actual
                return settings.hostKeyFingerprint.isBlank() || settings.hostKeyFingerprint == actual
            }
            override fun findExistingAlgorithms(hostname: String, port: Int): List<String> = emptyList()
        })
        client.setConnectTimeout(15_000)
        client.setTimeout(20_000)
        try {
            client.connect(settings.host, settings.port)
            return observed[0] ?: error("SSH server did not provide a host key")
        } finally { try { client.disconnect() } catch (_: IOException) { } }
    }

    private class RemoteNamedFile(file: File, private val remoteName: String) : FileSystemFile(file) {
        override fun getName() = remoteName
    }

    companion object {
        fun discoverFingerprint(settings: TransferSettings): String = SshTransfer().discoverClient(settings)
    }
}
