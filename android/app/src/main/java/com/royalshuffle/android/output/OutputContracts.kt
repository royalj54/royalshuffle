package com.royalshuffle.android.output

import com.royalshuffle.android.domain.model.Playlist

data class PlaylistItemsPage(
    val itemUris: List<String?>,
    val nextUrl: String?,
)

interface OutputPlaylistApi {
    suspend fun getPlaylistItemsPage(url: String, accessToken: String): PlaylistItemsPage

    suspend fun createPrivatePlaylist(
        name: String,
        description: String,
        accessToken: String,
    ): Playlist

    suspend fun addItems(playlistId: String, uris: List<String>, accessToken: String)
}

fun interface UriShuffler {
    fun shuffle(uris: List<String>): List<String>
}

sealed interface OutputProgress {
    data object LoadingItems : OutputProgress
    data class Shuffling(val itemCount: Int) : OutputProgress
    data object CreatingPlaylist : OutputProgress
    data class AddingItems(val added: Int, val total: Int) : OutputProgress
}

data class OutputResult(
    val playlist: Playlist,
    val itemCount: Int,
)

class OutputPlaylistException(val reason: Reason) : Exception() {
    enum class Reason {
        INVALID_PAGINATION,
        NETWORK,
        NOT_AUTHENTICATED,
        SOURCE_OUTPUT_ID_COLLISION,
    }
}
