package com.royalshuffle.android.data.local

import android.content.Context
import com.royalshuffle.android.playlist.PlaylistPreferences

class SharedPreferencesPlaylistPreferences(context: Context) : PlaylistPreferences {
    private val preferences = context.getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE)

    override fun loadManagedPlaylistIds(): Set<String> =
        preferences.getStringSet(KEY_MANAGED_PLAYLIST_IDS, emptySet()).orEmpty().toSet()

    override fun addManagedPlaylistId(playlistId: String) {
        val updatedIds = loadManagedPlaylistIds() + playlistId
        preferences.edit().putStringSet(KEY_MANAGED_PLAYLIST_IDS, updatedIds).apply()
    }

    override fun loadSelectedPlaylistId(): String? =
        preferences.getString(KEY_SELECTED_PLAYLIST_ID, null)

    override fun saveSelectedPlaylistId(playlistId: String) {
        preferences.edit().putString(KEY_SELECTED_PLAYLIST_ID, playlistId).apply()
    }

    override fun clearSelectedPlaylistId() {
        preferences.edit().remove(KEY_SELECTED_PLAYLIST_ID).apply()
    }

    private companion object {
        const val FILE_NAME = "royalshuffle_playlists"
        const val KEY_MANAGED_PLAYLIST_IDS = "managed_playlist_ids"
        const val KEY_SELECTED_PLAYLIST_ID = "selected_playlist_id"
    }
}
