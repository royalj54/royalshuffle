package com.royalshuffle.android.data.local

import android.content.Context
import android.content.SharedPreferences
import com.royalshuffle.android.playlist.PlaylistPreferences
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.royalshuffle.android.output.OrdinaryOutputRegistry
import com.royalshuffle.android.output.OutputIdentity
import com.royalshuffle.android.output.OutputRegistryException
import com.royalshuffle.android.output.validateBindings
import org.json.JSONArray
import org.json.JSONObject
import java.util.WeakHashMap

class SharedPreferencesPlaylistPreferences internal constructor(
    private val preferences: SharedPreferences,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : PlaylistPreferences, OrdinaryOutputRegistry {
    constructor(
        context: Context,
        ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    ) : this(
        context.getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE),
        ioDispatcher,
    )

    override fun loadManagedPlaylistIds(): Set<String> = synchronized(MUTATION_LOCK) {
        preferences.getStringSet(KEY_MANAGED_PLAYLIST_IDS, emptySet()).orEmpty().toSet() +
            loadBindings().values
    }

    override suspend fun addManagedPlaylistId(playlistId: String): Boolean =
        addManagedPlaylistIds(setOf(playlistId))

    override suspend fun addManagedPlaylistIds(playlistIds: Set<String>): Boolean =
        withContext(ioDispatcher) {
            try {
                synchronized(MUTATION_LOCK) {
                    val updatedIds = loadManagedPlaylistIds() + playlistIds
                    preferences.edit().putStringSet(KEY_MANAGED_PLAYLIST_IDS, updatedIds).commit()
                }
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
                synchronized(MUTATION_LOCK) {
                    val updatedIds = loadDeclinedRecoveryPlaylistIds() + playlistIds
                    preferences.edit()
                        .putStringSet(KEY_DECLINED_RECOVERY_PLAYLIST_IDS, updatedIds)
                        .commit()
                }
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

    override fun boundOutput(identity: OutputIdentity): String? = synchronized(MUTATION_LOCK) {
        loadBindings()[identity]
    }

    override fun requireCurrent(identity: OutputIdentity, expectedOutputId: String?) = synchronized(MUTATION_LOCK) {
        if (loadBindings()[identity] != expectedOutputId) {
            throw OutputRegistryException("Managed output binding changed. Retry after reviewing the current output.")
        }
    }

    override suspend fun bind(identity: OutputIdentity, outputId: String, expectedOutputId: String?) =
        withContext(ioDispatcher) {
            synchronized(MUTATION_LOCK) {
                val bindings = loadBindings()
                if (bindings[identity] != expectedOutputId) {
                    throw OutputRegistryException("Managed output binding changed; the new playlist was not populated.")
                }
                // A creation response must not adopt a legacy or already managed playlist.
                if (outputId in loadManagedPlaylistIds()) {
                    throw OutputRegistryException("Created playlist conflicts with an existing managed output.")
                }
                val updated = bindings + (identity to outputId)
                validateBindings(updated)
                val document = JSONObject().put("schema_version", 1).put("bindings", JSONArray().apply {
                    updated.forEach { (key, value) -> put(JSONObject()
                        .put("source_id", key.sourceId).put("session", key.sessionKey).put("output_id", value)) }
                })
                val managed = loadManagedPlaylistIds() + outputId
                // One checked commit makes binding and exclusion registration durable together.
                // Old exclusion IDs are deliberately retained, including stale associations.
                val saved = try {
                    preferences.edit().putString(KEY_OUTPUT_BINDINGS, document.toString())
                        .putStringSet(KEY_MANAGED_PLAYLIST_IDS, managed).commit()
                } catch (error: CancellationException) {
                    failedBindingStores[preferences] = true
                    throw error
                } catch (error: Exception) {
                    failedBindingStores[preferences] = true
                    throw OutputRegistryException("Could not durably save the managed output binding.", restartRequired = true)
                }
                if (!saved) {
                    // Android may update SharedPreferences memory even when disk commit fails.
                    // Fail closed across wrappers until restart rather than trust that memory.
                    failedBindingStores[preferences] = true
                    throw OutputRegistryException("Could not durably save the managed output binding. Restart before retrying.", restartRequired = true)
                }
            }
        }

    private fun loadBindings(): Map<OutputIdentity, String> {
        if (failedBindingStores[preferences] == true) {
            throw OutputRegistryException("Managed output persistence failed. Restart before retrying.", restartRequired = true)
        }
        try {
            val text = preferences.getString(KEY_OUTPUT_BINDINGS, null) ?: return emptyMap()
            val document = JSONObject(text)
            if (document.opt("schema_version") != 1) throw OutputRegistryException("Unsupported output binding schema.")
            val rows = document.getJSONArray("bindings")
            val bindings = linkedMapOf<OutputIdentity, String>()
            for (index in 0 until rows.length()) {
                val row = rows.getJSONObject(index)
                val identity = OutputIdentity(row.get("source_id") as String, row.get("session") as String)
                val outputId = row.get("output_id") as String
                if (bindings.put(identity, outputId) != null) throw OutputRegistryException("Duplicate output binding.")
            }
            validateBindings(bindings)
            return bindings
        } catch (error: OutputRegistryException) {
            throw error
        } catch (_: Exception) {
            throw OutputRegistryException("Cannot read managed output bindings. Existing state was preserved.")
        }
    }

    private companion object {
        const val FILE_NAME = "royalshuffle_playlists"
        const val KEY_MANAGED_PLAYLIST_IDS = "managed_playlist_ids"
        const val KEY_DECLINED_RECOVERY_PLAYLIST_IDS = "declined_recovery_playlist_ids"
        const val KEY_SELECTED_PLAYLIST_ID = "selected_playlist_id"
        const val KEY_OUTPUT_BINDINGS = "ordinary_output_bindings"
        val MUTATION_LOCK = Any()
        val failedBindingStores = WeakHashMap<SharedPreferences, Boolean>()
    }
}
