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
import org.json.JSONObject
import org.junit.Test

class SpotifyOutputPlaylistApiTest {
    @Test fun `membership 401 invalidates session and preserves exact request without replay`() = runBlocking {
        val transport = FakeTransport(WebApiResponse(401,emptyMap(),"{}"))
        val invalidator = FakeSessionInvalidator()
        val error = runCatching {
            SpotifyOutputPlaylistApi(client(transport,invalidator)).isPlaylistSaved("bound","token")
        }.exceptionOrNull() as SpotifyWebApiException
        assertEquals(401,error.httpStatus)
        assertEquals(WebApiFailureCategory.AUTHENTICATION,error.category)
        assertEquals(1,invalidator.invalidationCount)
        assertEquals(1,transport.attempts)
        assertEquals("https://api.spotify.com/v1/me/library/contains?uris=spotify%3Aplaylist%3Abound",transport.requests.single().url)
    }

    @Test fun `membership 403 records operation and status and produces generic permission message`() = runBlocking {
        val transport = FakeTransport(WebApiResponse(403,emptyMap(),"{}"))
        val events = mutableListOf<WebApiDiagnosticEvent>()
        val api = SpotifyOutputPlaylistApi(SpotifyWebApiClient(
            transport = transport,diagnostics = WebApiDiagnostics { events += it },
        ))
        val error = runCatching { api.isPlaylistSaved("bound","token") }
            .exceptionOrNull() as SpotifyWebApiException
        assertEquals(403,error.httpStatus)
        assertEquals(WebApiFailureCategory.PERMISSION,error.category)
        assertEquals("managed output library membership",events.single().operationName)
        assertEquals(403,events.single().httpStatus)
        assertEquals(WebApiOperationClass.READ,events.single().operationClass)
        assertEquals(1,transport.attempts)
    }

    @Test fun `membership explicit 429 retries exact read and accepts boolean acknowledgement`() = runBlocking {
        val transport = FakeTransport(response(429,"0"),WebApiResponse(200,emptyMap(),"[true]"))
        assertTrue(SpotifyOutputPlaylistApi(client(transport)).isPlaylistSaved("bound","token"))
        assertEquals(2,transport.attempts)
        assertEquals(transport.requests.first(),transport.requests.last())
        assertTrue(transport.requests.all { it.method == "GET" })
    }

    @Test fun `membership quota exceeded 429 aborts without replay and retains category`() = runBlocking {
        val transport = FakeTransport(WebApiResponse(429,mapOf("Retry-After" to listOf("0")),
            """{"error":{"reason":"QUOTA_EXCEEDED"}}"""))
        val error = runCatching { SpotifyOutputPlaylistApi(client(transport)).isPlaylistSaved("bound","token") }
            .exceptionOrNull() as SpotifyWebApiException
        assertEquals(429,error.httpStatus)
        assertEquals(WebApiFailureCategory.QUOTA_EXCEEDED,error.category)
        assertEquals(1,transport.attempts)
    }

    @Test fun `library membership checks exact playlist URI with strict boolean response`() = runBlocking {
        for (saved in listOf(true,false)) {
            val transport = FakeTransport(WebApiResponse(200,emptyMap(),"[$saved]"))
            assertEquals(saved,SpotifyOutputPlaylistApi(client(transport)).isPlaylistSaved("bound","token"))
            assertEquals("GET",transport.requests.single().method)
            assertEquals("https://api.spotify.com/v1/me/library/contains?uris=spotify%3Aplaylist%3Abound",transport.requests.single().url)
        }
        for (body in listOf("[]","[false,true]","[\"false\"]","[null]","{}","not-json")) {
            val transport = FakeTransport(WebApiResponse(200,emptyMap(),body))
            val error = runCatching { SpotifyOutputPlaylistApi(client(transport)).isPlaylistSaved("bound","token") }
                .exceptionOrNull() as SpotifyWebApiException
            assertEquals(WebApiFailureCategory.INVALID_RESPONSE,error.category)
            assertEquals(200,error.httpStatus)
            assertEquals(1,transport.attempts)
        }
    }

    @Test fun `membership and clear preserve permission and missing statuses without replay`() = runBlocking {
        for (status in listOf(403,404)) for (clear in listOf(false,true)) {
            val transport = FakeTransport(WebApiResponse(status,emptyMap(),"{}"))
            val api = SpotifyOutputPlaylistApi(client(transport))
            val error = runCatching {
                if (clear) api.clearItems("bound","token") else api.isPlaylistSaved("bound","token")
            }.exceptionOrNull() as SpotifyWebApiException
            assertEquals(status,error.httpStatus)
            assertEquals(1,transport.attempts)
        }
    }
    @Test
    fun `direct lookup uses exact ID and preserves current name and performs no write`() = runBlocking {
        val transport = FakeTransport(WebApiResponse(200,emptyMap(),"""{"id":"bound","name":"Renamed"}"""))
        val playlist = SpotifyOutputPlaylistApi(client(transport)).getPlaylist("bound","token")
        assertEquals("Renamed",playlist.name)
        assertEquals("GET",transport.requests.single().method)
        assertEquals("https://api.spotify.com/v1/playlists/bound",transport.requests.single().url)
    }

    @Test
    fun `direct lookup rejects mismatched identity and nonstring name`() = runBlocking {
        for (body in listOf("""{"id":"wrong","name":"Name"}""","""{"id":"bound","name":12}""")) {
            val transport = FakeTransport(WebApiResponse(200,emptyMap(),body))
            val error = runCatching { SpotifyOutputPlaylistApi(client(transport)).getPlaylist("bound","token") }.exceptionOrNull()
            assertTrue(error is SpotifyWebApiException)
            assertEquals(WebApiFailureCategory.INVALID_RESPONSE,(error as SpotifyWebApiException).category)
            assertEquals(1,transport.attempts)
        }
    }

    @Test
    fun `direct lookup retains confirmed 404 status for guarded recovery`() = runBlocking {
        val transport = FakeTransport(WebApiResponse(404,emptyMap(),"{}"))
        val error = runCatching { SpotifyOutputPlaylistApi(client(transport)).getPlaylist("bound","token") }
            .exceptionOrNull() as SpotifyWebApiException
        assertEquals(404,error.httpStatus)
        assertEquals(1,transport.attempts)
    }

    @Test
    fun `clear is PUT empty URI replacement without name or description changes`() = runBlocking {
        val transport = FakeTransport(WebApiResponse(200,emptyMap(),"""{"snapshot_id":"snapshot"}"""))
        SpotifyOutputPlaylistApi(client(transport)).clearItems("bound","token")
        val request = transport.requests.single()
        assertEquals("PUT",request.method)
        assertEquals("https://api.spotify.com/v1/playlists/bound/items",request.url)
        val body = JSONObject(request.body!!)
        assertEquals(0,body.getJSONArray("uris").length())
        assertFalse(body.has("name"))
        assertFalse(body.has("description"))
    }

    @Test
    fun `clear repeats only after explicit 429 and honors ambiguous write protection`() = runBlocking {
        val retry = FakeTransport(response(429,"0"),WebApiResponse(200,emptyMap(),"{}"))
        SpotifyOutputPlaylistApi(client(retry)).clearItems("bound","token")
        assertEquals(2,retry.attempts)
        for (outcome in listOf(SocketTimeoutException("timeout"),WebApiResponse(500,emptyMap(),"{}"),
            WebApiResponse(200,emptyMap(),"not-json"))) {
            val transport = FakeTransport(outcome)
            assertTrue(runCatching { SpotifyOutputPlaylistApi(client(transport)).clearItems("bound","token") }.isFailure)
            assertEquals(1,transport.attempts)
        }
    }

    @Test
    fun `clear 401 invalidates authentication without replay`() = runBlocking {
        val transport = FakeTransport(WebApiResponse(401,emptyMap(),"{}"))
        val invalidator = FakeSessionInvalidator()
        assertTrue(runCatching { SpotifyOutputPlaylistApi(client(transport,invalidator)).clearItems("bound","token") }.isFailure)
        assertEquals(1,invalidator.invalidationCount)
        assertEquals(1,transport.attempts)
    }
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
        val requests = mutableListOf<WebApiRequest>()

        override suspend fun execute(request: WebApiRequest): WebApiResponse {
            attempts += 1
            requests += request
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
