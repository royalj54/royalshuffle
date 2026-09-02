package com.royalshuffle.android.output

import com.royalshuffle.android.auth.AccessTokenProvider
import com.royalshuffle.android.data.remote.SpotifyWebApiException
import com.royalshuffle.android.domain.model.Playlist
import com.royalshuffle.android.diagnostics.DiagnosticEvent
import com.royalshuffle.android.diagnostics.DiagnosticLogger
import com.royalshuffle.android.diagnostics.NoOpDiagnosticLogger
import com.royalshuffle.android.diagnostics.recordSafely
import com.royalshuffle.android.playlist.PlaylistPreferences
import kotlinx.coroutines.CancellationException

class CreateOutputPlaylist(
    private val accessTokenProvider: AccessTokenProvider,
    private val api: OutputPlaylistApi,
    private val preferences: PlaylistPreferences,
    private val shuffler: UriShuffler,
    private val diagnostics: DiagnosticLogger = NoOpDiagnosticLogger,
) {
    suspend fun execute(
        source: Playlist,
        onProgress: (OutputProgress) -> Unit = {},
    ): OutputResult {
        val accessToken = accessTokenProvider.getValidAccessToken()
            ?: throw OutputPlaylistException(OutputPlaylistException.Reason.NOT_AUTHENTICATED)

        onProgress(OutputProgress.LoadingItems)
        val items = loadAllItems(source.id, accessToken)
        val skippedLocalItemCount = items.count(OutputPlaylistItem::isLocal)
        val uris = items.mapNotNull { item ->
            item.uri?.takeIf { it.isNotBlank() && !item.isLocal }
        }
        diagnostics.recordSafely(
            DiagnosticEvent(
                eventName = "playlist_items_filtered",
                intendedItems = uris.size,
                skippedItems = skippedLocalItemCount,
            ),
        )
        onProgress(OutputProgress.Shuffling(uris.size))
        val shuffledUris = shuffler.shuffle(uris.toList())

        onProgress(OutputProgress.CreatingPlaylist)
        val output = api.createPrivatePlaylist(
            name = "${source.name} - RANDOM",
            description = OUTPUT_DESCRIPTION,
            accessToken = accessToken,
        )
        if (output.id == source.id) {
            throw OutputPlaylistException(OutputPlaylistException.Reason.SOURCE_OUTPUT_ID_COLLISION)
        }
        if (!preferences.addManagedPlaylistId(output.id)) {
            diagnostics.recordSafely(
                DiagnosticEvent(
                    eventName = "managed_playlist_registration_failed",
                    intendedItems = shuffledUris.size,
                ),
            )
            throw ManagedPlaylistRegistrationException(output.id, output.name)
        }

        var added = 0
        shuffledUris.chunked(MAX_BATCH_SIZE).forEachIndexed { batchIndex, batch ->
            try {
                api.addItems(output.id, batch, accessToken)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                diagnostics.recordSafely(
                    DiagnosticEvent(
                        eventName = "playlist_population_failed",
                        operationName = "add_playlist_items",
                        operationClass = "NON_IDEMPOTENT_WRITE",
                        failureCategory = (error as? SpotifyWebApiException)?.category?.name,
                        batchNumber = batchIndex + 1,
                        confirmedItems = added,
                        intendedItems = shuffledUris.size,
                        skippedItems = skippedLocalItemCount,
                        exceptionClass = error::class.java.simpleName,
                    ),
                )
                throw PartialPlaylistWriteException(
                    outputPlaylistId = output.id,
                    outputPlaylistName = output.name,
                    confirmedItemsWritten = added,
                    totalItemsIntended = shuffledUris.size,
                    underlyingReason = (error as? OutputPlaylistException)?.reason,
                    underlyingFailureCategory = (error as? SpotifyWebApiException)?.category,
                    cause = error,
                )
            }
            added += batch.size
            onProgress(OutputProgress.AddingItems(added, shuffledUris.size))
        }

        diagnostics.recordSafely(
            DiagnosticEvent(
                eventName = "playlist_population_completed",
                operationName = "add_playlist_items",
                operationClass = "NON_IDEMPOTENT_WRITE",
                confirmedItems = added,
                intendedItems = shuffledUris.size,
                skippedItems = skippedLocalItemCount,
            ),
        )

        return OutputResult(output, shuffledUris.size, skippedLocalItemCount)
    }

    private suspend fun loadAllItems(
        sourceId: String,
        accessToken: String,
    ): List<OutputPlaylistItem> =
        buildList {
            var pageNumber = 0
            var nextUrl: String? = "https://api.spotify.com/v1/playlists/$sourceId/items?limit=50"
            val visitedUrls = mutableSetOf<String>()
            while (nextUrl != null) {
                if (!visitedUrls.add(nextUrl)) {
                    throw OutputPlaylistException(OutputPlaylistException.Reason.INVALID_PAGINATION)
                }
                val page = api.getPlaylistItemsPage(nextUrl, accessToken)
                pageNumber += 1
                addAll(page.items)
                diagnostics.recordSafely(
                    DiagnosticEvent(
                        eventName = "playlist_items_page_loaded",
                        operationName = "load_playlist_items",
                        operationClass = "READ",
                        pageNumber = pageNumber,
                        intendedItems = size,
                    ),
                )
                nextUrl = page.nextUrl
            }
        }

    companion object {
        const val OUTPUT_DESCRIPTION = "True-randomized copy generated by RoyalShuffle"
        const val MAX_BATCH_SIZE = 100
    }
}
