package com.royalshuffle.android.opportunity

import java.net.URI
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** Ordered raw slots, never the source eligibility-filtered view. */
class OpportunityRawVerifier(private val api: OpportunityPlaylistApi) {
    suspend fun read(outputId: String, token: String): List<String?> {
        checkState(validId(outputId), "Invalid output ID.")
        var next: String? = "https://api.spotify.com/v1/playlists/$outputId/items?limit=50"
        val visited = mutableSetOf<String>()
        var total: Int? = null
        val slots = mutableListOf<String?>()
        while (next != null) {
            currentCoroutineContext().ensureActive()
            val uri = runCatching { URI(next) }.getOrElse { throw OpportunityStateException("Invalid raw page URL.", it) }
            checkState(uri.scheme == "https" && uri.host == "api.spotify.com" && uri.userInfo == null &&
                uri.port in listOf(-1, 443) && uri.fragment == null && uri.path == "/v1/playlists/$outputId/items" && visited.add(next),
                "Invalid or cyclic raw pagination.")
            val page = api.rawDealPage(next, token)
            if (total == null) total = page.total
            checkState(page.total >= 0 && page.total == total && (page.offset == null || page.offset == slots.size),
                "Raw pagination changed or skipped contents.")
            slots.addAll(page.slots)
            checkState(slots.size <= total && (page.nextUrl == null || (page.slots.isNotEmpty() && slots.size < total)),
                "Inconsistent raw pagination.")
            next = page.nextUrl
        }
        checkState(slots.size == total, "Incomplete raw verification.")
        return frozenList(slots)
    }
}
