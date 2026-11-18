package com.ruangfoto.app

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class FrameDesignStoreTest {
    @Test fun savesRevisionsAndDisablesWithoutLosingHistory() {
        val directory = File(
            InstrumentationRegistry.getInstrumentation().targetContext.cacheDir,
            "design-test-${UUID.randomUUID()}"
        ).apply { check(mkdir()) }
        try {
            val design = FrameDesign(
                canvas = FrameCanvas.SQUARE,
                labels = listOf(FrameLabel("Emoji 😀\nsecond line", x = 0.5f, y = 0.5f, size = 0.1f, color = Color.WHITE))
            )
            FrameDesignStore(directory).save(design)

            // A fresh store instance simulates reopening the session in another process.
            val reopened = FrameDesignStore(directory)
            assertEquals(design, reopened.activeDesign())
            val activeOverlay = checkNotNull(reopened.activeOverlay())
            val overlayBounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(activeOverlay.path, overlayBounds)
            assertEquals("image/png", overlayBounds.outMimeType)
            assertEquals(2400, overlayBounds.outHeight)

            val revisions = reopened.revisions()
            assertEquals(1, revisions.size)
            assertEquals(design, reopened.loadRevision(revisions.single().id))
            try {
                reopened.loadRevision("../../escape")
                fail("An invalid revision id was accepted")
            } catch (_: IllegalArgumentException) {
            }

            reopened.disable()
            assertNull(FrameDesignStore(directory).activeDesign())
            assertNull(FrameDesignStore(directory).activeOverlay())
            assertEquals(1, FrameDesignStore(directory).revisions().size)
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test fun rendersRedTextWithoutATemplateAndFallsBackOnMalformedDuration() {
        val directory = File(
            InstrumentationRegistry.getInstrumentation().targetContext.cacheDir,
            "design-test-${UUID.randomUUID()}"
        ).apply { check(mkdir()) }
        try {
            val store = FrameDesignStore(directory)
            assertEquals(15, store.previewDurationSeconds())
            store.setPreviewDurationSeconds(0)
            assertEquals(0, store.previewDurationSeconds())
            store.setPreviewDurationSeconds(30)
            assertEquals(30, store.previewDurationSeconds())
            File(directory, ".ruangfoto-design/preview-seconds").writeText("not-a-number")
            assertEquals(15, FrameDesignStore(directory).previewDurationSeconds())

            val design = FrameDesign(
                canvas = FrameCanvas.LANDSCAPE,
                labels = listOf(FrameLabel("R", x = 0.5f, y = 0.5f, size = 0.3f, color = Color.RED))
            )
            val preview = store.renderPreview(design, 300)
            try {
                assertEquals(300, preview.width)
                assertEquals(200, preview.height)
                assertTrue(hasRedPixelNearCenter(preview))
            } finally {
                preview.recycle()
            }
        } finally {
            directory.deleteRecursively()
        }
    }

    private fun hasRedPixelNearCenter(bitmap: Bitmap): Boolean {
        for (y in bitmap.height / 3 until bitmap.height * 2 / 3) {
            for (x in bitmap.width / 3 until bitmap.width * 2 / 3) {
                val pixel = bitmap.getPixel(x, y)
                if (Color.alpha(pixel) > 0 && Color.red(pixel) > 200 &&
                    Color.green(pixel) < 80 && Color.blue(pixel) < 80) return true
            }
        }
        return false
    }
}
