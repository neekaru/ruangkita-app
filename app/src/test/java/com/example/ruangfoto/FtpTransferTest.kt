package com.ruangfoto.app

import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.file.Files
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

class FtpTransferTest {
    @Test fun configurableRetryIsCappedWithoutRedirectingQueuedPhotos() {
        val policy = TransferTiming(9, 45, 7, 20, 3)
        policy.validate()
        assertEquals(7_000L, policy.retryDelayMillis(1))
        assertEquals(14_000L, policy.retryDelayMillis(2))
        assertEquals(20_000L, policy.retryDelayMillis(3))
        assertEquals(20_000L, policy.retryDelayMillis(50))
        val settings = TransferSettings("localhost", 21, "user", "secret", "/", TransferProtocol.FTP)
        assertEquals(settings.destination(), settings.copy(timing = policy).destination())
        for (bad in listOf(policy.copy(connectTimeoutSeconds = 0), policy.copy(readTimeoutSeconds = 0),
            policy.copy(maxRetryDelaySeconds = 6), policy.copy(maxAttempts = 0))) {
            try { bad.validate(); fail("Invalid transfer timing accepted") } catch (_: IllegalArgumentException) { }
        }
    }

    @Test fun rejectsCommandInjectionAndUnsafeRemotePaths() {
        val base = TransferSettings("localhost", 21, "user", "secret", "/photos", TransferProtocol.FTP)
        for (bad in listOf(base.copy(user = "user\r\nDELE x"), base.copy(password = "a\nb"),
            base.copy(folder = "/../photos"), base.copy(host = "ftp://localhost"), base.copy(port = 0))) {
            try { bad.validate(); fail("Unsafe settings accepted") } catch (_: IllegalArgumentException) { }
        }
        val ssh = base.copy(protocol = TransferProtocol.SFTP)
        try { ssh.validate(); fail("Unpinned SSH server accepted") } catch (_: IllegalArgumentException) { }
        assertNotEquals(base.destination(), ssh.copy(hostKeyFingerprint = "SHA256:" + "a".repeat(43)).destination())
        assertTrue(uploadMatches("photo.CR3", "raw"))
        assertFalse(uploadMatches("photo.ARW.part", "all"))
        assertFalse(uploadMatches("photo.JPG", "raw"))
    }

    @Test fun transfersExactBytesAndRecoversLostRenameAcknowledgement() {
        LocalFtp().use { server ->
            val bytes = ByteArray(180_003) { (it * 73).toByte() }
            val file = File.createTempFile("ruangfoto", ".ARW").apply { writeBytes(bytes) }
            val settings = TransferSettings("127.0.0.1", server.port, "user", "password", "/photos", TransferProtocol.FTP)
            val name = remotePhotoName("c0ffee", file.name)
            try {
                FtpTransfer().test(settings)
                var recorded = false
                FtpTransfer().upload(settings, file, file.length(), file.lastModified(), name, false, { recorded = true }, {})
                assertTrue(recorded)
                assertArrayEquals(bytes, server.files["/photos/$name"])
                assertFalse(server.files.containsKey("/photos/$name.part"))
                val writes = server.stores
                // Simulate process death after RNTO succeeded, before SQLite marked the job done.
                FtpTransfer().upload(settings, file, file.length(), file.lastModified(), name, true, {}, {})
                assertEquals(writes, server.stores)
                // A different job must not adopt or overwrite an existing final file.
                try {
                    FtpTransfer().upload(settings, file, file.length(), file.lastModified(), name, false, {}, {})
                    fail("Existing photo was overwritten")
                } catch (error: TransferFailure) { assertTrue(error.permanent) }
                assertArrayEquals(bytes, server.files["/photos/$name"])
            } finally { file.delete() }
        }
    }

    @Test fun createsSessionFoldersBelowTheLoginFolderOrConfiguredBase() {
        LocalFtp().use { server ->
            val bytes = byteArrayOf(0, 10, 13, -1)
            val file = File.createTempFile("ruangfoto", ".JPG").apply { writeBytes(bytes) }
            val settings = TransferSettings("127.0.0.1", server.port, "user", "password", ".", TransferProtocol.FTP)
            fun upload(target: TransferSettings, session: String, name: String) =
                transferClient(target, session).upload(target, file, file.length(), file.lastModified(), name, false, {}, {})
            try {
                upload(settings, "Wisuda É (2)", "first.jpg")
                upload(settings, "Wisuda É (2)", "second.jpg")
                upload(settings.copy(folder = "/photos"), "Family", "first.jpg")

                assertEquals(setOf("/home/user/Wisuda É (2)/first.jpg", "/home/user/Wisuda É (2)/second.jpg",
                    "/photos/Family/first.jpg"), server.files.keys)
                server.files.values.forEach { assertArrayEquals(bytes, it) }

                for (unsafe in listOf("..", "dir/subdir", "bad\r\nMKD injected")) {
                    try {
                        upload(settings, unsafe, "rejected.jpg")
                        fail("Unsafe FTP session name accepted")
                    } catch (error: TransferFailure) {
                        assertEquals("Session name cannot be used as an FTP folder", error.message)
                    }
                }
            } finally { file.delete() }
        }
    }

    @Test fun rejectsChangedLocalSourceBeforeConnecting() {
        val file = File.createTempFile("ruangfoto", ".JPG").apply { writeBytes(byteArrayOf(1, 2, 3)) }
        try {
            try {
                FtpTransfer().upload(TransferSettings("127.0.0.1", 1, "x", "x", "/", TransferProtocol.FTP), file, 4, file.lastModified(), "owned.jpg", false, {}, {})
                fail("Changed source was accepted")
            } catch (error: TransferFailure) { assertTrue(error.permanent) }
        } finally { file.delete() }
    }

    /** Isolated loopback FTP peer, with real passive data sockets and binary storage. */
    private class LocalFtp : AutoCloseable {
        private val control = ServerSocket(0, 10, InetAddress.getLoopbackAddress())
        private val executor = Executors.newCachedThreadPool()
        private val sockets = ConcurrentHashMap.newKeySet<Socket>()
        val files = ConcurrentHashMap<String, ByteArray>()
        private val directories = ConcurrentHashMap.newKeySet<String>().apply { addAll(listOf("/", "/photos", "/home/user")) }
        @Volatile var stores = 0
        val port get() = control.localPort
        init {
            executor.execute {
                try {
                    while (!control.isClosed) {
                        val socket = control.accept(); sockets.add(socket)
                        executor.execute { serve(socket) }
                    }
                } catch (_: java.io.IOException) { }
            }
        }
        private fun serve(socket: Socket) {
            socket.soTimeout = 5_000
            var passive: ServerSocket? = null
            var renameFrom = ""
            var cwd = "/home/user"
            fun resolve(name: String) = if (name.startsWith('/')) name else "${cwd.trimEnd('/')}/$name"
            try {
                val output = socket.getOutputStream().bufferedWriter(Charsets.UTF_8)
                fun reply(text: String) { output.write(text + "\r\n"); output.flush() }
                reply("220 Test FTP ready")
                val input = socket.getInputStream().bufferedReader(Charsets.UTF_8)
                while (true) {
                    val line = input.readLine() ?: break
                    val command = line.substringBefore(' ').uppercase()
                    val arg = line.substringAfter(' ', "")
                    when (command) {
                        "USER" -> reply("331 Password required")
                        "PASS" -> reply("230 Logged in")
                        "TYPE", "OPTS" -> reply("200 OK")
                        "CWD" -> {
                            val path = resolve(arg)
                            if (path in directories) {
                                cwd = path
                                reply("250 Directory changed")
                            } else reply("550 Not found")
                        }
                        "MKD" -> reply(if (directories.add(resolve(arg))) "257 Directory created" else "550 Already exists")
                        "SYST" -> reply("215 UNIX Type: L8")
                        "MLST" -> {
                            val data = files[resolve(arg)]
                            if (data == null) reply("550 Not found")
                            else reply("250-Listing\r\n type=file;size=${data.size}; $arg\r\n250 End")
                        }
                        "PASV", "EPSV" -> {
                            passive?.close()
                            passive = ServerSocket(0, 1, InetAddress.getLoopbackAddress())
                            passive.soTimeout = 5_000
                            val p = passive.localPort
                            if (command == "EPSV") reply("229 Entering Extended Passive Mode (|||$p|)")
                            else reply("227 Entering Passive Mode (127,0,0,1,${p / 256},${p % 256})")
                        }
                        "LIST" -> {
                            reply("150 Listing")
                            passive!!.accept().use { data ->
                                val file = files[resolve(arg)]
                                if (file != null) data.getOutputStream().write("-rw-r--r-- 1 user group ${file.size} Jan 01 2026 $arg\r\n".toByteArray())
                            }
                            passive.close(); passive = null; reply("226 Listing complete")
                        }
                        "STOR" -> {
                            reply("150 Send file")
                            passive!!.accept().use { data -> files[resolve(arg)] = data.getInputStream().readBytes() }
                            stores++; passive.close(); passive = null; reply("226 Stored")
                        }
                        "RNFR" -> { renameFrom = resolve(arg); reply(if (files.containsKey(renameFrom)) "350 Rename ready" else "550 Missing") }
                        "RNTO" -> { files[resolve(arg)] = files.remove(renameFrom)!!; reply("250 Renamed") }
                        "DELE" -> reply(if (files.remove(resolve(arg)) != null) "250 Deleted" else "550 Missing")
                        "QUIT" -> { reply("221 Bye"); break }
                        else -> reply("502 Not supported")
                    }
                }
            } catch (_: java.io.IOException) { }
            finally { passive?.close(); sockets.remove(socket); socket.close() }
        }
        override fun close() { control.close(); sockets.forEach { it.close() }; executor.shutdownNow() }
    }
}