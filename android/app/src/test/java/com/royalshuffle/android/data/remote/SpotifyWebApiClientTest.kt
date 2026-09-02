package com.royalshuffle.android.data.remote

import com.royalshuffle.android.auth.SessionInvalidator
import com.royalshuffle.android.auth.SpotifySessionState
import java.io.IOException
import java.net.SocketTimeoutException
import java.util.concurrent.CancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class SpotifyWebApiClientTest {
    @Test
    fun `429 then success honors Retry-After for a read`() = runBlocking {
        val fixture = fixture(response(429, "2"), response(200))

        fixture.client.read()

        assertEquals(2, fixture.transport.attempts)
        assertEquals(listOf(2.0), fixture.delays)
        assertEquals(WebApiOperationClass.READ, fixture.diagnostics.single().operationClass)
    }

    @Test
    fun `missing Retry-After uses fallback`() = runBlocking {
        assertFallbackDelay(null)
    }

    @Test
    fun `malformed Retry-After uses fallback`() = runBlocking {
        assertFallbackDelay("later")
    }

    @Test
    fun `negative Retry-After uses fallback`() = runBlocking {
        assertFallbackDelay("-1")
    }

    @Test
    fun `non-finite Retry-After uses fallback`() = runBlocking {
        assertFallbackDelay("NaN")
    }

    @Test
    fun `Retry-After over sixty fails without delay or retry`() = runBlocking {
        val fixture = fixture(response(429, "61"))

        val error = runCatching { fixture.client.read() }.exceptionOrNull()

        assertCategory(error, WebApiFailureCategory.RATE_LIMITED)
        assertEquals(1, fixture.transport.attempts)
        assertTrue(fixture.delays.isEmpty())
    }

    @Test
    fun `rate limiting stops after exactly three retries`() = runBlocking {
        val fixture = fixture(
            response(429, "1"), response(429, "1"),
            response(429, "1"), response(429, "1"),
        )

        val error = runCatching { fixture.client.read() }.exceptionOrNull()

        assertCategory(error, WebApiFailureCategory.RATE_LIMITED)
        assertEquals(4, fixture.transport.attempts)
        assertEquals(listOf(1.0, 1.0, 1.0), fixture.delays)
    }

    @Test
    fun `quota exceeded does not delay or retry`() = runBlocking {
        val fixture = fixture(
            response(
                status = 429,
                retryAfter = "2",
                body = "{\"error\":{\"reason\":\"QUOTA_EXCEEDED\"}}",
            ),
        )

        val error = runCatching { fixture.client.read() }.exceptionOrNull()

        assertCategory(error, WebApiFailureCategory.QUOTA_EXCEEDED)
        assertEquals(1, fixture.transport.attempts)
        assertTrue(fixture.delays.isEmpty())
        assertEquals("QUOTA_EXCEEDED", fixture.diagnostics.single().quotaReason)
    }

    @Test
    fun `explicit 429 can retry a non-idempotent write`() = runBlocking {
        val fixture = fixture(response(429, "0"), response(200))

        fixture.client.write()

        assertEquals(2, fixture.transport.attempts)
        assertEquals(listOf(0.0), fixture.delays)
        assertEquals(
            WebApiOperationClass.NON_IDEMPOTENT_WRITE,
            fixture.diagnostics.single().operationClass,
        )
    }

    @Test
    fun `non-idempotent write does not retry timeout or disconnect`() = runBlocking {
        listOf(SocketTimeoutException("timeout"), IOException("disconnect")).forEach { failure ->
            val fixture = fixture(failure)

            val error = runCatching { fixture.client.write() }.exceptionOrNull()

            assertCategory(error, WebApiFailureCategory.CONNECTIVITY)
            assertEquals(1, fixture.transport.attempts)
            assertSame(failure, error?.cause)
        }
    }

    @Test
    fun `non-idempotent write does not retry server failures`() = runBlocking {
        listOf(500, 503).forEach { status ->
            val fixture = fixture(response(status))

            val error = runCatching { fixture.client.write() }.exceptionOrNull()

            assertCategory(error, WebApiFailureCategory.SERVER)
            assertEquals(1, fixture.transport.attempts)
        }
    }

    @Test
    fun `non-idempotent write does not retry malformed response`() = runBlocking {
        val fixture = fixture(response(200, body = "not-json"))

        val error = runCatching { fixture.client.write() }.exceptionOrNull()

        assertCategory(error, WebApiFailureCategory.INVALID_RESPONSE)
        assertEquals(1, fixture.transport.attempts)
    }

    @Test
    fun `coroutine cancellation propagates without retry`() = runBlocking {
        val cancellation = CancellationException("cancelled")
        val fixture = fixture(cancellation)

        val error = runCatching { fixture.client.read() }.exceptionOrNull()

        assertSame(cancellation, error)
        assertEquals(1, fixture.transport.attempts)
        assertTrue(fixture.delays.isEmpty())
    }

    @Test
    fun `HTTP statuses map to typed categories`() = runBlocking {
        mapOf(
            401 to WebApiFailureCategory.AUTHENTICATION,
            403 to WebApiFailureCategory.PERMISSION,
            429 to WebApiFailureCategory.RATE_LIMITED,
            500 to WebApiFailureCategory.SERVER,
            503 to WebApiFailureCategory.SERVER,
        ).forEach { (status, category) ->
            val fixture = if (status == 429) {
                fixture(response(status, "61"))
            } else {
                fixture(response(status))
            }

            val error = runCatching { fixture.client.read() }.exceptionOrNull()

            assertCategory(error, category)
        }
    }

    @Test
    fun `401 invalidates session and is never replayed`() = runBlocking {
        val invalidator = FakeSessionInvalidator()
        val fixture = fixture(response(401), sessionInvalidator = invalidator)

        val error = runCatching { fixture.client.write() }.exceptionOrNull()

        assertCategory(error, WebApiFailureCategory.AUTHENTICATION)
        assertEquals(1, fixture.transport.attempts)
        assertEquals(1, invalidator.invalidationCount)
        assertEquals(SpotifySessionState.INVALIDATED, invalidator.sessionState.value)
    }

    @Test
    fun `empty and malformed JSON map to invalid response`() = runBlocking {
        listOf("", "not-json").forEach { body ->
            val fixture = fixture(response(200, body = body))

            val error = runCatching { fixture.client.read() }.exceptionOrNull()

            assertCategory(error, WebApiFailureCategory.INVALID_RESPONSE)
        }
    }

    @Test
    fun `invalid pagination target is rejected before transport`() = runBlocking {
        val fixture = fixture(response(200))

        val error = runCatching {
            fixture.client.requestJson(
                WebApiRequest("GET", "http://example.com/page", "token"),
                READ_OPERATION,
            ) { Unit }
        }.exceptionOrNull()

        assertCategory(error, WebApiFailureCategory.INVALID_PAGINATION)
        assertEquals(0, fixture.transport.attempts)
    }

    private suspend fun assertFallbackDelay(retryAfter: String?) {
        val fixture = fixture(response(429, retryAfter), response(200))
        fixture.client.read()
        assertEquals(listOf(1.0), fixture.delays)
    }

    private fun fixture(
        vararg outcomes: Any,
        sessionInvalidator: SessionInvalidator? = null,
    ): Fixture {
        val transport = FakeTransport(outcomes.toList())
        val delays = mutableListOf<Double>()
        val diagnostics = mutableListOf<WebApiDiagnosticEvent>()
        return Fixture(
            client = SpotifyWebApiClient(
                transport = transport,
                retryDelay = RetryDelay { delays += it },
                diagnostics = WebApiDiagnostics { diagnostics += it },
                sessionInvalidator = sessionInvalidator,
            ),
            transport = transport,
            delays = delays,
            diagnostics = diagnostics,
        )
    }

    private suspend fun SpotifyWebApiClient.read() = requestJson(
        WebApiRequest("GET", VALID_URL, "token"),
        READ_OPERATION,
    ) { Unit }

    private suspend fun SpotifyWebApiClient.write() = requestJson(
        WebApiRequest("POST", VALID_URL, "token", "{}"),
        WRITE_OPERATION,
    ) { Unit }

    private fun response(
        status: Int,
        retryAfter: String? = null,
        body: String = "{}",
    ) = WebApiResponse(
        statusCode = status,
        headers = retryAfter?.let { mapOf("Retry-After" to listOf(it)) }.orEmpty(),
        body = body,
    )

    private fun assertCategory(error: Throwable?, expected: WebApiFailureCategory) {
        assertTrue(error is SpotifyWebApiException)
        assertEquals(expected, (error as SpotifyWebApiException).category)
    }

    private data class Fixture(
        val client: SpotifyWebApiClient,
        val transport: FakeTransport,
        val delays: List<Double>,
        val diagnostics: List<WebApiDiagnosticEvent>,
    )

    private class FakeTransport(outcomes: List<Any>) : WebApiTransport {
        private val outcomes = ArrayDeque(outcomes)
        var attempts = 0

        override suspend fun execute(request: WebApiRequest): WebApiResponse {
            attempts += 1
            return when (val outcome = outcomes.removeFirst()) {
                is WebApiResponse -> outcome
                is Throwable -> throw outcome
                else -> error("Unsupported outcome")
            }
        }
    }

    private class FakeSessionInvalidator : SessionInvalidator {
        private val mutableState = MutableStateFlow(SpotifySessionState.ACTIVE)
        override val sessionState: StateFlow<SpotifySessionState> = mutableState
        var invalidationCount = 0

        override fun invalidateSession() {
            invalidationCount += 1
            mutableState.value = SpotifySessionState.INVALIDATED
        }
    }

    private companion object {
        const val VALID_URL = "https://api.spotify.com/v1/test"
        val READ_OPERATION = WebApiOperation("test read", WebApiOperationClass.READ)
        val WRITE_OPERATION = WebApiOperation(
            "test write",
            WebApiOperationClass.NON_IDEMPOTENT_WRITE,
        )
    }
}
