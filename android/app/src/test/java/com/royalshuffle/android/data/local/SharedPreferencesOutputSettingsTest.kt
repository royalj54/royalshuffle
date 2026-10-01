package com.royalshuffle.android.data.local

import com.royalshuffle.android.output.OutputSettings
import com.royalshuffle.android.output.SessionLengthMode
import java.math.BigInteger
import org.junit.Assert.*
import org.junit.Test

class SharedPreferencesOutputSettingsTest {
    @Test fun `fresh install defaults to Full and separation off`() {
        val preferences = SharedPreferencesPlaylistPreferencesTest.FakeSharedPreferences()
        assertEquals(OutputSettings(), SharedPreferencesOutputSettings(preferences).load())
        assertTrue(preferences.all.isEmpty())
    }

    @Test fun `mode custom value and separation persist together across fresh storage wrappers`() {
        val preferences = SharedPreferencesPlaylistPreferencesTest.FakeSharedPreferences()
        val storage = SharedPreferencesOutputSettings(preferences)
        val huge = BigInteger.TEN.pow(100).toString()
        val custom = OutputSettings(SessionLengthMode.CUSTOM, huge, true)
        storage.save(custom)
        assertEquals(custom, SharedPreferencesOutputSettings(preferences).load())
        storage.save(custom.copy(mode = SessionLengthMode.FULL))
        assertEquals(huge, SharedPreferencesOutputSettings(preferences).load().customMinutes)
        storage.save(custom.copy(mode = SessionLengthMode.SIXTY_MINUTES))
        val loaded = SharedPreferencesOutputSettings(preferences).load()
        assertEquals(huge, loaded.customMinutes)
        assertTrue(loaded.artistSeparation)
        assertEquals(BigInteger.valueOf(60), loaded.snapshot().sessionMinutes)
    }

    @Test fun `invalid Custom draft remains visibly invalid after restart`() {
        val preferences = SharedPreferencesPlaylistPreferencesTest.FakeSharedPreferences()
        SharedPreferencesOutputSettings(preferences).save(OutputSettings(SessionLengthMode.CUSTOM,"-1"))
        val fresh = SharedPreferencesOutputSettings(preferences).load()
        assertEquals("-1", fresh.customMinutes)
        assertNotNull(fresh.validationMessage)
        assertTrue(runCatching { fresh.snapshot() }.isFailure)
    }

    @Test fun `settings never remove legacy authentication selection or managed exclusion keys`() {
        val preferences = SharedPreferencesPlaylistPreferencesTest.FakeSharedPreferences()
        preferences.edit()
            .putString("access_token","token").putString("refresh_token","refresh")
            .putLong("expires_at",1234).putString("selected_playlist_id","source")
            .putStringSet("managed_playlist_ids",setOf("legacy"))
            .putStringSet("declined_recovery_playlist_ids",setOf("declined")).apply()
        val original = preferences.all
        SharedPreferencesOutputSettings(preferences).save(OutputSettings(SessionLengthMode.CUSTOM,"90",true))
        original.forEach { (key, value) -> assertEquals(value, preferences.all[key]) }
        assertEquals(setOf("legacy"), SharedPreferencesPlaylistPreferences(preferences).loadManagedPlaylistIds())
        assertEquals("source", SharedPreferencesPlaylistPreferences(preferences).loadSelectedPlaylistId())
    }

    @Test fun `unrecognized mode safely falls back to Full without erasing stored Custom`() {
        val preferences = SharedPreferencesPlaylistPreferencesTest.FakeSharedPreferences()
        preferences.edit().putString("session_length_mode","future-mode").putString("custom_minutes","240").apply()
        val settings = SharedPreferencesOutputSettings(preferences).load()
        assertEquals(SessionLengthMode.FULL, settings.mode)
        assertEquals("240", settings.customMinutes)
    }
}
