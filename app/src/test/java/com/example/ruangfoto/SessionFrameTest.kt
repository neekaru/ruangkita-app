package com.ruangfoto.app

import org.junit.Assert.assertEquals
import org.junit.Test

class SessionFrameTest {
    @Test fun centerCropFillsPortraitFrameWithExifOrientedDimensions() {
        assertEquals(2f, frameCropScale(600, 400, 600, 800, 0f), 0f)
        assertEquals(1.5f, frameCropScale(600, 400, 600, 800, 90f), 0f)
        assertEquals(1.5f, frameCropScale(600, 400, 600, 800, 270f), 0f)
    }
}
