package com.ruangfoto.app

import android.content.Context
import android.content.ContextWrapper
import android.database.DatabaseErrorHandler
import android.database.sqlite.SQLiteDatabase
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.UUID

class UploadStoreTest {
    @Test fun keepsBaselineDedupAndDestinationOwnershipAcrossReopen() {
        val prefix = "upload-test-${UUID.randomUUID()}-"
        val base = InstrumentationRegistry.getInstrumentation().targetContext
        val context = object : ContextWrapper(base) {
            override fun getSharedPreferences(name: String, mode: Int) = base.getSharedPreferences(prefix + name, mode)
            override fun getDatabasePath(name: String) = base.getDatabasePath(prefix + name)
            override fun openOrCreateDatabase(name: String, mode: Int, factory: SQLiteDatabase.CursorFactory?) = base.openOrCreateDatabase(prefix + name, mode, factory)
            override fun openOrCreateDatabase(name: String, mode: Int, factory: SQLiteDatabase.CursorFactory?, errorHandler: DatabaseErrorHandler?) = base.openOrCreateDatabase(prefix + name, mode, factory, errorHandler)
        }
        val directory = File(base.cacheDir, prefix).apply { mkdirs() }
        var store = UploadStore(context)
        val settings = TransferSettings("first.example", 21, "user", "secret", "/photos", TransferProtocol.FTPS)
        try {
            store.saveSettings(settings)
            File(directory, "old.JPG").writeBytes(byteArrayOf(1))
            store.configureSession(directory, true, "raw", false)
            File(directory, "new.ARW").writeBytes(byteArrayOf(0, 10, 13, -1))
            File(directory, "new.JPG").writeBytes(byteArrayOf(2))
            File(directory, "unfinished.ARW.part").writeBytes(byteArrayOf(3))
            store.scan(); store.scan()
            assertEquals(1, store.countUnfinished())
            val original = store.next()!!
            store.finalizing(original)
            store.close()
            store = UploadStore(context)
            val recovered = store.next()!!
            assertEquals(original.id, recovered.id)
            assertEquals(original.name, recovered.name)
            assertTrue(recovered.finalizing)
            assertEquals("secret", store.settings()!!.password)
            try { store.saveSettings(settings.copy(host = "second.example")); fail("Queued upload silently redirected") }
            catch (_: IllegalArgumentException) { }
            store.fail(recovered, "Bad destination", true)
            store.cancelUnfinished()
            store.saveSettings(settings.copy(host = "second.example"))
            assertFalse(store.rule(directory).enabled)
            store.configureSession(directory, true, "raw", true)
            store.scan()
            assertEquals(1, store.countUnfinished())
            assertNotEquals(original.id, store.next()!!.id)
        } finally {
            store.close()
            base.deleteDatabase(prefix + "uploads.db")
            base.getSharedPreferences(prefix + "ftp_settings", Context.MODE_PRIVATE).edit().clear().commit()
            directory.deleteRecursively()
        }
    }
}