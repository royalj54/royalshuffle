package com.royalshuffle.android.output

import java.math.BigInteger

enum class SessionLengthMode { FULL, SIXTY_MINUTES, CUSTOM }

data class OutputOptions(
    val sessionMinutes: BigInteger? = null,
    val artistSeparation: Boolean = false,
) {
    fun validate() {
        if (sessionMinutes != null && sessionMinutes.signum() <= 0) {
            throw OutputPlanningException("Enter a positive whole number of minutes.")
        }
    }
}

data class OutputSettings(
    val mode: SessionLengthMode = SessionLengthMode.FULL,
    val customMinutes: String = "",
    val artistSeparation: Boolean = false,
) {
    val validationMessage: String?
        get() = if (mode == SessionLengthMode.CUSTOM && parseCustomMinutes(customMinutes) == null)
            "Enter a positive whole number of minutes." else null

    fun snapshot(): OutputOptions {
        if (validationMessage != null) throw OutputPlanningException(validationMessage!!)
        return OutputOptions(
            when (mode) {
                SessionLengthMode.FULL -> null
                SessionLengthMode.SIXTY_MINUTES -> BigInteger.valueOf(60)
                SessionLengthMode.CUSTOM -> parseCustomMinutes(customMinutes)!!
            },
            artistSeparation,
        )
    }
}

fun parseCustomMinutes(text: String): BigInteger? {
    val digits = text.trim()
    if (digits.isEmpty() || digits.any { it !in '0'..'9' }) return null
    return try { BigInteger(digits).takeIf { it.signum() > 0 } }
    catch (_: NumberFormatException) { null }
}

interface OutputSettingsStorage {
    fun load(): OutputSettings
    fun save(settings: OutputSettings)
}

internal class MemoryOutputSettingsStorage : OutputSettingsStorage {
    private var settings = OutputSettings()
    override fun load() = settings
    override fun save(settings: OutputSettings) { this.settings = settings }
}

class OutputPlanningException(message: String) : IllegalArgumentException(message)
