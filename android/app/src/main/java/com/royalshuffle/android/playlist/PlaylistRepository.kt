package com.royalshuffle.android.playlist

import com.royalshuffle.android.auth.AccessTokenProvider

class PlaylistRepository(
    private val accessTokenProvider: AccessTokenProvider,
    private val playlistApi: PlaylistApi,
    private val preferences: PlaylistPreferences,
) {
    suspend fun loadEligiblePlaylists(): PlaylistLoadResult {
        val accessToken = accessTokenProvider.getValidAccessToken()
            ?: throw PlaylistException(PlaylistException.Reason.NOT_AUTHENTICATED)
        val playlists = buildList {
            var nextUrl: String? = INITIAL_URL
            val visitedUrls = mutableSetOf<String>()
            while (nextUrl != null) {
                if (!visitedUrls.add(nextUrl)) {
                    throw PlaylistException(PlaylistException.Reason.INVALID_PAGINATION)
                }
                val page = playlistApi.getPlaylistsPage(nextUrl, accessToken)
                addAll(page.playlists)
                nextUrl = page.nextUrl
            }
        }

        val managedIds = preferences.loadManagedPlaylistIds()
        val eligible = playlists.filterNot { it.id in managedIds }
        val savedSelection = preferences.loadSelectedPlaylistId()
        val restoredSelection = savedSelection?.takeIf { selectedId ->
            eligible.any { it.id == selectedId }
        }
        if (savedSelection != null && restoredSelection == null) {
            preferences.clearSelectedPlaylistId()
        }

        return PlaylistLoadResult(eligible, restoredSelection)
    }

    fun selectPlaylist(playlistId: String) {
        preferences.saveSelectedPlaylistId(playlistId)
    }

    private companion object {
        const val INITIAL_URL = "https://api.spotify.com/v1/me/playlists?limit=50"
    }
}

class PlaylistException(
    val reason: Reason,
) : Exception() {
    enum class Reason {
        INVALID_PAGINATION,
        NETWORK,
        NOT_AUTHENTICATED,
    }
}
