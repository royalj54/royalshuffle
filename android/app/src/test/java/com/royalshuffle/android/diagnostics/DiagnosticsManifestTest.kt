package com.royalshuffle.android.diagnostics

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DiagnosticsManifestTest {
    @Test
    fun `sharing uses FileProvider without broad storage permission`() {
        val manifest = File("src/main/AndroidManifest.xml").readText()
        assertTrue(manifest.contains("androidx.core.content.FileProvider"))
        assertTrue(manifest.contains("android:grantUriPermissions=\"true\""))
        assertFalse(manifest.contains("READ_EXTERNAL_STORAGE"))
        assertFalse(manifest.contains("WRITE_EXTERNAL_STORAGE"))
        assertFalse(manifest.contains("MANAGE_EXTERNAL_STORAGE"))
    }
}
