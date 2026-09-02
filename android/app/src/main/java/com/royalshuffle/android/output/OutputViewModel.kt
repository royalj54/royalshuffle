package com.royalshuffle.android.output

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.royalshuffle.android.data.remote.WebApiFailureCategory
import com.royalshuffle.android.domain.model.Playlist
import com.royalshuffle.android.ui.spotifyWebApiMessage
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

sealed interface OutputUiState {
    data object Idle : OutputUiState
    data class Working(val message: String) : OutputUiState
    data class Success(
        val playlistName: String,
        val itemCount: Int,
        val skippedLocalItemCount: Int,
    ) : OutputUiState
    data class PartialFailure(val message: String) : OutputUiState
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
                OutputUiState.Success(
                    result.playlist.name,
                    result.itemCount,
                    result.skippedLocalItemCount,
                )
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                when {
                    error is ManagedPlaylistRegistrationException ->
                        OutputUiState.Error(MANAGED_REGISTRATION_FAILURE_MESSAGE)
                    error is PartialPlaylistWriteException ->
                        OutputUiState.PartialFailure(error.toUserMessage())
                    error.requiresSpotifyReconnect() -> OutputUiState.Idle
                    else -> OutputUiState.Error(
                        when {
                        error is OutputPlaylistException &&
                            error.reason ==
                            OutputPlaylistException.Reason.SOURCE_OUTPUT_ID_COLLISION ->
                            "Spotify returned the source playlist as the output. Nothing was modified."
                        else -> spotifyWebApiMessage(error)
                            ?: "Could not finish the shuffled playlist. You can try again."
                        },
                    )
                }
            }
        }
    }

    fun clear() {
        mutableUiState.value = OutputUiState.Idle
    }

    fun clearForSessionInvalidation() {
        if (mutableUiState.value !is OutputUiState.PartialFailure) {
            mutableUiState.value = OutputUiState.Idle
        }
    }

    private fun OutputProgress.message(): String = when (this) {
        OutputProgress.LoadingItems -> "Loading playlist items…"
        is OutputProgress.Shuffling -> "Shuffling $itemCount items…"
        OutputProgress.CreatingPlaylist -> "Creating private playlist…"
        is OutputProgress.AddingItems -> "Adding items… $added of $total"
    }

    companion object {
        const val MANAGED_REGISTRATION_FAILURE_MESSAGE =
            "RoyalShuffle created the Spotify playlist, but could not safely register it on " +
                "this device. No tracks were added."

        fun factory(useCase: CreateOutputPlaylist): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T =
                    OutputViewModel(useCase) as T
            }
    }
}

internal fun OutputUiState.Success.message(): String =
    "Created $playlistName with $itemCount items." +
        if (skippedLocalItemCount > 0) {
            " $skippedLocalItemCount local items were excluded."
        } else {
            ""
        }

internal fun PartialPlaylistWriteException.toUserMessage(): String =
    "RoyalShuffle created $outputPlaylistName, but " +
        (if (underlyingFailureCategory == WebApiFailureCategory.QUOTA_EXCEEDED) {
             "Spotify developer quota was exceeded before population completed "
         } else {
             "population stopped before completion "
         }) +
        "($confirmedItemsWritten of $totalItemsIntended items confirmed written). " +
        "The partial private playlist remains in Spotify; RoyalShuffle did not automatically " +
        "retry or roll it back."

private fun Throwable.requiresSpotifyReconnect(): Boolean =
    this is OutputPlaylistException && reason == OutputPlaylistException.Reason.NOT_AUTHENTICATED ||
        this is com.royalshuffle.android.data.remote.SpotifyWebApiException &&
        category == com.royalshuffle.android.data.remote.WebApiFailureCategory.AUTHENTICATION
