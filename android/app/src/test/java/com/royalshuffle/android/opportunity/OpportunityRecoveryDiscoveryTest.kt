package com.royalshuffle.android.opportunity

import com.royalshuffle.android.auth.AccessTokenProvider
import com.royalshuffle.android.data.remote.SpotifyOpportunityPlaylistApi
import com.royalshuffle.android.domain.model.Playlist
import com.royalshuffle.android.output.CreateOutputPlaylist
import com.royalshuffle.android.playlist.*
import java.io.IOException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class OpportunityRecoveryDiscoveryTest {
    private class Decisions : PlaylistPreferences {
        val managed = linkedSetOf<String>()
        val declined = linkedSetOf<String>()
        var writes = 0
        override fun loadManagedPlaylistIds() = managed.toSet()
        override fun loadDeclinedRecoveryPlaylistIds() = declined.toSet()
        override suspend fun addManagedPlaylistId(playlistId: String) = addManagedPlaylistIds(setOf(playlistId))
        override suspend fun addManagedPlaylistIds(playlistIds: Set<String>): Boolean {
            writes++; managed.addAll(playlistIds); return true
        }
        override suspend fun addDeclinedRecoveryPlaylistIds(playlistIds: Set<String>): Boolean {
            writes++; declined.addAll(playlistIds); return true
        }
        override fun loadSelectedPlaylistId(): String? = null
        override fun saveSelectedPlaylistId(playlistId: String) = Unit
        override fun clearSelectedPlaylistId() = Unit
    }

    @Test fun `process restart Resume then reload releases Deal without adopting or suppressing it`() = runTest {
        val f = OpportunityTestEnvironment(StandardTestDispatcher(testScheduler))
        f.api.appendFailure = IOException("interrupted before population")
        assertTrue(runCatching { f.generate() }.isFailure)
        val outputId = f.pending().outputId!!
        val uris = f.pending().uris.toList()
        val restarted = f.restart()
        val decisions = Decisions()
        val api = object : PlaylistApi {
            override suspend fun getPlaylistsPage(url: String, accessToken: String) = PlaylistPage(listOf(
                Playlist(outputId, "An arbitrary external rename", SpotifyOpportunityPlaylistApi.DESCRIPTION),
                Playlist("source", "Source"),
                Playlist("modern", "Ordinary external rename", CreateOutputPlaylist.OUTPUT_DESCRIPTION),
                Playlist("legacy", "Legacy external rename", CreateOutputPlaylist.LEGACY_OUTPUT_DESCRIPTION),
            ), null)
        }
        val guard = OpportunityIdentityProtection(restarted.store)
        val repository = PlaylistRepository(AccessTokenProvider { "token" }, api, decisions, identityProtection = guard)
        val before = repository.loadEligiblePlaylists()
        assertEquals(setOf("modern", "legacy"), before.recoveryCandidates.map { it.id }.toSet())
        assertEquals(listOf("source"), before.playlists.map { it.id })
        assertEquals(setOf(outputId), guard.pendingOutputIds())
        assertTrue(runCatching { repository.selectPlaylist(outputId) }.isFailure)

        f.api.appendFailure = null
        val result = restarted.resume()
        assertEquals(outputId, result.output.id)
        assertEquals(uris, result.uris)
        assertEquals(uris, f.api.contents.getValue(outputId))
        assertEquals(0, restarted.shuffles)
        assertTrue(guard.pendingOutputIds().isEmpty())
        val durable = restarted.store.load()
        assertEquals(setOf("source"), durable.sources.keys)
        assertNull(durable.sources.getValue("source").pending)
        assertEquals(1, durable.sources.getValue("source").active!!.completedDealCount)

        val savedDocument = restarted.disk.text
        repeat(2) {
            val freshRepository = PlaylistRepository(AccessTokenProvider { "token" }, api, decisions,
                identityProtection = OpportunityIdentityProtection(restarted.store))
            val after = freshRepository.loadEligiblePlaylists()
            assertEquals(setOf("modern", "legacy"), after.recoveryCandidates.map { it.id }.toSet())
            assertEquals(listOf(outputId, "source"), after.playlists.map { it.id })
            freshRepository.selectPlaylist(outputId)
        }
        assertEquals(savedDocument, restarted.disk.text)
        assertTrue(decisions.managed.isEmpty())
        assertTrue(decisions.declined.isEmpty())
        assertEquals(0, decisions.writes)
        assertTrue(f.preferences.managed.isEmpty())
        assertTrue(f.preferences.bindings.isEmpty())
    }

    @Test fun `pending exact ID stays protected even if external metadata resembles ordinary output`() = runTest {
        val f = OpportunityTestEnvironment(StandardTestDispatcher(testScheduler))
        f.api.appendFailure = IOException("interrupted")
        assertTrue(runCatching { f.generate() }.isFailure)
        val outputId = f.pending().outputId!!
        val decisions = Decisions()
        for (description in listOf(SpotifyOpportunityPlaylistApi.DESCRIPTION,
            CreateOutputPlaylist.OUTPUT_DESCRIPTION, CreateOutputPlaylist.LEGACY_OUTPUT_DESCRIPTION, null)) {
            val api = object : PlaylistApi {
                override suspend fun getPlaylistsPage(url: String, accessToken: String) = PlaylistPage(listOf(
                    Playlist(outputId, "Anything", description), Playlist("source", "Source")), null)
            }
            val repository = PlaylistRepository(AccessTokenProvider { "token" }, api, decisions,
                identityProtection = OpportunityIdentityProtection(f.store))
            val loaded = repository.loadEligiblePlaylists()
            assertEquals(listOf("source"), loaded.playlists.map { it.id })
            assertTrue(loaded.recoveryCandidates.isEmpty())
        }
        assertEquals(0, decisions.writes)
    }

    @Test fun `Deal metadata is ordinary source metadata regardless of name without Opportunity state`() = runTest {
        val decisions = Decisions()
        val rows = listOf(Playlist("one", "RND60M-Source", SpotifyOpportunityPlaylistApi.DESCRIPTION),
            Playlist("two", "Kpop - Deal 900", SpotifyOpportunityPlaylistApi.DESCRIPTION),
            Playlist("three", "Completely renamed", SpotifyOpportunityPlaylistApi.DESCRIPTION))
        val api = object : PlaylistApi {
            override suspend fun getPlaylistsPage(url: String, accessToken: String) = PlaylistPage(rows, null)
        }
        val repository = PlaylistRepository(AccessTokenProvider { "token" }, api, decisions)
        val loaded = repository.loadEligiblePlaylists()
        assertEquals(rows, loaded.playlists)
        assertTrue(loaded.recoveryCandidates.isEmpty())
        assertEquals(0, decisions.writes)
    }
}
