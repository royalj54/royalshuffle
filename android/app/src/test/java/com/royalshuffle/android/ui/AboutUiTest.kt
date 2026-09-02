package com.royalshuffle.android.ui

import com.royalshuffle.android.BuildConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AboutUiTest {
    @Test
    fun `About version is derived from generated build metadata`() {
        val appInfo = androidAboutAppInfo()

        assertTrue(appInfo.versionText.contains(BuildConfig.VERSION_NAME))
        assertEquals("Version ${BuildConfig.VERSION_NAME} (development)", appInfo.versionText)
    }

    @Test
    fun `About includes application and description information`() {
        val appInfo = androidAboutAppInfo(versionName = "2.3.4", isDebug = false)

        assertEquals("RoyalShuffle", appInfo.applicationName)
        assertEquals("Version 2.3.4", appInfo.versionText)
        assertEquals("Transparent, user-controlled Spotify shuffle.", appInfo.description)
    }

    @Test
    fun `debug builds are identified while release versions remain clean`() {
        assertEquals(
            "Version 2.3.4 (development)",
            androidAboutAppInfo("2.3.4", isDebug = true).versionText,
        )
        assertEquals(
            "Version 2.3.4",
            androidAboutAppInfo("2.3.4", isDebug = false).versionText,
        )
    }

    @Test
    fun `About controller opens and closes the surface`() {
        val controller = AboutDialogController()
        assertFalse(controller.isVisible)

        controller.open()
        assertTrue(controller.isVisible)

        controller.close()
        assertFalse(controller.isVisible)
    }
}
