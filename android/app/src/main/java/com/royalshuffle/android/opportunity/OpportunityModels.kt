package com.royalshuffle.android.opportunity

import com.royalshuffle.android.output.*
import java.math.BigInteger
import java.util.Collections
import java.util.UUID

class OpportunityStateException(message: String, cause: Throwable? = null) : IllegalStateException(message, cause)

internal fun checkState(condition: Boolean, message: String) {
    if (!condition) throw OpportunityStateException(message)
}
internal fun validId(value: String) = value.isNotEmpty() && value.all { it in 'a'..'z' || it in 'A'..'Z' || it in '0'..'9' }
internal fun validUuid(value: String) = runCatching { UUID.fromString(value).toString() == value }.getOrDefault(false)
internal fun <T> frozenList(values: Collection<T>): List<T> = Collections.unmodifiableList(ArrayList(values))

data class OpportunityTrack(val uri: String, val durationMs: Long, val primaryArtistId: String?) {
    init {
        checkState(isUsableTrackUri(uri) && durationMs > 0, "Invalid Opportunity track.")
        checkState(primaryArtistId == null || primaryArtistId.isNotBlank(), "Invalid primary artist.")
    }
    internal fun item() = OutputPlaylistItem(uri, durationMs = durationMs, primaryArtistId = primaryArtistId, itemType = "track")
}

class OpportunityRotation(
    val rotationId: String,
    val originalUniqueCount: Int,
    val completedDealCount: Int,
    undealt: Collection<OpportunityTrack>,
) {
    val undealt = frozenList(undealt)
    val revision: Int get() = completedDealCount
    val exhausted: Boolean get() = undealt.isEmpty()
    init {
        val depleted = originalUniqueCount.toLong() - this.undealt.size
        checkState(validUuid(rotationId) && originalUniqueCount > 0 && completedDealCount >= 0,
            "Invalid rotation identity/count.")
        checkState(depleted >= 0 && ((completedDealCount == 0 && depleted == 0L) ||
            (completedDealCount > 0 && completedDealCount.toLong() <= depleted)), "Impossible rotation progress.")
        checkState(this.undealt.map { it.uri }.toSet().size == this.undealt.size, "Duplicate rotation URI.")
    }
}

class PreparedOpportunityDeal internal constructor(val sourceId: String, val rotation: OpportunityRotation, tracks: Collection<OpportunityTrack>) {
    val tracks = frozenList(tracks)
    val uris = frozenList(this.tracks.map { it.uri })
}

class OpportunityPlanner(private val planner: OutputPlanner) {
    fun candidate(loaded: List<OutputPlaylistItem>): OpportunityRotation {
        val unique = linkedMapOf<String, OpportunityTrack>()
        loaded.forEach { item ->
            if (!item.isLocal && item.uri?.startsWith("spotify:local:") != true &&
                (item.itemType == null || item.itemType == "track") && isUsableTrackUri(item.uri) && item.uri !in unique) {
                unique[item.uri!!] = OpportunityTrack(item.uri, item.durationMs
                    ?: throw OpportunityStateException("Opportunity requires positive durations."), item.primaryArtistId)
            }
        }
        checkState(unique.isNotEmpty(), "No eligible Opportunity tracks.")
        return OpportunityRotation(UUID.randomUUID().toString(), unique.size, 0, unique.values)
    }

    fun prepare(sourceId: String, rotation: OpportunityRotation, artistSeparation: Boolean = false): PreparedOpportunityDeal {
        checkState(validId(sourceId) && !rotation.exhausted, "Invalid source or exhausted rotation.")
        val selected = planner.plan(rotation.undealt.map { it.item() }, OutputOptions(BigInteger.valueOf(60), artistSeparation))
        val records = rotation.undealt.associateBy { it.uri }
        return PreparedOpportunityDeal(sourceId, rotation, selected.items.map { records.getValue(it.uri!!) })
    }
}

enum class OpportunityAction { NEXT_SESSION, NEW_ROTATION }
enum class OpportunityPhase { STAGED, CREATING, OUTPUT_KNOWN, POPULATING, DELIVERED }
enum class OpportunityWriteMethod { CLEAR, APPEND }

data class OpportunityReceipt(val index: Int, val method: OpportunityWriteMethod, val start: Int, val end: Int,
    val status: Int, val snapshotId: String)

class OpportunityAttempt(val attemptId: String, val outputId: String, uris: Collection<String>, receipts: Collection<OpportunityReceipt>) {
    val uris = frozenList(uris)
    val receipts = frozenList(receipts)
    val complete: Boolean get() = receipts.size == 1 + (uris.size + 99) / 100
    init {
        checkState(validUuid(attemptId) && validId(outputId) && this.uris.isNotEmpty(), "Invalid delivery attempt.")
        checkState(this.receipts.size <= 1 + (this.uris.size + 99) / 100, "Excess receipt coverage.")
        this.receipts.forEachIndexed { index, receipt ->
            val start = if (index == 0) 0 else (index - 1) * 100
            val end = if (index == 0) 0 else minOf(start + 100, this.uris.size)
            checkState(receipt.index == index && receipt.method == if (index == 0) OpportunityWriteMethod.CLEAR else OpportunityWriteMethod.APPEND,
                "Invalid receipt sequence.")
            checkState(receipt.start == start && receipt.end == end && receipt.status == (if (index == 0) 200 else 201) &&
                receipt.snapshotId.isNotBlank(), "Invalid receipt acknowledgement.")
        }
    }
}

class OpportunityPending internal constructor(
    val operationId: String, val action: OpportunityAction,
    val expectedRotationId: String?, val expectedRevision: Int, val expectedCompletedDealCount: Int,
    val expectedSnapshot: String?,
    uris: Collection<String>, val candidate: OpportunityRotation?, val creationName: String,
    val phase: OpportunityPhase = OpportunityPhase.STAGED, val outputId: String? = null,
    val attempt: OpportunityAttempt? = null, val exactContentsConfirmed: Boolean = false,
) {
    val uris = frozenList(uris)
}

data class OpportunitySource(val active: OpportunityRotation?, val pending: OpportunityPending?)
class OpportunityDocument(sources: Map<String, OpportunitySource> = emptyMap()) {
    val sources: Map<String, OpportunitySource> = Collections.unmodifiableMap(LinkedHashMap(sources))
    companion object { const val VERSION = 1 }
}
