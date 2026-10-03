package com.royalshuffle.android.opportunity

import java.util.UUID

/** Writes must atomically replace the document or throw. No remote operations belong here. */
interface OpportunityPersistence {
    val coordinationKey: String
    fun read(): String?
    fun replace(document: String)
}

class OpportunityStore(private val persistence: OpportunityPersistence) {
    private class Guard { var failed = false }
    private val guard = synchronized(guards) { guards.getOrPut(persistence.coordinationKey) { Guard() } }

    fun load(): OpportunityDocument = synchronized(guard) { read() }

    private fun read(): OpportunityDocument {
        checkState(!guard.failed, "Opportunity persistence failed; restart before continuing.")
        return try {
            persistence.read()?.let(OpportunityCodec::decode) ?: OpportunityDocument()
        } catch (error: Exception) {
            throw OpportunityStateException("Cannot read Opportunity state; existing data was preserved.", error)
        }
    }

    private fun save(document: OpportunityDocument) {
        OpportunityCodec.validate(document)
        val text = OpportunityCodec.encode(document)
        try { persistence.replace(text) } catch (error: Exception) {
            guard.failed = true
            throw OpportunityStateException("Cannot durably save Opportunity state; restart before continuing.", error)
        }
    }

    /** Initial/fully exhausted rotation only. Replacement rotations are staged as candidates. */
    fun beginRotation(sourceId: String, candidate: OpportunityRotation) = synchronized(guard) {
        val document = read()
        val previous = document.sources[sourceId]
        checkState(candidate.completedDealCount == 0 && previous?.pending == null &&
            (previous?.active == null || (previous.active.exhausted && previous.active.rotationId != candidate.rotationId)),
            "Existing rotation must be completed and replacement identity must be fresh.")
        save(OpportunityDocument(document.sources + (sourceId to OpportunitySource(candidate, null))))
    }

    fun stage(deal: PreparedOpportunityDeal, creationName: String, candidate: OpportunityRotation? = null,
        abandonPendingOperationId: String? = null): OpportunityPending = synchronized(guard) {
        val document = read()
        val source = document.sources[deal.sourceId]
        val oldPending = source?.pending
        if (abandonPendingOperationId == null) checkState(oldPending == null, "Resume pending first.")
        else checkState(candidate != null && oldPending?.operationId == abandonPendingOperationId,
            "Pending abandonment confirmation is stale.")
        val active = source?.active
        val selected = candidate ?: active
        checkState(selected != null && sameRotation(selected, deal.rotation), "Prepared rotation is stale.")
        checkState(deal.tracks.isNotEmpty() && deal.uris.toSet().size == deal.uris.size &&
            deal.tracks.all { it in selected!!.undealt }, "Prepared membership changed.")
        val pending = OpportunityPending(UUID.randomUUID().toString(),
            if (candidate == null) OpportunityAction.NEXT_SESSION else OpportunityAction.NEW_ROTATION,
            active?.rotationId, active?.revision ?: 0, active?.completedDealCount ?: 0,
            active?.let(OpportunityCodec::fingerprint),
            deal.uris, candidate, creationName)
        save(OpportunityDocument(document.sources + (deal.sourceId to OpportunitySource(active, pending))))
        pending
    }

    fun markCreating(sourceId: String, operationId: String) = update(sourceId, operationId) {
        checkState(it.phase == OpportunityPhase.STAGED && it.outputId == null, "Invalid creation transition.")
        copy(it, phase = OpportunityPhase.CREATING)
    }

    /** C2 may call only after a known creation rejection, never for ambiguous acknowledgement. */
    fun recordCreationRejected(sourceId: String, operationId: String) = update(sourceId, operationId) {
        checkState(it.phase == OpportunityPhase.CREATING && it.outputId == null, "Invalid creation rejection.")
        copy(it, phase = OpportunityPhase.STAGED)
    }

    fun recordCreatedOutput(sourceId: String, operationId: String, outputId: String) = update(sourceId, operationId) {
        checkState(it.phase == OpportunityPhase.CREATING && it.outputId == null, "Output identity already known or creation not begun.")
        copy(it, phase = OpportunityPhase.OUTPUT_KNOWN, outputId = outputId)
    }

    /** C2 must establish confirmed missing status and obtain a replacement name before calling. */
    fun stageConfirmedMissingOutput(sourceId: String, operationId: String, expectedOutputId: String,
        creationName: String) = update(sourceId, operationId) {
        checkState(it.outputId == expectedOutputId && it.phase in setOf(OpportunityPhase.OUTPUT_KNOWN, OpportunityPhase.POPULATING, OpportunityPhase.DELIVERED) &&
            it.attempt?.complete != true, "Output replacement is stale or submission already acknowledged.")
        copy(it, phase = OpportunityPhase.STAGED, outputId = null, attempt = null, creationName = creationName, exactContentsConfirmed = false)
    }

    /** Start/rebuild from clear; never continue an uncertain append attempt.
     * C2 must reverify incomplete evidence on recovery, including a prior readback-only DELIVERED state. */
    fun beginDeliveryAttempt(sourceId: String, operationId: String) = update(sourceId, operationId) {
        checkState(it.outputId != null && it.phase in setOf(OpportunityPhase.OUTPUT_KNOWN, OpportunityPhase.POPULATING, OpportunityPhase.DELIVERED) &&
            it.attempt?.complete != true, "Invalid delivery attempt transition.")
        copy(it, phase = OpportunityPhase.POPULATING,
            attempt = OpportunityAttempt(UUID.randomUUID().toString(), it.outputId!!, it.uris, emptyList()), exactContentsConfirmed = false)
    }

    fun appendReceipt(sourceId: String, operationId: String, attemptId: String, receipt: OpportunityReceipt) = update(sourceId, operationId) {
        val attempt = it.attempt
        checkState(it.phase == OpportunityPhase.POPULATING && attempt != null && attempt.attemptId == attemptId && !attempt.complete,
            "Stale delivery receipt.")
        copy(it, attempt = OpportunityAttempt(attempt!!.attemptId, attempt.outputId, attempt.uris, attempt.receipts + receipt))
    }

    fun markAcknowledged(sourceId: String, operationId: String) = update(sourceId, operationId) {
        checkState(it.phase == OpportunityPhase.POPULATING && it.attempt?.complete == true, "Incomplete delivery acknowledgements.")
        copy(it, phase = OpportunityPhase.DELIVERED)
    }

    /** C2 supplies a complete, validated raw read; this method does not fetch or filter it. */
    fun confirmExactContents(sourceId: String, operationId: String, outputId: String, actual: List<String?>) = update(sourceId, operationId) {
        checkState(it.outputId == outputId && it.phase in setOf(OpportunityPhase.OUTPUT_KNOWN, OpportunityPhase.POPULATING, OpportunityPhase.DELIVERED) && actual == it.uris,
            "Remote contents do not exactly match pending session.")
        copy(it, phase = OpportunityPhase.DELIVERED, exactContentsConfirmed = true)
    }

    fun finalize(sourceId: String, operationId: String): OpportunityRotation = synchronized(guard) {
        val document = read()
        val source = document.sources[sourceId] ?: throw OpportunityStateException("No source state.")
        val pending = source.pending ?: throw OpportunityStateException("No pending session.")
        checkState(pending.operationId == operationId && pending.phase == OpportunityPhase.DELIVERED &&
            (pending.exactContentsConfirmed || pending.attempt?.complete == true), "Delivery not confirmed or operation stale.")
        val selected = pending.candidate ?: source.active!!
        val committed = OpportunityRotation(selected.rotationId, selected.originalUniqueCount,
            selected.completedDealCount + 1, selected.undealt.filterNot { it.uri in pending.uris })
        save(OpportunityDocument(document.sources + (sourceId to OpportunitySource(committed, null))))
        committed
    }

    /** Explicit confirmation discards only local pending intent; remote playlists are untouched. */
    fun abandonPending(sourceId: String, operationId: String) = synchronized(guard) {
        val document = read()
        val source = document.sources[sourceId] ?: throw OpportunityStateException("No source state.")
        checkState(source.pending?.operationId == operationId, "Pending abandonment confirmation is stale.")
        val updated = if (source.active == null) document.sources - sourceId
            else document.sources + (sourceId to OpportunitySource(source.active, null))
        save(OpportunityDocument(updated))
    }

    private fun update(sourceId: String, operationId: String, change: (OpportunityPending) -> OpportunityPending): OpportunityPending = synchronized(guard) {
        val document = read()
        val source = document.sources[sourceId] ?: throw OpportunityStateException("No source state.")
        val pending = source.pending ?: throw OpportunityStateException("No pending session.")
        checkState(pending.operationId == operationId, "Pending operation changed.")
        val updated = change(pending)
        save(OpportunityDocument(document.sources + (sourceId to OpportunitySource(source.active, updated))))
        updated
    }

    private fun copy(p: OpportunityPending, phase: OpportunityPhase = p.phase, outputId: String? = p.outputId,
        attempt: OpportunityAttempt? = p.attempt, exactContentsConfirmed: Boolean = p.exactContentsConfirmed,
        creationName: String = p.creationName) =
        OpportunityPending(p.operationId, p.action, p.expectedRotationId, p.expectedRevision, p.expectedCompletedDealCount,
            p.expectedSnapshot, p.uris, p.candidate, creationName, phase, outputId, attempt, exactContentsConfirmed)

    companion object {
        private val guards = mutableMapOf<String, Guard>()
        internal fun sameRotation(a: OpportunityRotation, b: OpportunityRotation) =
            a.rotationId == b.rotationId && a.originalUniqueCount == b.originalUniqueCount &&
                a.completedDealCount == b.completedDealCount && a.undealt == b.undealt
    }
}
