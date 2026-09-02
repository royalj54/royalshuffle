package com.royalshuffle.android.playlist

import com.royalshuffle.android.auth.AccessTokenProvider
import com.royalshuffle.android.diagnostics.DiagnosticEvent
import com.royalshuffle.android.diagnostics.DiagnosticLogger
import com.royalshuffle.android.diagnostics.NoOpDiagnosticLogger
import com.royalshuffle.android.diagnostics.recordSafely
import com.royalshuffle.android.output.CreateOutputPlaylist

class PlaylistRepository(
    private val accessTokenProvider: AccessTokenProvider,
    private val playlistApi: PlaylistApi,
    private val preferences: PlaylistPreferences,
    private val diagnostics: DiagnosticLogger = NoOpDiagnosticLogger,
) {
    suspend fun loadEligiblePlaylists(): PlaylistLoadResult {
        val accessToken = accessTokenProvider.getValidAccessToken()
            ?: throw PlaylistException(PlaylistException.Reason.NOT_AUTHENTICATED)
        val playlists = buildList {
            var pageNumber = 0
            var nextUrl: String? = INITIAL_URL
            val visitedUrls = mutableSetOf<String>()
            while (nextUrl != null) {
                if (!visitedUrls.add(nextUrl)) {
                    throw PlaylistException(PlaylistException.Reason.INVALID_PAGINATION)
                }
                val page = playlistApi.getPlaylistsPage(nextUrl, accessToken)
                pageNumber += 1
                addAll(page.playlists)
                diagnostics.recordSafely(
                    DiagnosticEvent(
                        eventName = "playlist_page_loaded",
                        operationName = "load_current_user_playlists",
                        operationClass = "READ",
                        pageNumber = pageNumber,
                        intendedItems = size,
                    ),
                )
                nextUrl = page.nextUrl
            }
        }

        val managedIds = preferences.loadManagedPlaylistIds()
        val declinedIds = preferences.loadDeclinedRecoveryPlaylistIds()
        val candidates = playlists.filter {
            it.id !in managedIds &&
                it.id !in declinedIds &&
                it.description == CreateOutputPlaylist.OUTPUT_DESCRIPTION
        }
        diagnostics.recordSafely(
            DiagnosticEvent(
                eventName = "managed_playlist_recovery_candidates_detected",
                intendedItems = candidates.size,
            ),
        )
        val candidateIds = candidates.mapTo(mutableSetOf()) { it.id }
        val eligible = playlists.filterNot { it.id in managedIds || it.id in candidateIds }
        val savedSelection = preferences.loadSelectedPlaylistId()
        val restoredSelection = savedSelection?.takeIf { selectedId ->
            eligible.any { it.id == selectedId }
        }
        if (savedSelection != null && restoredSelection == null) {
            preferences.clearSelectedPlaylistId()
        }

        return PlaylistLoadResult(
            playlists = eligible,
            selectedPlaylistId = restoredSelection,
            recoveryCandidates = candidates,
            playlistsIfRecoveryDeclined = playlists.filterNot { it.id in managedIds },
        )
    }

    suspend fun recoverCandidates(playlistIds: Set<String>): Boolean {
        diagnostics.recordSafely(
            DiagnosticEvent(
                eventName = "managed_playlist_recovery_chosen",
                intendedItems = playlistIds.size,
            ),
        )
        val succeeded = preferences.addManagedPlaylistIds(playlistIds)
        diagnostics.recordSafely(
            DiagnosticEvent(
                eventName = if (succeeded) {
                    "managed_playlist_recovery_persisted"
                } else {
                    "managed_playlist_recovery_persistence_failed"
                },
                intendedItems = playlistIds.size,
            ),
        )
        return succeeded
    }

    suspend fun declineCandidates(playlistIds: Set<String>): Boolean {
        diagnostics.recordSafely(
            DiagnosticEvent(
                eventName = "managed_playlist_recovery_declined",
                intendedItems = playlistIds.size,
            ),
        )
        val succeeded = preferences.addDeclinedRecoveryPlaylistIds(playlistIds)
        if (!succeeded) {
            diagnostics.recordSafely(
                DiagnosticEvent(
                    eventName = "managed_playlist_recovery_decline_persistence_failed",
                    intendedItems = playlistIds.size,
                ),
            )
        }
        return succeeded
    }

    fun selectPlaylist(playlistId: String) {
        preferences.saveSelectedPlaylistId(playlistId)
    }

    fun clearSelection() {
        preferences.clearSelectedPlaylistId()
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
