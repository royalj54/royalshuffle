package com.royalshuffle.android.data.remote

import com.royalshuffle.android.opportunity.*
import java.io.IOException
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class SpotifyOpportunityPlaylistApiTest {
    private class Transport(vararg outcomes: Any) : WebApiTransport {
        val queue = outcomes.toMutableList()
        val requests = mutableListOf<WebApiRequest>()
        override suspend fun execute(request: WebApiRequest): WebApiResponse {
            requests += request
            return when (val outcome = queue.removeAt(0)) {
                is Exception -> throw outcome
                else -> outcome as WebApiResponse
            }
        }
    }
    private fun response(status: Int, body: String) = WebApiResponse(status, emptyMap(), body)
    private fun api(transport: Transport) = SpotifyOpportunityPlaylistApi(SpotifyWebApiClient(transport,
        RetryDelay { }, WebApiDiagnostics { }))
    private suspend fun invalid(block: suspend () -> Any?) {
        val error = runCatching { block() }.exceptionOrNull()
        assertTrue(error is SpotifyWebApiException)
        assertEquals(WebApiFailureCategory.INVALID_RESPONSE, (error as SpotifyWebApiException).category)
    }

    @Test fun `creation requires 201 exact string ID and private Deal description`() = runBlocking {
        val transport = Transport(response(201, """{"id":"newDeal","name":"User choice"}"""))
        assertEquals("newDeal", api(transport).createDeal("User choice", "token").id)
        val request = transport.requests.single()
        assertEquals("POST", request.method)
        assertEquals("https://api.spotify.com/v1/me/playlists", request.url)
        val payload = JSONObject(request.body!!)
        assertFalse(payload.getBoolean("public"))
        assertEquals(SpotifyOpportunityPlaylistApi.DESCRIPTION, payload.getString("description"))
        assertEquals("Dealt by RoyalShuffle | Spotify Companion", payload.getString("description"))
        assertNotEquals(com.royalshuffle.android.output.CreateOutputPlaylist.OUTPUT_DESCRIPTION,
            SpotifyOpportunityPlaylistApi.DESCRIPTION)
        for ((status, body) in listOf(200 to """{"id":"good"}""", 201 to "{}", 201 to """{"id":12}""",
            201 to """{"id":"bad id"}""", 201 to """{"id":"good","name":12}""")) {
            val bad = Transport(response(status, body))
            invalid { api(bad).createDeal("Name", "token") }
            assertEquals(1, bad.requests.size)
        }
    }

    @Test fun `clear and append require exact status and nonblank string snapshot`() = runBlocking {
        val transport = Transport(response(200, """{"snapshot_id":"clear"}"""), response(201, """{"snapshot_id":"append"}"""))
        val api = api(transport)
        assertEquals(OpportunityWriteAcknowledgement(200, "clear"), api.clearDeal("output", "token"))
        assertEquals(OpportunityWriteAcknowledgement(201, "append"), api.appendDeal("output", listOf("spotify:track:a"), "token"))
        assertEquals(listOf("PUT", "POST"), transport.requests.map { it.method })
        assertEquals(0, JSONObject(transport.requests[0].body!!).getJSONArray("uris").length())
        for ((status, body) in listOf(201 to """{"snapshot_id":"s"}""", 200 to "{}", 200 to """{"snapshot_id":" "}""",
            200 to """{"snapshot_id":12}""")) {
            val bad = Transport(response(status, body))
            invalid { api(bad).clearDeal("output", "token") }
            assertEquals(1, bad.requests.size)
        }
        invalid { api(Transport(response(200, """{"snapshot_id":"s"}"""))).appendDeal("output", listOf("spotify:track:a"), "token") }
    }

    @Test fun `ambiguous write never retries while explicit 429 preserves accepted safe retry`() = runBlocking {
        for (outcome in listOf(IOException("timeout"), response(500, "{}"), response(201, "{}"))) {
            val transport = Transport(outcome)
            assertTrue(runCatching { api(transport).appendDeal("output", listOf("spotify:track:a"), "token") }.isFailure)
            assertEquals(1, transport.requests.size)
        }
        val transport = Transport(WebApiResponse(429, mapOf("Retry-After" to listOf("0")), "{}"),
            response(201, """{"snapshot_id":"s"}"""))
        api(transport).appendDeal("output", listOf("spotify:track:a"), "token")
        assertEquals(2, transport.requests.size)
        assertEquals(transport.requests[0], transport.requests[1])
    }

    @Test fun `append rejects excessive or empty batches before transport`() = runBlocking {
        val transport = Transport()
        for (uris in listOf(emptyList(), List(101) { "spotify:track:a" })) {
            assertTrue(runCatching { api(transport).appendDeal("output", uris, "token") }.isFailure)
        }
        assertTrue(transport.requests.isEmpty())
    }

    @Test fun `raw slots preserve order duplicates null local unsupported and unusable URI positions`() = runBlocking {
        val body = """{"total":7,"next":null,"items":[
            {"item":{"uri":"spotify:track:a","type":"track"}},
            {"item":{"uri":"spotify:track:a","type":"track"}},
            {"item":null},
            {"item":{"uri":"spotify:track:b","type":"episode"}},
            {"is_local":true,"item":{"uri":"spotify:track:c"}},
            {"item":{"uri":"spotify:track:d","is_local":true}},
            {"item":{"uri":"spotify:track:"}}]}"""
        val page = api(Transport(response(200, body))).rawDealPage("https://api.spotify.com/v1/playlists/output/items", "token")
        assertEquals(listOf("spotify:track:a", "spotify:track:a", null, null, null, null, null), page.slots)
        assertEquals(7, page.total)
    }

    @Test fun `malformed raw pages reject missing total fractional total bad slots next and offsets`() = runBlocking {
        for (body in listOf("""{"next":null,"items":[]}""", """{"total":"0","next":null,"items":[]}""",
            """{"total":0.5,"next":null,"items":[]}""", """{"total":-1,"next":null,"items":[]}""",
            """{"total":0,"items":[]}""", """{"total":0,"next":12,"items":[]}""",
            """{"total":1,"next":null,"items":[{}]}""", """{"total":1,"next":null,"items":[null]}""",
            """{"total":1,"next":null,"items":[{"item":12}]}""",
            """{"total":1,"next":null,"items":[{"item":null,"is_local":"false"}]}""",
            """{"total":0,"next":null,"items":[],"offset":"0"}""")) {
            invalid { api(Transport(response(200, body))).rawDealPage("https://api.spotify.com/v1/playlists/output/items", "token") }
        }
    }
}
