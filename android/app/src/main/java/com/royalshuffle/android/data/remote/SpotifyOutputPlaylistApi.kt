package com.royalshuffle.android.data.remote

import com.royalshuffle.android.domain.model.Playlist
import com.royalshuffle.android.output.OutputPlaylistApi
import com.royalshuffle.android.output.OutputPlaylistItem
import com.royalshuffle.android.output.PlaylistItemsPage
import org.json.JSONArray
import org.json.JSONObject

class SpotifyOutputPlaylistApi(
    private val webApi: SpotifyWebApiClient = SpotifyWebApiClient(),
) : OutputPlaylistApi {
    override suspend fun getPlaylistItemsPage(
        url: String,
        accessToken: String,
    ): PlaylistItemsPage = webApi.requestJson(
        request = WebApiRequest("GET", url, accessToken),
        operation = WebApiOperation("playlist item page fetch", WebApiOperationClass.READ),
    ) { json ->
        val items = json.getJSONArray("items")
        val playlistItems = buildList {
            for (index in 0 until items.length()) {
                val playlistItem = items.optJSONObject(index)
                val item = playlistItem?.optJSONObject("item")
                val uri = item?.optString("uri")?.takeIf { it.isNotBlank() }
                add(
                    OutputPlaylistItem(
                        uri = uri,
                        isLocal = isLocalPlaylistItem(
                            playlistItemIsLocal = playlistItem?.optBoolean("is_local", false)
                                ?: false,
                            itemIsLocal = item?.optBoolean("is_local", false) ?: false,
                            uri = uri,
                        ),
                    ),
                )
            }
        }
        val nextUrl = if (json.isNull("next")) null else json.getString("next")
        PlaylistItemsPage(playlistItems, nextUrl)
    }

    override suspend fun createPrivatePlaylist(
        name: String,
        description: String,
        accessToken: String,
    ): Playlist = webApi.requestJson(
        request = WebApiRequest(
            method = "POST",
            url = "https://api.spotify.com/v1/me/playlists",
            accessToken = accessToken,
            body = JSONObject()
                .put("name", name)
                .put("public", false)
                .put("description", description)
                .toString(),
        ),
        operation = WebApiOperation("playlist creation", WebApiOperationClass.NON_IDEMPOTENT_WRITE),
    ) { json ->
        val id = json.optString("id").takeIf { it.isNotBlank() }
            ?: error("Spotify playlist response did not contain an ID")
        val returnedName = json.optString("name").takeIf { it.isNotBlank() } ?: name
        Playlist(id, returnedName)
    }

    override suspend fun addItems(
        playlistId: String,
        uris: List<String>,
        accessToken: String,
    ) {
        require(uris.isNotEmpty() && uris.size <= 100)
        require(playlistId.matches(SPOTIFY_ID))
        webApi.requestJson(
            request = WebApiRequest(
                method = "POST",
                url = "https://api.spotify.com/v1/playlists/$playlistId/items",
                accessToken = accessToken,
                body = JSONObject().put("uris", JSONArray(uris)).toString(),
            ),
            operation = WebApiOperation(
                "playlist item batch write",
                WebApiOperationClass.NON_IDEMPOTENT_WRITE,
            ),
        ) { Unit }
    }

    private companion object {
        val SPOTIFY_ID = Regex("[A-Za-z0-9]+")
    }
}

internal fun isLocalPlaylistItem(
    playlistItemIsLocal: Boolean,
    itemIsLocal: Boolean,
    uri: String?,
): Boolean = playlistItemIsLocal || itemIsLocal || uri?.startsWith("spotify:local:") == true
