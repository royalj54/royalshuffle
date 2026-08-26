package com.royalshuffle.android.auth

import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder
import java.security.MessageDigest

class SpotifyAuthRepository(
    private val clientId: String,
    private val redirectUri: String,
    private val scopes: List<String>,
    private val storage: AuthStorage,
    private val tokenClient: TokenEndpointClient,
    private val pkceProvider: PkceProvider,
    private val clock: EpochClock,
) {
    fun beginAuthorization(): String {
        if (clientId.isBlank()) {
            throw AuthException(AuthException.Reason.CONFIGURATION_MISSING)
        }

        val pending = pkceProvider.createPendingAuthorization()
        storage.savePendingAuthorization(pending)

        val parameters = linkedMapOf(
            "client_id" to clientId,
            "response_type" to "code",
            "redirect_uri" to redirectUri,
            "scope" to scopes.joinToString(" "),
            "code_challenge_method" to "S256",
            "code_challenge" to pkceProvider.createCodeChallenge(pending.codeVerifier),
            "state" to pending.state,
        )
        return "https://accounts.spotify.com/authorize?" + parameters.toQueryString()
    }

    suspend fun restoreSession(): Boolean {
        val session = storage.loadSession() ?: return false
        if (session.expiresAtEpochSeconds > clock.nowSeconds() + EXPIRY_SKEW_SECONDS) {
            return true
        }

        return try {
            refresh(session)
            true
        } catch (error: TokenEndpointException) {
            if (error.errorCode == "invalid_grant") {
                storage.clearSession()
                false
            } else {
                throw AuthException(AuthException.Reason.NETWORK)
            }
        }
    }

    suspend fun handleCallback(callbackUri: String) {
        val parameters = parseCallback(callbackUri)
        val pending = storage.loadPendingAuthorization()
            ?: throw AuthException(AuthException.Reason.CALLBACK_INVALID)

        val returnedState = parameters["state"]
            ?: throw AuthException(AuthException.Reason.CALLBACK_INVALID)
        if (!secureEquals(pending.state, returnedState)) {
            throw AuthException(AuthException.Reason.STATE_MISMATCH)
        }

        if (parameters["error"] != null) {
            storage.clearPendingAuthorization()
            throw AuthException(AuthException.Reason.AUTHORIZATION_DENIED)
        }

        val code = parameters["code"]
            ?: throw AuthException(AuthException.Reason.CALLBACK_INVALID)
        try {
            val response = tokenClient.exchangeAuthorizationCode(
                clientId = clientId,
                code = code,
                codeVerifier = pending.codeVerifier,
                redirectUri = redirectUri,
            )
            val refreshToken = response.refreshToken
                ?: throw AuthException(AuthException.Reason.TOKEN_EXCHANGE_FAILED)
            storage.saveSession(response.toSession(refreshToken))
            storage.clearPendingAuthorization()
        } catch (error: TokenEndpointException) {
            throw AuthException(AuthException.Reason.TOKEN_EXCHANGE_FAILED)
        }
    }

    fun cancelAuthentication() {
        storage.clearPendingAuthorization()
    }

    fun disconnect() {
        storage.clearPendingAuthorization()
        storage.clearSession()
    }

    private suspend fun refresh(existing: TokenSession) {
        val response = tokenClient.refreshAccessToken(clientId, existing.refreshToken)
        storage.saveSession(
            response.toSession(response.refreshToken ?: existing.refreshToken),
        )
    }

    private fun TokenResponse.toSession(refreshToken: String) = TokenSession(
        accessToken = accessToken,
        refreshToken = refreshToken,
        expiresAtEpochSeconds = clock.nowSeconds() + expiresInSeconds,
    )

    private fun parseCallback(callbackUri: String): Map<String, String> {
        val uri = try {
            URI(callbackUri)
        } catch (error: Exception) {
            throw AuthException(AuthException.Reason.CALLBACK_INVALID)
        }
        val expectedUri = URI(redirectUri)
        if (
            !uri.scheme.equals(expectedUri.scheme, ignoreCase = true) ||
            !uri.host.equals(expectedUri.host, ignoreCase = true) ||
            normalizePath(uri.path) != normalizePath(expectedUri.path) ||
            uri.port != expectedUri.port ||
            uri.userInfo != expectedUri.userInfo ||
            uri.fragment != null
        ) {
            throw AuthException(AuthException.Reason.CALLBACK_INVALID)
        }
        return uri.rawQuery.orEmpty()
            .split('&')
            .filter { it.isNotBlank() }
            .associate { parameter ->
                val parts = parameter.split('=', limit = 2)
                decode(parts[0]) to decode(parts.getOrElse(1) { "" })
            }
    }

    private fun normalizePath(path: String?): String = path.orEmpty().trimEnd('/')

    private fun secureEquals(expected: String, actual: String): Boolean = MessageDigest.isEqual(
        expected.toByteArray(Charsets.UTF_8),
        actual.toByteArray(Charsets.UTF_8),
    )

    private fun Map<String, String>.toQueryString(): String = entries.joinToString("&") {
        "${encode(it.key)}=${encode(it.value)}"
    }

    private fun encode(value: String): String = URLEncoder.encode(value, Charsets.UTF_8.name())
    private fun decode(value: String): String = URLDecoder.decode(value, Charsets.UTF_8.name())

    private companion object {
        const val EXPIRY_SKEW_SECONDS = 60L
    }
}
