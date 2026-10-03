package com.royalshuffle.android.opportunity

import com.royalshuffle.android.auth.AccessTokenProvider
import com.royalshuffle.android.data.remote.*
import com.royalshuffle.android.domain.model.Playlist
import com.royalshuffle.android.output.*
import com.royalshuffle.android.playlist.*
import java.io.IOException
import java.util.UUID
import kotlinx.coroutines.CoroutineDispatcher
import org.json.JSONObject

internal class OpportunityTestDisk : OpportunityPersistence {
    override val coordinationKey = UUID.randomUUID().toString()
    var text: String? = null
    var fail: (JSONObject) -> Boolean = { false }
    override fun read() = text
    override fun replace(document: String) {
        if (fail(JSONObject(document))) throw IOException("Injected persistence failure")
        text = document
    }
    fun restarted() = OpportunityTestDisk().also { it.text = text }
}

internal class OpportunityTestPreferences : PlaylistPreferences, OrdinaryOutputRegistry {
    val managed = mutableSetOf<String>()
    val bindings = mutableMapOf<OutputIdentity, String>()
    override fun loadManagedPlaylistIds() = managed.toSet() + bindings.values
    override suspend fun addManagedPlaylistId(playlistId: String): Boolean { managed += playlistId; return true }
    override suspend fun addManagedPlaylistIds(playlistIds: Set<String>): Boolean { managed += playlistIds; return true }
    override fun loadDeclinedRecoveryPlaylistIds() = emptySet<String>()
    override suspend fun addDeclinedRecoveryPlaylistIds(playlistIds: Set<String>) = true
    override fun loadSelectedPlaylistId(): String? = null
    override fun saveSelectedPlaylistId(playlistId: String) = Unit
    override fun clearSelectedPlaylistId() = Unit
    override fun boundOutput(identity: OutputIdentity) = bindings[identity]
    override fun sourcePlaylistIds() = bindings.keys.map { it.sourceId }.toSet()
    override fun requireCurrent(identity: OutputIdentity, expectedOutputId: String?) { check(bindings[identity] == expectedOutputId) }
    override suspend fun bind(identity: OutputIdentity, outputId: String, expectedOutputId: String?) {
        requireCurrent(identity, expectedOutputId)
        bindings[identity] = outputId
        managed += outputId
    }
}

internal class OpportunityTestApi : OpportunityPlaylistApi {
    val events = mutableListOf<String>()
    val contents = linkedMapOf<String, MutableList<String?>>()
    val names = mutableMapOf<String, String>()
    var sourceItems = items(8)
    var createId: String? = null
    var createFailure: Exception? = null
    var loseCreate = false
    var clearFailure: Exception? = null
    var appendFailure: Exception? = null
    var failAppendNumber = 1
    var loseAppend = false
    var listingFailure: Exception? = null
    var directFailure: Exception? = null
    var membershipFailure: Exception? = null
    var rawFailure: Exception? = null
    var listed = true
    var saved = true
    var rawOverride: List<String?>? = null
    var rawTransform: (RawOpportunityPage) -> RawOpportunityPage = { it }
    var observe: (String) -> Unit = {}
    var beforeMutation: suspend (String) -> Unit = {}
    private var nextId = 1
    var appendCount = 0
    var sourceLoads = 0
    var creationCalls = 0
    private fun event(name: String) { events += name; observe(name) }
    override suspend fun getPlaylistItemsPage(url: String, accessToken: String): PlaylistItemsPage {
        event("source"); sourceLoads++
        return PlaylistItemsPage(sourceItems, null)
    }
    override suspend fun createDeal(name: String, accessToken: String): Playlist {
        event("create"); creationCalls++
        beforeMutation("create")
        createFailure?.let { throw it }
        val id = createId ?: "deal${nextId++}"
        names[id] = name
        contents.putIfAbsent(id, mutableListOf())
        if (loseCreate) throw IOException("Lost creation response")
        return Playlist(id, name)
    }
    override suspend fun clearDeal(outputId: String, accessToken: String): OpportunityWriteAcknowledgement {
        event("clear:$outputId")
        beforeMutation("clear:$outputId")
        clearFailure?.let { throw it }
        contents[outputId] = mutableListOf()
        return OpportunityWriteAcknowledgement(200, "clearSnapshot")
    }
    override suspend fun appendDeal(outputId: String, uris: List<String>, accessToken: String): OpportunityWriteAcknowledgement {
        event("append:$outputId:${uris.size}"); appendCount++
        beforeMutation("append:$outputId")
        if (appendCount == failAppendNumber && appendFailure != null && !loseAppend) throw appendFailure!!
        contents.getValue(outputId).addAll(uris)
        if (appendCount == failAppendNumber && appendFailure != null) throw appendFailure!!
        return OpportunityWriteAcknowledgement(201, "appendSnapshot$appendCount")
    }
    override suspend fun rawDealPage(url: String, accessToken: String): RawOpportunityPage {
        event("raw")
        rawFailure?.let { throw it }
        val id = url.substringAfter("/playlists/").substringBefore("/")
        val slots = rawOverride ?: contents[id] ?: throw SpotifyWebApiException(WebApiFailureCategory.OTHER, 404)
        val offset = url.substringAfter("offset=", "0").substringBefore('&').toInt()
        val end = minOf(offset + 50, slots.size)
        return rawTransform(RawOpportunityPage(slots.subList(offset, end).toList(), slots.size,
            if (end < slots.size) "https://api.spotify.com/v1/playlists/$id/items?offset=$end&limit=50" else null, offset))
    }
    override suspend fun getPlaylistsPage(url: String, accessToken: String): PlaylistPage {
        event("list")
        listingFailure?.let { throw it }
        return PlaylistPage(if (listed) contents.keys.map { Playlist(it, names.getValue(it)) } else emptyList(), null)
    }
    override suspend fun getPlaylist(playlistId: String, accessToken: String): Playlist {
        event("lookup")
        directFailure?.let { throw it }
        return if (playlistId in contents) Playlist(playlistId, names.getValue(playlistId))
            else throw SpotifyWebApiException(WebApiFailureCategory.OTHER, 404)
    }
    override suspend fun isPlaylistSaved(playlistId: String, accessToken: String): Boolean {
        event("membership")
        membershipFailure?.let { throw it }
        return saved
    }
    override suspend fun createPrivatePlaylist(name: String, description: String, accessToken: String) = createDeal(name, accessToken)
    override suspend fun clearItems(playlistId: String, accessToken: String) { clearDeal(playlistId, accessToken) }
    override suspend fun addItems(playlistId: String, uris: List<String>, accessToken: String) { appendDeal(playlistId, uris, accessToken) }

    companion object {
        fun items(count: Int, duration: Long = 1_200_000, prefix: String = "t") = (1..count).map {
            OutputPlaylistItem("spotify:track:$prefix$it", durationMs = duration, primaryArtistId = if (it % 3 == 0) "B" else "A")
        }
    }
}

internal class OpportunityTestEnvironment(
    private val dispatcher: CoroutineDispatcher,
    val disk: OpportunityTestDisk = OpportunityTestDisk(),
    val api: OpportunityTestApi = OpportunityTestApi(),
    val preferences: OpportunityTestPreferences = OpportunityTestPreferences(),
    val coordinator: ManagedMutationCoordinator = ManagedMutationCoordinator(),
) {
    val store = OpportunityStore(disk)
    val prompts = mutableListOf<String>()
    val names = CreationNameProvider { prompts += it; it }
    val source = Playlist("source", "Source")
    var tokenCalls = 0
    var token: String? = "token"
    var shuffles = 0
    val planner = OpportunityPlanner(OutputPlanner(OccurrenceShuffler { shuffles++; it }))
    fun workflow() = OpportunityWorkflow(AccessTokenProvider { tokenCalls++; token }, api, store, planner, preferences, preferences,
        coordinator, dispatcher)
    suspend fun generate(artist: Boolean = false) = workflow().generateNextSession(source, names, artist)!!
    suspend fun resume() = workflow().resumePendingSession(source.id, names)!!
    fun pending() = store.load().sources.getValue(source.id).pending!!
    fun restart() = OpportunityTestEnvironment(dispatcher, disk.restarted(), api, preferences, coordinator)
}
