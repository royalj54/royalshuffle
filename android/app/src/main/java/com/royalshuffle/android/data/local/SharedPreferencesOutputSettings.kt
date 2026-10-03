package com.royalshuffle.android.data.local

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import com.royalshuffle.android.output.OutputSettings
import com.royalshuffle.android.output.OutputSettingsStorage
import com.royalshuffle.android.output.SessionLengthMode

class SharedPreferencesOutputSettings internal constructor(
    private val preferences: SharedPreferences,
) : OutputSettingsStorage {
    constructor(context: Context) : this(
        context.getSharedPreferences("royalshuffle_output_settings", Context.MODE_PRIVATE),
    )

    override fun load() = OutputSettings(
        mode = SessionLengthMode.entries.firstOrNull {
            it.name == preferences.getString("session_length_mode", null)
        } ?: SessionLengthMode.FULL,
        customMinutes = preferences.getString("custom_minutes", "").orEmpty(),
        artistSeparation = preferences.getBoolean("artist_separation", false),
        opportunityEnabled = preferences.getBoolean("balanced_opportunity", false),
    )

    override fun save(settings: OutputSettings) {
        preferences.edit()
            .putString("session_length_mode", settings.mode.name)
            .putString("custom_minutes", settings.customMinutes)
            .putBoolean("artist_separation", settings.artistSeparation)
            .putBoolean("balanced_opportunity", settings.opportunityEnabled)
            .apply()
    }

    override fun saveOpportunityEnabled(enabled: Boolean) {
        preferences.edit { putBoolean("balanced_opportunity", enabled) }
    }
}
