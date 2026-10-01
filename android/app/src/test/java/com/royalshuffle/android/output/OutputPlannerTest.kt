package com.royalshuffle.android.output

import java.math.BigInteger
import java.util.Random
import org.junit.Assert.*
import org.junit.Test

class OutputPlannerTest {
    private val planner = OutputPlanner(OccurrenceShuffler { it })

    @Test fun `full retains exact randomized occurrences and duplicates`() {
        val same = track("same")
        val items = listOf(same, track("other"), same, same.copy())
        val plan = OutputPlanner(OccurrenceShuffler { it.reversed() }).plan(items, OutputOptions())
        assertEquals(items.reversed(), plan.items)
        items.reversed().zip(plan.items).forEach { (a, b) -> assertSame(a, b) }
    }

    @Test fun `60M includes crossing track without optimizing duration`() {
        val items = listOf(track("one", 2_000_000), track("two", 2_000_000), track("three", 1))
        val plan = planner.plan(items, OutputSettings(mode = SessionLengthMode.SIXTY_MINUTES).snapshot())
        assertEquals(items.take(2), plan.items)
        assertEquals(BigInteger.valueOf(4_000_000), plan.durationMs)
        assertEquals(BigInteger.valueOf(3_600_000), plan.requestedDurationMs)
        assertFalse(plan.sourceShorterThanTarget)
    }

    @Test fun `timed membership consumes randomized order rather than source order`() {
        val items = listOf(track("first", 60_000), track("second", 30_000), track("third", 40_000))
        val plan = OutputPlanner(OccurrenceShuffler { it.reversed() }).plan(items, timed(1))
        assertEquals(listOf(items[2], items[1]), plan.items)
        assertEquals(BigInteger.valueOf(70_000), plan.durationMs)
    }

    @Test fun `custom crossing exact boundary and short source match Windows fixtures`() {
        val items = (1..3).map { track("$it", 900_000) }
        for ((minutes, count) in listOf(1 to 1, 16 to 2, 30 to 2, 120 to 3)) {
            val plan = planner.plan(items, timed(minutes))
            assertEquals(items.take(count), plan.items)
            assertEquals(BigInteger.valueOf(900_000L * count), plan.durationMs)
            assertEquals(minutes > 45, plan.sourceShorterThanTarget)
        }
    }

    @Test fun `custom 60 equals preset and leading zeros normalize`() {
        val items = (1..10).map { track("$it", 800_000) }
        val preset = OutputSettings(mode = SessionLengthMode.SIXTY_MINUTES).snapshot()
        val custom = OutputSettings(mode = SessionLengthMode.CUSTOM, customMinutes = "060").snapshot()
        assertEquals(preset, custom)
        assertEquals(planner.plan(items, preset), planner.plan(items, custom))
    }

    @Test fun `positive integer parsing rejects invalid drafts`() {
        for (text in listOf("", " ", "0", "-1", "1.5", "+60", "NaN", "60M", "６０")) {
            assertNull(parseCustomMinutes(text))
            fails { OutputSettings(mode = SessionLengthMode.CUSTOM, customMinutes = text).snapshot() }
        }
        assertEquals(BigInteger.valueOf(90), parseCustomMinutes(" 0090 "))
        for (minutes in listOf(BigInteger.ZERO, BigInteger.valueOf(-1))) {
            fails { planner.plan(listOf(track("one")), OutputOptions(minutes)) }
        }
    }

    @Test fun `arbitrary precision target and summed durations cannot wrap`() {
        val huge = BigInteger.TEN.pow(100)
        val options = OutputSettings(mode = SessionLengthMode.CUSTOM, customMinutes = huge.toString()).snapshot()
        val items = listOf(track("one", Long.MAX_VALUE), track("two", Long.MAX_VALUE))
        val plan = planner.plan(items, options)
        assertEquals(items, plan.items)
        assertEquals(huge.multiply(BigInteger.valueOf(60_000)), plan.requestedDurationMs)
        assertEquals(BigInteger.valueOf(Long.MAX_VALUE).multiply(BigInteger.TWO), plan.durationMs)
        assertTrue(plan.sourceShorterThanTarget)
        assertEquals(plan.durationMs, planner.plan(items, OutputOptions()).durationMs)
    }

    @Test fun `timed validates durations beyond selected prefix while Full remains usable`() {
        for (duration in listOf(null, 0L, -1L)) {
            val items = listOf(track("crossing", 3_600_000), track("invalid").copy(durationMs = duration))
            fails { planner.plan(items, timed(60)) }
            assertEquals(items, planner.plan(items, OutputOptions()).items)
        }
    }

    @Test fun `eligibility excludes malformed URIs local and explicit unsupported types`() {
        val good = track("good").copy(itemType = "track")
        val legacyTypeMissing = track("legacy")
        val loaded = listOf(good, legacyTypeMissing,
            track("local").copy(isLocal = true),
            track("one").copy(uri = "spotify:local:a:b:c:1"),
            track("one").copy(itemType = "episode"),
            track("one").copy(uri = "spotify:episode:abc"),
            track("one").copy(uri = null), track("one").copy(uri = ""),
            track("one").copy(uri = "spotify:track:"),
            track("one").copy(uri = "spotify:track:a b"),
            track("one").copy(uri = "spotify:track:é"))
        val plan = planner.plan(loaded, OutputOptions())
        assertEquals(listOf(good, legacyTypeMissing), plan.items)
        assertEquals(2, plan.skippedLocalItemCount)
        assertEquals(7, plan.skippedUnsupportedItemCount)
    }

    @Test fun `zero eligible tracks fails even in Full`() {
        fails { planner.plan(emptyList(), OutputOptions()) }
        fails { planner.plan(listOf(track("one").copy(isLocal = true)), OutputOptions()) }
    }

    @Test fun `separation preserves timed membership and duration after randomized prefix`() {
        val items = listOf(track("one", 40_000, "A"), track("two", 40_000, "A"),
            track("three", 40_000, "B"), track("four", 40_000, "C"))
        val ordinary = planner.plan(items, timed(2))
        val separated = planner.plan(items, timed(2).copy(artistSeparation = true))
        assertEquals(ordinary.items.toSet(), separated.items.toSet())
        assertEquals(ordinary.durationMs, separated.durationMs)
        assertEquals(listOf("A", "B", "A"), separated.items.map { it.primaryArtistId })
        assertFalse(separated.items.contains(items.last()))
    }

    @Test fun `missing artist identity fails only if selected and separation enabled`() {
        val items = listOf(track("one", 60_000), track("two").copy(primaryArtistId = null))
        assertEquals(1, planner.plan(items, timed(1).copy(artistSeparation = true)).items.size)
        assertEquals(2, planner.plan(items, OutputOptions()).items.size)
        fails { planner.plan(items, OutputOptions(artistSeparation = true)) }
    }

    @Test fun `planner independent guard rejects faulty ordering and equal-valued replacements`() {
        val items = listOf(track("one", artist = "A"), track("two", artist = "A"), track("three", artist = "B"))
        fails { OutputPlanner(OccurrenceShuffler { it }, separator = { it })
            .plan(items, OutputOptions(artistSeparation = true)) }
        fails { OutputPlanner(OccurrenceShuffler { it.map { item -> item.copy() } })
            .plan(items, OutputOptions()) }
        fails { OutputPlanner(OccurrenceShuffler { it }, separator = {
            it.toMutableList().apply { clear() }
        }).plan(items, OutputOptions(artistSeparation = true)) }
    }

    private fun timed(minutes: Int) = OutputOptions(BigInteger.valueOf(minutes.toLong()))
    private fun fails(block: () -> Unit) {
        assertTrue(runCatching(block).exceptionOrNull() is OutputPlanningException)
    }
    private fun track(id: String, duration: Long = 60_000, artist: String = "artist") =
        OutputPlaylistItem("spotify:track:$id", durationMs = duration, primaryArtistId = artist)
}

class ArtistSeparationTest {
    @Test fun `representative feasible impossible boundary and degenerate distributions`() {
        for (counts in listOf(emptyList(), listOf(1), listOf(13), listOf(1,1,1,1,1),
            listOf(7,7), listOf(5,5,5,5), listOf(8,3,3,2,2,2), listOf(12,2,2,1),
            listOf(36,15,8,6,5), listOf(36,15,8,6,6), listOf(36,15,8,6,7))) {
            for (seed in listOf(0L, 1L, 17L, 42L)) check(counts, seed)
        }
    }

    @Test fun `small exhaustive distributions match minimum adjacency and longest run oracle`() {
        for (a in 0..4) for (b in 0..4) for (c in 0..4) {
            val counts = listOf(a,b,c)
            if (counts.sum() !in 1..7) continue
            val expected = oracle(counts)
            for (seed in listOf(0L,5L)) {
                val result = check(counts, seed)
                assertEquals(expected, metrics(result.map { it.primaryArtistId!! }))
            }
        }
    }

    @Test fun `dominant runs are balanced with randomized placement of extras`() {
        val outputs = (0L..10L).map { seed ->
            val result = check(listOf(14,2,2), seed)
            runs(result.map { it.primaryArtistId!! }).filter { it.first == "0" }.map { it.second }
        }
        outputs.forEach { assertEquals(listOf(2,3,3,3,3), it.sorted()) }
        assertTrue(outputs.distinct().size > 1)
    }

    @Test fun `IDs distinguish group from solo and preserve identity across display changes`() {
        val group = OutputPlaylistItem("spotify:track:group", primaryArtistId = "BLACKPINK-id")
        val solo = OutputPlaylistItem("spotify:track:solo", primaryArtistId = "Lisa-id")
        val result = ArtistSeparation.separate(listOf(group,group,solo), Random(0))
        assertEquals(listOf(group,solo,group), result)
        // The repeated first/last occurrence is legal; no wraparound constraint.
        ArtistSeparation.validate(listOf(group,group,solo), result)
    }

    @Test fun `missing artist rejected without changing input`() {
        for (artist in listOf(null, "", " \t")) {
            val items = listOf(OutputPlaylistItem("spotify:track:one", primaryArtistId = artist))
            assertTrue(runCatching { ArtistSeparation.separate(items) }.exceptionOrNull() is OutputPlanningException)
            assertEquals(artist, items.single().primaryArtistId)
        }
    }

    @Test fun `guard rejects lost duplicates metadata copies adjacency and within artist reorder`() {
        val a = OutputPlaylistItem("spotify:track:a", primaryArtistId = "A")
        val a2 = a.copy(uri = "spotify:track:b")
        val b = a.copy(uri = "spotify:track:c", primaryArtistId = "B")
        val original = listOf(a,a2,b)
        for (bad in listOf(listOf(a,b), listOf(a.copy(),b,a2), original, listOf(a2,b,a))) {
            assertTrue(runCatching { ArtistSeparation.validate(original, bad) }
                .exceptionOrNull() is OutputPlanningException)
        }
        ArtistSeparation.validate(original, listOf(a,b,a2))
        ArtistSeparation.validate(listOf(a,a,b), listOf(a,b,a))
    }

    private fun check(counts: List<Int>, seed: Long): List<OutputPlaylistItem> {
        val items = counts.flatMapIndexed { artist, count -> (0 until count).map {
            OutputPlaylistItem("spotify:track:duplicate", durationMs = it.toLong() + 1,
                primaryArtistId = "$artist")
        } }
        val before = items.toList()
        val result = ArtistSeparation.separate(items, Random(seed))
        assertEquals(before, items)
        ArtistSeparation.validate(before, result)
        val n = counts.sum()
        val m = counts.maxOrNull() ?: 0
        val (adjacency, longest) = metrics(result.map { it.primaryArtistId!! })
        assertEquals(maxOf(0, 2*m-n-1), adjacency)
        assertEquals(if (n == 0) 0 else (n / (n-m+1)), longest)
        return result
    }

    private fun runs(labels: List<String>): List<Pair<String, Int>> {
        val result = mutableListOf<Pair<String, Int>>()
        labels.forEach { label ->
            if (result.lastOrNull()?.first == label) {
                result[result.lastIndex] = label to result.last().second + 1
            } else result += label to 1
        }
        return result
    }
    private fun metrics(labels: List<String>): Pair<Int, Int> {
        val runs = runs(labels)
        return runs.sumOf { it.second - 1 } to (runs.maxOfOrNull { it.second } ?: 0)
    }
    private fun oracle(counts: List<Int>): Pair<Int, Int> {
        var best = Int.MAX_VALUE to Int.MAX_VALUE
        fun visit(remaining: MutableList<Int>, order: List<String>) {
            if (remaining.sum() == 0) {
                val value = metrics(order)
                if (value.first < best.first || (value.first == best.first && value.second < best.second)) best = value
            } else remaining.indices.filter { remaining[it] > 0 }.forEach { artist ->
                remaining[artist]--
                visit(remaining, order + "$artist")
                remaining[artist]++
            }
        }
        visit(counts.toMutableList(), emptyList())
        return best
    }
}
