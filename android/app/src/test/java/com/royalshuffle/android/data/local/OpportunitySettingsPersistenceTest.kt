package com.royalshuffle.android.data.local

import com.royalshuffle.android.output.OutputSettings
import com.royalshuffle.android.output.SessionLengthMode
import org.junit.Assert.*
import org.junit.Test

class OpportunitySettingsPersistenceTest {
    @Test fun `Opportunity flag writes only its own preference and restarts with ordinary choices preserved`() {
        val preferences = SharedPreferencesPlaylistPreferencesTest.FakeSharedPreferences()
        val storage = SharedPreferencesOutputSettings(preferences)
        storage.save(OutputSettings(SessionLengthMode.CUSTOM, "077", true))
        val before = preferences.all.filterKeys { it != "balanced_opportunity" }
        storage.saveOpportunityEnabled(true)
        assertEquals(before, preferences.all.filterKeys { it != "balanced_opportunity" })
        val fresh = SharedPreferencesOutputSettings(preferences)
        assertEquals(OutputSettings(SessionLengthMode.CUSTOM, "077", true, true), fresh.load())
        fresh.saveOpportunityEnabled(false)
        assertEquals(OutputSettings(SessionLengthMode.CUSTOM, "077", true), storage.load())
        assertEquals(before, preferences.all.filterKeys { it != "balanced_opportunity" })
    }

    @Test fun `enabling fresh settings does not create ordinary settings keys`() {
        val preferences = SharedPreferencesPlaylistPreferencesTest.FakeSharedPreferences()
        SharedPreferencesOutputSettings(preferences).saveOpportunityEnabled(true)
        assertEquals(mapOf("balanced_opportunity" to true), preferences.all)
    }
}
