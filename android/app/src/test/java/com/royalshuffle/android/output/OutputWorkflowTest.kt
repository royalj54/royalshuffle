package com.royalshuffle.android.output

import com.royalshuffle.android.auth.AccessTokenProvider
import com.royalshuffle.android.domain.model.Playlist
import com.royalshuffle.android.playlist.PlaylistPreferences
import java.math.BigInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.test.*
import org.junit.After
import org.junit.Before
import org.junit.Assert.*
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class OutputWorkflowTest {
    private val dispatcher = StandardTestDispatcher()
    @Before fun setUp() { Dispatchers.setMain(dispatcher) }
    @After fun tearDown() { Dispatchers.resetMain() }

    @Test fun `rapid double submission locks before coroutine and suspended token acquisition`() = runTest(dispatcher) {
        val token = CompletableDeferred<String>()
        var requests = 0
        val api = TestOutputApi()
        val viewModel = autoNamedViewModel(useCase(api, AccessTokenProvider { requests++; token.await() }))
        viewModel.create(SOURCE)
        viewModel.create(SOURCE)
        assertTrue(viewModel.isRunning.value)
        assertTrue(viewModel.uiState.value is OutputUiState.Working)
        runCurrent()
        assertEquals(1, requests)
        viewModel.create(SOURCE)
        token.complete("token")
        advanceUntilIdle()
        assertEquals(1, api.createCount)
        assertFalse(viewModel.isRunning.value)
    }

    @Test fun `operation snapshots settings before suspended token while next submission uses new settings`() = runTest(dispatcher) {
        val token = CompletableDeferred<String>()
        val api = TestOutputApi().apply {
            items = listOf(track("one", 40_000, "A"), track("two", 40_000, "A"),
                track("three", 40_000, "B"), track("four", 40_000, "C"))
        }
        val viewModel = autoNamedViewModel(useCase(api, AccessTokenProvider { token.await() }))
        viewModel.setSessionLength(SessionLengthMode.CUSTOM)
        viewModel.setCustomMinutes("2")
        viewModel.setArtistSeparation(true)
        viewModel.create(SOURCE)
        runCurrent()
        viewModel.setSessionLength(SessionLengthMode.FULL)
        viewModel.setCustomMinutes("invalid")
        viewModel.setArtistSeparation(false)
        token.complete("token")
        advanceUntilIdle()
        assertEquals(listOf("spotify:track:one", "spotify:track:three", "spotify:track:two"), api.batches.flatten())
        assertEquals("RND2M-Source", api.names.single())
        val first = viewModel.uiState.value as OutputUiState.Success
        assertEquals(BigInteger.valueOf(120_000), first.requestedDurationMs)
        api.batches.clear()
        viewModel.create(SOURCE)
        advanceUntilIdle()
        assertEquals(4, api.batches.flatten().size)
        assertEquals("RND-Source", api.names.last())
    }

    @Test fun `clear while token is suspended cancels work and permits a fresh operation`() = runTest(dispatcher) {
        val token = CompletableDeferred<String>()
        val api = TestOutputApi()
        val viewModel = autoNamedViewModel(useCase(api, AccessTokenProvider { token.await() }))
        viewModel.create(SOURCE)
        runCurrent()
        viewModel.clear()
        assertEquals(OutputUiState.Idle, viewModel.uiState.value)
        token.complete("token")
        advanceUntilIdle()
        assertEquals(0, api.createCount)
        assertFalse(viewModel.isRunning.value)
        viewModel.create(SOURCE)
        advanceUntilIdle()
        assertEquals(1, api.createCount)
        assertTrue(viewModel.uiState.value is OutputUiState.Success)
    }

    @Test fun `clear before coroutine starts releases gate without token or writes`() = runTest(dispatcher) {
        var tokens = 0
        val api = TestOutputApi()
        val viewModel = autoNamedViewModel(useCase(api, AccessTokenProvider { tokens++; "token" }))
        viewModel.create(SOURCE)
        viewModel.clear()
        advanceUntilIdle()
        assertEquals(0, tokens)
        assertEquals(0, api.createCount)
        assertFalse(viewModel.isRunning.value)
    }

    @Test fun `disconnect suppresses noncooperative old loading and prevents overlapping newer operations`() = runTest(dispatcher) {
        val release = CompletableDeferred<Unit>()
        val api = TestOutputApi().apply { pageGate = release }
        val viewModel = autoNamedViewModel(useCase(api))
        viewModel.create(SOURCE)
        runCurrent()
        viewModel.clearForSessionInvalidation()
        viewModel.create(Playlist("newsource", "New"))
        runCurrent()
        assertEquals(1, api.pageCount)
        assertTrue(viewModel.isRunning.value)
        release.complete(Unit)
        advanceUntilIdle()
        assertEquals(OutputUiState.Idle, viewModel.uiState.value)
        assertEquals(0, api.createCount)
        viewModel.create(Playlist("newsource", "New"))
        advanceUntilIdle()
        assertEquals("RND-New", (viewModel.uiState.value as OutputUiState.Success).playlistName)
    }

    @Test fun `obsolete completion and error after population cannot publish success or failure`() = runTest(dispatcher) {
        for (fail in listOf(false, true)) {
            val release = CompletableDeferred<Unit>()
            val api = TestOutputApi().apply { writeGate = release; failWrite = fail }
            val viewModel = autoNamedViewModel(useCase(api))
            viewModel.create(SOURCE)
            runCurrent()
            assertEquals(1, api.createCount)
            viewModel.clear()
            release.complete(Unit)
            advanceUntilIdle()
            assertEquals(OutputUiState.Idle, viewModel.uiState.value)
            assertFalse(viewModel.isRunning.value)
            api.failWrite = false
            viewModel.create(Playlist("newsource", "New"))
            advanceUntilIdle()
            assertEquals("RND-New", (viewModel.uiState.value as OutputUiState.Success).playlistName)
        }
    }

    @Test fun `invalid Custom blocks token acquisition and output mutation with validation state`() = runTest(dispatcher) {
        var tokens = 0
        val api = TestOutputApi()
        val viewModel = autoNamedViewModel(useCase(api, AccessTokenProvider { tokens++; "token" }))
        viewModel.setSessionLength(SessionLengthMode.CUSTOM)
        for (value in listOf("", "0", "-1", "1.5")) {
            viewModel.setCustomMinutes(value)
            viewModel.create(SOURCE)
            assertNotNull(viewModel.settings.value.validationMessage)
            assertEquals(OutputUiState.Error("Enter a positive whole number of minutes."), viewModel.uiState.value)
        }
        advanceUntilIdle()
        assertEquals(0, tokens)
        assertEquals(0, api.createCount)
    }

    @Test fun `Custom draft survives switching modes and settings survive ViewModel recreation`() = runTest(dispatcher) {
        val storage = MemoryOutputSettingsStorage()
        val first = autoNamedViewModel(useCase(TestOutputApi()), storage)
        assertEquals(OutputSettings(), first.settings.value)
        first.setSessionLength(SessionLengthMode.CUSTOM)
        first.setCustomMinutes("240")
        first.setArtistSeparation(true)
        first.setSessionLength(SessionLengthMode.FULL)
        first.setSessionLength(SessionLengthMode.SIXTY_MINUTES)
        first.setSessionLength(SessionLengthMode.CUSTOM)
        assertEquals("240", first.settings.value.customMinutes)
        assertEquals(first.settings.value, autoNamedViewModel(useCase(TestOutputApi()), storage).settings.value)
    }

    @Test fun `all planning validation fails before creation registration and writes`() = runTest(dispatcher) {
        val cases = listOf(
            emptyList<OutputPlaylistItem>() to OutputOptions(),
            listOf(track("one").copy(isLocal = true)) to OutputOptions(),
            listOf(track("one").copy(itemType = "episode")) to OutputOptions(),
            listOf(track("one").copy(uri = null)) to OutputOptions(),
            listOf(track("one").copy(primaryArtistId = null)) to OutputOptions(artistSeparation = true),
            listOf(track("one"), track("two").copy(durationMs = null)) to OutputOptions(BigInteger.ONE),
        )
        for ((items, options) in cases) {
            val api = TestOutputApi().apply { this.items = items }
            val preferences = TestPlaylistPreferences()
            val error = runCatching { useCase(api, preferences = preferences).execute(SOURCE, options) }.exceptionOrNull()
            assertTrue(error is OutputPlanningException)
            assertEquals(0, api.createCount)
            assertTrue(api.batches.isEmpty())
            assertTrue(preferences.managedIds.isEmpty())
        }
    }

    @Test fun `independent separation guard prevents mutation for faulty implementation`() = runTest(dispatcher) {
        val api = TestOutputApi().apply {
            items = listOf(track("one", artist = "A"),track("two", artist = "A"),track("three", artist = "B"))
        }
        val prefs = TestPlaylistPreferences()
        val shuffler = OccurrenceShuffler { it }
        val useCase = CreateOutputPlaylist(AccessTokenProvider { "token" }, api, prefs, shuffler,
            planner = OutputPlanner(shuffler, separator = { it }), registry = TestOutputRegistry(prefs))
        assertTrue(runCatching { useCase.execute(SOURCE, OutputOptions(artistSeparation = true)) }
            .exceptionOrNull() is OutputPlanningException)
        assertEquals(0, api.createCount)
        assertTrue(prefs.managedIds.isEmpty())
        assertTrue(api.batches.isEmpty())
    }

    @Test fun `success reports requested actual short source and all skipped counts`() {
        val message = OutputUiState.Success("Output", 1, 2, 3,
            BigInteger.valueOf(3_600_000), BigInteger.valueOf(120_000), true).message()
        assertTrue(message.contains("2 local"))
        assertTrue(message.contains("3 unsupported"))
        assertTrue(message.contains("Requested 60M"))
        assertTrue(message.contains("actual 120 seconds"))
        assertTrue(message.contains("shorter than requested"))
    }

    private fun autoNamedViewModel(useCase: CreateOutputPlaylist, storage: OutputSettingsStorage = MemoryOutputSettingsStorage()) =
        OutputViewModel(useCase, storage, CreationNameProvider { it })

    private fun useCase(api: TestOutputApi, tokens: AccessTokenProvider = AccessTokenProvider { "token" },
        preferences: TestPlaylistPreferences = TestPlaylistPreferences()) =
        CreateOutputPlaylist(tokens, api, preferences, OccurrenceShuffler { it }, registry = TestOutputRegistry(preferences))

    private class TestOutputApi : TestUnboundOutputApi {
        var items = listOf(track("one"))
        var createCount = 0
        var pageCount = 0
        var pageGate: CompletableDeferred<Unit>? = null
        var writeGate: CompletableDeferred<Unit>? = null
        var failWrite = false
        val batches = mutableListOf<List<String>>()
        val names = mutableListOf<String>()
        override suspend fun getPlaylistItemsPage(url: String, accessToken: String): PlaylistItemsPage {
            pageCount++
            pageGate?.let { withContext(NonCancellable) { it.await() } }
            return PlaylistItemsPage(items, null)
        }
        override suspend fun createPrivatePlaylist(name: String, description: String, accessToken: String): Playlist {
            createCount++
            names += name
            return Playlist("output$createCount", name)
        }
        override suspend fun addItems(playlistId: String, uris: List<String>, accessToken: String) {
            writeGate?.let { withContext(NonCancellable) { it.await() } }
            if (failWrite) error("write failed")
            batches += uris
        }
    }

    private class TestPlaylistPreferences : PlaylistPreferences {
        val managedIds = mutableSetOf<String>()
        override fun loadManagedPlaylistIds() = managedIds.toSet()
        override suspend fun addManagedPlaylistId(playlistId: String): Boolean { managedIds += playlistId; return true }
        override suspend fun addManagedPlaylistIds(playlistIds: Set<String>): Boolean { managedIds += playlistIds; return true }
        override fun loadDeclinedRecoveryPlaylistIds() = emptySet<String>()
        override suspend fun addDeclinedRecoveryPlaylistIds(playlistIds: Set<String>) = true
        override fun loadSelectedPlaylistId(): String? = null
        override fun saveSelectedPlaylistId(playlistId: String) = Unit
        override fun clearSelectedPlaylistId() = Unit
    }

    companion object {
        private val SOURCE = Playlist("source", "Source")
        private fun track(id: String, duration: Long = 60_000, artist: String = "A") =
            OutputPlaylistItem("spotify:track:$id", durationMs = duration, primaryArtistId = artist)
    }
}
