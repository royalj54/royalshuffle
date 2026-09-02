package com.royalshuffle.android.playlist

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.royalshuffle.android.domain.model.Playlist
import com.royalshuffle.android.ui.spotifyWebApiMessage
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

sealed interface PlaylistUiState {
    data object Idle : PlaylistUiState
    data object Loading : PlaylistUiState
    data object Empty : PlaylistUiState
    data class Content(
        val playlists: List<Playlist>,
        val selectedPlaylistId: String?,
    ) : PlaylistUiState
    data class Error(val message: String) : PlaylistUiState
}

class PlaylistViewModel(
    private val repository: PlaylistRepository,
) : ViewModel() {
    private val mutableUiState = MutableStateFlow<PlaylistUiState>(PlaylistUiState.Idle)
    val uiState: StateFlow<PlaylistUiState> = mutableUiState.asStateFlow()

    fun loadPlaylists() {
        if (mutableUiState.value == PlaylistUiState.Loading) return
        mutableUiState.value = PlaylistUiState.Loading
        viewModelScope.launch {
            mutableUiState.value = try {
                val result = repository.loadEligiblePlaylists()
                if (result.playlists.isEmpty()) {
                    PlaylistUiState.Empty
                } else {
                    PlaylistUiState.Content(result.playlists, result.selectedPlaylistId)
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                if (error.requiresSpotifyReconnect()) {
                    PlaylistUiState.Idle
                } else PlaylistUiState.Error(
                    spotifyWebApiMessage(error)
                        ?: "Could not load Spotify playlists. Try again.",
                )
            }
        }
    }

    fun selectPlaylist(playlistId: String) {
        val current = mutableUiState.value as? PlaylistUiState.Content ?: return
        if (current.playlists.none { it.id == playlistId }) return
        repository.selectPlaylist(playlistId)
        mutableUiState.value = current.copy(selectedPlaylistId = playlistId)
    }

    fun clear() {
        mutableUiState.value = PlaylistUiState.Idle
    }

    fun clearForSessionInvalidation() {
        repository.clearSelection()
        mutableUiState.value = PlaylistUiState.Idle
    }

    companion object {
        fun factory(repository: PlaylistRepository): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T {
                    return PlaylistViewModel(repository) as T
                }
            }
    }
}

private fun Throwable.requiresSpotifyReconnect(): Boolean =
    this is PlaylistException && reason == PlaylistException.Reason.NOT_AUTHENTICATED ||
        this is com.royalshuffle.android.data.remote.SpotifyWebApiException &&
        category == com.royalshuffle.android.data.remote.WebApiFailureCategory.AUTHENTICATION
