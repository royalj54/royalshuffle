package com.royalshuffle.android.playlist

import com.royalshuffle.android.domain.model.Playlist

data class PlaylistPage(
    val playlists: List<Playlist>,
    val nextUrl: String?,
)

data class PlaylistLoadResult(
    val playlists: List<Playlist>,
    val selectedPlaylistId: String?,
    val recoveryCandidates: List<Playlist> = emptyList(),
    val playlistsIfRecoveryDeclined: List<Playlist> = playlists,
)

interface PlaylistApi {
    suspend fun getPlaylistsPage(url: String, accessToken: String): PlaylistPage
}

interface PlaylistPreferences {
    fun loadManagedPlaylistIds(): Set<String>
    suspend fun addManagedPlaylistId(playlistId: String): Boolean
    suspend fun addManagedPlaylistIds(playlistIds: Set<String>): Boolean
    fun loadDeclinedRecoveryPlaylistIds(): Set<String>
    suspend fun addDeclinedRecoveryPlaylistIds(playlistIds: Set<String>): Boolean
    fun loadSelectedPlaylistId(): String?
    fun saveSelectedPlaylistId(playlistId: String)
    fun clearSelectedPlaylistId()
}
