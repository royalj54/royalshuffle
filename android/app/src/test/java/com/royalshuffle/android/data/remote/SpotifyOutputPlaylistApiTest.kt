package com.royalshuffle.android.data.remote

import com.royalshuffle.android.auth.SessionInvalidator
import com.royalshuffle.android.auth.SpotifySessionState
import java.net.SocketTimeoutException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertNull
import org.junit.Test

class SpotifyOutputPlaylistApiTest {
    @Test
    fun `existing item response supplies duration and first artist without extra requests`() = runBlocking {
        val transport = FakeTransport(WebApiResponse(200, emptyMap(), """{
            "items": [
                {"item":{"uri":"spotify:track:group","type":"track","duration_ms":123456,
                    "artists":[{"id":"BLACKPINK-id","name":"Same name"},{"id":"Lisa-id"}]}},
                {"item":{"uri":"spotify:track:solo","duration_ms":60000,
                    "artists":[{"id":"Lisa-id","name":"Same name"}]}},
                {"item":{"uri":"spotify:episode:podcast","type":"episode","duration_ms":1000}},
                {"item":{"type":"track","artists":[{"name":"Missing ID"}]}},
                {"item":null}
            ], "next":null
        }"""))
        val page = SpotifyOutputPlaylistApi(client(transport)).getPlaylistItemsPage(ITEMS_URL, "token")
        assertEquals(1, transport.attempts)
        assertEquals(listOf("BLACKPINK-id", "Lisa-id", null, null, null), page.items.map { it.primaryArtistId })
        assertEquals(listOf(123456L,60000L,1000L,null,null), page.items.map { it.durationMs })
        assertEquals("episode", page.items[2].itemType)
        assertNull(page.items[3].uri)
        assertNull(page.items[4].uri)
    }

    @Test
    fun `duration parser does not coerce fractions strings or booleans`() = runBlocking {
        val transport = FakeTransport(WebApiResponse(200, emptyMap(), """{
            "items":[
                {"item":{"uri":"spotify:track:a","duration_ms":1.5}},
                {"item":{"uri":"spotify:track:b","duration_ms":"60000"}},
                {"item":{"uri":"spotify:track:c","duration_ms":0}},
                {"item":{"uri":"spotify:track:d","duration_ms":-1}},
                {"item":{"uri":"spotify:track:e","duration_ms":true}},
                {"item":{"uri":"spotify:track:f","duration_ms":9223372036854775807}}
            ],"next":null
        }"""))
        val page = SpotifyOutputPlaylistApi(client(transport)).getPlaylistItemsPage(ITEMS_URL,"token")
        assertEquals(listOf(null,null,null,null,null,Long.MAX_VALUE), page.items.map { it.durationMs })
    }

    @Test
    fun `nonstring URI artist and type cannot be coerced into eligible metadata`() = runBlocking {
        val transport = FakeTransport(WebApiResponse(200, emptyMap(), """{
            "items":[{"item":{"uri":12,"type":true,"artists":[{"id":12}]}},
                {"item":{"uri":"spotify:track:a","artists":[{}, {"id":"second"}]}}],"next":null
        }"""))
        val page = SpotifyOutputPlaylistApi(client(transport)).getPlaylistItemsPage(ITEMS_URL,"token")
        assertNull(page.items[0].uri)
        assertNull(page.items[0].primaryArtistId)
        assertEquals("unsupported", page.items[0].itemType)
        assertNull(page.items[1].primaryArtistId)
    }
    @Test
    fun `playlist level is_local marks entry local`() {
        assertTrue(
            isLocalPlaylistItem(
                playlistItemIsLocal = true,
                itemIsLocal = false,
                uri = "spotify:track:one",
            ),
        )
    }

    @Test
    fun `item level is_local marks entry local`() {
        assertTrue(
            isLocalPlaylistItem(
                playlistItemIsLocal = false,
                itemIsLocal = true,
                uri = "spotify:track:one",
            ),
        )
    }

    @Test
    fun `local URI is a defensive fallback`() {
        assertTrue(
            isLocalPlaylistItem(
                playlistItemIsLocal = false,
                itemIsLocal = false,
                uri = "spotify:local:artist:album:title:120",
            ),
        )
    }

    @Test
    fun `ordinary URI remains non-local without explicit signals`() {
        assertFalse(
            isLocalPlaylistItem(
                playlistItemIsLocal = false,
                itemIsLocal = false,
                uri = "spotify:track:one",
            ),
        )
    }

    @Test
    fun `playlist item response preserves both explicit local signals and URI fallback`() =
        runBlocking {
            val transport = FakeTransport(
                WebApiResponse(
                    200,
                    emptyMap(),
                    """{
                        "items": [
                            {"is_local":true,"item":{"uri":"spotify:track:one"}},
                            {"item":{"uri":"spotify:track:two","is_local":true}},
                            {"item":{"uri":"spotify:local:a:b:c:1"}},
                            {"item":{"uri":"spotify:track:three"}}
                        ],
                        "next": null
                    }""".trimIndent(),
                ),
            )
            val api = SpotifyOutputPlaylistApi(client(transport))

            val page = api.getPlaylistItemsPage(ITEMS_URL, "token")

            assertEquals(listOf(true, true, true, false), page.items.map { it.isLocal })
        }

    @Test
    fun `playlist creation repeats after explicit 429`() = runBlocking {
        val transport = FakeTransport(
            response(429, "0"),
            WebApiResponse(201, emptyMap(), "{\"id\":\"outputid\",\"name\":\"Output\"}"),
        )
        val api = SpotifyOutputPlaylistApi(client(transport))

        api.createPrivatePlaylist("Output", "Description", "token")

        assertEquals(2, transport.attempts)
    }

    @Test
    fun `add items repeats after explicit 429`() = runBlocking {
        val transport = FakeTransport(
            response(429, "0"),
            WebApiResponse(201, emptyMap(), "{\"snapshot_id\":\"snapshot\"}"),
        )
        val api = SpotifyOutputPlaylistApi(client(transport))

        api.addItems("outputid", listOf("spotify:track:one"), "token")

        assertEquals(2, transport.attempts)
    }

    @Test
    fun `playlist creation 401 invalidates session without replay`() = runBlocking {
        val transport = FakeTransport(WebApiResponse(401, emptyMap(), "{}"))
        val invalidator = FakeSessionInvalidator()
        val api = SpotifyOutputPlaylistApi(client(transport, invalidator))

        val error = runCatching {
            api.createPrivatePlaylist("Output", "Description", "token")
        }.exceptionOrNull()

        assertTrue(error is SpotifyWebApiException)
        assertEquals(
            WebApiFailureCategory.AUTHENTICATION,
            (error as SpotifyWebApiException).category,
        )
        assertEquals(1, transport.attempts)
        assertEquals(1, invalidator.invalidationCount)
    }

    @Test
    fun `add items 401 invalidates session without replay`() = runBlocking {
        val transport = FakeTransport(WebApiResponse(401, emptyMap(), "{}"))
        val invalidator = FakeSessionInvalidator()
        val api = SpotifyOutputPlaylistApi(client(transport, invalidator))

        val error = runCatching {
            api.addItems("outputid", listOf("spotify:track:one"), "token")
        }.exceptionOrNull()

        assertTrue(error is SpotifyWebApiException)
        assertEquals(
            WebApiFailureCategory.AUTHENTICATION,
            (error as SpotifyWebApiException).category,
        )
        assertEquals(1, transport.attempts)
        assertEquals(1, invalidator.invalidationCount)
    }

    @Test
    fun `create and add do not retry ambiguous failures`() = runBlocking {
        val outcomes = listOf(
            SocketTimeoutException("timeout"),
            WebApiResponse(500, emptyMap(), "{}"),
            WebApiResponse(503, emptyMap(), "{}"),
            WebApiResponse(201, emptyMap(), "not-json"),
        )
        outcomes.forEach { outcome ->
            val createTransport = FakeTransport(outcome)
            runCatching {
                SpotifyOutputPlaylistApi(client(createTransport))
                    .createPrivatePlaylist("Output", "Description", "token")
            }
            assertEquals(1, createTransport.attempts)

            val addTransport = FakeTransport(outcome)
            runCatching {
                SpotifyOutputPlaylistApi(client(addTransport))
                    .addItems("outputid", listOf("spotify:track:one"), "token")
            }
            assertEquals(1, addTransport.attempts)
        }
    }

    private fun client(
        transport: FakeTransport,
        sessionInvalidator: SessionInvalidator? = null,
    ) = SpotifyWebApiClient(
        transport = transport,
        retryDelay = RetryDelay { },
        diagnostics = WebApiDiagnostics { },
        sessionInvalidator = sessionInvalidator,
    )

    private fun response(status: Int, retryAfter: String) = WebApiResponse(
        status,
        mapOf("Retry-After" to listOf(retryAfter)),
        "{}",
    )

    private class FakeTransport(vararg outcomes: Any) : WebApiTransport {
        private val outcomes = ArrayDeque(outcomes.toList())
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
        const val ITEMS_URL = "https://api.spotify.com/v1/playlists/source/items?limit=50"
    }
}
