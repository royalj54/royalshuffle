package com.royalshuffle.android.data.remote

import com.royalshuffle.android.domain.model.Playlist
import com.royalshuffle.android.output.OutputPlaylistApi
import com.royalshuffle.android.output.OutputPlaylistException
import com.royalshuffle.android.output.PlaylistItemsPage
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

class SpotifyOutputPlaylistApi : OutputPlaylistApi {
    override suspend fun getPlaylistItemsPage(
        url: String,
        accessToken: String,
    ): PlaylistItemsPage = request("GET", url, accessToken) { json ->
        val items = json.getJSONArray("items")
        val uris = buildList<String?> {
            for (index in 0 until items.length()) {
                val playlistItem = items.optJSONObject(index)
                val item = playlistItem?.optJSONObject("item")
                add(item?.optString("uri")?.takeIf { it.isNotBlank() })
            }
        }
        val nextUrl = if (json.isNull("next")) null else json.getString("next")
        PlaylistItemsPage(uris, nextUrl)
    }

    override suspend fun createPrivatePlaylist(
        name: String,
        description: String,
        accessToken: String,
    ): Playlist = request(
        method = "POST",
        url = "https://api.spotify.com/v1/me/playlists",
        accessToken = accessToken,
        body = JSONObject()
            .put("name", name)
            .put("public", false)
            .put("description", description)
            .toString(),
    ) { json ->
        val id = json.optString("id").takeIf { it.isNotBlank() }
            ?: throw OutputPlaylistException(OutputPlaylistException.Reason.NETWORK)
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
        request(
            method = "POST",
            url = "https://api.spotify.com/v1/playlists/$playlistId/items",
            accessToken = accessToken,
            body = JSONObject().put("uris", JSONArray(uris)).toString(),
        ) { Unit }
    }

    private suspend fun <T> request(
        method: String,
        url: String,
        accessToken: String,
        body: String? = null,
        transform: (JSONObject) -> T,
    ): T = withContext(Dispatchers.IO) {
        validateUrl(url)
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = TIMEOUT_MILLIS
            readTimeout = TIMEOUT_MILLIS
            setRequestProperty("Authorization", "Bearer $accessToken")
            setRequestProperty("Accept", "application/json")
            if (body != null) {
                doOutput = true
                setRequestProperty("Content-Type", "application/json")
            }
        }
        try {
            if (body != null) {
                connection.outputStream.bufferedWriter(Charsets.UTF_8).use { it.write(body) }
            }
            if (connection.responseCode !in 200..299) {
                throw OutputPlaylistException(OutputPlaylistException.Reason.NETWORK)
            }
            val response = connection.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            transform(if (response.isBlank()) JSONObject() else JSONObject(response))
        } catch (error: OutputPlaylistException) {
            throw error
        } catch (error: Exception) {
            throw OutputPlaylistException(OutputPlaylistException.Reason.NETWORK)
        } finally {
            connection.disconnect()
        }
    }

    private fun validateUrl(url: String) {
        val uri = try {
            URI(url)
        } catch (error: Exception) {
            throw OutputPlaylistException(OutputPlaylistException.Reason.INVALID_PAGINATION)
        }
        if (uri.scheme != "https" || uri.host != "api.spotify.com" || uri.userInfo != null) {
            throw OutputPlaylistException(OutputPlaylistException.Reason.INVALID_PAGINATION)
        }
    }

    private companion object {
        const val TIMEOUT_MILLIS = 15_000
        val SPOTIFY_ID = Regex("[A-Za-z0-9]+")
    }
}
