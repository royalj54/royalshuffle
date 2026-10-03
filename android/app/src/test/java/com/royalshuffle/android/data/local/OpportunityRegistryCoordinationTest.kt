package com.royalshuffle.android.data.local

import com.royalshuffle.android.opportunity.*
import com.royalshuffle.android.output.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class OpportunityRegistryCoordinationTest {
    @Test fun `real registry helper joins workflow without deadlock and direct exclusion mutation waits`() = runTest {
        val stored = SharedPreferencesPlaylistPreferencesTest.FakeSharedPreferences()
        val coordinator = ManagedMutationCoordinator()
        val registry = SharedPreferencesPlaylistPreferences(stored, StandardTestDispatcher(testScheduler), coordinator)
        val inside = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val owner = launch {
            coordinator.workflow {
                registry.bind(OutputIdentity("source", "full"), "output", null)
                inside.complete(Unit)
                release.await()
            }
        }
        inside.await()
        val mutation = async { registry.addManagedPlaylistIds(setOf("legacy")) }
        runCurrent()
        assertEquals(setOf("output"), registry.loadManagedPlaylistIds())
        assertEquals(setOf("source"), registry.sourcePlaylistIds())
        release.complete(Unit)
        owner.join()
        assertTrue(mutation.await())
        assertEquals(setOf("output", "legacy"), registry.loadManagedPlaylistIds())
    }

    @Test fun `production registry boundary rejects pending output active source and pending source adoption`() = runTest {
        val f = OpportunityTestEnvironment(StandardTestDispatcher(testScheduler))
        f.api.appendFailure = java.io.IOException("interrupted")
        assertTrue(runCatching { f.generate() }.isFailure)
        val stored = SharedPreferencesPlaylistPreferencesTest.FakeSharedPreferences()
        val registry = SharedPreferencesPlaylistPreferences(stored, StandardTestDispatcher(testScheduler), f.coordinator,
            OpportunityIdentityProtection(f.store))
        for ((source, output) in listOf("ordinarySource" to "source", "ordinarySource" to "deal1", "deal1" to "newOutput")) {
            assertTrue(runCatching { registry.bind(OutputIdentity(source, "full"), output, null) }.isFailure)
        }
        assertFalse(registry.addManagedPlaylistId("deal1"))
        assertFalse(registry.addManagedPlaylistId("source"))
        assertEquals(0, stored.commitCount)
        assertTrue(registry.loadManagedPlaylistIds().isEmpty())
        // Confirmed completed Deals carry no reservation/binding and are not automatically registered.
        f.api.appendFailure = null
        f.resume()
        assertTrue(registry.loadManagedPlaylistIds().isEmpty())
        assertNull(registry.boundOutput(OutputIdentity("source", "full")))
    }
}
