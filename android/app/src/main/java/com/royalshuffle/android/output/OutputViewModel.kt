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
import kotlinx.coroutines.Job
import kotlinx.coroutines.CoroutineStart
import java.math.BigInteger
import kotlinx.coroutines.CompletableDeferred

sealed interface OutputUiState {
    data object Idle : OutputUiState
    data class Working(val message: String) : OutputUiState
    data class Success(
        val playlistName: String,
        val itemCount: Int,
        val skippedLocalItemCount: Int,
        val skippedUnsupportedItemCount: Int = 0,
        val requestedDurationMs: BigInteger? = null,
        val durationMs: BigInteger? = null,
        val sourceShorterThanTarget: Boolean = false,
        val action: OutputAction = OutputAction.CREATED,
    ) : OutputUiState
    data class PartialFailure(val message: String) : OutputUiState
    data class Error(val message: String) : OutputUiState
}

class OutputViewModel(
    private val createOutputPlaylist: CreateOutputPlaylist,
    private val settingsStorage: OutputSettingsStorage = MemoryOutputSettingsStorage(),
    private val creationNameProvider: CreationNameProvider? = null,
) : ViewModel() {
    private val mutableUiState = MutableStateFlow<OutputUiState>(OutputUiState.Idle)
    val uiState: StateFlow<OutputUiState> = mutableUiState.asStateFlow()
    private val mutableSettings = MutableStateFlow(settingsStorage.load())
    val settings: StateFlow<OutputSettings> = mutableSettings.asStateFlow()
    private val mutableIsRunning = MutableStateFlow(false)
    val isRunning: StateFlow<Boolean> = mutableIsRunning.asStateFlow()
    private var generation = 0L
    private var operation: Job? = null
    private val mutableNameRequest = MutableStateFlow<CreationNameRequest?>(null)
    val nameRequest: StateFlow<CreationNameRequest?> = mutableNameRequest.asStateFlow()
    private var pendingName: CompletableDeferred<String?>? = null

    fun confirmName(requestId: Long, name: String) {
        val request = mutableNameRequest.value ?: return
        if (request.requestId != requestId || generation != requestId) return
        if (name.isBlank()) {
            mutableNameRequest.value = request.copy(errorMessage = "Playlist name cannot be blank.")
            return
        }
        pendingName?.complete(name.trim())
        mutableNameRequest.value = null
    }

    fun cancelName(requestId: Long) {
        if (mutableNameRequest.value?.requestId != requestId || generation != requestId) return
        pendingName?.complete(null)
        mutableNameRequest.value = null
    }

    private suspend fun requestName(defaultName: String, requestId: Long): String? {
        val response = CompletableDeferred<String?>()
        pendingName = response
        mutableNameRequest.value = CreationNameRequest(requestId, defaultName)
        return try { response.await() } finally {
            if (mutableNameRequest.value?.requestId == requestId) mutableNameRequest.value = null
            if (pendingName === response) pendingName = null
        }
    }

    fun setSessionLength(mode: SessionLengthMode) = updateSettings(mutableSettings.value.copy(mode = mode))
    fun setCustomMinutes(value: String) = updateSettings(mutableSettings.value.copy(customMinutes = value))
    fun setArtistSeparation(enabled: Boolean) = updateSettings(mutableSettings.value.copy(artistSeparation = enabled))

    private fun updateSettings(settings: OutputSettings) {
        settingsStorage.save(settings)
        mutableSettings.value = settings
    }

    fun create(source: Playlist) {
        if (mutableIsRunning.value) return
        val options = try { mutableSettings.value.snapshot() } catch (error: OutputPlanningException) {
            mutableUiState.value = OutputUiState.Error(error.message!!)
            return
        }
        val submittedGeneration = ++generation
        // Acquire the submission gate before scheduling or suspended token acquisition.
        mutableIsRunning.value = true
        mutableUiState.value = OutputUiState.Working("Preparing shuffle…")
        operation = viewModelScope.launch(start = CoroutineStart.LAZY) {
            val nextState = try {
                val nameProvider = creationNameProvider ?: CreationNameProvider {
                    requestName(it, submittedGeneration)
                }
                val result = createOutputPlaylist.execute(source, options, nameProvider) { progress ->
                    if (generation == submittedGeneration) {
                        mutableUiState.value = OutputUiState.Working(progress.message())
                    }
                }
                if (result == null) OutputUiState.Idle else OutputUiState.Success(
                    result.playlist.name,
                    result.itemCount,
                    result.skippedLocalItemCount,
                    result.skippedUnsupportedItemCount,
                    result.requestedDurationMs,
                    result.durationMs,
                    result.sourceShorterThanTarget,
                    result.action,
                )
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                when {
                    error is ManagedPlaylistRegistrationException ->
                        OutputUiState.Error(MANAGED_REGISTRATION_FAILURE_MESSAGE +
                            if ((error.cause as? OutputRegistryException)?.restartRequired == true)
                                " Close RoyalShuffle completely and reopen it before retrying." else "")
                    error is PartialPlaylistWriteException ->
                        OutputUiState.PartialFailure(error.toUserMessage())
                    error.requiresSpotifyReconnect() -> OutputUiState.Idle
                    else -> OutputUiState.Error(
                        when {
                            error is OutputPlaylistException &&
                                error.reason == OutputPlaylistException.Reason.SOURCE_OUTPUT_ID_COLLISION ->
                                "Spotify returned the source playlist as the output. Nothing was modified."
                            error is OutputPlanningException -> error.message!!
                            error is OutputRegistryException -> error.message!!
                            else -> spotifyWebApiMessage(error)
                                ?: "Could not finish the shuffled playlist. You can try again."
                        },
                    )
                }
            }
            if (generation == submittedGeneration) mutableUiState.value = nextState
        }
        // Completion also releases the gate if cancelled before the coroutine starts.
        operation!!.invokeOnCompletion { mutableIsRunning.value = false }
        operation!!.start()
    }

    fun clear() {
        invalidateOperation()
        mutableUiState.value = OutputUiState.Idle
    }

    fun clearForSessionInvalidation() {
        invalidateOperation()
        if (mutableUiState.value !is OutputUiState.PartialFailure) {
            mutableUiState.value = OutputUiState.Idle
        }
    }

    private fun invalidateOperation() {
        generation++
        operation?.cancel()
        mutableNameRequest.value = null
        // Keep the gate held until cancellation completes, including blocking HTTP work.
    }

    private fun OutputProgress.message(): String = when (this) {
        OutputProgress.LoadingItems -> "Loading playlist items…"
        is OutputProgress.Shuffling -> "Shuffling $itemCount items…"
        OutputProgress.CreatingPlaylist -> "Creating private playlist…"
        OutputProgress.ResolvingOutput -> "Resolving managed output…"
        OutputProgress.ReplacingItems -> "Replacing managed playlist contents…"
        is OutputProgress.AddingItems -> "Adding items… $added of $total"
    }

    companion object {
        const val MANAGED_REGISTRATION_FAILURE_MESSAGE =
            "RoyalShuffle created the Spotify playlist, but could not safely register it on " +
                "this device. No tracks were added."

        fun factory(
            useCase: CreateOutputPlaylist,
            settingsStorage: OutputSettingsStorage = MemoryOutputSettingsStorage(),
        ): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T =
                    OutputViewModel(useCase, settingsStorage) as T
            }
    }
}

data class CreationNameRequest(val requestId: Long, val defaultName: String, val errorMessage: String? = null)

internal fun OutputUiState.Success.message(): String =
    "${if (action == OutputAction.CREATED) "Created" else "Updated"} $playlistName with $itemCount items." +
        if (skippedLocalItemCount > 0) {
            " $skippedLocalItemCount local items were excluded."
        } else {
            ""
        } +
        (if (skippedUnsupportedItemCount > 0) " $skippedUnsupportedItemCount unsupported or unusable items were excluded." else "") +
        (if (requestedDurationMs != null && durationMs != null) {
            " Requested ${requestedDurationMs.divide(BigInteger.valueOf(60_000))}M; " +
                "actual ${durationMs.divide(BigInteger.valueOf(1_000))} seconds." +
                if (sourceShorterThanTarget) " Source is shorter than requested; all eligible tracks were included." else ""
        } else "")

internal fun PartialPlaylistWriteException.toUserMessage(): String =
    (if (action == OutputAction.CREATED) "RoyalShuffle created $outputPlaylistName, but "
    else "RoyalShuffle could not finish updating $outputPlaylistName: ") +
        (if (failedStage == OutputWriteStage.CLEAR) {
            (if (underlyingFailureCategory == WebApiFailureCategory.QUOTA_EXCEEDED)
                "Spotify developer quota was exceeded; " else "") +
            "replacement clear was not acknowledged; previous contents may or may not have changed "
        } else {
        (if (underlyingFailureCategory == WebApiFailureCategory.QUOTA_EXCEEDED) {
             "Spotify developer quota was exceeded before population completed "
         } else {
             "population stopped before completion "
         }) + (if (action == OutputAction.UPDATED) "after clearing the previous contents " else "")
        }) +
        "($confirmedItemsWritten of $totalItemsIntended items confirmed written). " +
        (if (action == OutputAction.CREATED) "The partial private playlist remains in Spotify; "
        else "The update was not completed; ") + "RoyalShuffle did not automatically " +
        "retry or roll it back."

private fun Throwable.requiresSpotifyReconnect(): Boolean =
    this is OutputPlaylistException && reason == OutputPlaylistException.Reason.NOT_AUTHENTICATED ||
        this is com.royalshuffle.android.data.remote.SpotifyWebApiException &&
        category == com.royalshuffle.android.data.remote.WebApiFailureCategory.AUTHENTICATION
