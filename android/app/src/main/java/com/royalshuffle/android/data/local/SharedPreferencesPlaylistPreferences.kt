package com.royalshuffle.android.data.local

import android.content.Context
import android.content.SharedPreferences
import com.royalshuffle.android.playlist.PlaylistPreferences
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class SharedPreferencesPlaylistPreferences internal constructor(
    private val preferences: SharedPreferences,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : PlaylistPreferences {
    constructor(
        context: Context,
        ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    ) : this(
        context.getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE),
        ioDispatcher,
    )

    override fun loadManagedPlaylistIds(): Set<String> =
        preferences.getStringSet(KEY_MANAGED_PLAYLIST_IDS, emptySet()).orEmpty().toSet()

    override suspend fun addManagedPlaylistId(playlistId: String): Boolean =
        addManagedPlaylistIds(setOf(playlistId))

    override suspend fun addManagedPlaylistIds(playlistIds: Set<String>): Boolean =
        withContext(ioDispatcher) {
            try {
                val updatedIds = loadManagedPlaylistIds() + playlistIds
                preferences.edit().putStringSet(KEY_MANAGED_PLAYLIST_IDS, updatedIds).commit()
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                false
            }
        }

    override fun loadDeclinedRecoveryPlaylistIds(): Set<String> =
        preferences.getStringSet(KEY_DECLINED_RECOVERY_PLAYLIST_IDS, emptySet()).orEmpty().toSet()

    override suspend fun addDeclinedRecoveryPlaylistIds(playlistIds: Set<String>): Boolean =
        withContext(ioDispatcher) {
            try {
                val updatedIds = loadDeclinedRecoveryPlaylistIds() + playlistIds
                preferences.edit()
                    .putStringSet(KEY_DECLINED_RECOVERY_PLAYLIST_IDS, updatedIds)
                    .commit()
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                false
            }
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
        const val KEY_DECLINED_RECOVERY_PLAYLIST_IDS = "declined_recovery_playlist_ids"
        const val KEY_SELECTED_PLAYLIST_ID = "selected_playlist_id"
    }
}
