package com.royalshuffle.android.auth

import java.net.URI
import java.net.URLDecoder
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class SpotifyAuthRepositoryTest {
    private val storage = FakeAuthStorage()
    private val tokenClient = FakeTokenClient()
    private var nowSeconds = 1_000L
    private val repository = SpotifyAuthRepository(
        clientId = "client-id",
        redirectUri = REDIRECT_URI,
        scopes = listOf("playlist-read-private", "playlist-modify-private"),
        storage = storage,
        tokenClient = tokenClient,
        pkceProvider = FixedPkceProvider,
        clock = EpochClock { nowSeconds },
    )

    @Test
    fun `begin authorization stores pending values and sends only the challenge`() {
        val authorizationUrl = repository.beginAuthorization()
        val query = parseQuery(authorizationUrl)

        assertEquals(PENDING, storage.pending)
        assertEquals("S256", query["code_challenge_method"])
        assertEquals("fixed-challenge", query["code_challenge"])
        assertEquals("fixed-state", query["state"])
        assertEquals(REDIRECT_URI, query["redirect_uri"])
        assertFalse(authorizationUrl.contains("fixed-verifier"))
    }

    @Test
    fun `callback rejects mismatched state without exchanging the code`() = runBlocking {
        storage.pending = PENDING

        val error = expectAuthException {
            repository.handleCallback("$REDIRECT_URI?code=authorization-code&state=wrong")
        }

        assertEquals(AuthException.Reason.STATE_MISMATCH, error.reason)
        assertEquals(0, tokenClient.exchangeCount)
        assertNotNull(storage.pending)
    }

    @Test
    fun `callback exchanges code and persists a complete session`() = runBlocking {
        storage.pending = PENDING
        tokenClient.exchangeResponse = TokenResponse("access", "refresh", 3_600L)

        repository.handleCallback(
            "$REDIRECT_URI?code=authorization-code&state=${PENDING.state}",
        )

        assertEquals(TokenSession("access", "refresh", 4_600L), storage.session)
        assertEquals("authorization-code", tokenClient.lastCode)
        assertEquals("fixed-verifier", tokenClient.lastVerifier)
        assertNull(storage.pending)
    }

    @Test
    fun `callback accepts Android trailing slash representation`() = runBlocking {
        storage.pending = PENDING

        repository.handleCallback(
            "$REDIRECT_URI/?code=authorization-code&state=${PENDING.state}",
        )

        assertEquals(1, tokenClient.exchangeCount)
        assertNotNull(storage.session)
    }

    @Test
    fun `authorization error validates state and ignores description value`() = runBlocking {
        storage.pending = PENDING

        val error = expectAuthException {
            repository.handleCallback(
                "$REDIRECT_URI?error=access_denied&error_description=denied" +
                    "&state=${PENDING.state}",
            )
        }

        assertEquals(AuthException.Reason.AUTHORIZATION_DENIED, error.reason)
        assertNull(storage.pending)
        assertEquals(0, tokenClient.exchangeCount)
    }

    @Test
    fun `restore keeps a valid session without refreshing`() = runBlocking {
        storage.session = TokenSession("access", "refresh", 2_000L)

        assertTrue(repository.restoreSession())

        assertEquals(0, tokenClient.refreshCount)
    }

    @Test
    fun `refresh preserves the existing refresh token when omitted`() = runBlocking {
        storage.session = TokenSession("old-access", "existing-refresh", 1_020L)
        tokenClient.refreshResponse = TokenResponse("new-access", null, 3_600L)

        assertTrue(repository.restoreSession())

        assertEquals(TokenSession("new-access", "existing-refresh", 4_600L), storage.session)
    }

    @Test
    fun `invalid refresh token clears the session for clean reauthorization`() = runBlocking {
        storage.session = TokenSession("old-access", "expired-refresh", 1_020L)
        tokenClient.refreshError = TokenEndpointException("invalid_grant")

        assertFalse(repository.restoreSession())

        assertNull(storage.session)
    }

    @Test
    fun `disconnect clears session and pending authorization`() {
        storage.session = TokenSession("access", "refresh", 2_000L)
        storage.pending = PENDING

        repository.disconnect()

        assertNull(storage.session)
        assertNull(storage.pending)
    }

    private suspend fun expectAuthException(block: suspend () -> Unit): AuthException {
        try {
            block()
            fail("Expected AuthException")
        } catch (error: AuthException) {
            return error
        }
        error("Unreachable")
    }

    private fun parseQuery(url: String): Map<String, String> = URI(url).rawQuery
        .split('&')
        .associate { parameter ->
            val parts = parameter.split('=', limit = 2)
            decode(parts[0]) to decode(parts[1])
        }

    private fun decode(value: String): String = URLDecoder.decode(value, Charsets.UTF_8.name())

    private class FakeAuthStorage : AuthStorage {
        var session: TokenSession? = null
        var pending: PendingAuthorization? = null

        override fun loadSession() = session
        override fun saveSession(session: TokenSession) {
            this.session = session
        }

        override fun clearSession() {
            session = null
        }

        override fun loadPendingAuthorization() = pending
        override fun savePendingAuthorization(pendingAuthorization: PendingAuthorization) {
            pending = pendingAuthorization
        }

        override fun clearPendingAuthorization() {
            pending = null
        }
    }

    private class FakeTokenClient : TokenEndpointClient {
        var exchangeResponse = TokenResponse("access", "refresh", 3_600L)
        var refreshResponse = TokenResponse("access", null, 3_600L)
        var refreshError: TokenEndpointException? = null
        var exchangeCount = 0
        var refreshCount = 0
        var lastCode: String? = null
        var lastVerifier: String? = null

        override suspend fun exchangeAuthorizationCode(
            clientId: String,
            code: String,
            codeVerifier: String,
            redirectUri: String,
        ): TokenResponse {
            exchangeCount += 1
            lastCode = code
            lastVerifier = codeVerifier
            return exchangeResponse
        }

        override suspend fun refreshAccessToken(
            clientId: String,
            refreshToken: String,
        ): TokenResponse {
            refreshCount += 1
            refreshError?.let { throw it }
            return refreshResponse
        }
    }

    private object FixedPkceProvider : PkceProvider {
        override fun createPendingAuthorization() = PENDING
        override fun createCodeChallenge(codeVerifier: String) = "fixed-challenge"
    }

    private companion object {
        const val REDIRECT_URI = "com.royalshuffle.android.auth://callback"
        val PENDING = PendingAuthorization("fixed-state", "fixed-verifier")
    }
}
