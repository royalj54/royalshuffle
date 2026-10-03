package com.royalshuffle.android.opportunity

import com.royalshuffle.android.output.ManagedIdentityProtection
import com.royalshuffle.android.output.OutputRegistryException

/** Pending-only reservations. Completed Deal IDs are deliberately not retained or managed. */
class OpportunityIdentityProtection(private val store: OpportunityStore) : ManagedIdentityProtection {
    override fun pendingOutputIds(): Set<String> = store.load().sources.values.mapNotNull { it.pending?.outputId }.toSet()
    override fun requireSourceAllowed(sourceId: String) {
        if (sourceId in pendingOutputIds()) throw OutputRegistryException("Pending Opportunity output cannot be used as a source.")
    }
    override fun requireOutputAvailable(outputId: String) {
        val state = store.load()
        if (outputId in state.sources || state.sources.values.any { it.pending?.outputId == outputId }) {
            throw OutputRegistryException("Output conflicts with an Opportunity source or pending output.")
        }
    }
}
