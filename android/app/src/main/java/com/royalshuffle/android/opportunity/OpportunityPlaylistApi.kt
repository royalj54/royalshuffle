package com.royalshuffle.android.opportunity

import com.royalshuffle.android.domain.model.Playlist
import com.royalshuffle.android.output.OutputPlaylistApi

data class OpportunityWriteAcknowledgement(val status: Int, val snapshotId: String)
data class RawOpportunityPage(val slots: List<String?>, val total: Int, val nextUrl: String?, val offset: Int? = null)

interface OpportunityPlaylistApi : OutputPlaylistApi {
    suspend fun createDeal(name: String, accessToken: String): Playlist
    suspend fun clearDeal(outputId: String, accessToken: String): OpportunityWriteAcknowledgement
    suspend fun appendDeal(outputId: String, uris: List<String>, accessToken: String): OpportunityWriteAcknowledgement
    suspend fun rawDealPage(url: String, accessToken: String): RawOpportunityPage
}
