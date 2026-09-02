package com.royalshuffle.android.auth

data class TokenSession(
    val accessToken: String,
    val refreshToken: String,
    val expiresAtEpochSeconds: Long,
)

data class PendingAuthorization(
    val state: String,
    val codeVerifier: String,
)

data class TokenResponse(
    val accessToken: String,
    val refreshToken: String?,
    val expiresInSeconds: Long,
)

sealed interface AuthUiState {
    data object Restoring : AuthUiState
    data class Disconnected(val message: String? = null) : AuthUiState
    data object Authenticating : AuthUiState
    data object Connected : AuthUiState
    data class Error(val message: String) : AuthUiState
}

class AuthException(
    val reason: Reason,
) : Exception() {
    enum class Reason {
        AUTHORIZATION_DENIED,
        CALLBACK_INVALID,
        CONFIGURATION_MISSING,
        NETWORK,
        SESSION_EXPIRED,
        STATE_MISMATCH,
        TOKEN_EXCHANGE_FAILED,
    }
}

class TokenEndpointException(
    val errorCode: String?,
) : Exception()
