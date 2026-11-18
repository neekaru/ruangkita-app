package com.ruangfoto.app

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.media.ExifInterface
import androidx.test.platform.app.InstrumentationRegistry
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.UUID
import org.junit.Assert.*
import org.junit.Test

class SessionFrameExportTest {
    @Test fun savesAnOrientedJpegWithTheOverlayAndKeepsTheSessionOriginals() {
        val cache = InstrumentationRegistry.getInstrumentation().targetContext.cacheDir
        val directory = File(cache, "frame-test-${UUID.randomUUID()}").apply { check(mkdir()) }
        try {
            val session = SessionFrame(directory)
            val designs = FrameDesignStore(directory)
            val cameraJpeg = File(directory, "capture.jpg")
            val raw = File(directory, "capture.ARW").apply { writeBytes(byteArrayOf(1, 2, 3)) }
            val source = Bitmap.createBitmap(120, 60, Bitmap.Config.ARGB_8888)
            try {
                source.eraseColor(Color.YELLOW)
                Canvas(source).drawRect(0f, 0f, 60f, 60f, Paint().apply { color = Color.BLUE })
                cameraJpeg.outputStream().use { assertTrue(source.compress(Bitmap.CompressFormat.JPEG, 100, it)) }
            } finally {
                source.recycle()
            }
            ExifInterface(cameraJpeg.path).apply {
                setAttribute(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_ROTATE_90.toString())
                saveAttributes()
            }
            val original = cameraJpeg.readBytes()
            val overlay = designs.importOverlay(png(transparent = true).inputStream())
            designs.save(FrameDesign(overlay = overlay))
            assertNull(FrameDesignStore(File(directory, "second-session")).activeDesign())
            try {
                designs.importOverlay(png(transparent = false).inputStream())
                fail("An opaque replacement covered the entire photo")
            } catch (_: IllegalArgumentException) {
            }

            assertTrue(SessionFrame(directory).renderNewPhotos(listOf(cameraJpeg)))
            assertArrayEquals(original, cameraJpeg.readBytes())
            assertArrayEquals(byteArrayOf(1, 2, 3), raw.readBytes())
            val framedFile = session.outputFor(cameraJpeg)
            val framed = checkNotNull(BitmapFactory.decodeFile(framedFile.path))
            try {
                assertEquals(60, framed.width)
                assertEquals(120, framed.height)
                val border = framed.getPixel(30, 8)
                assertTrue(Color.red(border) > 200 && Color.blue(border) < 60)
                val top = framed.getPixel(30, 36)
                assertTrue(Color.blue(top) > 200 && Color.red(top) < 60)
                val bottom = framed.getPixel(30, 96)
                assertTrue(Color.red(bottom) > 200 && Color.green(bottom) > 200 && Color.blue(bottom) < 60)
            } finally {
                framed.recycle()
            }

            designs.disable()
            val anotherJpeg = cameraJpeg.copyTo(File(directory, "next.jpg"))
            assertTrue(session.renderNewPhotos(listOf(anotherJpeg)))
            assertFalse(session.outputFor(anotherJpeg).exists())
            assertTrue(framedFile.isFile)
        } finally {
            directory.deleteRecursively()
        }
    }

    private fun png(transparent: Boolean): ByteArray {
        val bitmap = Bitmap.createBitmap(60, 120, Bitmap.Config.ARGB_8888)
        try {
            if (transparent) Canvas(bitmap).drawRect(0f, 0f, 60f, 16f, Paint().apply { color = Color.RED })
            else bitmap.eraseColor(Color.CYAN)
            return ByteArrayOutputStream().use {
                assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it))
                it.toByteArray()
            }
        } finally {
            bitmap.recycle()
        }
    }
}
