package com.royalshuffle.android.output

import com.royalshuffle.android.opportunity.OpportunityRotationConfirmation
import com.royalshuffle.android.opportunity.OpportunitySource
import com.royalshuffle.android.opportunity.OpportunityPending

data class PendingOpportunitySession(val sourceId: String, val pending: OpportunityPending)

/** A local read projection; transaction state belongs exclusively to OpportunityStore. */
data class OpportunityUiState(
    val sourceId: String? = null,
    val savedSource: OpportunitySource? = null,
    val isLoading: Boolean = true,
    val pendingSourceCount: Int = 0,
    val errorMessage: String? = null,
    val pendingSessions: List<PendingOpportunitySession> = emptyList(),
) {
    val hasPending: Boolean get() = savedSource?.pending != null
    val primaryActionText: String get() = if (hasPending) "Resume Pending Session" else "Generate Next Session"
    val canSubmit: Boolean get() = sourceId != null && !isLoading && errorMessage == null
    val status: String get() {
        errorMessage?.let { return it }
        if (isLoading) return "Loading saved Opportunity state…"
        val rotation = savedSource?.pending?.candidate ?: savedSource?.active
        val progress = rotation?.let {
            "${it.completedDealCount} Deals completed · ${it.undealt.size} unique opportunities remaining."
        } ?: "Balanced Opportunity uses 60-minute sessions."
        return progress + when {
            hasPending -> " Pending session saved; resume to finish delivery."
            rotation?.exhausted == true -> " Rotation complete. The next session starts a fresh rotation."
            pendingSourceCount > 0 -> " $pendingSourceCount saved pending session(s). Use the saved pending session controls to resume or abandon."
            else -> ""
        }
    }
}

data class OpportunityConfirmationRequest(val requestId: Long, val confirmation: OpportunityRotationConfirmation,
    val abandonOnly: Boolean = false) {
    val title: String get() = if (abandonOnly) "Abandon Pending Session?" else "Start New Rotation?"
    val message: String get() = if (abandonOnly)
        "Abandon the saved pending session? Any playlist already created for it will be left untouched. No replacement rotation will be prepared."
    else "${confirmation.remainingUniqueCount} unique opportunities remain. " +
        (if (confirmation.pendingOperationId != null)
            "The pending session will be abandoned. Any playlist already created for it will be left untouched. "
        else "The unfinished rotation will be abandoned. ") +
        "After confirmation and naming, start a new rotation from the current source."
}
