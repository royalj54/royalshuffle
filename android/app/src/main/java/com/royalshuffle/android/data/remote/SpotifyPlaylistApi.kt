package com.royalshuffle.android.data.remote

import com.royalshuffle.android.domain.model.Playlist
import com.royalshuffle.android.playlist.PlaylistApi
import com.royalshuffle.android.playlist.PlaylistException
import com.royalshuffle.android.playlist.PlaylistPage
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

class SpotifyPlaylistApi : PlaylistApi {
    override suspend fun getPlaylistsPage(url: String, accessToken: String): PlaylistPage =
        withContext(Dispatchers.IO) {
            validateUrl(url)
            val connection = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = TIMEOUT_MILLIS
                readTimeout = TIMEOUT_MILLIS
                setRequestProperty("Authorization", "Bearer $accessToken")
                setRequestProperty("Accept", "application/json")
            }

            try {
                if (connection.responseCode !in 200..299) {
                    throw PlaylistException(PlaylistException.Reason.NETWORK)
                }
                val json = connection.inputStream.bufferedReader(Charsets.UTF_8).use {
                    JSONObject(it.readText())
                }
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
            } catch (error: PlaylistException) {
                throw error
            } catch (error: Exception) {
                throw PlaylistException(PlaylistException.Reason.NETWORK)
            } finally {
                connection.disconnect()
            }
        }

    private fun validateUrl(url: String) {
        val uri = try {
            URI(url)
        } catch (error: Exception) {
            throw PlaylistException(PlaylistException.Reason.INVALID_PAGINATION)
        }
        if (uri.scheme != "https" || uri.host != "api.spotify.com" || uri.userInfo != null) {
            throw PlaylistException(PlaylistException.Reason.INVALID_PAGINATION)
        }
    }

    private companion object {
        const val TIMEOUT_MILLIS = 15_000
    }
}
