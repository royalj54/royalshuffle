package com.royalshuffle.android.output

import com.royalshuffle.android.auth.AccessTokenProvider
import com.royalshuffle.android.data.remote.SpotifyWebApiException
import com.royalshuffle.android.data.remote.WebApiFailureCategory
import com.royalshuffle.android.domain.model.Playlist
import com.royalshuffle.android.diagnostics.DiagnosticEvent
import com.royalshuffle.android.diagnostics.DiagnosticLogger
import com.royalshuffle.android.playlist.PlaylistApi
import com.royalshuffle.android.playlist.PlaylistPage
import com.royalshuffle.android.playlist.PlaylistPreferences
import com.royalshuffle.android.playlist.PlaylistRepository
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.CancellationException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class CreateOutputPlaylistTest {
    private val events = mutableListOf<String>()
    private val shuffledInputs = mutableListOf<List<String>>()
    private val api = FakeOutputApi(events)
    private val preferences = FakePreferences(events)
    private val diagnostics = mutableListOf<DiagnosticEvent>()
    private val useCase = CreateOutputPlaylist(
        accessTokenProvider = AccessTokenProvider { "access-token" },
        api = api,
        preferences = preferences,
        shuffler = UriShuffler {
            shuffledInputs += it
            it.reversed()
        },
        diagnostics = DiagnosticLogger { diagnostics += it },
    )

    @Test
    fun `follows item pagination and skips null missing and blank URIs`() = runBlocking {
        api.pages[INITIAL_URL] = PlaylistItemsPage(
            listOf(item("spotify:track:one"), item(null), item("")),
            SECOND_URL,
        )
        api.pages[SECOND_URL] = PlaylistItemsPage(
            listOf(item(null), item("spotify:track:two")),
            null,
        )

        useCase.execute(SOURCE)

        assertEquals(listOf(INITIAL_URL, SECOND_URL), api.requestedUrls)
        assertEquals(listOf("spotify:track:two", "spotify:track:one"), api.addedUris.flatten())
    }

    @Test
    fun `adds shuffled items in ordered batches of at most 100`() = runBlocking {
        val uris = (1..205).map { "spotify:track:$it" }
        api.singlePage(uris)

        useCase.execute(SOURCE)

        assertEquals(listOf(100, 100, 5), api.addedUris.map(List<String>::size))
        assertEquals(uris.reversed(), api.addedUris.flatten())
    }

    @Test
    fun `registers output ID after creation and before first population call`() = runBlocking {
        api.singlePage(listOf("spotify:track:one"))

        useCase.execute(SOURCE)

        assertEquals(listOf("output-id"), preferences.managedIds)
        assertEquals(listOf("create", "register", "add"), events)
    }

    @Test
    fun `durable registration failure leaves created playlist untouched and prevents population`() =
        runBlocking {
            api.singlePage(listOf("spotify:track:one"))
            preferences.registrationSucceeds = false

            val error = runCatching { useCase.execute(SOURCE) }.exceptionOrNull()

            assertTrue(error is ManagedPlaylistRegistrationException)
            error as ManagedPlaylistRegistrationException
            assertEquals("output-id", error.outputPlaylistId)
            assertEquals("Source - RANDOM", error.outputPlaylistName)
            assertEquals(1, api.createCount)
            assertEquals(1, preferences.registrationCount)
            assertTrue(api.addedUris.isEmpty())
            assertEquals(listOf("create", "register"), events)
        }

    @Test
    fun `protects source when Spotify returns the source ID as output`() = runBlocking {
        api.singlePage(listOf("spotify:track:one"))
        api.createdPlaylist = SOURCE.copy(name = "Unexpected")

        val error = runCatching { useCase.execute(SOURCE) }.exceptionOrNull()

        assertTrue(error is OutputPlaylistException)
        assertEquals(
            OutputPlaylistException.Reason.SOURCE_OUTPUT_ID_COLLISION,
            (error as OutputPlaylistException).reason,
        )
        assertTrue(api.addedUris.isEmpty())
        assertTrue(preferences.managedIds.isEmpty())
        assertEquals(0, preferences.registrationCount)
    }

    @Test
    fun `does not register when playlist creation fails`() = runBlocking {
        api.singlePage(emptyList())
        api.failCreation = true

        assertTrue(runCatching { useCase.execute(SOURCE) }.isFailure)
        assertTrue(preferences.managedIds.isEmpty())
    }

    @Test
    fun `first population batch failure reports zero confirmed`() = runBlocking {
        api.singlePage((1..205).map { "spotify:track:$it" })
        api.failAddCall = 1

        val error = runCatching { useCase.execute(SOURCE) }.exceptionOrNull()

        assertPartialFailure(error, confirmed = 0, total = 205)
        assertEquals(listOf("output-id"), preferences.managedIds)
        assertEquals(listOf("create", "register", "add"), events)
    }

    @Test
    fun `second population batch failure reports prior successful batch`() = runBlocking {
        api.singlePage((1..205).map { "spotify:track:$it" })
        api.failAddCall = 2

        val error = runCatching { useCase.execute(SOURCE) }.exceptionOrNull()

        assertPartialFailure(error, confirmed = 100, total = 205)
        assertEquals(listOf(100), api.addedUris.map(List<String>::size))
    }

    @Test
    fun `final population batch failure reports all prior successful batches`() = runBlocking {
        api.singlePage((1..205).map { "spotify:track:$it" })
        api.failAddCall = 3

        val error = runCatching { useCase.execute(SOURCE) }.exceptionOrNull()

        assertPartialFailure(error, confirmed = 200, total = 205)
        assertEquals(listOf(100, 100), api.addedUris.map(List<String>::size))
    }

    @Test
    fun `partial output remains excluded from future source selection`() = runBlocking {
        api.singlePage(listOf("spotify:track:one"))
        api.failAddCall = 1
        assertTrue(runCatching { useCase.execute(SOURCE) }.isFailure)
        val playlistApi = object : PlaylistApi {
            override suspend fun getPlaylistsPage(
                url: String,
                accessToken: String,
            ): PlaylistPage = PlaylistPage(
                playlists = listOf(SOURCE, api.createdPlaylist),
                nextUrl = null,
            )
        }
        val repository = PlaylistRepository(
            accessTokenProvider = AccessTokenProvider { "access-token" },
            playlistApi = playlistApi,
            preferences = preferences,
        )

        val result = repository.loadEligiblePlaylists()

        assertEquals(listOf(SOURCE), result.playlists)
    }

    @Test
    fun `durable registration is visible through a fresh preferences instance`() = runBlocking {
        api.singlePage(listOf("spotify:track:one"))
        useCase.execute(SOURCE)
        val freshPreferences = FakePreferences(mutableListOf(), preferences.store)
        val playlistApi = object : PlaylistApi {
            override suspend fun getPlaylistsPage(url: String, accessToken: String) =
                PlaylistPage(listOf(SOURCE, api.createdPlaylist), null)
        }
        val repository = PlaylistRepository(
            accessTokenProvider = AccessTokenProvider { "access-token" },
            playlistApi = playlistApi,
            preferences = freshPreferences,
        )

        val result = repository.loadEligiblePlaylists()

        assertEquals(listOf(SOURCE), result.playlists)
        assertEquals(listOf("output-id"), freshPreferences.managedIds)
    }

    @Test
    fun `partial failure retains Web API failure category and cause`() = runBlocking {
        api.singlePage(listOf("spotify:track:one"))
        val cause = SpotifyWebApiException(WebApiFailureCategory.SERVER, httpStatus = 503)
        api.failAddCall = 1
        api.addFailure = cause

        val error = runCatching { useCase.execute(SOURCE) }.exceptionOrNull()

        assertTrue(error is PartialPlaylistWriteException)
        error as PartialPlaylistWriteException
        assertEquals(WebApiFailureCategory.SERVER, error.underlyingFailureCategory)
        assertEquals(null, error.underlyingReason)
        assertSame(cause, error.cause)
        val diagnostic = diagnostics.single { it.eventName == "playlist_population_failed" }
        assertEquals(0, diagnostic.confirmedItems)
        assertEquals(1, diagnostic.intendedItems)
        assertEquals(1, diagnostic.batchNumber)
    }

    @Test
    fun `later population authentication failure preserves confirmed partial count`() =
        runBlocking {
            api.singlePage((1..150).map { "spotify:track:$it" })
            val cause = SpotifyWebApiException(
                WebApiFailureCategory.AUTHENTICATION,
                httpStatus = 401,
            )
            api.failAddCall = 2
            api.addFailure = cause

            val error = runCatching { useCase.execute(SOURCE) }.exceptionOrNull()

            assertTrue(error is PartialPlaylistWriteException)
            error as PartialPlaylistWriteException
            assertEquals(100, error.confirmedItemsWritten)
            assertEquals(150, error.totalItemsIntended)
            assertEquals(WebApiFailureCategory.AUTHENTICATION, error.underlyingFailureCategory)
            assertSame(cause, error.cause)
        assertEquals(2, api.addCallCount)
        val diagnostic = diagnostics.single { it.eventName == "playlist_population_failed" }
        assertEquals(100, diagnostic.confirmedItems)
        assertEquals(150, diagnostic.intendedItems)
        assertEquals("AUTHENTICATION", diagnostic.failureCategory)
        }

    @Test
    fun `population cancellation propagates without becoming partial failure`() = runBlocking {
        api.singlePage(listOf("spotify:track:one"))
        val cancellation = CancellationException("cancelled")
        api.failAddCall = 1
        api.addFailure = cancellation

        val error = runCatching { useCase.execute(SOURCE) }.exceptionOrNull()

        assertSame(cancellation, error)
    }

    @Test
    fun `preserves duplicate items`() = runBlocking {
        api.singlePage(listOf("spotify:track:same", "spotify:track:other", "spotify:track:same"))

        useCase.execute(SOURCE)

        val added = api.addedUris.flatten()
        assertEquals(3, added.size)
        assertEquals(2, added.count { it == "spotify:track:same" })
        assertFalse(added.distinct().size == added.size)
    }

    @Test
    fun `excludes every local entry before shuffle and population`() = runBlocking {
        api.pages[INITIAL_URL] = PlaylistItemsPage(
            listOf(
                item("spotify:track:one"),
                item("spotify:local:artist:album:one:120", isLocal = true),
                item("spotify:track:two", isLocal = true),
                item("spotify:track:one"),
            ),
            null,
        )

        val result = useCase.execute(SOURCE)

        val expected = listOf("spotify:track:one", "spotify:track:one")
        assertEquals(listOf(expected), shuffledInputs)
        assertEquals(expected.reversed(), api.addedUris.flatten())
        assertEquals(2, result.itemCount)
        assertEquals(2, result.skippedLocalItemCount)
        val diagnostic = diagnostics.single { it.eventName == "playlist_items_filtered" }
        assertEquals(2, diagnostic.skippedItems)
        assertEquals(2, diagnostic.intendedItems)
        val diagnosticText = diagnostics.joinToString()
        assertFalse(diagnosticText.contains("Source"))
        assertFalse(diagnosticText.contains("spotify:track"))
        assertFalse(diagnosticText.contains("spotify:local"))
    }

    @Test
    fun `zero local entries keep existing result and duplicate behavior`() = runBlocking {
        val uris = listOf("spotify:track:same", "spotify:track:other", "spotify:track:same")
        api.singlePage(uris)

        val result = useCase.execute(SOURCE)

        assertEquals(uris.reversed(), api.addedUris.flatten())
        assertEquals(3, result.itemCount)
        assertEquals(0, result.skippedLocalItemCount)
    }

    private fun assertPartialFailure(error: Throwable?, confirmed: Int, total: Int) {
        assertTrue(error is PartialPlaylistWriteException)
        error as PartialPlaylistWriteException
        assertEquals("output-id", error.outputPlaylistId)
        assertEquals("Source - RANDOM", error.outputPlaylistName)
        assertEquals(confirmed, error.confirmedItemsWritten)
        assertEquals(total, error.totalItemsIntended)
        assertEquals(OutputPlaylistException.Reason.NETWORK, error.underlyingReason)
        assertEquals(null, error.underlyingFailureCategory)
        assertTrue(error.cause is OutputPlaylistException)
    }

    private class FakeOutputApi(private val events: MutableList<String>) : OutputPlaylistApi {
        val pages = mutableMapOf<String, PlaylistItemsPage>()
        val requestedUrls = mutableListOf<String>()
        val addedUris = mutableListOf<List<String>>()
        var createdPlaylist = Playlist("output-id", "Source - RANDOM")
        var failCreation = false
        var createCount = 0
        var failAddCall: Int? = null
        var addCallCount = 0
        var addFailure: Exception =
            OutputPlaylistException(OutputPlaylistException.Reason.NETWORK)

        override suspend fun getPlaylistItemsPage(url: String, accessToken: String): PlaylistItemsPage {
            requestedUrls += url
            return pages.getValue(url)
        }

        override suspend fun createPrivatePlaylist(
            name: String,
            description: String,
            accessToken: String,
        ): Playlist {
            events += "create"
            createCount += 1
            if (failCreation) error("creation failed")
            assertEquals("Source - RANDOM", name)
            assertEquals(CreateOutputPlaylist.OUTPUT_DESCRIPTION, description)
            return createdPlaylist
        }

        override suspend fun addItems(playlistId: String, uris: List<String>, accessToken: String) {
            events += "add"
            addCallCount += 1
            if (addCallCount == failAddCall) {
                throw addFailure
            }
            addedUris += uris
        }

        fun singlePage(uris: List<String>) {
            pages[INITIAL_URL] = PlaylistItemsPage(uris.map(::item), null)
        }
    }

    private class FakePreferences(
        private val events: MutableList<String>,
        val store: ManagedStore = ManagedStore(),
    ) : PlaylistPreferences {
        val managedIds: MutableList<String> get() = store.managedIds
        var registrationSucceeds = true
        var registrationCount = 0
        private var selectedId: String? = null
        override fun loadManagedPlaylistIds(): Set<String> = managedIds.toSet()
        override suspend fun addManagedPlaylistId(playlistId: String): Boolean {
            events += "register"
            registrationCount += 1
            if (registrationSucceeds) managedIds += playlistId
            return registrationSucceeds
        }
        override suspend fun addManagedPlaylistIds(playlistIds: Set<String>): Boolean {
            if (registrationSucceeds) managedIds += playlistIds
            return registrationSucceeds
        }
        override fun loadDeclinedRecoveryPlaylistIds(): Set<String> = emptySet()
        override suspend fun addDeclinedRecoveryPlaylistIds(playlistIds: Set<String>) = true
        override fun loadSelectedPlaylistId(): String? = selectedId
        override fun saveSelectedPlaylistId(playlistId: String) { selectedId = playlistId }
        override fun clearSelectedPlaylistId() { selectedId = null }
    }

    private class ManagedStore(
        val managedIds: MutableList<String> = mutableListOf(),
    )

    private companion object {
        fun item(uri: String?, isLocal: Boolean = false) =
            OutputPlaylistItem(uri = uri, isLocal = isLocal)

        val SOURCE = Playlist("source-id", "Source")
        const val INITIAL_URL =
            "https://api.spotify.com/v1/playlists/source-id/items?limit=50"
        const val SECOND_URL =
            "https://api.spotify.com/v1/playlists/source-id/items?limit=50&offset=50"
    }
}
