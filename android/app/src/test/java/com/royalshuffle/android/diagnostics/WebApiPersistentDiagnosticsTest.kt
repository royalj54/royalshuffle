package com.royalshuffle.android.diagnostics

import com.royalshuffle.android.data.remote.RetryDelay
import com.royalshuffle.android.data.remote.SpotifyWebApiClient
import com.royalshuffle.android.data.remote.WebApiOperation
import com.royalshuffle.android.data.remote.WebApiOperationClass
import com.royalshuffle.android.data.remote.WebApiRequest
import com.royalshuffle.android.data.remote.WebApiResponse
import com.royalshuffle.android.data.remote.WebApiTransport
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WebApiPersistentDiagnosticsTest {
    @Test
    fun `retry and quota metadata persist without request or response secrets`() = runBlocking {
        val directory = Files.createTempDirectory("web-diagnostics-test").toFile()
        val logger = RollingFileDiagnosticLogger(directory, DiagnosticClock { "now" })
        val accessToken = "secret-access-token"
        val authorizationHeader = "Bearer $accessToken"
        val responseSecret = "secret-response-body"
        val responses = ArrayDeque(
            listOf(
                WebApiResponse(429, mapOf("Retry-After" to listOf("2")), "{}"),
                WebApiResponse(200, emptyMap(), "{}"),
                WebApiResponse(
                    429,
                    mapOf("Retry-After" to listOf("5")),
                    "{\"error\":{\"reason\":\"QUOTA_EXCEEDED\",\"message\":\"$responseSecret\"}}",
                ),
            ),
        )
        val client = SpotifyWebApiClient(
            transport = WebApiTransport { responses.removeFirst() },
            retryDelay = RetryDelay { },
            diagnostics = logger.asWebApiDiagnostics(),
        )
        val request = WebApiRequest(
            method = "GET",
            url = "https://api.spotify.com/v1/me/playlists",
            accessToken = accessToken,
        )
        client.requestJson(request, READ) { Unit }
        runCatching { client.requestJson(request, READ) { Unit } }

        val text = File(directory, RollingFileDiagnosticLogger.ACTIVE_FILE_NAME).readText()
        assertTrue(text.contains("operationClass=READ"))
        assertTrue(text.contains("httpStatus=429"))
        assertTrue(text.contains("retryAfter=2"))
        assertTrue(text.contains("retryAttempt=1"))
        assertTrue(text.contains("retryDelaySeconds=2.0"))
        assertTrue(text.contains("failureCategory=QUOTA_EXCEEDED"))
        assertTrue(text.contains("quotaReason=QUOTA_EXCEEDED"))
        assertFalse(text.contains(accessToken))
        assertFalse(text.contains(authorizationHeader))
        assertFalse(text.contains(responseSecret))
        assertFalse(text.contains("message"))
    }

    private companion object {
        val READ = WebApiOperation("load_current_user_playlists", WebApiOperationClass.READ)
    }
}
