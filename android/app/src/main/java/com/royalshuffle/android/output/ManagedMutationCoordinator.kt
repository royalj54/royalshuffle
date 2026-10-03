package com.royalshuffle.android.output

import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext

/** One workflow owns the lease through all awaited transport work, including cancellation.
 * Helpers may join its lease; they must not launch independent mutations as child coroutines. */
class ManagedMutationCoordinator {
    private val mutex = Mutex()
    private class Lease(val coordinator: ManagedMutationCoordinator) : AbstractCoroutineContextElement(Key) {
        companion object Key : CoroutineContext.Key<Lease>
    }

    suspend fun <T> workflow(block: suspend () -> T): T {
        check(currentCoroutineContext()[Lease]?.coordinator !== this) { "Nested mutation workflow is not allowed." }
        mutex.lock()
        try {
            currentCoroutineContext().ensureActive()
            // Keep accepted exception identity: coroutine stacktrace recovery may otherwise copy exceptions.
            val result = withContext(Lease(this)) {
                try { Result.success(block()) } catch (error: Throwable) { Result.failure(error) }
            }
            return result.getOrThrow()
        } finally { mutex.unlock() }
    }

    /** Registry entry points join the workflow lease, or acquire their own when called directly. */
    suspend fun <T> registryMutation(block: suspend () -> T): T =
        if (currentCoroutineContext()[Lease]?.coordinator === this) block() else workflow(block)

    companion object { val Process = ManagedMutationCoordinator() }
}
