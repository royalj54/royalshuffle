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
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

class CreateOutputPlaylist(
    private val accessTokenProvider: AccessTokenProvider,
    private val api: OutputPlaylistApi,
    private val preferences: PlaylistPreferences,
    shuffler: OccurrenceShuffler,
    private val diagnostics: DiagnosticLogger = NoOpDiagnosticLogger,
    private val planner: OutputPlanner = OutputPlanner(shuffler),
    private val registry: OrdinaryOutputRegistry,
) {
    suspend fun execute(
        source: Playlist,
        options: OutputOptions = OutputOptions(),
        nameProvider: CreationNameProvider = RequiredCreationName,
        onProgress: (OutputProgress) -> Unit = {},
    ): OutputResult? {
        options.validate()
        val identity = OutputIdentity.from(source.id, options)
        val expectedOutputId = registry.boundOutput(identity)
        if (source.id in preferences.loadManagedPlaylistIds()) {
            throw OutputRegistryException("Managed outputs cannot be used as source playlists.")
        }
        val accessToken = accessTokenProvider.getValidAccessToken()
            ?: throw OutputPlaylistException(OutputPlaylistException.Reason.NOT_AUTHENTICATED)
        currentCoroutineContext().ensureActive()

        onProgress(OutputProgress.LoadingItems)
        val items = loadAllItems(source.id, accessToken)
        currentCoroutineContext().ensureActive()
        onProgress(OutputProgress.Shuffling(items.size))
        val plan = planner.plan(items, options)
        val skippedLocalItemCount = plan.skippedLocalItemCount
        val shuffledUris = plan.items.map { it.uri!! }
        diagnostics.recordSafely(
            DiagnosticEvent(
                eventName = "playlist_items_filtered",
                intendedItems = shuffledUris.size,
                skippedItems = skippedLocalItemCount + plan.skippedUnsupportedItemCount,
            ),
        )
        currentCoroutineContext().ensureActive()
        onProgress(OutputProgress.ResolvingOutput)
        val existing = expectedOutputId?.let { resolveOutput(it, accessToken) }
        val action = if (existing == null) OutputAction.CREATED else OutputAction.UPDATED
        val output = if (existing != null) existing else {
            // A confirmed-missing association remains intact throughout naming and creation.
            val chosen = nameProvider.chooseName(defaultOutputName(source.name, options)) ?: return null
            val name = chosen.trim()
            if (name.isEmpty()) throw OutputPlanningException("Playlist name cannot be blank.")
            currentCoroutineContext().ensureActive()
            registry.requireCurrent(identity, expectedOutputId)
            onProgress(OutputProgress.CreatingPlaylist)
            val created = api.createPrivatePlaylist(name, OUTPUT_DESCRIPTION, accessToken)
            currentCoroutineContext().ensureActive()
            if (created.id == source.id) {
                throw OutputPlaylistException(OutputPlaylistException.Reason.SOURCE_OUTPUT_ID_COLLISION)
            }
            try {
                registry.bind(identity, created.id, expectedOutputId)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                diagnostics.recordSafely(DiagnosticEvent(
                    eventName = "managed_playlist_registration_failed",
                    intendedItems = shuffledUris.size,
                    exceptionClass = error::class.java.simpleName,
                ))
                throw ManagedPlaylistRegistrationException(created.id, created.name, error)
            }
            created
        }

        currentCoroutineContext().ensureActive()
        registry.requireCurrent(identity, output.id)
        if (output.id == source.id) {
            throw OutputPlaylistException(OutputPlaylistException.Reason.SOURCE_OUTPUT_ID_COLLISION)
        }
        if (action == OutputAction.UPDATED) {
            onProgress(OutputProgress.ReplacingItems)
            try {
                api.clearItems(output.id, accessToken)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                throw PartialPlaylistWriteException(output.id, output.name, 0, shuffledUris.size,
                    (error as? OutputPlaylistException)?.reason,
                    (error as? SpotifyWebApiException)?.category, error,
                    action, OutputWriteStage.CLEAR)
            }
        }

        var added = 0
        shuffledUris.chunked(MAX_BATCH_SIZE).forEachIndexed { batchIndex, batch ->
            currentCoroutineContext().ensureActive()
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
                    action = action,
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

        currentCoroutineContext().ensureActive()
        return OutputResult(output, shuffledUris.size, skippedLocalItemCount,
            plan.skippedUnsupportedItemCount, plan.requestedDurationMs, plan.durationMs,
            plan.sourceShorterThanTarget, action)
    }

    private suspend fun resolveOutput(outputId: String, accessToken: String): Playlist? {
        var next: String? = "https://api.spotify.com/v1/me/playlists?limit=50"
        val visited = mutableSetOf<String>()
        while (next != null) {
            currentCoroutineContext().ensureActive()
            if (!visited.add(next)) throw OutputPlaylistException(OutputPlaylistException.Reason.INVALID_PAGINATION)
            val page = api.getPlaylistsPage(next, accessToken)
            page.playlists.firstOrNull { it.id == outputId }?.let {
                // Fresh authenticated /me/playlists is the user's owned/followed list.
                // The persisted exact-ID binding supplies managed-output provenance.
                return it
            }
            next = page.nextUrl
        }
        val readable = try {
            api.getPlaylist(outputId, accessToken).also {
                if (it.id != outputId) throw OutputRegistryException("Managed output lookup returned a different identity.")
            }
        } catch (error: SpotifyWebApiException) {
            if (error.httpStatus == 404) null else throw error
        }
        // Spotify's "delete" is unfollow: the exact old ID can remain readable.
        // Only an explicit false confirms removal. Errors must never authorize replacement.
        return readable?.takeIf { api.isPlaylistSaved(outputId, accessToken) }
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
        const val OUTPUT_DESCRIPTION = "Randomized by RoyalShuffle | Spotify Companion"
        const val LEGACY_OUTPUT_DESCRIPTION = "True-randomized copy generated by RoyalShuffle"
        const val MAX_BATCH_SIZE = 100
    }
}
