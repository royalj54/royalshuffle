package com.royalshuffle.android.output

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.royalshuffle.android.domain.model.Playlist
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

sealed interface OutputUiState {
    data object Idle : OutputUiState
    data class Working(val message: String) : OutputUiState
    data class Success(val playlistName: String, val itemCount: Int) : OutputUiState
    data class Error(val message: String) : OutputUiState
}

class OutputViewModel(private val createOutputPlaylist: CreateOutputPlaylist) : ViewModel() {
    private val mutableUiState = MutableStateFlow<OutputUiState>(OutputUiState.Idle)
    val uiState: StateFlow<OutputUiState> = mutableUiState.asStateFlow()

    fun create(source: Playlist) {
        if (mutableUiState.value is OutputUiState.Working) return
        viewModelScope.launch {
            mutableUiState.value = try {
                val result = createOutputPlaylist.execute(source) { progress ->
                    mutableUiState.value = OutputUiState.Working(progress.message())
                }
                OutputUiState.Success(result.playlist.name, result.itemCount)
            } catch (error: Exception) {
                OutputUiState.Error(
                    if (error is OutputPlaylistException &&
                        error.reason == OutputPlaylistException.Reason.SOURCE_OUTPUT_ID_COLLISION
                    ) {
                        "Spotify returned the source playlist as the output. Nothing was modified."
                    } else {
                        "Could not finish the shuffled playlist. You can try again."
                    },
                )
            }
        }
    }

    fun clear() {
        mutableUiState.value = OutputUiState.Idle
    }

    private fun OutputProgress.message(): String = when (this) {
        OutputProgress.LoadingItems -> "Loading playlist items…"
        is OutputProgress.Shuffling -> "Shuffling $itemCount items…"
        OutputProgress.CreatingPlaylist -> "Creating private playlist…"
        is OutputProgress.AddingItems -> "Adding items… $added of $total"
    }

    companion object {
        fun factory(useCase: CreateOutputPlaylist): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T =
                    OutputViewModel(useCase) as T
            }
    }
}
