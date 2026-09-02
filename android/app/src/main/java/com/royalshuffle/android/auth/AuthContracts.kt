package com.royalshuffle.android.auth

import kotlinx.coroutines.flow.StateFlow

interface AuthStorage {
    fun loadSession(): TokenSession?
    fun saveSession(session: TokenSession)
    fun clearSession()
    fun loadPendingAuthorization(): PendingAuthorization?
    fun savePendingAuthorization(pendingAuthorization: PendingAuthorization)
    fun clearPendingAuthorization()
}

interface TokenEndpointClient {
    suspend fun exchangeAuthorizationCode(
        clientId: String,
        code: String,
        codeVerifier: String,
        redirectUri: String,
    ): TokenResponse

    suspend fun refreshAccessToken(
        clientId: String,
        refreshToken: String,
    ): TokenResponse
}

fun interface EpochClock {
    fun nowSeconds(): Long
}

interface PkceProvider {
    fun createPendingAuthorization(): PendingAuthorization
    fun createCodeChallenge(codeVerifier: String): String
}

fun interface AccessTokenProvider {
    suspend fun getValidAccessToken(): String?
}

enum class SpotifySessionState {
    UNKNOWN,
    ACTIVE,
    MISSING,
    INVALIDATED,
}

interface SessionInvalidator {
    val sessionState: StateFlow<SpotifySessionState>
    fun invalidateSession()
}
