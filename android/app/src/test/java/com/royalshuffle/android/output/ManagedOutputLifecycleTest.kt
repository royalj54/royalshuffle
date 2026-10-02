package com.royalshuffle.android.output

import com.royalshuffle.android.auth.AccessTokenProvider
import com.royalshuffle.android.domain.model.Playlist
import com.royalshuffle.android.playlist.PlaylistPage
import com.royalshuffle.android.playlist.PlaylistPreferences
import com.royalshuffle.android.data.remote.SpotifyWebApiException
import com.royalshuffle.android.data.remote.WebApiFailureCategory
import java.math.BigInteger
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class ManagedOutputLifecycleTest {
    @Test fun `permission or missing clear response never triggers automatic replacement after resolution`() = runTest {
        for (status in listOf(403,404)) {
            val h = Harness()
            h.seed("bound")
            h.api.clearFailure = SpotifyWebApiException(
                if (status == 403) WebApiFailureCategory.PERMISSION else WebApiFailureCategory.OTHER,status,
            )
            val error = runCatching { h.generate() }.exceptionOrNull() as PartialPlaylistWriteException
            assertEquals(status,(error.cause as SpotifyWebApiException).httpStatus)
            assertEquals(OutputWriteStage.CLEAR,error.failedStage)
            assertEquals("bound",h.registry.boundOutput(full()))
            assertTrue(h.prompts.isEmpty())
            assertEquals(listOf("clear:bound"),h.api.writes)
        }
    }
    @Test fun `unfollowed but readable owned output prompts before any mutation and replaces only after consent`() = runTest {
        val requests = mutableListOf<com.royalshuffle.android.data.remote.WebApiRequest>()
        val remote = com.royalshuffle.android.data.remote.SpotifyOutputPlaylistApi(
            com.royalshuffle.android.data.remote.SpotifyWebApiClient(
                transport = com.royalshuffle.android.data.remote.WebApiTransport { request ->
                    requests += request
                    val body = when {
                        request.url.contains("/sourceA/items") -> """{"items":[{"item":{"uri":"spotify:track:one","type":"track","duration_ms":40000,"artists":[{"id":"artist"}]}}],"next":null}"""
                        request.url.contains("/me/playlists?") -> """{"items":[],"next":null}"""
                        request.url.endsWith("/playlists/old") -> """{"id":"old","name":"RND30M-Kpop","owner":{"id":"david"},"collaborative":false,"public":false}"""
                        request.url.contains("/library/contains?") -> "[false]"
                        request.method == "POST" && request.url.endsWith("/me/playlists") -> """{"id":"replacement","name":"RND30M-Kpop"}"""
                        request.method == "POST" && request.url.endsWith("/replacement/items") -> """{"snapshot_id":"written"}"""
                        else -> error("Unexpected request: ${request.method} ${request.url}")
                    }
                    com.royalshuffle.android.data.remote.WebApiResponse(200,emptyMap(),body)
                }, diagnostics = com.royalshuffle.android.data.remote.WebApiDiagnostics { },
            ),
        )
        val h = Harness(outputApi = remote)
        h.seed("old",timed(30))
        for (chosen in listOf(null," ","RND30M-Kpop")) {
            val result = runCatching { h.useCase.execute(SOURCE,timed(30),CreationNameProvider { default ->
                assertEquals("RND30M-Kpop",default)
                assertEquals("old",h.registry.boundOutput(OutputIdentity.from(SOURCE.id,timed(30))))
                assertTrue(requests.all { it.method == "GET" })
                chosen
            }) }
            if (chosen == null) assertNull(result.getOrThrow())
            else if (chosen.isBlank()) assertTrue(result.exceptionOrNull() is OutputPlanningException)
            else {
                assertEquals(OutputAction.CREATED,result.getOrThrow()!!.action)
                assertEquals("replacement",h.registry.boundOutput(OutputIdentity.from(SOURCE.id,timed(30))))
            }
        }
        assertEquals(listOf("POST","POST"),requests.filter { it.method != "GET" }.map { it.method })
        assertFalse(requests.any { it.method == "PUT" })
        assertTrue("old" in h.preferences.managed)
    }

    @Test fun `fresh exact listing hit authorizes reuse without second membership evidence`() = runTest {
        val h = Harness()
        h.seed("old")
        h.api.listed = listOf(Playlist("old","Existing"))
        h.api.saved = false
        assertEquals(OutputAction.UPDATED,h.generate()!!.action)
        assertTrue(h.prompts.isEmpty())
        assertEquals(0,h.api.membershipCount)
        assertEquals(listOf("clear:old","append:old"),h.api.writes)
    }

    @Test fun `membership failures including 404 do not authorize replacement or writes`() = runTest {
        for (failure in listOf(
            SpotifyWebApiException(WebApiFailureCategory.PERMISSION,403),
            SpotifyWebApiException(WebApiFailureCategory.OTHER,404),
            SpotifyWebApiException(WebApiFailureCategory.INVALID_RESPONSE,200),
            SpotifyWebApiException(WebApiFailureCategory.CONNECTIVITY),
            SpotifyWebApiException(WebApiFailureCategory.AUTHENTICATION,401),
            SpotifyWebApiException(WebApiFailureCategory.SERVER,500),
            SpotifyWebApiException(WebApiFailureCategory.QUOTA_EXCEEDED,429),
        )) {
            val h = Harness()
            h.seed("old")
            h.api.membershipFailure = failure
            assertSame(failure,runCatching { h.generate() }.exceptionOrNull())
            assertEquals("old",h.registry.boundOutput(full()))
            assertTrue(h.prompts.isEmpty())
            assertTrue(h.api.writes.isEmpty())
        }
    }

    @Test fun `listed newly bound Custom30 reuses same ID despite forbidden membership endpoint`() = runTest {
        val h = Harness()
        val created = h.generate(timed(30))!!.playlist
        h.api.listed = listOf(created.copy(name = "External Rename"))
        h.api.membershipFailure = SpotifyWebApiException(WebApiFailureCategory.PERMISSION,403)
        val reused = h.generate(timed(30))!!
        assertEquals(created.id,reused.playlist.id)
        assertEquals("External Rename",reused.playlist.name)
        assertEquals(OutputAction.UPDATED,reused.action)
        assertEquals(1,h.prompts.size)
        assertEquals(1,h.api.created.size)
        assertEquals(0,h.api.directCount)
        assertEquals(0,h.api.membershipCount)
        assertEquals(created.id,h.registry.boundOutput(OutputIdentity.from(SOURCE.id,timed(30))))
    }

    @Test fun `later listing page authorizes reuse without forbidden membership probe`() = runTest {
        val h = Harness()
        h.seed("bound")
        h.api.listPages[LIST_URL] = PlaylistPage(listOf(SOURCE),SECOND_LIST_URL)
        h.api.listPages[SECOND_LIST_URL] = PlaylistPage(listOf(Playlist("bound","Later")),null)
        h.api.membershipFailure = SpotifyWebApiException(WebApiFailureCategory.PERMISSION,403)
        assertEquals(OutputAction.UPDATED,h.generate()!!.action)
        assertEquals(listOf(LIST_URL,SECOND_LIST_URL),h.api.listUrls)
        assertEquals(0,h.api.membershipCount)
        assertEquals(0,h.api.directCount)
        assertTrue(h.prompts.isEmpty())
    }

    @Test fun `readable unfollowed replacement still guards expected old association before creation`() = runTest {
        val h = Harness()
        h.seed("old")
        h.api.saved = false
        val error = runCatching { h.useCase.execute(SOURCE,nameProvider = CreationNameProvider {
            h.registry.bind(full(),"concurrent","old")
            "Replacement"
        }) }.exceptionOrNull()
        assertTrue(error is OutputRegistryException)
        assertEquals("concurrent",h.registry.boundOutput(full()))
        assertTrue(h.api.writes.isEmpty())
    }
    @Test fun `creation defaults are concise and edited name is used with modern description`() = runTest {
        for ((options, default) in listOf(OutputOptions() to "RND-Kpop",timed(60) to "RND60M-Kpop",timed(30) to "RND30M-Kpop")) {
            val h = Harness()
            val result = h.generate(options,"  My Auto Playlist  ")!!
            assertEquals(listOf(default),h.prompts)
            assertEquals("My Auto Playlist",h.api.created.single().name)
            assertEquals(CreateOutputPlaylist.OUTPUT_DESCRIPTION,h.api.created.single().description)
            assertEquals("Randomized by RoyalShuffle | Spotify Companion",h.api.created.single().description)
            assertEquals(result.playlist.id,h.registry.boundOutput(OutputIdentity.from(SOURCE.id,options)))
            assertEquals(OutputAction.CREATED,result.action)
        }
    }

    @Test fun `cancel and blank name cause zero writes and zero binding mutations`() = runTest {
        for (name in listOf(null,""," \t")) {
            val h = Harness()
            val result = runCatching { h.generate(chosen = name) }
            if (name == null) assertNull(result.getOrThrow())
            else assertTrue(result.exceptionOrNull() is OutputPlanningException)
            assertTrue(h.api.writes.isEmpty())
            assertTrue(h.registry.bindings.isEmpty())
            assertTrue(h.preferences.managed.isEmpty())
        }
    }

    @Test fun `a creation path requires explicit naming callback`() = runTest {
        val h = Harness()
        assertTrue(runCatching { h.useCase.execute(SOURCE) }.exceptionOrNull() is OutputPlanningException)
        assertTrue(h.api.writes.isEmpty())
    }

    @Test fun `listing hit reuses ID and externally edited name without naming or creation`() = runTest {
        val h = Harness()
        h.seed("bound",name = "Renamed Outside")
        h.api.listed = listOf(Playlist("bound","Renamed Outside","custom description"))
        val result = h.generate()!!
        assertEquals("bound",result.playlist.id)
        assertEquals("Renamed Outside",result.playlist.name)
        assertEquals("custom description",result.playlist.description)
        assertEquals(OutputAction.UPDATED,result.action)
        assertTrue(h.prompts.isEmpty())
        assertTrue(h.api.created.isEmpty())
        assertEquals(0,h.api.directCount)
        assertEquals(listOf("clear:bound","append:bound"),h.api.writes)
    }

    @Test fun `listing miss direct exact ID success reuses rather than replaces`() = runTest {
        val h = Harness()
        h.seed("bound",name = "Direct Rename")
        val result = h.generate()!!
        assertEquals("Direct Rename",result.playlist.name)
        assertEquals(listOf("bound"),h.api.directIds)
        assertTrue(h.prompts.isEmpty())
        assertTrue(h.api.created.isEmpty())
    }

    @Test fun `listing pagination finds bound ID on later page without direct lookup`() = runTest {
        val h = Harness()
        h.seed("bound")
        h.api.listPages[LIST_URL] = PlaylistPage(listOf(SOURCE),SECOND_LIST_URL)
        h.api.listPages[SECOND_LIST_URL] = PlaylistPage(listOf(Playlist("bound","Later Page")),null)
        assertEquals("Later Page",h.generate()!!.playlist.name)
        assertEquals(listOf(LIST_URL,SECOND_LIST_URL),h.api.listUrls)
        assertEquals(0,h.api.directCount)
    }

    @Test fun `confirmed 404 creates guarded replacement after naming and keeps old exclusion`() = runTest {
        val h = Harness()
        h.seed("old")
        h.api.existing.clear()
        h.api.directFailure = SpotifyWebApiException(WebApiFailureCategory.OTHER,404)
        val result = h.useCase.execute(SOURCE,nameProvider = CreationNameProvider {
            assertEquals("old",h.registry.boundOutput(full()))
            assertTrue(h.api.writes.isEmpty())
            "Replacement"
        })!!
        assertEquals("Replacement",result.playlist.name)
        assertEquals(result.playlist.id,h.registry.boundOutput(full()))
        assertTrue("old" in h.preferences.managed)
        assertEquals(listOf("create","append:${result.playlist.id}"),h.api.writes)
    }

    @Test fun `cancel or blank after confirmed 404 leaves stale binding intact`() = runTest {
        for (chosen in listOf(null," ")) {
            val h = Harness()
            h.seed("old")
            h.api.existing.clear()
            val before = h.registry.bindings.toMap()
            val result = runCatching { h.generate(chosen = chosen) }
            if (chosen == null) assertNull(result.getOrThrow())
            else assertTrue(result.exceptionOrNull() is OutputPlanningException)
            assertEquals(before,h.registry.bindings)
            assertTrue(h.api.writes.isEmpty())
        }
    }

    @Test fun `non404 direct failures preserve binding for all failure categories`() = runTest {
        val failures = listOf(
            SpotifyWebApiException(WebApiFailureCategory.CONNECTIVITY),
            SpotifyWebApiException(WebApiFailureCategory.AUTHENTICATION,401),
            SpotifyWebApiException(WebApiFailureCategory.PERMISSION,403),
            SpotifyWebApiException(WebApiFailureCategory.RATE_LIMITED,429),
            SpotifyWebApiException(WebApiFailureCategory.QUOTA_EXCEEDED,429),
            SpotifyWebApiException(WebApiFailureCategory.SERVER,500),
            SpotifyWebApiException(WebApiFailureCategory.INVALID_RESPONSE,200),
            IllegalStateException("ambiguous lookup failure"),
        )
        for (failure in failures) {
            val h = Harness()
            h.seed("bound")
            h.api.directFailure = failure
            assertSame(failure,runCatching { h.generate() }.exceptionOrNull())
            assertEquals("bound",h.registry.boundOutput(full()))
            assertTrue(h.prompts.isEmpty())
            assertTrue(h.api.writes.isEmpty())
        }
    }

    @Test fun `listing failure is not deletion and does not fall through to direct or naming`() = runTest {
        val h = Harness()
        h.seed("bound")
        h.api.listFailure = SpotifyWebApiException(WebApiFailureCategory.CONNECTIVITY)
        assertTrue(runCatching { h.generate() }.isFailure)
        assertEquals("bound",h.registry.boundOutput(full()))
        assertEquals(0,h.api.directCount)
        assertTrue(h.prompts.isEmpty())
        assertTrue(h.api.writes.isEmpty())
    }

    @Test fun `invalid pagination and mismatched direct ID fail without replacement`() = runTest {
        val pagination = Harness()
        pagination.seed("bound")
        pagination.api.listPages[LIST_URL] = PlaylistPage(emptyList(),LIST_URL)
        assertTrue(runCatching { pagination.generate() }.exceptionOrNull() is OutputPlaylistException)
        assertTrue(pagination.api.writes.isEmpty())
        val wrongId = Harness()
        wrongId.seed("bound")
        wrongId.api.directOverride = Playlist("wrong","Wrong")
        assertTrue(runCatching { wrongId.generate() }.exceptionOrNull() is OutputRegistryException)
        assertEquals("bound",wrongId.registry.boundOutput(full()))
        assertTrue(wrongId.api.writes.isEmpty())
    }

    @Test fun `expected association change during naming prevents even replacement creation`() = runTest {
        val h = Harness()
        h.seed("old")
        h.api.existing.clear()
        val error = runCatching { h.useCase.execute(SOURCE,nameProvider = CreationNameProvider {
            h.registry.bind(full(),"concurrent","old")
            "Replacement"
        }) }.exceptionOrNull()
        assertTrue(error is OutputRegistryException)
        assertEquals("concurrent",h.registry.boundOutput(full()))
        assertTrue(h.api.writes.isEmpty())
    }

    @Test fun `association change while creating preserves newer binding and leaves new output unpopulated`() = runTest {
        val h = Harness()
        h.seed("old")
        h.api.existing.clear()
        h.api.onCreate = { h.registry.bind(full(),"concurrent","old") }
        val error = runCatching { h.generate() }.exceptionOrNull()
        assertTrue(error is ManagedPlaylistRegistrationException)
        assertEquals("concurrent",h.registry.boundOutput(full()))
        assertEquals(listOf("create"),h.api.writes)
    }

    @Test fun `creation failure preserves confirmed missing association`() = runTest {
        val h = Harness()
        h.seed("old")
        h.api.existing.clear()
        h.api.creationFailure = SpotifyWebApiException(WebApiFailureCategory.CONNECTIVITY)
        assertTrue(runCatching { h.generate() }.isFailure)
        assertEquals("old",h.registry.boundOutput(full()))
        assertTrue(h.api.batches.isEmpty())
        assertEquals(listOf("create"),h.api.writes)
    }

    @Test fun `source output collision never registers clears or appends`() = runTest {
        val h = Harness()
        h.api.createdId = SOURCE.id
        val error = runCatching { h.generate() }.exceptionOrNull()
        assertTrue(error is OutputPlaylistException)
        assertEquals(OutputPlaylistException.Reason.SOURCE_OUTPUT_ID_COLLISION,(error as OutputPlaylistException).reason)
        assertTrue(h.registry.bindings.isEmpty())
        assertEquals(listOf("create"),h.api.writes)
    }

    @Test fun `conflicting creation output ID preserves unrelated binding and never populates`() = runTest {
        val h = Harness()
        h.registry.bind(OutputIdentity("other","60"),"occupied",null)
        h.api.createdId = "occupied"
        val error = runCatching { h.generate() }.exceptionOrNull()
        assertTrue(error is ManagedPlaylistRegistrationException)
        assertNull(h.registry.boundOutput(full()))
        assertEquals("occupied",h.registry.boundOutput(OutputIdentity("other","60")))
        assertEquals(listOf("create"),h.api.writes)
    }

    @Test fun `binding persistence failure prevents population`() = runTest {
        val h = Harness()
        h.preferences.registrationSucceeds = false
        assertTrue(runCatching { h.generate() }.exceptionOrNull() is ManagedPlaylistRegistrationException)
        assertTrue(h.registry.bindings.isEmpty())
        assertEquals(listOf("create"),h.api.writes)
    }

    @Test fun `ordinary identity shares Custom60 preset and Artist Separation but isolates Full other minutes sources`() = runTest {
        val h = Harness()
        val sixty = h.generate(timed(60))!!.playlist.id
        h.api.listed = h.api.existing.values.toList()
        assertEquals(sixty,h.generate(OutputSettings(SessionLengthMode.CUSTOM,"060",true).snapshot())!!.playlist.id)
        val full = h.generate()!!.playlist.id
        val thirty = h.generate(timed(30))!!.playlist.id
        val other = h.useCase.execute(Playlist("other","Kpop"),timed(60),CreationNameProvider { "Other" })!!.playlist.id
        assertEquals(4,setOf(sixty,full,thirty,other).size)
        assertEquals(3,h.prompts.size)
        assertEquals(4,h.api.created.size)
    }

    @Test fun `reused playlist clears then appends planned order in batches preserving duplicates`() = runTest {
        val h = Harness(reverse = true)
        h.seed("bound")
        val items = (0 until 205).map { track(if (it % 10 == 0) "duplicate" else "track$it") }
        h.api.items = items
        val result = h.generate()!!
        assertEquals(OutputAction.UPDATED,result.action)
        assertEquals(listOf(100,100,5),h.api.batches.map { it.size })
        assertEquals(items.reversed().map { it.uri },h.api.batches.flatten())
        assertEquals(listOf("clear:bound","append:bound","append:bound","append:bound"),h.api.writes)
        assertTrue(h.api.created.isEmpty())
    }

    @Test fun `clear failure reports unknown prior contents and zero new items without append`() = runTest {
        val h = Harness()
        h.seed("bound")
        val cause = SpotifyWebApiException(WebApiFailureCategory.CONNECTIVITY)
        h.api.clearFailure = cause
        val error = runCatching { h.generate() }.exceptionOrNull() as PartialPlaylistWriteException
        assertEquals(OutputAction.UPDATED,error.action)
        assertEquals(OutputWriteStage.CLEAR,error.failedStage)
        assertEquals(0,error.confirmedItemsWritten)
        assertSame(cause,error.cause)
        assertTrue(error.toUserMessage().contains("previous contents may or may not have changed"))
        assertEquals("bound",h.registry.boundOutput(full()))
        assertEquals(listOf("clear:bound"),h.api.writes)
    }

    @Test fun `quota failure during clear is explicit and does not replay or change binding`() = runTest {
        val h = Harness()
        h.seed("bound")
        h.api.clearFailure = SpotifyWebApiException(WebApiFailureCategory.QUOTA_EXCEEDED,429)
        val error = runCatching { h.generate() }.exceptionOrNull() as PartialPlaylistWriteException
        assertTrue(error.toUserMessage().contains("Spotify developer quota was exceeded"))
        assertEquals("bound",h.registry.boundOutput(full()))
        assertEquals(listOf("clear:bound"),h.api.writes)
    }

    @Test fun `partial reused append reports only acknowledged batches after clearing previous contents`() = runTest {
        val h = Harness()
        h.seed("bound")
        h.api.items = (0 until 205).map { track("$it") }
        h.api.failAppend = 2
        val error = runCatching { h.generate() }.exceptionOrNull() as PartialPlaylistWriteException
        assertEquals(OutputAction.UPDATED,error.action)
        assertEquals(OutputWriteStage.APPEND,error.failedStage)
        assertEquals(100,error.confirmedItemsWritten)
        assertEquals(205,error.totalItemsIntended)
        assertTrue(error.toUserMessage().contains("after clearing the previous contents"))
        assertEquals("bound",h.registry.boundOutput(full()))
        assertEquals(listOf("clear:bound","append:bound","append:bound"),h.api.writes)
        assertEquals(100,h.api.batches.flatten().size)
    }

    @Test fun `new partial output remains bound and exclusion registered for safe retry`() = runTest {
        val h = Harness()
        h.api.failAppend = 1
        val error = runCatching { h.generate() }.exceptionOrNull() as PartialPlaylistWriteException
        assertEquals(OutputAction.CREATED,error.action)
        assertEquals(0,error.confirmedItemsWritten)
        assertEquals(error.outputPlaylistId,h.registry.boundOutput(full()))
        assertTrue(error.outputPlaylistId in h.preferences.managed)
        h.api.failAppend = null
        assertEquals(OutputAction.UPDATED,h.generate()!!.action)
        assertEquals(1,h.api.created.size)
        assertEquals(1,h.prompts.size)
    }

    @Test fun `planning validation before reuse resolution prevents destructive output writes`() = runTest {
        for ((items, options) in listOf(emptyList<OutputPlaylistItem>() to OutputOptions(),
            listOf(track("one").copy(durationMs = null)) to timed(60),
            listOf(track("one").copy(primaryArtistId = null)) to OutputOptions(artistSeparation = true))) {
            val h = Harness()
            h.seed("bound")
            h.api.items = items
            assertTrue(runCatching { h.generate(options) }.exceptionOrNull() is OutputPlanningException)
            assertTrue(h.api.writes.isEmpty())
            assertTrue(h.api.listUrls.isEmpty())
            assertEquals("bound",h.registry.boundOutput(full()))
        }
    }

    @Test fun `legacy exclusion matching modern default is not reused or adopted`() = runTest {
        val h = Harness()
        h.preferences.managed += "legacy"
        h.api.listed = listOf(Playlist("legacy","RND-Kpop",CreateOutputPlaylist.LEGACY_OUTPUT_DESCRIPTION))
        val result = h.generate()!!
        assertNotEquals("legacy",result.playlist.id)
        assertEquals(1,h.prompts.size)
        assertEquals(1,h.api.created.size)
        assertTrue("legacy" in h.preferences.managed)
    }

    private class Harness(reverse: Boolean = false, outputApi: OutputPlaylistApi? = null) {
        val api = FakeApi()
        val preferences = Preferences()
        val registry = TestOutputRegistry(preferences)
        val prompts = mutableListOf<String>()
        val useCase = CreateOutputPlaylist(AccessTokenProvider { "token" },outputApi ?: api,preferences,
            OccurrenceShuffler { if (reverse) it.reversed() else it },registry = registry)
        suspend fun seed(id: String, options: OutputOptions = OutputOptions(), name: String = "Existing") {
            registry.bind(OutputIdentity.from(SOURCE.id,options),id,null)
            api.existing[id] = Playlist(id,name)
        }
        suspend fun generate(options: OutputOptions = OutputOptions(),chosen: String? = "Edited") =
            useCase.execute(SOURCE,options,CreationNameProvider { prompts += it; chosen })
    }

    private class Preferences : PlaylistPreferences {
        val managed = mutableSetOf<String>()
        var registrationSucceeds = true
        override fun loadManagedPlaylistIds() = managed.toSet()
        override suspend fun addManagedPlaylistId(playlistId: String): Boolean {
            if (registrationSucceeds) managed += playlistId
            return registrationSucceeds
        }
        override suspend fun addManagedPlaylistIds(playlistIds: Set<String>): Boolean { managed += playlistIds; return true }
        override fun loadDeclinedRecoveryPlaylistIds() = emptySet<String>()
        override suspend fun addDeclinedRecoveryPlaylistIds(playlistIds: Set<String>) = true
        override fun loadSelectedPlaylistId(): String? = null
        override fun saveSelectedPlaylistId(playlistId: String) = Unit
        override fun clearSelectedPlaylistId() = Unit
    }

    private class FakeApi : OutputPlaylistApi {
        var saved = true
        var membershipCount = 0
        var membershipFailure: Exception? = null
        override suspend fun isPlaylistSaved(playlistId: String, accessToken: String): Boolean {
            membershipCount++
            membershipFailure?.let { throw it }
            return saved
        }
        var items = listOf(track("one"),track("two"))
        var listed = emptyList<Playlist>()
        val existing = mutableMapOf<String,Playlist>()
        val listPages = mutableMapOf<String,PlaylistPage>()
        val listUrls = mutableListOf<String>()
        val directIds = mutableListOf<String>()
        val directCount get() = directIds.size
        val created = mutableListOf<Playlist>()
        val writes = mutableListOf<String>()
        val batches = mutableListOf<List<String>>()
        var listFailure: Exception? = null
        var directFailure: Exception? = null
        var directOverride: Playlist? = null
        var creationFailure: Exception? = null
        var clearFailure: Exception? = null
        var failAppend: Int? = null
        var appendCount = 0
        var createdId: String? = null
        var onCreate: (suspend () -> Unit)? = null
        override suspend fun getPlaylistItemsPage(url: String, accessToken: String) = PlaylistItemsPage(items,null)
        override suspend fun getPlaylistsPage(url: String, accessToken: String): PlaylistPage {
            listUrls += url
            listFailure?.let { throw it }
            return listPages[url] ?: PlaylistPage(listed,null)
        }
        override suspend fun getPlaylist(playlistId: String, accessToken: String): Playlist {
            directIds += playlistId
            directFailure?.let { throw it }
            return directOverride ?: existing[playlistId] ?: throw SpotifyWebApiException(WebApiFailureCategory.OTHER,404)
        }
        override suspend fun createPrivatePlaylist(name: String, description: String, accessToken: String): Playlist {
            writes += "create"
            creationFailure?.let { throw it }
            onCreate?.invoke()
            return Playlist(createdId ?: "new${created.size}",name,description).also {
                created += it; existing[it.id] = it
            }
        }
        override suspend fun clearItems(playlistId: String, accessToken: String) {
            writes += "clear:$playlistId"
            clearFailure?.let { throw it }
        }
        override suspend fun addItems(playlistId: String, uris: List<String>, accessToken: String) {
            writes += "append:$playlistId"
            appendCount++
            if (appendCount == failAppend) throw SpotifyWebApiException(WebApiFailureCategory.CONNECTIVITY)
            batches += uris
        }
    }

    companion object {
        private val SOURCE = Playlist("sourceA","Kpop")
        private const val LIST_URL = "https://api.spotify.com/v1/me/playlists?limit=50"
        private const val SECOND_LIST_URL = "https://api.spotify.com/v1/me/playlists?limit=50&offset=50"
        private fun full() = OutputIdentity(SOURCE.id,OutputIdentity.FULL)
        private fun timed(minutes: Int) = OutputOptions(BigInteger.valueOf(minutes.toLong()))
        private fun track(id: String) = OutputPlaylistItem("spotify:track:$id",durationMs = 40_000,primaryArtistId = "artist")
    }
}
