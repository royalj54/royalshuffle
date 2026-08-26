package com.royalshuffle.android.output

import com.royalshuffle.android.auth.AccessTokenProvider
import com.royalshuffle.android.domain.model.Playlist
import com.royalshuffle.android.playlist.PlaylistPreferences
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CreateOutputPlaylistTest {
    private val events = mutableListOf<String>()
    private val api = FakeOutputApi(events)
    private val preferences = FakePreferences(events)
    private val useCase = CreateOutputPlaylist(
        accessTokenProvider = AccessTokenProvider { "access-token" },
        api = api,
        preferences = preferences,
        shuffler = UriShuffler { it.reversed() },
    )

    @Test
    fun `follows item pagination and skips null missing and blank URIs`() = runBlocking {
        api.pages[INITIAL_URL] = PlaylistItemsPage(
            listOf("spotify:track:one", null, ""),
            SECOND_URL,
        )
        api.pages[SECOND_URL] = PlaylistItemsPage(listOf(null, "spotify:track:two"), null)

        useCase.execute(SOURCE)

        assertEquals(listOf(INITIAL_URL, SECOND_URL), api.requestedUrls)
        assertEquals(listOf("spotify:track:two", "spotify:track:one"), api.addedUris.flatten())
    }

    @Test
    fun `adds shuffled items in ordered batches of at most 100`() = runBlocking {
        val uris = (1..205).map { "spotify:track:$it" }
        api.singlePage(uris)

        useCase.execute(SOURCE)

        assertEquals(listOf(100, 100, 5), api.addedUris.map(List<String>::size))
        assertEquals(uris.reversed(), api.addedUris.flatten())
    }

    @Test
    fun `registers output ID only after creation and all population succeeds`() = runBlocking {
        api.singlePage(listOf("spotify:track:one"))

        useCase.execute(SOURCE)

        assertEquals(listOf("output-id"), preferences.managedIds)
        assertEquals(listOf("create", "add", "register"), events)
    }

    @Test
    fun `protects source when Spotify returns the source ID as output`() = runBlocking {
        api.singlePage(listOf("spotify:track:one"))
        api.createdPlaylist = SOURCE.copy(name = "Unexpected")

        val error = runCatching { useCase.execute(SOURCE) }.exceptionOrNull()

        assertTrue(error is OutputPlaylistException)
        assertEquals(
            OutputPlaylistException.Reason.SOURCE_OUTPUT_ID_COLLISION,
            (error as OutputPlaylistException).reason,
        )
        assertTrue(api.addedUris.isEmpty())
        assertTrue(preferences.managedIds.isEmpty())
    }

    @Test
    fun `does not register when playlist creation fails`() = runBlocking {
        api.singlePage(emptyList())
        api.failCreation = true

        assertTrue(runCatching { useCase.execute(SOURCE) }.isFailure)
        assertTrue(preferences.managedIds.isEmpty())
    }

    @Test
    fun `does not register when population fails`() = runBlocking {
        api.singlePage(listOf("spotify:track:one"))
        api.failAdd = true

        assertTrue(runCatching { useCase.execute(SOURCE) }.isFailure)
        assertTrue(preferences.managedIds.isEmpty())
    }

    @Test
    fun `preserves duplicate items`() = runBlocking {
        api.singlePage(listOf("spotify:track:same", "spotify:track:other", "spotify:track:same"))

        useCase.execute(SOURCE)

        val added = api.addedUris.flatten()
        assertEquals(3, added.size)
        assertEquals(2, added.count { it == "spotify:track:same" })
        assertFalse(added.distinct().size == added.size)
    }

    private class FakeOutputApi(private val events: MutableList<String>) : OutputPlaylistApi {
        val pages = mutableMapOf<String, PlaylistItemsPage>()
        val requestedUrls = mutableListOf<String>()
        val addedUris = mutableListOf<List<String>>()
        var createdPlaylist = Playlist("output-id", "Source - RANDOM")
        var failCreation = false
        var failAdd = false

        override suspend fun getPlaylistItemsPage(url: String, accessToken: String): PlaylistItemsPage {
            requestedUrls += url
            return pages.getValue(url)
        }

        override suspend fun createPrivatePlaylist(
            name: String,
            description: String,
            accessToken: String,
        ): Playlist {
            events += "create"
            if (failCreation) error("creation failed")
            assertEquals("Source - RANDOM", name)
            assertEquals(CreateOutputPlaylist.OUTPUT_DESCRIPTION, description)
            return createdPlaylist
        }

        override suspend fun addItems(playlistId: String, uris: List<String>, accessToken: String) {
            events += "add"
            if (failAdd) error("add failed")
            addedUris += uris
        }

        fun singlePage(uris: List<String>) {
            pages[INITIAL_URL] = PlaylistItemsPage(uris, null)
        }
    }

    private class FakePreferences(private val events: MutableList<String>) : PlaylistPreferences {
        val managedIds = mutableListOf<String>()
        private var selectedId: String? = null
        override fun loadManagedPlaylistIds(): Set<String> = managedIds.toSet()
        override fun addManagedPlaylistId(playlistId: String) {
            managedIds += playlistId
            events += "register"
        }
        override fun loadSelectedPlaylistId(): String? = selectedId
        override fun saveSelectedPlaylistId(playlistId: String) { selectedId = playlistId }
        override fun clearSelectedPlaylistId() { selectedId = null }
    }

    private companion object {
        val SOURCE = Playlist("source-id", "Source")
        const val INITIAL_URL =
            "https://api.spotify.com/v1/playlists/source-id/items?limit=50"
        const val SECOND_URL =
            "https://api.spotify.com/v1/playlists/source-id/items?limit=50&offset=50"
    }
}
