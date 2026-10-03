package com.royalshuffle.android.opportunity

import com.royalshuffle.android.auth.AccessTokenProvider
import com.royalshuffle.android.data.remote.SpotifyWebApiException
import com.royalshuffle.android.domain.model.Playlist
import com.royalshuffle.android.output.*
import com.royalshuffle.android.playlist.PlaylistPreferences
import com.royalshuffle.android.diagnostics.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

class OpportunityPendingException(message: String) : IllegalStateException(message)
class OpportunityUnknownCreationException(val returnedOutputId: String? = null, cause: Throwable? = null) :
    IllegalStateException("Playlist creation outcome is unresolved; automatic creation retry is unsafe.", cause)

data class OpportunityRotationConfirmation(val sourceId: String, val activeRotationId: String?, val activeSnapshot: String?,
    val pendingOperationId: String?, val remainingUniqueCount: Int)
data class OpportunityProgress(val originalUniqueCount: Int, val remainingUniqueCount: Int, val completedDealCount: Int)
data class OpportunityResult(val output: Playlist, val uris: List<String>, val progress: OpportunityProgress)
sealed interface OpportunityWorkflowProgress {
    data object LoadingSource : OpportunityWorkflowProgress
    data object ChoosingName : OpportunityWorkflowProgress
    data object Creating : OpportunityWorkflowProgress
    data object Verifying : OpportunityWorkflowProgress
    data object Clearing : OpportunityWorkflowProgress
    data class Appending(val batchNumber: Int, val total: Int) : OpportunityWorkflowProgress
    data object Finalizing : OpportunityWorkflowProgress
}

/** Workflow owns the process lease. Stores/APIs never acquire a second workflow lease. */
class OpportunityWorkflow(
    private val tokens: AccessTokenProvider,
    private val api: OpportunityPlaylistApi,
    private val store: OpportunityStore,
    private val planner: OpportunityPlanner,
    private val preferences: PlaylistPreferences,
    private val ordinaryRegistry: OrdinaryOutputRegistry,
    private val coordinator: ManagedMutationCoordinator = ManagedMutationCoordinator.Process,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val diagnostics: DiagnosticLogger = NoOpDiagnosticLogger,
) {
    private val resolver = ManagedOutputResolver(api)

    /** Local restoration only: never starts a delivery or contacts Spotify. */
    suspend fun inspectSources(): Map<String, OpportunitySource> = withContext(ioDispatcher) {
        store.load().sources
    }

    suspend fun inspectSource(sourceId: String): OpportunitySource? = withContext(ioDispatcher) {
        checkState(validId(sourceId), "Invalid source playlist ID.")
        store.load().sources[sourceId]
    }

    suspend fun inspectNewRotation(sourceId: String): OpportunityRotationConfirmation = withContext(ioDispatcher) {
        confirmation(sourceId, store.load().sources[sourceId])
    }

    suspend fun generateNextSession(source: Playlist, nameProvider: CreationNameProvider,
        artistSeparation: Boolean = false, onProgress: (OpportunityWorkflowProgress) -> Unit = {}): OpportunityResult? =
        coordinator.workflow { withContext(ioDispatcher) { generate(source, false, null, nameProvider, artistSeparation, onProgress) } }

    suspend fun startNewRotation(source: Playlist, nameProvider: CreationNameProvider,
        confirmation: OpportunityRotationConfirmation? = null, artistSeparation: Boolean = false,
        onProgress: (OpportunityWorkflowProgress) -> Unit = {}): OpportunityResult? =
        coordinator.workflow { withContext(ioDispatcher) { generate(source, true, confirmation, nameProvider, artistSeparation, onProgress) } }

    /** Exact pending token is the caller's explicit abandonment authorization. No remote calls. */
    suspend fun abandonPendingSession(sourceId: String, operationId: String) = coordinator.workflow {
        withContext(ioDispatcher) { store.abandonPending(sourceId, operationId) }
    }

    private fun confirmation(sourceId: String, source: OpportunitySource?): OpportunityRotationConfirmation {
        val selected = source?.pending?.candidate ?: source?.active
        return OpportunityRotationConfirmation(sourceId, source?.active?.rotationId, source?.active?.let(OpportunityCodec::fingerprint),
            source?.pending?.operationId, selected?.undealt?.size ?: 0)
    }

    private suspend fun generate(source: Playlist, startNew: Boolean, confirmed: OpportunityRotationConfirmation?,
        names: CreationNameProvider, artistSeparation: Boolean, progress: (OpportunityWorkflowProgress) -> Unit): OpportunityResult? {
        checkState(validId(source.id), "Invalid source playlist ID.")
        val state = store.load()
        val saved = state.sources[source.id]
        if (!startNew && saved?.pending != null) throw OpportunityPendingException("Resume the pending session first.")
        if (startNew) {
            val needsConfirmation = saved?.pending != null || saved?.active?.exhausted == false
            if ((needsConfirmation || confirmed != null) && confirmed != confirmation(source.id, saved)) {
                throw OpportunityPendingException("Confirm abandonment of the current rotation/pending session again.")
            }
        }
        requireSource(source.id)
        val token = accessToken()
        val active = saved?.active
        val candidate = if (startNew || active == null || active.exhausted) {
            progress(OpportunityWorkflowProgress.LoadingSource)
            planner.candidate(loadSource(source.id, token))
        } else null
        val selected = candidate ?: active!!
        val deal = planner.prepare(source.id, selected, artistSeparation)
        progress(OpportunityWorkflowProgress.ChoosingName)
        checkState(source.name.isNotBlank(), "Source name is required for deal naming.")
        val chosen = names.chooseName("${source.name} - Deal ${selected.completedDealCount + 1}") ?: return null
        val name = chosen.trim()
        checkState(name.isNotEmpty(), "Playlist name cannot be blank.")
        currentCoroutineContext().ensureActive()
        requireSource(source.id)
        val pending = store.stage(deal, name, candidate, if (startNew) confirmed?.pendingOperationId else null)
        return deliver(source.id, pending, null, token, progress)
    }

    suspend fun resumePendingSession(sourceId: String, nameProvider: CreationNameProvider,
        onProgress: (OpportunityWorkflowProgress) -> Unit = {},
        expectedOperationId: String? = null): OpportunityResult? = coordinator.workflow {
        withContext(ioDispatcher) {
            var pending = store.load().sources[sourceId]?.pending ?: throw OpportunityPendingException("No pending session to resume.")
            if (expectedOperationId != null && pending.operationId != expectedOperationId) {
                throw OpportunityPendingException("Pending operation changed.")
            }
            // Durable submission proof wins over authentication, listing, later edits, and deletion.
            if (pending.attempt?.complete == true) {
                if (pending.phase != OpportunityPhase.DELIVERED) pending = store.markAcknowledged(sourceId, pending.operationId)
                return@withContext finalize(sourceId, pending, Playlist(pending.outputId!!, pending.creationName), onProgress)
            }
            if (pending.phase == OpportunityPhase.CREATING) throw OpportunityUnknownCreationException()
            val token = accessToken()
            if (pending.outputId == null) return@withContext deliver(sourceId, pending, null, token, onProgress)
            val outputId = pending.outputId
            requirePendingOutput(sourceId, pending)
            var playlist = resolver.resolve(outputId, token)
            if (playlist != null) {
                onProgress(OpportunityWorkflowProgress.Verifying)
                val actual = try { OpportunityRawVerifier(api).read(outputId, token) } catch (error: SpotifyWebApiException) {
                    if (error.httpStatus != 404) throw error
                    playlist = resolver.resolveDirect(outputId, token)
                    if (playlist != null) throw OpportunityPendingException("Output changed during verification; resume later.")
                    null
                }
                if (actual != null) {
                    if (actual == pending.uris) {
                        pending = store.confirmExactContents(sourceId, pending.operationId, outputId, actual)
                        return@withContext finalize(sourceId, pending, playlist!!, onProgress)
                    }
                    diagnostics.recordSafely(DiagnosticEvent(eventName = "opportunity_exact_readback_mismatch",
                        operationName = "Opportunity recovery", operationClass = "READ", intendedItems = pending.uris.size,
                        confirmedItems = actual.size))
                    return@withContext deliver(sourceId, pending, playlist, token, onProgress)
                }
            }
            // Confirmed stale replacement preserves membership/order/candidate. Cancel preserves old intent/ID.
            val chosen = nameProvider.chooseName(pending.creationName) ?: return@withContext null
            val name = chosen.trim()
            checkState(name.isNotEmpty(), "Playlist name cannot be blank.")
            currentCoroutineContext().ensureActive()
            pending = store.stageConfirmedMissingOutput(sourceId, pending.operationId, outputId, name)
            deliver(sourceId, pending, null, token, onProgress)
        }
    }

    private suspend fun deliver(sourceId: String, original: OpportunityPending, known: Playlist?, token: String,
        progress: (OpportunityWorkflowProgress) -> Unit): OpportunityResult {
        var pending = original
        var playlist = known
        if (pending.outputId == null) {
            currentCoroutineContext().ensureActive()
            pending = store.markCreating(sourceId, pending.operationId)
            progress(OpportunityWorkflowProgress.Creating)
            val created = try { api.createDeal(pending.creationName, token) } catch (error: CancellationException) {
                throw error
            } catch (error: SpotifyWebApiException) {
                if (error.httpStatus in 400..499) store.recordCreationRejected(sourceId, pending.operationId)
                else throw OpportunityUnknownCreationException(cause = error)
                throw error
            } catch (error: Exception) { throw OpportunityUnknownCreationException(cause = error) }
            // This synchronous durable write runs immediately after the response, before cancellation checks/population.
            try { pending = store.recordCreatedOutput(sourceId, pending.operationId, created.id) }
            catch (error: Exception) { throw OpportunityUnknownCreationException(created.id, error) }
            playlist = created
        }
        requirePendingOutput(sourceId, pending)
        currentCoroutineContext().ensureActive()
        pending = store.beginDeliveryAttempt(sourceId, pending.operationId)
        val attemptId = pending.attempt!!.attemptId
        val outputId = pending.outputId!!
        progress(OpportunityWorkflowProgress.Clearing)
        val clear = api.clearDeal(outputId, token)
        pending = store.appendReceipt(sourceId, pending.operationId, attemptId,
            OpportunityReceipt(0, OpportunityWriteMethod.CLEAR, 0, 0, clear.status, clear.snapshotId))
        pending.uris.chunked(100).forEachIndexed { index, batch ->
            currentCoroutineContext().ensureActive()
            requirePendingOutput(sourceId, pending)
            progress(OpportunityWorkflowProgress.Appending(index + 1, pending.uris.size))
            val ack = api.appendDeal(outputId, batch, token)
            val start = index * 100
            pending = store.appendReceipt(sourceId, pending.operationId, attemptId,
                OpportunityReceipt(index + 1, OpportunityWriteMethod.APPEND, start, start + batch.size, ack.status, ack.snapshotId))
        }
        pending = store.markAcknowledged(sourceId, pending.operationId)
        return finalize(sourceId, pending, playlist ?: Playlist(outputId, pending.creationName), progress)
    }

    private fun finalize(sourceId: String, pending: OpportunityPending, playlist: Playlist,
        progress: (OpportunityWorkflowProgress) -> Unit): OpportunityResult {
        progress(OpportunityWorkflowProgress.Finalizing)
        val active = store.finalize(sourceId, pending.operationId)
        diagnostics.recordSafely(DiagnosticEvent(eventName = "opportunity_session_committed",
            operationName = "Opportunity delivery", operationClass = "NON_IDEMPOTENT_WRITE", confirmedItems = pending.uris.size))
        return OpportunityResult(playlist, pending.uris, OpportunityProgress(active.originalUniqueCount, active.undealt.size, active.completedDealCount))
    }

    private fun requireSource(sourceId: String) {
        checkState(sourceId !in preferences.loadManagedPlaylistIds(), "Managed outputs cannot be Opportunity sources.")
        OpportunityIdentityProtection(store).requireSourceAllowed(sourceId)
    }
    private fun requirePendingOutput(sourceId: String, pending: OpportunityPending) {
        val outputId = pending.outputId ?: throw OpportunityPendingException("No saved output identity.")
        val state = store.load()
        checkState(state.sources[sourceId]?.pending?.operationId == pending.operationId &&
            state.sources[sourceId]?.pending?.outputId == outputId, "Pending output changed.")
        checkState(outputId !in preferences.loadManagedPlaylistIds() && outputId !in ordinaryRegistry.sourcePlaylistIds() &&
            outputId !in state.sources && state.sources.any { (id, s) -> id != sourceId && s.pending?.outputId == outputId }.not(),
            "Pending output conflicts with a source or managed output; no contents changed.")
    }
    private suspend fun accessToken() = tokens.getValidAccessToken()
        ?: throw OutputPlaylistException(OutputPlaylistException.Reason.NOT_AUTHENTICATED)

    private suspend fun loadSource(sourceId: String, token: String): List<OutputPlaylistItem> = buildList {
        var next: String? = "https://api.spotify.com/v1/playlists/$sourceId/items?limit=50"
        val visited = mutableSetOf<String>()
        while (next != null) {
            currentCoroutineContext().ensureActive()
            checkState(visited.add(next), "Cyclic source pagination.")
            val page = api.getPlaylistItemsPage(next, token)
            addAll(page.items)
            next = page.nextUrl
        }
    }
}
