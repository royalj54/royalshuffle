package com.royalshuffle.android.opportunity

import com.royalshuffle.android.auth.AccessTokenProvider
import com.royalshuffle.android.domain.model.Playlist
import com.royalshuffle.android.data.remote.SpotifyOpportunityPlaylistApi
import com.royalshuffle.android.output.*
import com.royalshuffle.android.playlist.*
import java.io.IOException
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class OpportunityIdentityProtectionTest {
    private fun ordinary(f: OpportunityTestEnvironment) = CreateOutputPlaylist(AccessTokenProvider { "token" }, f.api,
        f.preferences, OccurrenceShuffler { it }, registry = f.preferences, coordinator = f.coordinator,
        identityProtection = OpportunityIdentityProtection(f.store))

    @Test fun `pending exact ID excluded without registry binding completed Deal remains ordinary source eligible`() = runTest {
        val f = OpportunityTestEnvironment(StandardTestDispatcher(testScheduler))
        f.api.appendFailure = IOException("interrupted")
        f.api.loseAppend = true
        assertTrue(runCatching { f.generate() }.isFailure)
        val guard = OpportunityIdentityProtection(f.store)
        val repository = PlaylistRepository(AccessTokenProvider { "token" }, object : PlaylistApi {
            override suspend fun getPlaylistsPage(url: String, accessToken: String) = PlaylistPage(
                listOf(Playlist("deal1", "External name", SpotifyOpportunityPlaylistApi.DESCRIPTION),
                    Playlist("source", "Source")), null)
        }, f.preferences, identityProtection = guard)
        assertEquals(listOf("source"), repository.loadEligiblePlaylists().playlists.map { it.id })
        assertTrue(runCatching { repository.selectPlaylist("deal1") }.isFailure)
        assertEquals(setOf("deal1"), guard.pendingOutputIds())
        assertTrue(f.preferences.managed.isEmpty())
        f.resume()
        assertTrue(guard.pendingOutputIds().isEmpty())
        val result = repository.loadEligiblePlaylists()
        assertEquals(listOf("deal1", "source"), result.playlists.map { it.id })
        assertTrue(result.recoveryCandidates.isEmpty())
        assertTrue(f.preferences.bindings.isEmpty())
        assertTrue(f.preferences.loadDeclinedRecoveryPlaylistIds().isEmpty())
        assertNull(f.store.load().sources.getValue("source").pending)
    }

    @Test fun `ordinary workflow rejects pending output source or output before destructive writes`() = runTest {
        val f = OpportunityTestEnvironment(StandardTestDispatcher(testScheduler))
        f.api.appendFailure = IOException("interrupted")
        assertTrue(runCatching { f.generate() }.isFailure)
        val events = f.api.events.size
        assertTrue(runCatching { ordinary(f).execute(Playlist("deal1", "Pending"), nameProvider = f.names) }.isFailure)
        assertEquals(events, f.api.events.size)
        f.preferences.bindings[OutputIdentity("ordinarySource", "full")] = "deal1"
        val before = f.api.contents.getValue("deal1").toList()
        assertTrue(runCatching { ordinary(f).execute(Playlist("ordinarySource", "Ordinary"), nameProvider = f.names) }.isFailure)
        assertEquals(before, f.api.contents.getValue("deal1"))
        assertFalse(f.api.events.drop(events).any { it.startsWith("clear") || it.startsWith("append") || it == "create" })
    }

    @Test fun `ordinary creation response cannot bind an active Opportunity source or pending output`() = runTest {
        for (collision in listOf("source", "deal1")) {
            val f = OpportunityTestEnvironment(StandardTestDispatcher(testScheduler))
            f.api.appendFailure = IOException("interrupted")
            assertTrue(runCatching { f.generate() }.isFailure)
            f.api.createId = collision
            val events = f.api.events.size
            assertTrue(runCatching { ordinary(f).execute(Playlist("ordinarySource", "Ordinary"), nameProvider = f.names) }.isFailure)
            assertTrue(f.preferences.bindings.isEmpty())
            assertFalse(f.api.events.drop(events).any { it.startsWith("clear") || it.startsWith("append") })
        }
    }

    @Test fun `another Opportunity source cannot use pending output and conflicting creation never populates`() = runTest {
        val f = OpportunityTestEnvironment(StandardTestDispatcher(testScheduler))
        f.api.appendFailure = IOException("interrupted")
        assertTrue(runCatching { f.generate() }.isFailure)
        val events = f.api.events.size
        assertTrue(runCatching { f.workflow().generateNextSession(Playlist("deal1", "Pending"), f.names) }.isFailure)
        assertEquals(events, f.api.events.size)
        f.api.createId = "deal1"
        assertTrue(runCatching { f.workflow().generateNextSession(Playlist("other", "Other"), f.names) }.isFailure)
        assertFalse(f.api.events.drop(events).any { it.startsWith("clear") || it.startsWith("append") })
        assertEquals(OpportunityPhase.CREATING, f.store.load().sources.getValue("other").pending!!.phase)
    }

    @Test fun `corrupt journal blocks identity protection without adopting names or resetting state`() = runTest {
        val f = OpportunityTestEnvironment(StandardTestDispatcher(testScheduler))
        f.disk.text = "corrupt"
        assertTrue(runCatching { ordinary(f).execute(f.source, nameProvider = f.names) }.isFailure)
        assertEquals("corrupt", f.disk.text)
        assertTrue(f.api.events.isEmpty())
    }
}
