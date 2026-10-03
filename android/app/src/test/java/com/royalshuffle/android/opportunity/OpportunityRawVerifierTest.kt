package com.royalshuffle.android.opportunity

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class OpportunityRawVerifierTest {
    private val url = "https://api.spotify.com/v1/playlists/output/items?limit=50"
    @Test fun `complete multipage slots preserve exact order and missing positions`() = runBlocking {
        val fake = OpportunityTestApi()
        fake.contents["output"] = MutableList(101) { if (it == 50) null else "spotify:track:t$it" }
        val result = OpportunityRawVerifier(fake).read("output", "token")
        assertEquals(fake.contents["output"], result)
        assertEquals(3, fake.events.count { it == "raw" })
    }

    @Test fun `cycles changed totals wrong offsets truncation and foreign pagination fail closed`() = runBlocking {
        val transforms: List<(RawOpportunityPage) -> RawOpportunityPage> = listOf(
            { it.copy(nextUrl = url) },
            { it.copy(total = if (it.offset == 0) 101 else 102) },
            { it.copy(offset = 1) },
            { it.copy(nextUrl = null) },
            { it.copy(nextUrl = "https://other.example/next") },
            { it.copy(nextUrl = "https://api.spotify.com/v1/playlists/another/items") },
            { it.copy(nextUrl = "https://user@api.spotify.com/v1/playlists/output/items") },
            { it.copy(slots = emptyList(), nextUrl = url) })
        for (transform in transforms) {
            val fake = OpportunityTestApi()
            fake.contents["output"] = MutableList(101) { "spotify:track:t$it" }
            fake.rawTransform = transform
            val error = runCatching { OpportunityRawVerifier(fake).read("output", "token") }.exceptionOrNull()
            assertTrue(error is OpportunityStateException)
        }
    }
}
