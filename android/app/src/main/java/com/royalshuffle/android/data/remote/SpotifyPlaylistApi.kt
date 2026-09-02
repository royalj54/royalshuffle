package com.royalshuffle.android.data.remote

import com.royalshuffle.android.domain.model.Playlist
import com.royalshuffle.android.playlist.PlaylistApi
import com.royalshuffle.android.playlist.PlaylistPage

class SpotifyPlaylistApi(
    private val webApi: SpotifyWebApiClient = SpotifyWebApiClient(),
) : PlaylistApi {
    override suspend fun getPlaylistsPage(url: String, accessToken: String): PlaylistPage =
        webApi.requestJson(
            request = WebApiRequest("GET", url, accessToken),
            operation = WebApiOperation(
                "playlist list page fetch",
                WebApiOperationClass.READ,
            ),
        ) { json ->
                val items = json.getJSONArray("items")
                val playlists = buildList {
                    for (index in 0 until items.length()) {
                        val item = items.optJSONObject(index) ?: continue
                        val id = item.optString("id").takeIf { it.isNotBlank() } ?: continue
                        val name = item.optString("name").takeIf { it.isNotBlank() } ?: continue
                        add(Playlist(id = id, name = name))
                    }
                }
                val nextUrl = if (json.isNull("next")) null else json.getString("next")
                PlaylistPage(playlists, nextUrl)
        }
}
