package com.royalshuffle.android.playlist

import com.royalshuffle.android.auth.AccessTokenProvider
import com.royalshuffle.android.domain.model.Playlist
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import com.royalshuffle.android.diagnostics.DiagnosticEvent
import com.royalshuffle.android.diagnostics.DiagnosticLogger
import com.royalshuffle.android.output.CreateOutputPlaylist

class PlaylistRepositoryTest {
    private val api = FakePlaylistApi()
    private val preferences = FakePlaylistPreferences()
    private val repository = PlaylistRepository(
        accessTokenProvider = AccessTokenProvider { "access-token" },
        playlistApi = api,
        preferences = preferences,
    )

    @Test
    fun `loads every page by following Spotify next URLs`() = runBlocking {
        api.pages[INITIAL_URL] = PlaylistPage(
            playlists = listOf(playlist("one")),
            nextUrl = SECOND_URL,
        )
        api.pages[SECOND_URL] = PlaylistPage(
            playlists = listOf(playlist("two"), playlist("three")),
            nextUrl = null,
        )

        val result = repository.loadEligiblePlaylists()

        assertEquals(listOf(INITIAL_URL, SECOND_URL), api.requestedUrls)
        assertEquals(listOf("one", "two", "three"), result.playlists.map { it.id })
    }

    @Test
    fun `excludes playlists registered as RoyalShuffle managed`() = runBlocking {
        api.singlePage(playlist("source"), playlist("managed"), playlist("another"))
        preferences.managedIds += "managed"

        val result = repository.loadEligiblePlaylists()

        assertEquals(listOf("source", "another"), result.playlists.map { it.id })
    }

    @Test
    fun `persists and restores eligible playlist selection`() = runBlocking {
        api.singlePage(playlist("one"), playlist("two"))
        repository.selectPlaylist("two")

        val result = repository.loadEligiblePlaylists()

        assertEquals("two", preferences.selectedId)
        assertEquals("two", result.selectedPlaylistId)
    }

    @Test
    fun `clears saved selection when playlist is stale`() = runBlocking {
        api.singlePage(playlist("one"))
        preferences.selectedId = "missing"

        val result = repository.loadEligiblePlaylists()

        assertNull(result.selectedPlaylistId)
        assertNull(preferences.selectedId)
        assertEquals(1, preferences.clearSelectionCount)
    }

    @Test
    fun `clears saved selection when playlist became managed`() = runBlocking {
        api.singlePage(playlist("source"), playlist("managed"))
        preferences.selectedId = "managed"
        preferences.managedIds += "managed"

        val result = repository.loadEligiblePlaylists()

        assertEquals(listOf("source"), result.playlists.map { it.id })
        assertNull(result.selectedPlaylistId)
        assertNull(preferences.selectedId)
    }

    @Test
    fun `detects exact marker regardless of name and excludes unresolved candidate`() = runBlocking {
        api.singlePage(
            playlist("source"),
            playlist("legacy", "An arbitrary name", CreateOutputPlaylist.OUTPUT_DESCRIPTION),
        )

        val result = repository.loadEligiblePlaylists()

        assertEquals(listOf("legacy"), result.recoveryCandidates.map { it.id })
        assertEquals(listOf("source"), result.playlists.map { it.id })
        assertEquals(listOf("source", "legacy"), result.playlistsIfRecoveryDeclined.map { it.id })
    }

    @Test
    fun `name-only random suffix is not a recovery candidate`() = runBlocking {
        api.singlePage(playlist("ordinary", "Something - RANDOM"))

        val result = repository.loadEligiblePlaylists()

        assertTrue(result.recoveryCandidates.isEmpty())
        assertEquals(listOf("ordinary"), result.playlists.map { it.id })
    }

    @Test
    fun `managed and declined marked playlists are not candidates`() = runBlocking {
        api.singlePage(
            playlist("managed", description = CreateOutputPlaylist.OUTPUT_DESCRIPTION),
            playlist("declined", description = CreateOutputPlaylist.OUTPUT_DESCRIPTION),
        )
        preferences.managedIds += "managed"
        preferences.declinedIds += "declined"

        val result = repository.loadEligiblePlaylists()

        assertTrue(result.recoveryCandidates.isEmpty())
        assertEquals(listOf("declined"), result.playlists.map { it.id })
    }

    @Test
    fun `recover registers multiple candidates durably and recreated repository excludes them`() = runBlocking {
        val candidates = setOf("legacy-one", "legacy-two")
        assertTrue(repository.recoverCandidates(candidates))
        api.singlePage(
            *candidates.map {
                playlist(it, description = CreateOutputPlaylist.OUTPUT_DESCRIPTION)
            }.toTypedArray(),
        )

        val recreated = PlaylistRepository(AccessTokenProvider { "access-token" }, api, preferences)
        val result = recreated.loadEligiblePlaylists()

        assertEquals(candidates, preferences.managedIds)
        assertTrue(result.recoveryCandidates.isEmpty())
        assertTrue(result.playlists.isEmpty())
    }

    @Test
    fun `decline persists and makes candidate eligible after repository recreation`() = runBlocking {
        assertTrue(repository.declineCandidates(setOf("legacy")))
        api.singlePage(playlist("legacy", description = CreateOutputPlaylist.OUTPUT_DESCRIPTION))

        val recreated = PlaylistRepository(AccessTokenProvider { "access-token" }, api, preferences)
        val result = recreated.loadEligiblePlaylists()

        assertEquals(setOf("legacy"), preferences.declinedIds)
        assertTrue(result.recoveryCandidates.isEmpty())
        assertEquals(listOf("legacy"), result.playlists.map { it.id })
    }

    @Test
    fun `failed recovery persistence leaves every candidate unresolved and does not write Spotify`() = runBlocking {
        preferences.durableWriteSucceeds = false
        api.singlePage(
            playlist("one", description = CreateOutputPlaylist.OUTPUT_DESCRIPTION),
            playlist("two", description = CreateOutputPlaylist.OUTPUT_DESCRIPTION),
        )

        assertFalse(repository.recoverCandidates(setOf("one", "two")))
        val result = repository.loadEligiblePlaylists()

        assertTrue(preferences.managedIds.isEmpty())
        assertEquals(setOf("one", "two"), result.recoveryCandidates.map { it.id }.toSet())
        assertEquals(1, api.requestedUrls.size)
    }

    @Test
    fun `recovery diagnostics contain counts and actions but no playlist metadata`() = runBlocking {
        val events = mutableListOf<DiagnosticEvent>()
        val diagnosticRepository = PlaylistRepository(
            AccessTokenProvider { "access-token" },
            api,
            preferences,
            DiagnosticLogger(events::add),
        )
        val secretName = "Private playlist name"
        api.singlePage(playlist("secret-id", secretName, CreateOutputPlaylist.OUTPUT_DESCRIPTION))

        diagnosticRepository.loadEligiblePlaylists()
        diagnosticRepository.recoverCandidates(setOf("secret-id"))

        assertTrue(events.any { it.eventName == "managed_playlist_recovery_candidates_detected" && it.intendedItems == 1 })
        assertTrue(events.any { it.eventName == "managed_playlist_recovery_chosen" && it.intendedItems == 1 })
        assertFalse(events.joinToString().contains(secretName))
        assertFalse(events.joinToString().contains("secret-id"))
        assertFalse(events.joinToString().contains(CreateOutputPlaylist.OUTPUT_DESCRIPTION))
    }

    private fun playlist(
        id: String,
        name: String = "Playlist $id",
        description: String? = null,
    ) = Playlist(id = id, name = name, description = description)

    private class FakePlaylistApi : PlaylistApi {
        val pages = mutableMapOf<String, PlaylistPage>()
        val requestedUrls = mutableListOf<String>()

        override suspend fun getPlaylistsPage(url: String, accessToken: String): PlaylistPage {
            requestedUrls += url
            return pages.getValue(url)
        }

        fun singlePage(vararg playlists: Playlist) {
            pages[INITIAL_URL] = PlaylistPage(playlists.toList(), nextUrl = null)
        }
    }

    private class FakePlaylistPreferences : PlaylistPreferences {
        val managedIds = mutableSetOf<String>()
        val declinedIds = mutableSetOf<String>()
        var durableWriteSucceeds = true
        var selectedId: String? = null
        var clearSelectionCount = 0

        override fun loadManagedPlaylistIds(): Set<String> = managedIds
        override suspend fun addManagedPlaylistId(playlistId: String): Boolean {
            if (!durableWriteSucceeds) return false
            managedIds += playlistId
            return true
        }
        override suspend fun addManagedPlaylistIds(playlistIds: Set<String>): Boolean {
            if (!durableWriteSucceeds) return false
            managedIds += playlistIds
            return true
        }
        override fun loadDeclinedRecoveryPlaylistIds(): Set<String> = declinedIds
        override suspend fun addDeclinedRecoveryPlaylistIds(playlistIds: Set<String>): Boolean {
            if (!durableWriteSucceeds) return false
            declinedIds += playlistIds
            return true
        }

        override fun loadSelectedPlaylistId(): String? = selectedId
        override fun saveSelectedPlaylistId(playlistId: String) {
            selectedId = playlistId
        }

        override fun clearSelectedPlaylistId() {
            selectedId = null
            clearSelectionCount += 1
        }
    }

    private companion object {
        const val INITIAL_URL = "https://api.spotify.com/v1/me/playlists?limit=50"
        const val SECOND_URL = "https://api.spotify.com/v1/me/playlists?limit=50&offset=50"
    }
}
