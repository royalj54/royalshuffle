package com.royalshuffle.android.output

import com.royalshuffle.android.auth.AccessTokenProvider
import com.royalshuffle.android.domain.model.Playlist
import com.royalshuffle.android.opportunity.*
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ManagedMutationCoordinatorTest {
    private fun ordinary(f: OpportunityTestEnvironment) = CreateOutputPlaylist(AccessTokenProvider { "token" }, f.api,
        f.preferences, OccurrenceShuffler { it }, registry = f.preferences, coordinator = f.coordinator,
        identityProtection = OpportunityIdentityProtection(f.store))

    @Test fun `ordinary and Opportunity cannot overlap and helpers do not reacquire workflow lease`() = runTest {
        val f = OpportunityTestEnvironment(StandardTestDispatcher(testScheduler))
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        var first = true
        f.api.beforeMutation = {
            if (first) { first = false; entered.complete(Unit); release.await() }
        }
        val opportunity = async { f.generate() }
        entered.await()
        val output = async { ordinary(f).execute(Playlist("ordinarySource", "Ordinary"), nameProvider = f.names) }
        runCurrent()
        assertEquals(1, f.api.creationCalls)
        assertEquals(1, f.api.sourceLoads)
        release.complete(Unit)
        opportunity.await()
        output.await()
        assertEquals(2, f.api.creationCalls)
        assertEquals(1, f.preferences.bindings.size)
    }

    @Test fun `two Opportunity submissions serialize then use shrinking frozen pool`() = runTest {
        val f = OpportunityTestEnvironment(StandardTestDispatcher(testScheduler))
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        var first = true
        f.api.beforeMutation = { if (first) { first = false; entered.complete(Unit); release.await() } }
        val one = async { f.generate() }
        entered.await()
        val two = async { f.generate() }
        runCurrent()
        assertEquals(1, f.api.creationCalls)
        release.complete(Unit)
        val firstResult = one.await()
        val secondResult = two.await()
        assertTrue(firstResult.uris.toSet().intersect(secondResult.uris.toSet()).isEmpty())
        assertEquals(1, f.api.sourceLoads)
        assertEquals(2, secondResult.progress.completedDealCount)
    }

    @Test fun `registry helper joins lease direct registry mutation waits and nested workflow rejected`() = runTest {
        val coordinator = ManagedMutationCoordinator()
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        var helper = false
        var direct = false
        val owner = launch {
            coordinator.workflow {
                coordinator.registryMutation { helper = true }
                assertTrue(runCatching { coordinator.workflow { } }.isFailure)
                entered.complete(Unit)
                release.await()
            }
        }
        entered.await()
        val registry = launch { coordinator.registryMutation { direct = true } }
        runCurrent()
        assertTrue(helper)
        assertFalse(direct)
        release.complete(Unit)
        owner.join(); registry.join()
        assertTrue(direct)
    }

    @Test fun `cancellation retains ownership until blocking transport actually finishes`() = runBlocking {
        val f = OpportunityTestEnvironment(Dispatchers.IO)
        val entered = CompletableDeferred<Unit>()
        val release = CountDownLatch(1)
        val transportFinished = AtomicBoolean(false)
        val secondEntered = CompletableDeferred<Unit>()
        var first = true
        f.api.beforeMutation = {
            if (first) {
                first = false
                withContext(Dispatchers.IO) {
                    entered.complete(Unit)
                    check(release.await(5, TimeUnit.SECONDS))
                    transportFinished.set(true)
                }
            } else {
                assertTrue(transportFinished.get())
                secondEntered.complete(Unit)
            }
        }
        val one = launch(Dispatchers.Default) { f.generate() }
        withTimeout(5_000) { entered.await() }
        one.cancel()
        val ready = CompletableDeferred<Unit>()
        val two = async(Dispatchers.Default) {
            ready.complete(Unit)
            ordinary(f).execute(Playlist("ordinarySource", "Ordinary"), nameProvider = f.names)
        }
        try {
            ready.await()
            assertNull(withTimeoutOrNull(100) { secondEntered.await() })
            assertFalse(transportFinished.get())
            release.countDown()
            withTimeout(5_000) { one.join(); two.await() }
            assertTrue(transportFinished.get())
            assertEquals(OpportunityPhase.CREATING, f.pending().phase)
        } finally { release.countDown(); one.cancel(); two.cancel() }
    }
}
