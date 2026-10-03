package com.royalshuffle.android.output

import com.royalshuffle.android.data.remote.SpotifyWebApiException
import com.royalshuffle.android.domain.model.Playlist
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** Accepted Catch-Up B exact-ID listing/membership semantics, shared without extra retries. */
class ManagedOutputResolver(private val api: OutputPlaylistApi) {
    suspend fun resolve(outputId: String, accessToken: String): Playlist? {
        var next: String? = "https://api.spotify.com/v1/me/playlists?limit=50"
        val visited = mutableSetOf<String>()
        while (next != null) {
            currentCoroutineContext().ensureActive()
            if (!visited.add(next)) throw OutputPlaylistException(OutputPlaylistException.Reason.INVALID_PAGINATION)
            val page = api.getPlaylistsPage(next, accessToken)
            page.playlists.firstOrNull { it.id == outputId }?.let { return it }
            next = page.nextUrl
        }
        return resolveDirect(outputId, accessToken)
    }

    /** Used to confirm a readback 404 independently of a possibly earlier listing hit. */
    suspend fun resolveDirect(outputId: String, accessToken: String): Playlist? {
        val readable = try {
            api.getPlaylist(outputId, accessToken).also {
                if (it.id != outputId) throw OutputRegistryException("Managed output lookup returned a different identity.")
            }
        } catch (error: SpotifyWebApiException) {
            if (error.httpStatus == 404) null else throw error
        }
        return readable?.takeIf { api.isPlaylistSaved(outputId, accessToken) }
    }
}
