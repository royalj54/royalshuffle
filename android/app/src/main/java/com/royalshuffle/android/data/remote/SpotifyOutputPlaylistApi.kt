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
    override suspend fun isPlaylistSaved(playlistId: String, accessToken: String): Boolean {
        require(playlistId.matches(SPOTIFY_ID))
        return webApi.requestJsonArray(
            WebApiRequest("GET", "https://api.spotify.com/v1/me/library/contains?uris=spotify%3Aplaylist%3A$playlistId", accessToken),
            WebApiOperation("managed output library membership", WebApiOperationClass.READ),
        ) { json ->
            require(json.length() == 1)
            json.get(0) as Boolean
        }
    }

    override suspend fun getPlaylistsPage(url: String, accessToken: String) =
        SpotifyPlaylistApi(webApi).getPlaylistsPage(url, accessToken)

    override suspend fun getPlaylist(playlistId: String, accessToken: String): Playlist {
        require(playlistId.matches(SPOTIFY_ID))
        return webApi.requestJson(
            WebApiRequest("GET", "https://api.spotify.com/v1/playlists/$playlistId", accessToken),
            WebApiOperation("managed output lookup", WebApiOperationClass.READ),
        ) { json ->
            val id = json.get("id") as String
            val name = json.get("name") as String
            require(id == playlistId)
            Playlist(id, name)
        }
    }

    override suspend fun clearItems(playlistId: String, accessToken: String) {
        require(playlistId.matches(SPOTIFY_ID))
        webApi.requestJson(
            WebApiRequest("PUT", "https://api.spotify.com/v1/playlists/$playlistId/items", accessToken,
                JSONObject().put("uris", JSONArray()).toString()),
            WebApiOperation("managed output clear", WebApiOperationClass.NON_IDEMPOTENT_WRITE),
        ) { Unit }
    }
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
                val uri = (item?.opt("uri") as? String)?.takeIf { it.isNotBlank() }
                add(
                    OutputPlaylistItem(
                        uri = uri,
                        durationMs = positiveIntegerDuration(item?.opt("duration_ms")),
                        primaryArtistId = item?.optJSONArray("artists")
                            ?.optJSONObject(0)?.opt("id") as? String,
                        itemType = if (item == null || item.isNull("type")) null
                            else (item.opt("type") as? String) ?: "unsupported",
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

// JSONObject's optLong coerces strings/fractions; preserve the integer contract instead.
internal fun positiveIntegerDuration(value: Any?): Long? = when (value) {
    is Int -> value.toLong().takeIf { it > 0 }
    is Long -> value.takeIf { it > 0 }
    else -> null
}

internal fun isLocalPlaylistItem(
    playlistItemIsLocal: Boolean,
    itemIsLocal: Boolean,
    uri: String?,
): Boolean = playlistItemIsLocal || itemIsLocal || uri?.startsWith("spotify:local:") == true
