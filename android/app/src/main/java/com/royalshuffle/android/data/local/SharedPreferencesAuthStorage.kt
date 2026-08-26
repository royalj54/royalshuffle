package com.royalshuffle.android.data.local

import android.content.Context
import com.royalshuffle.android.auth.AuthStorage
import com.royalshuffle.android.auth.PendingAuthorization
import com.royalshuffle.android.auth.TokenSession

class SharedPreferencesAuthStorage(context: Context) : AuthStorage {
    private val preferences = context.getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE)

    override fun loadSession(): TokenSession? {
        val accessToken = preferences.getString(KEY_ACCESS_TOKEN, null) ?: return null
        val refreshToken = preferences.getString(KEY_REFRESH_TOKEN, null) ?: return null
        val expiresAt = preferences.getLong(KEY_EXPIRES_AT, 0L)
        if (expiresAt <= 0L) return null
        return TokenSession(accessToken, refreshToken, expiresAt)
    }

    override fun saveSession(session: TokenSession) {
        preferences.edit()
            .putString(KEY_ACCESS_TOKEN, session.accessToken)
            .putString(KEY_REFRESH_TOKEN, session.refreshToken)
            .putLong(KEY_EXPIRES_AT, session.expiresAtEpochSeconds)
            .apply()
    }

    override fun clearSession() {
        preferences.edit()
            .remove(KEY_ACCESS_TOKEN)
            .remove(KEY_REFRESH_TOKEN)
            .remove(KEY_EXPIRES_AT)
            .apply()
    }

    override fun loadPendingAuthorization(): PendingAuthorization? {
        val state = preferences.getString(KEY_PENDING_STATE, null) ?: return null
        val verifier = preferences.getString(KEY_CODE_VERIFIER, null) ?: return null
        return PendingAuthorization(state, verifier)
    }

    override fun savePendingAuthorization(pendingAuthorization: PendingAuthorization) {
        preferences.edit()
            .putString(KEY_PENDING_STATE, pendingAuthorization.state)
            .putString(KEY_CODE_VERIFIER, pendingAuthorization.codeVerifier)
            .apply()
    }

    override fun clearPendingAuthorization() {
        preferences.edit()
            .remove(KEY_PENDING_STATE)
            .remove(KEY_CODE_VERIFIER)
            .apply()
    }

    private companion object {
        const val FILE_NAME = "royalshuffle_auth"
        const val KEY_ACCESS_TOKEN = "access_token"
        const val KEY_REFRESH_TOKEN = "refresh_token"
        const val KEY_EXPIRES_AT = "expires_at"
        const val KEY_PENDING_STATE = "pending_state"
        const val KEY_CODE_VERIFIER = "code_verifier"
    }
}
