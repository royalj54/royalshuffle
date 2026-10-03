package com.royalshuffle.android.output

data class OutputIdentity(val sourceId: String, val sessionKey: String) {
    init {
        require(sourceId.isNotBlank()) { "Source playlist ID is required." }
        require(sessionKey == FULL || (parseCustomMinutes(sessionKey)?.toString() == sessionKey)) {
            "Invalid session identity."
        }
    }
    companion object {
        const val FULL = "full"
        fun from(sourceId: String, options: OutputOptions): OutputIdentity {
            options.validate()
            return OutputIdentity(sourceId, options.sessionMinutes?.toString() ?: FULL)
        }
    }
}

interface OrdinaryOutputRegistry {
    /** Sources reserved by ordinary bindings; needed for cross-family output collision checks. */
    fun sourcePlaylistIds(): Set<String> = emptySet()
    fun boundOutput(identity: OutputIdentity): String?
    /** Compare the association again immediately before remote mutation. */
    fun requireCurrent(identity: OutputIdentity, expectedOutputId: String?)
    /** Atomically bind/register only if the checked association still matches. */
    suspend fun bind(identity: OutputIdentity, outputId: String, expectedOutputId: String?)
}

class OutputRegistryException(message: String, val restartRequired: Boolean = false) : IllegalStateException(message)

internal fun validateBindings(bindings: Map<OutputIdentity, String>) {
    val outputIds = bindings.values
    if (outputIds.any { it.isBlank() } || outputIds.toSet().size != outputIds.size ||
        bindings.keys.any { it.sourceId in outputIds }) {
        throw OutputRegistryException("Conflicting managed output identities. Recovery stopped; no playlist contents were changed.")
    }
}

fun defaultOutputName(sourceName: String, options: OutputOptions): String =
    if (options.sessionMinutes == null) "RND-$sourceName" else "RND${options.sessionMinutes}M-$sourceName"

fun interface CreationNameProvider {
    /** null cancels the operation without writes or registry changes. */
    suspend fun chooseName(defaultName: String): String?
}

internal val RequiredCreationName = CreationNameProvider {
    throw OutputPlanningException("Choose a name before creating a managed output.")
}
