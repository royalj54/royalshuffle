package com.royalshuffle.android.data.remote

import com.royalshuffle.android.auth.SessionInvalidator
import com.royalshuffle.android.auth.SpotifySessionState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SpotifyPlaylistApiTest {
    @Test
    fun `playlist listing response still maps items and pagination`() = runBlocking {
        val transport = WebApiTransport {
            WebApiResponse(
                200,
                emptyMap(),
                """{
                    "items":[{"id":"one","name":"One"},{"id":"two","name":"Two"}],
                    "next":"https://api.spotify.com/v1/me/playlists?offset=2"
                }""".trimIndent(),
            )
        }
        val api = SpotifyPlaylistApi(
            SpotifyWebApiClient(
                transport = transport,
                retryDelay = RetryDelay { },
                diagnostics = WebApiDiagnostics { },
            ),
        )

        val page = api.getPlaylistsPage(
            "https://api.spotify.com/v1/me/playlists?limit=50",
            "token",
        )

        assertEquals(listOf("one", "two"), page.playlists.map { it.id })
        assertEquals("https://api.spotify.com/v1/me/playlists?offset=2", page.nextUrl)
    }

    @Test
    fun `playlist listing 401 invalidates session without replay`() = runBlocking {
        var attempts = 0
        val transport = WebApiTransport {
            attempts += 1
            WebApiResponse(401, emptyMap(), "{}")
        }
        val invalidator = FakeSessionInvalidator()
        val api = SpotifyPlaylistApi(
            SpotifyWebApiClient(
                transport = transport,
                retryDelay = RetryDelay { },
                diagnostics = WebApiDiagnostics { },
                sessionInvalidator = invalidator,
            ),
        )

        val error = runCatching {
            api.getPlaylistsPage("https://api.spotify.com/v1/me/playlists", "token")
        }.exceptionOrNull()

        assertTrue(error is SpotifyWebApiException)
        assertEquals(
            WebApiFailureCategory.AUTHENTICATION,
            (error as SpotifyWebApiException).category,
        )
        assertEquals(1, attempts)
        assertEquals(1, invalidator.invalidationCount)
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
}
