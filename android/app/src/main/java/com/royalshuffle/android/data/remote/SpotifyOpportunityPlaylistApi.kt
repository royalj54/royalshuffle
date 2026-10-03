package com.royalshuffle.android.data.remote

import com.royalshuffle.android.domain.model.Playlist
import com.royalshuffle.android.opportunity.*
import com.royalshuffle.android.output.OutputPlaylistApi
import com.royalshuffle.android.output.isUsableTrackUri
import org.json.JSONArray
import org.json.JSONObject

class SpotifyOpportunityPlaylistApi(private val webApi: SpotifyWebApiClient = SpotifyWebApiClient()) :
    OpportunityPlaylistApi, OutputPlaylistApi by SpotifyOutputPlaylistApi(webApi) {
    override suspend fun createDeal(name: String, accessToken: String): Playlist = webApi.requestAcknowledgedJson(
        WebApiRequest("POST", "https://api.spotify.com/v1/me/playlists", accessToken,
            JSONObject().put("name", name).put("public", false).put("description", DESCRIPTION).toString()),
        WebApiOperation("Opportunity playlist creation", WebApiOperationClass.NON_IDEMPOTENT_WRITE), 201,
    ) { json ->
        val id = json.get("id") as String
        require(validId(id))
        val returnedName = if (json.has("name")) json.get("name") as String else name
        require(returnedName.isNotBlank())
        Playlist(id, returnedName)
    }

    override suspend fun clearDeal(outputId: String, accessToken: String) = write(outputId, emptyList(), accessToken, true)
    override suspend fun appendDeal(outputId: String, uris: List<String>, accessToken: String): OpportunityWriteAcknowledgement {
        require(uris.isNotEmpty() && uris.size <= 100 && uris.all(::isUsableTrackUri))
        return write(outputId, uris, accessToken, false)
    }
    private suspend fun write(outputId: String, uris: List<String>, token: String, clear: Boolean): OpportunityWriteAcknowledgement {
        require(validId(outputId))
        val status = if (clear) 200 else 201
        return webApi.requestAcknowledgedJson(
            WebApiRequest(if (clear) "PUT" else "POST", "https://api.spotify.com/v1/playlists/$outputId/items", token,
                JSONObject().put("uris", JSONArray(uris)).toString()),
            WebApiOperation(if (clear) "Opportunity clear" else "Opportunity append", WebApiOperationClass.NON_IDEMPOTENT_WRITE), status,
        ) { json ->
            val snapshot = json.get("snapshot_id") as String
            require(snapshot.isNotBlank())
            OpportunityWriteAcknowledgement(status, snapshot)
        }
    }

    override suspend fun rawDealPage(url: String, accessToken: String): RawOpportunityPage = webApi.requestJson(
        WebApiRequest("GET", url, accessToken), WebApiOperation("Opportunity raw verification", WebApiOperationClass.READ),
    ) { json ->
        val total = nonnegativeInt(json.get("total"))
        require(json.has("next"))
        val next = if (json.isNull("next")) null else json.get("next") as String
        val rows = json.getJSONArray("items")
        val slots = (0 until rows.length()).map { index ->
            val row = rows.getJSONObject(index)
            require(row.has("item"))
            val item = if (row.isNull("item")) null else row.getJSONObject("item")
            val uri = item?.opt("uri") as? String
            val type = if (item == null || item.isNull("type")) null else item.opt("type")
            val local = strictLocal(row) || (item?.let(::strictLocal) ?: false)
            if (!local && (type == null || type == "track") && isUsableTrackUri(uri)) uri else null
        }
        RawOpportunityPage(slots, total, next, if (json.has("offset")) nonnegativeInt(json.get("offset")) else null)
    }

    private fun strictLocal(item: JSONObject): Boolean = if (item.has("is_local")) item.get("is_local") as Boolean else false
    private fun nonnegativeInt(value: Any): Int {
        require(value is Int || value is Long)
        val number = (value as Number).toLong()
        require(number in 0..Int.MAX_VALUE.toLong())
        return number.toInt()
    }

    companion object { const val DESCRIPTION = "Dealt by RoyalShuffle | Spotify Companion" }
}
