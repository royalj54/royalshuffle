package com.royalshuffle.android.data.local

import android.content.Context
import android.content.SharedPreferences
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
    )

    override fun save(settings: OutputSettings) {
        preferences.edit()
            .putString("session_length_mode", settings.mode.name)
            .putString("custom_minutes", settings.customMinutes)
            .putBoolean("artist_separation", settings.artistSeparation)
            .apply()
    }
}
