package com.royalshuffle.android.playlist

import com.royalshuffle.android.domain.model.Playlist

data class PlaylistPage(
    val playlists: List<Playlist>,
    val nextUrl: String?,
)

data class PlaylistLoadResult(
    val playlists: List<Playlist>,
    val selectedPlaylistId: String?,
)

interface PlaylistApi {
    suspend fun getPlaylistsPage(url: String, accessToken: String): PlaylistPage
}

interface PlaylistPreferences {
    fun loadManagedPlaylistIds(): Set<String>
    fun addManagedPlaylistId(playlistId: String)
    fun loadSelectedPlaylistId(): String?
    fun saveSelectedPlaylistId(playlistId: String)
    fun clearSelectedPlaylistId()
}
