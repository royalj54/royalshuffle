package com.royalshuffle.android.output

import com.royalshuffle.android.playlist.PlaylistPreferences
import com.royalshuffle.android.playlist.PlaylistPage
import com.royalshuffle.android.domain.model.Playlist
import com.royalshuffle.android.data.remote.SpotifyWebApiException
import com.royalshuffle.android.data.remote.WebApiFailureCategory

internal class TestOutputRegistry(private val preferences: PlaylistPreferences? = null) : OrdinaryOutputRegistry {
    val bindings = mutableMapOf<OutputIdentity, String>()
    override fun boundOutput(identity: OutputIdentity) = bindings[identity]
    override fun requireCurrent(identity: OutputIdentity, expectedOutputId: String?) {
        if (bindings[identity] != expectedOutputId) throw OutputRegistryException("Binding changed")
    }
    override suspend fun bind(identity: OutputIdentity, outputId: String, expectedOutputId: String?) {
        requireCurrent(identity, expectedOutputId)
        validateBindings(bindings + (identity to outputId))
        if (preferences?.addManagedPlaylistId(outputId) == false) throw OutputRegistryException("Commit failed")
        bindings[identity] = outputId
    }
}

internal interface TestUnboundOutputApi : OutputPlaylistApi {
    override suspend fun isPlaylistSaved(playlistId: String, accessToken: String) = true
    override suspend fun getPlaylistsPage(url: String, accessToken: String) = PlaylistPage(emptyList(), null)
    override suspend fun getPlaylist(playlistId: String, accessToken: String): Playlist =
        throw SpotifyWebApiException(WebApiFailureCategory.OTHER, 404)
    override suspend fun clearItems(playlistId: String, accessToken: String) = Unit
}
