package com.royalshuffle.android.output

import com.royalshuffle.android.auth.AccessTokenProvider
import com.royalshuffle.android.data.local.SharedPreferencesPlaylistPreferences
import com.royalshuffle.android.data.local.SharedPreferencesPlaylistPreferencesTest
import com.royalshuffle.android.domain.model.Playlist
import com.royalshuffle.android.playlist.PlaylistPage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.*
import org.junit.After
import org.junit.Before
import org.junit.Assert.*
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class CreationNameStateTest {
    private val dispatcher = StandardTestDispatcher()
    @Before fun setUp() { Dispatchers.setMain(dispatcher) }
    @After fun tearDown() { Dispatchers.resetMain() }

    @Test fun `prompt presents default only after planning and valid edit creates playlist`() = runTest(dispatcher) {
        val h = Harness(UnconfinedTestDispatcher(testScheduler))
        h.vm.create(SOURCE)
        advanceUntilIdle()
        val request = h.vm.nameRequest.value!!
        assertEquals("RND-Kpop",request.defaultName)
        assertTrue(h.vm.isRunning.value)
        assertEquals(1,h.api.loads)
        assertEquals(0,h.api.creates)
        h.vm.confirmName(request.requestId," Android Auto Mix ")
        advanceUntilIdle()
        assertEquals("Android Auto Mix",h.api.lastName)
        assertTrue(h.vm.uiState.value is OutputUiState.Success)
        assertNull(h.vm.nameRequest.value)
        assertFalse(h.vm.isRunning.value)
    }

    @Test fun `blank name shows validation and holds gate until corrected`() = runTest(dispatcher) {
        val h = Harness(UnconfinedTestDispatcher(testScheduler))
        h.vm.create(SOURCE)
        advanceUntilIdle()
        val request = h.vm.nameRequest.value!!
        h.vm.confirmName(request.requestId," \t ")
        h.vm.create(SOURCE)
        advanceUntilIdle()
        assertEquals("Playlist name cannot be blank.",h.vm.nameRequest.value!!.errorMessage)
        assertEquals(1,h.api.loads)
        assertEquals(0,h.api.creates)
        assertNull(h.preferences.boundOutput(full()))
        h.vm.confirmName(request.requestId,"Corrected")
        advanceUntilIdle()
        assertEquals(1,h.api.creates)
    }

    @Test fun `cancel dismisses prompt with zero output writes and no binding`() = runTest(dispatcher) {
        val h = Harness(UnconfinedTestDispatcher(testScheduler))
        h.vm.create(SOURCE)
        advanceUntilIdle()
        h.vm.cancelName(h.vm.nameRequest.value!!.requestId)
        advanceUntilIdle()
        assertEquals(OutputUiState.Idle,h.vm.uiState.value)
        assertFalse(h.vm.isRunning.value)
        assertEquals(0,h.api.creates)
        assertEquals(0,h.api.adds)
        assertNull(h.preferences.boundOutput(full()))
        assertFalse(h.store.contains("ordinary_output_bindings"))
    }

    @Test fun `reuse after external rename never requests another name`() = runTest(dispatcher) {
        val h = Harness(UnconfinedTestDispatcher(testScheduler))
        h.preferences.bind(full(),"existing",null)
        h.api.listed = listOf(Playlist("existing","Renamed in Spotify"))
        h.vm.create(SOURCE)
        advanceUntilIdle()
        assertNull(h.vm.nameRequest.value)
        assertEquals(0,h.api.creates)
        assertEquals(1,h.api.clears)
        val result = h.vm.uiState.value as OutputUiState.Success
        assertEquals("Renamed in Spotify",result.playlistName)
        assertEquals(OutputAction.UPDATED,result.action)
        assertTrue(result.message().startsWith("Updated Renamed in Spotify"))
    }

    @Test fun `disconnect removes pending prompt and obsolete name response cannot create`() = runTest(dispatcher) {
        val h = Harness(UnconfinedTestDispatcher(testScheduler))
        h.vm.create(SOURCE)
        advanceUntilIdle()
        val old = h.vm.nameRequest.value!!
        h.vm.clearForSessionInvalidation()
        h.vm.confirmName(old.requestId,"Obsolete")
        advanceUntilIdle()
        assertNull(h.vm.nameRequest.value)
        assertEquals(OutputUiState.Idle,h.vm.uiState.value)
        assertEquals(0,h.api.creates)
        h.vm.create(SOURCE)
        advanceUntilIdle()
        val fresh = h.vm.nameRequest.value!!
        assertNotEquals(old.requestId,fresh.requestId)
        h.vm.confirmName(old.requestId,"Still obsolete")
        h.vm.cancelName(old.requestId)
        assertEquals(fresh,h.vm.nameRequest.value)
        h.vm.confirmName(fresh.requestId,"Fresh")
        advanceUntilIdle()
        assertEquals("Fresh",h.api.lastName)
        assertEquals(1,h.api.creates)
    }

    @Test fun `settings changed while naming cannot change captured membership binding or default`() = runTest(dispatcher) {
        val h = Harness(UnconfinedTestDispatcher(testScheduler))
        h.vm.setSessionLength(SessionLengthMode.CUSTOM)
        h.vm.setCustomMinutes("1")
        h.vm.create(SOURCE)
        advanceUntilIdle()
        val request = h.vm.nameRequest.value!!
        assertEquals("RND1M-Kpop",request.defaultName)
        h.vm.setSessionLength(SessionLengthMode.FULL)
        h.vm.setArtistSeparation(true)
        h.vm.confirmName(request.requestId,"One minute")
        advanceUntilIdle()
        assertEquals(1,h.api.written.size)
        assertEquals("new",h.preferences.boundOutput(OutputIdentity(SOURCE.id,"1")))
        assertNull(h.preferences.boundOutput(full()))
    }

    @Test fun `validation failure never opens naming dialog or changes output bindings`() = runTest(dispatcher) {
        val h = Harness(UnconfinedTestDispatcher(testScheduler))
        h.api.items = emptyList()
        h.vm.create(SOURCE)
        advanceUntilIdle()
        assertNull(h.vm.nameRequest.value)
        assertTrue(h.vm.uiState.value is OutputUiState.Error)
        assertEquals(0,h.api.creates)
        assertFalse(h.store.contains("ordinary_output_bindings"))
    }

    @Test fun `failed production binding commit stops writes and tells user to restart`() = runTest(dispatcher) {
        val h = Harness(UnconfinedTestDispatcher(testScheduler))
        h.store.commitSucceeds = false
        h.vm.create(SOURCE)
        advanceUntilIdle()
        h.vm.confirmName(h.vm.nameRequest.value!!.requestId,"New")
        advanceUntilIdle()
        val error = h.vm.uiState.value as OutputUiState.Error
        assertTrue(error.message.contains("No tracks were added"))
        assertTrue(error.message.contains("reopen it before retrying"))
        assertEquals(0,h.api.adds)
        h.vm.create(SOURCE)
        advanceUntilIdle()
        assertTrue((h.vm.uiState.value as OutputUiState.Error).message.contains("Restart before retrying"))
        assertEquals(1,h.api.creates)
    }

    private class Harness(dispatcher: kotlinx.coroutines.CoroutineDispatcher) {
        val store = SharedPreferencesPlaylistPreferencesTest.FakeSharedPreferences()
        val preferences = SharedPreferencesPlaylistPreferences(store,dispatcher)
        val api = Api()
        val vm = OutputViewModel(CreateOutputPlaylist(AccessTokenProvider { "token" },api,preferences,
            OccurrenceShuffler { it },registry = preferences))
    }
    private class Api : TestUnboundOutputApi {
        var loads = 0
        var creates = 0
        var clears = 0
        var adds = 0
        var lastName: String? = null
        var listed = emptyList<Playlist>()
        var items = listOf(OutputPlaylistItem("spotify:track:a",durationMs = 60_000),
            OutputPlaylistItem("spotify:track:b",durationMs = 60_000))
        val written = mutableListOf<String>()
        override suspend fun getPlaylistItemsPage(url: String,accessToken: String): PlaylistItemsPage {
            loads++
            return PlaylistItemsPage(items,null)
        }
        override suspend fun getPlaylistsPage(url: String,accessToken: String) = PlaylistPage(listed,null)
        override suspend fun createPrivatePlaylist(name: String,description: String,accessToken: String): Playlist {
            creates++
            lastName = name
            return Playlist("new",name)
        }
        override suspend fun clearItems(playlistId: String,accessToken: String) { clears++ }
        override suspend fun addItems(playlistId: String,uris: List<String>,accessToken: String) {
            adds++
            written += uris
        }
    }
    companion object {
        private val SOURCE = Playlist("source","Kpop")
        private fun full() = OutputIdentity(SOURCE.id,OutputIdentity.FULL)
    }
}
