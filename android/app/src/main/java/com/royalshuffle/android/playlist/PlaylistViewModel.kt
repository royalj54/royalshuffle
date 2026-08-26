package com.royalshuffle.android.playlist

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.royalshuffle.android.domain.model.Playlist
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
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
            } catch (error: Exception) {
                PlaylistUiState.Error("Could not load Spotify playlists. Check your connection.")
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
