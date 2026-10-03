package com.royalshuffle.android.opportunity

import com.royalshuffle.android.output.*
import org.junit.Assert.*
import org.junit.Test

class OpportunityPlannerTest {
    private val ordinary = OutputPlanner(OccurrenceShuffler { it })
    private val planner = OpportunityPlanner(ordinary)
    private fun item(id: String, duration: Long = 2_000_000, artist: String = "artist") =
        OutputPlaylistItem("spotify:track:$id", durationMs = duration, primaryArtistId = artist)

    @Test fun `exact URI duplicates retain first metadata and different URIs remain distinct`() {
        val first = item("a")
        val rotation = planner.candidate(listOf(first, item("a", 1, "different"), item("b")))
        assertEquals(2, rotation.originalUniqueCount)
        assertEquals(OpportunityTrack(first.uri!!, first.durationMs!!, first.primaryArtistId), rotation.undealt[0])
        assertEquals(listOf("spotify:track:a", "spotify:track:b"), rotation.undealt.map { it.uri })
    }

    @Test fun `frozen records and immutable prepared order do not alias source`() {
        val source = mutableListOf(item("a"), item("b"), item("c"))
        val rotation = planner.candidate(source)
        source.clear()
        val deal = planner.prepare("source", rotation)
        assertEquals(3, rotation.undealt.size)
        assertEquals(listOf("spotify:track:a", "spotify:track:b"), deal.uris)
        assertThrows(UnsupportedOperationException::class.java) { (deal.uris as MutableList).clear() }
        assertThrows(UnsupportedOperationException::class.java) { (rotation.undealt as MutableList).clear() }
    }

    @Test fun `crossing track included then stops and preparation never depletes`() {
        val rotation = planner.candidate(listOf(item("a"), item("b"), item("c", 1)))
        val deal = planner.prepare("source", rotation)
        assertEquals(2, deal.tracks.size)
        assertEquals(4_000_000L, deal.tracks.sumOf { it.durationMs })
        assertEquals(3, rotation.undealt.size)
        assertEquals(0, rotation.completedDealCount)
    }

    @Test fun `exact threshold stops singleton overshoot and final short deal exhausts`() {
        assertEquals(1, planner.prepare("source", planner.candidate(listOf(item("a", 3_600_000), item("b")))).tracks.size)
        assertEquals(1, planner.prepare("source", planner.candidate(listOf(item("a", Long.MAX_VALUE)))).tracks.size)
        assertEquals(2, planner.prepare("source", planner.candidate(listOf(item("a", 1), item("b", 1)))).tracks.size)
        val exhausted = OpportunityRotation(java.util.UUID.randomUUID().toString(), 2, 1, emptyList())
        assertThrows(OpportunityStateException::class.java) { planner.prepare("source", exhausted) }
    }

    @Test fun `artist separation reorders only selected membership`() {
        val items = listOf(item("a", 1_200_000, "A"), item("b", 1_200_000, "A"), item("c", 1_200_000, "B"), item("d", 1, "C"))
        val rotation = planner.candidate(items)
        val plain = planner.prepare("source", rotation)
        val separated = planner.prepare("source", rotation, true)
        assertNotEquals(plain.uris, separated.uris)
        assertEquals(plain.tracks.toSet(), separated.tracks.toSet())
        assertEquals(plain.tracks.sumOf { it.durationMs }, separated.tracks.sumOf { it.durationMs })
        assertFalse(separated.uris.contains("spotify:track:d"))
        assertEquals(4, rotation.undealt.size)
    }

    @Test fun `ordinary planner preserves duplicate occurrences while opportunity deduplicates`() {
        val same = item("a", 1)
        val loaded = listOf(same, same, same.copy(), item("b", 1))
        assertEquals(loaded, ordinary.plan(loaded, OutputOptions()).items)
        assertEquals(4, ordinary.plan(loaded, OutputOptions(java.math.BigInteger.valueOf(60))).items.size)
        assertEquals(2, planner.candidate(loaded).undealt.size)
    }

    @Test fun `eligibility matches accepted filtering and invalid first duration fails`() {
        assertEquals(1, planner.candidate(listOf(item("ok"), item("local").copy(isLocal = true),
            item("episode").copy(itemType = "episode"), item("bad").copy(uri = null))).undealt.size)
        assertThrows(OpportunityStateException::class.java) { planner.candidate(listOf(item("a", 0), item("a"))) }
        assertThrows(OpportunityStateException::class.java) { planner.candidate(emptyList()) }
        // A later duplicate's invalid metadata cannot replace or invalidate the first record.
        assertEquals(1, planner.candidate(listOf(item("a"), item("a", 0))).undealt.size)
    }
}
