package com.ruangfoto.app

import org.apache.sshd.common.file.virtualfs.VirtualFileSystemFactory
import org.apache.sshd.server.SshServer
import org.apache.sshd.server.auth.password.PasswordAuthenticator
import org.apache.sshd.server.keyprovider.SimpleGeneratorHostKeyProvider
import org.apache.sshd.sftp.server.SftpSubsystemFactory
import org.apache.sshd.scp.server.ScpCommandFactory
import org.apache.sshd.server.Environment
import org.apache.sshd.server.ExitCallback
import org.apache.sshd.server.channel.ChannelSession
import org.apache.sshd.server.command.Command
import org.apache.sshd.server.command.CommandFactory
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.file.Path
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.net.InetAddress
import java.nio.file.Files

class SshTransferTest {
    @Test fun verifiesHostKeyBrowsesFoldersAndFinalizesSftpUploads() {
        val root = Files.createTempDirectory("ruangfoto-sftp-root")
        val key = Files.createTempDirectory("ruangfoto-sftp-key").resolve("hostkey")
        Files.createDirectories(root.resolve("photos"))
        Files.createDirectories(root.resolve("archive"))
        val server = createServer(root, key, "EC")
        val local = File.createTempFile("ruangfoto-sftp", ".ARW").apply { writeBytes(ByteArray(32_019) { (it * 17).toByte() }) }
        try {
            server.start()
            val unpinned = TransferSettings("127.0.0.1", server.port, "camera", "secret", "/photos", TransferProtocol.SFTP)
            val fingerprint = SshTransfer.discoverFingerprint(unpinned)
            assertTrue(fingerprint.startsWith("SHA256:"))
            val pinned = unpinned.copy(hostKeyFingerprint = fingerprint)
            assertEquals(setOf("photos", "archive"), SshTransfer().listDirectories(pinned, "/").toSet())
            SshTransfer().test(pinned)

            val name = remotePhotoName("integration", local.name)
            var finalizing = false
            SshTransfer().upload(pinned, local, local.length(), local.lastModified(), name, false,
                { finalizing = true }, {})
            assertTrue(finalizing)
            assertArrayEquals(local.readBytes(), Files.readAllBytes(root.resolve("photos").resolve(name)))
            SshTransfer().upload(pinned, local, local.length(), local.lastModified(), name, true, {}, {})
            try {
                SshTransfer().upload(pinned, local, local.length(), local.lastModified(), name, false, {}, {})
                fail("A different transfer overwrote an existing remote photo")
            } catch (error: TransferFailure) { assertTrue(error.permanent) }

            try {
                SshTransfer().test(pinned.copy(hostKeyFingerprint = "SHA256:" + "A".repeat(43)))
                fail("An untrusted SSH host key was accepted")
            } catch (error: TransferFailure) { assertTrue(error.configuration) }
        } finally {
            try { server.stop(true) } catch (_: Exception) { }
            local.delete()
            root.toFile().deleteRecursively()
            key.parent.toFile().deleteRecursively()
        }
    }
    @Test fun acceptsEd25519HostKeysOnModernSshServers() {
        val root = Files.createTempDirectory("ruangfoto-ed25519-root")
        val key = Files.createTempDirectory("ruangfoto-ed25519-key").resolve("hostkey")
        Files.createDirectories(root.resolve("photos"))
        val server = createServer(root, key, "Ed25519")
        try {
            server.start()
            val settings = TransferSettings("127.0.0.1", server.port, "camera", "secret", "/photos", TransferProtocol.SFTP)
            val pinned = settings.copy(hostKeyFingerprint = SshTransfer.discoverFingerprint(settings))
            SshTransfer().test(pinned)
        } finally {
            try { server.stop(true) } catch (_: Exception) { }
            root.toFile().deleteRecursively()
            key.parent.toFile().deleteRecursively()
        }
    }
    @Test fun transfersWithScpWithoutOverwritingFiles() {
        val root = Files.createTempDirectory("ruangfoto-scp-root")
        val key = Files.createTempDirectory("ruangfoto-scp-key").resolve("hostkey")
        Files.createDirectories(root.resolve("photos"))
        Files.createDirectories(root.resolve("archive"))
        val server = createServer(root, key, "EC", true)
        val local = File.createTempFile("ruangfoto-scp", ".NEF").apply { writeBytes(ByteArray(24_013) { (it * 31).toByte() }) }
        try {
            server.start()
            val settings = TransferSettings("127.0.0.1", server.port, "camera", "secret", "/photos", TransferProtocol.SCP)
            val pinned = settings.copy(hostKeyFingerprint = SshTransfer.discoverFingerprint(settings))
            assertEquals(setOf("photos", "archive"), SshTransfer().listDirectories(pinned, "/").toSet())
            SshTransfer().test(pinned)
            val name = remotePhotoName("scp-test", local.name)
            SshTransfer().upload(pinned, local, local.length(), local.lastModified(), name, false, {}, {})
            assertArrayEquals(local.readBytes(), Files.readAllBytes(root.resolve("photos").resolve(name)))
            SshTransfer().upload(pinned, local, local.length(), local.lastModified(), name, true, {}, {})
            try {
                SshTransfer().upload(pinned, local, local.length(), local.lastModified(), name, false, {}, {})
                fail("SCP transfer overwrote an existing photo")
            } catch (error: TransferFailure) { assertTrue(error.permanent) }
        } finally {
            try { server.stop(true) } catch (_: Exception) { }
            local.delete()
            root.toFile().deleteRecursively()
            key.parent.toFile().deleteRecursively()
        }
    }

    private fun createServer(root: Path, key: Path, hostKey: String, scp: Boolean = false): SshServer {
        val hostKeyProvider = SimpleGeneratorHostKeyProvider(key).apply { algorithm = hostKey }
        return SshServer.setUpDefaultServer().apply {
            host = InetAddress.getLoopbackAddress().hostAddress
            port = 0
            keyPairProvider = hostKeyProvider
            passwordAuthenticator = PasswordAuthenticator { username, password, _ -> username == "camera" && password == "secret" }
            fileSystemFactory = VirtualFileSystemFactory(root)
            subsystemFactories = listOf(SftpSubsystemFactory.Builder().build())
            if (scp) commandFactory = ScpCommandFactory.Builder().withDelegate(FixtureCommandFactory(root)).build()
        }
    }

    private class FixtureCommandFactory(private val root: Path) : CommandFactory {
        override fun createCommand(channel: ChannelSession, command: String): Command = FixtureCommand(root, command)
    }

    private class FixtureCommand(private val root: Path, private val command: String) : Command, Runnable {
        private var output: OutputStream = ByteArrayOutputStream()
        private var errors: OutputStream = ByteArrayOutputStream()
        private var exit: ExitCallback? = null
        override fun setInputStream(input: InputStream) = Unit
        override fun setOutputStream(output: OutputStream) { this.output = output }
        override fun setErrorStream(error: OutputStream) { errors = error }
        override fun setExitCallback(callback: ExitCallback) { exit = callback }
        override fun start(channel: ChannelSession, env: Environment) { Thread(this, "ssh-test-command").start() }
        override fun destroy(channel: ChannelSession) = Unit

        override fun run() {
            val result = runCatching { execute() }
            if (result.isSuccess) {
                output.write(result.getOrThrow().second.toByteArray())
                output.flush()
            } else {
                errors.write((result.exceptionOrNull()?.message ?: "test command failed").toByteArray())
                errors.flush()
            }
            exit?.onExit(if (result.isSuccess) result.getOrThrow().first else 1)
        }

        private fun execute(): Pair<Int, String> {
            val paths = Regex("'([^']*)'").findAll(command).map { it.groupValues[1] }.toList()
            fun file(index: Int) = root.resolve(paths[index].removePrefix("/")).normalize().also {
                require(it.startsWith(root)) { "Path escapes test root" }
            }
            return when {
                command.startsWith("test -d ") -> (if (file(0).toFile().isDirectory) 0 else 1) to ""
                command.startsWith("find ") -> {
                    val dir = file(0).toFile()
                    if (!dir.isDirectory) 1 to "" else 0 to dir.listFiles().orEmpty()
                        .filter { it.isDirectory }.joinToString("") { "${paths[0].trimEnd('/')}/${it.name}\n" }
                }
                command.startsWith("if [ -f ") -> {
                    val target = file(0).toFile()
                    if (target.isFile) 0 to "${target.length()}\n" else 1 to ""
                }
                command.startsWith("mv -n -- ") -> {
                    val from = file(0); val to = file(1)
                    if (Files.exists(to)) 1 to "" else { Files.move(from, to); 0 to "" }
                }
                command.startsWith("rm -f -- ") -> { Files.deleteIfExists(file(0)); 0 to "" }
                else -> throw IOException("Unsupported test command")
            }
        }
    }

}
