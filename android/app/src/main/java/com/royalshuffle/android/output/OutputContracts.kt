package com.royalshuffle.android.output

import com.royalshuffle.android.data.remote.WebApiFailureCategory
import com.royalshuffle.android.domain.model.Playlist
import java.math.BigInteger
import com.royalshuffle.android.playlist.PlaylistPage

data class PlaylistItemsPage(
    val items: List<OutputPlaylistItem>,
    val nextUrl: String?,
)

data class OutputPlaylistItem(
    val uri: String?,
    val isLocal: Boolean = false,
    val durationMs: Long? = null,
    val primaryArtistId: String? = null,
    val itemType: String? = null,
)

interface OutputPlaylistApi {
    suspend fun getPlaylistItemsPage(url: String, accessToken: String): PlaylistItemsPage

    suspend fun createPrivatePlaylist(
        name: String,
        description: String,
        accessToken: String,
    ): Playlist

    suspend fun addItems(playlistId: String, uris: List<String>, accessToken: String)

    suspend fun getPlaylistsPage(url: String, accessToken: String): PlaylistPage
    suspend fun getPlaylist(playlistId: String, accessToken: String): Playlist
    suspend fun isPlaylistSaved(playlistId: String, accessToken: String): Boolean
    suspend fun clearItems(playlistId: String, accessToken: String)
}

fun interface OccurrenceShuffler {
    fun shuffle(items: List<OutputPlaylistItem>): List<OutputPlaylistItem>
}

sealed interface OutputProgress {
    data object LoadingItems : OutputProgress
    data class Shuffling(val itemCount: Int) : OutputProgress
    data object CreatingPlaylist : OutputProgress
    data object ResolvingOutput : OutputProgress
    data object ReplacingItems : OutputProgress
    data class AddingItems(val added: Int, val total: Int) : OutputProgress
}

data class OutputResult(
    val playlist: Playlist,
    val itemCount: Int,
    val skippedLocalItemCount: Int,
    val skippedUnsupportedItemCount: Int = 0,
    val requestedDurationMs: BigInteger? = null,
    val durationMs: BigInteger? = null,
    val sourceShorterThanTarget: Boolean = false,
    val action: OutputAction = OutputAction.CREATED,
)

enum class OutputAction { CREATED, UPDATED }
enum class OutputWriteStage { CLEAR, APPEND }

class OutputPlaylistException(val reason: Reason) : Exception() {
    enum class Reason {
        INVALID_PAGINATION,
        NETWORK,
        NOT_AUTHENTICATED,
        SOURCE_OUTPUT_ID_COLLISION,
    }
}

class PartialPlaylistWriteException(
    val outputPlaylistId: String,
    val outputPlaylistName: String,
    val confirmedItemsWritten: Int,
    val totalItemsIntended: Int,
    val underlyingReason: OutputPlaylistException.Reason?,
    val underlyingFailureCategory: WebApiFailureCategory?,
    cause: Throwable,
    val action: OutputAction = OutputAction.CREATED,
    val failedStage: OutputWriteStage = OutputWriteStage.APPEND,
) : Exception(cause)

class ManagedPlaylistRegistrationException(
    val outputPlaylistId: String,
    val outputPlaylistName: String,
    cause: Throwable? = null,
) : Exception(cause)
