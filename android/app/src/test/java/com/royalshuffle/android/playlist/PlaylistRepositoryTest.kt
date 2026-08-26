package com.royalshuffle.android.playlist

import com.royalshuffle.android.auth.AccessTokenProvider
import com.royalshuffle.android.domain.model.Playlist
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

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

    private fun playlist(id: String) = Playlist(id = id, name = "Playlist $id")

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
        var selectedId: String? = null
        var clearSelectionCount = 0

        override fun loadManagedPlaylistIds(): Set<String> = managedIds
        override fun addManagedPlaylistId(playlistId: String) {
            managedIds += playlistId
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
