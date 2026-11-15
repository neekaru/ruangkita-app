package com.ruangfoto.app

import org.junit.Assert.*
import org.junit.Test

class CameraBrowserTest {
    @Test fun navigatesStorageFoldersAndKeepsVendorRawFormats() {
        assertEquals("/store_00010001", cameraChildPath("/", "store_00010001"))
        assertEquals("/store_00010001/DCIM", cameraChildPath("/store_00010001", "DCIM"))
        assertEquals("raw", cameraPhotoKind("DSC0001.ARW"))
        assertEquals("raw", cameraPhotoKind("DSC0001.NEF"))
        assertEquals("raw", cameraPhotoKind("IMG0001.CR3"))
        assertEquals("jpeg", cameraPhotoKind("IMG0001.JpEg"))
        assertNull(cameraPhotoKind("DSC0001.ARW.part"))
    }
    @Test fun refusesTraversalAndInjectedFolderSeparators() {
        for (name in listOf("", ".", "..", "../DCIM", "a/b", "a\\b", "a\u0000b", "a\nb")) {
            try { cameraChildPath("/", name); fail("Unsafe camera name accepted: $name") }
            catch (_: IllegalArgumentException) { }
        }
    }
}
