package com.royalshuffle.android.output

interface ManagedIdentityProtection {
    fun pendingOutputIds(): Set<String>
    fun requireSourceAllowed(sourceId: String)
    fun requireOutputAvailable(outputId: String)
}

object NoOpportunityIdentityProtection : ManagedIdentityProtection {
    override fun pendingOutputIds() = emptySet<String>()
    override fun requireSourceAllowed(sourceId: String) = Unit
    override fun requireOutputAvailable(outputId: String) = Unit
}
