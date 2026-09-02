package com.royalshuffle.android.ui

import com.royalshuffle.android.auth.AccessTokenProvider
import com.royalshuffle.android.auth.SessionInvalidator
import com.royalshuffle.android.auth.SpotifySessionState
import com.royalshuffle.android.domain.model.Playlist
import com.royalshuffle.android.output.CreateOutputPlaylist
import com.royalshuffle.android.output.OutputPlaylistApi
import com.royalshuffle.android.output.OutputPlaylistItem
import com.royalshuffle.android.output.OutputUiState
import com.royalshuffle.android.output.OutputViewModel
import com.royalshuffle.android.output.PlaylistItemsPage
import com.royalshuffle.android.output.UriShuffler
import com.royalshuffle.android.playlist.PlaylistApi
import com.royalshuffle.android.playlist.PlaylistPage
import com.royalshuffle.android.playlist.PlaylistPreferences
import com.royalshuffle.android.playlist.PlaylistRepository
import com.royalshuffle.android.playlist.PlaylistUiState
import com.royalshuffle.android.playlist.PlaylistViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class SessionStateCoordinationTest {
    private val dispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `invalid grant during playlist load invalidates session and leaves no stale list`() =
        runTest(dispatcher) {
            val invalidator = FakeSessionInvalidator()
            val preferences = FakePreferences().apply { selectedId = "source-id" }
            val repository = PlaylistRepository(
                accessTokenProvider = invalidGrantProvider(invalidator),
                playlistApi = SinglePlaylistApi,
                preferences = preferences,
            )
            val viewModel = PlaylistViewModel(repository)

            viewModel.loadPlaylists()
            advanceUntilIdle()
            viewModel.clearForSessionInvalidation()

            assertEquals(SpotifySessionState.INVALIDATED, invalidator.sessionState.value)
            assertEquals(PlaylistUiState.Idle, viewModel.uiState.value)
            assertEquals(null, preferences.selectedId)
        }

    @Test
    fun `invalid grant during output creation invalidates session and clears stale output`() =
        runTest(dispatcher) {
            val invalidator = FakeSessionInvalidator()
            val useCase = CreateOutputPlaylist(
                accessTokenProvider = invalidGrantProvider(invalidator),
                api = SuccessfulOutputApi,
                preferences = FakePreferences(),
                shuffler = UriShuffler { it },
            )
            val viewModel = OutputViewModel(useCase)

            viewModel.create(SOURCE)
            advanceUntilIdle()
            viewModel.clearForSessionInvalidation()

            assertEquals(SpotifySessionState.INVALIDATED, invalidator.sessionState.value)
            assertEquals(OutputUiState.Idle, viewModel.uiState.value)
        }

    @Test
    fun `session invalidation clears loaded playlist state and persisted selection`() =
        runTest(dispatcher) {
            val preferences = FakePreferences()
            val repository = PlaylistRepository(
                accessTokenProvider = AccessTokenProvider { "token" },
                playlistApi = SinglePlaylistApi,
                preferences = preferences,
            )
            val viewModel = PlaylistViewModel(repository)
            viewModel.loadPlaylists()
            advanceUntilIdle()
            viewModel.selectPlaylist(SOURCE.id)
            assertTrue(viewModel.uiState.value is PlaylistUiState.Content)

            viewModel.clearForSessionInvalidation()

            assertEquals(PlaylistUiState.Idle, viewModel.uiState.value)
            assertEquals(null, preferences.selectedId)
        }

    @Test
    fun `session invalidation clears completed output but preserves partial output`() =
        runTest(dispatcher) {
            val completed = OutputViewModel(outputUseCase(SuccessfulOutputApi))
            completed.create(SOURCE)
            advanceUntilIdle()
            assertTrue(completed.uiState.value is OutputUiState.Success)
            completed.clearForSessionInvalidation()
            assertEquals(OutputUiState.Idle, completed.uiState.value)

            val partial = OutputViewModel(outputUseCase(AuthenticationFailureOutputApi))
            partial.create(SOURCE)
            advanceUntilIdle()
            val partialState = partial.uiState.value
            assertTrue(partialState is OutputUiState.PartialFailure)
            partial.clearForSessionInvalidation()
            assertEquals(partialState, partial.uiState.value)
        }

    @Test
    fun `managed registration failure shows created but unpopulated message`() =
        runTest(dispatcher) {
            val preferences = FakePreferences().apply { registrationSucceeds = false }
            SuccessfulOutputApi.addCount = 0
            val viewModel = OutputViewModel(
                CreateOutputPlaylist(
                    accessTokenProvider = AccessTokenProvider { "token" },
                    api = SuccessfulOutputApi,
                    preferences = preferences,
                    shuffler = UriShuffler { it },
                ),
            )

            viewModel.create(SOURCE)
            advanceUntilIdle()

            assertEquals(
                OutputUiState.Error(OutputViewModel.MANAGED_REGISTRATION_FAILURE_MESSAGE),
                viewModel.uiState.value,
            )
            assertEquals(0, SuccessfulOutputApi.addCount)
        }

    private fun invalidGrantProvider(invalidator: FakeSessionInvalidator) = AccessTokenProvider {
        invalidator.invalidateSession()
        null
    }

    private fun outputUseCase(api: OutputPlaylistApi) = CreateOutputPlaylist(
        accessTokenProvider = AccessTokenProvider { "token" },
        api = api,
        preferences = FakePreferences(),
        shuffler = UriShuffler { it },
    )

    private class FakeSessionInvalidator : SessionInvalidator {
        private val mutableState = MutableStateFlow(SpotifySessionState.ACTIVE)
        override val sessionState: StateFlow<SpotifySessionState> = mutableState

        override fun invalidateSession() {
            mutableState.value = SpotifySessionState.INVALIDATED
        }
    }

    private class FakePreferences : PlaylistPreferences {
        private val managedIds = mutableSetOf<String>()
        var selectedId: String? = null
        var registrationSucceeds = true
        override fun loadManagedPlaylistIds(): Set<String> = managedIds
        override suspend fun addManagedPlaylistId(playlistId: String): Boolean {
            if (registrationSucceeds) managedIds += playlistId
            return registrationSucceeds
        }
        override fun loadSelectedPlaylistId(): String? = selectedId
        override fun saveSelectedPlaylistId(playlistId: String) { selectedId = playlistId }
        override fun clearSelectedPlaylistId() { selectedId = null }
    }

    private object SinglePlaylistApi : PlaylistApi {
        override suspend fun getPlaylistsPage(url: String, accessToken: String) =
            PlaylistPage(listOf(SOURCE), null)
    }

    private object SuccessfulOutputApi : OutputPlaylistApi {
        var addCount = 0
        override suspend fun getPlaylistItemsPage(url: String, accessToken: String) =
            PlaylistItemsPage(listOf(OutputPlaylistItem("spotify:track:one")), null)

        override suspend fun createPrivatePlaylist(
            name: String,
            description: String,
            accessToken: String,
        ) = Playlist("output-id", name)

        override suspend fun addItems(
            playlistId: String,
            uris: List<String>,
            accessToken: String,
        ) {
            addCount += 1
        }
    }

    private object AuthenticationFailureOutputApi : OutputPlaylistApi by SuccessfulOutputApi {
        override suspend fun addItems(
            playlistId: String,
            uris: List<String>,
            accessToken: String,
        ) {
            throw com.royalshuffle.android.data.remote.SpotifyWebApiException(
                com.royalshuffle.android.data.remote.WebApiFailureCategory.AUTHENTICATION,
                httpStatus = 401,
            )
        }
    }

    private companion object {
        val SOURCE = Playlist("source-id", "Source")
    }
}
