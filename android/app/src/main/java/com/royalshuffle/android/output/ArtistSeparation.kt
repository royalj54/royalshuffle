package com.royalshuffle.android.output

import java.security.SecureRandom
import java.util.IdentityHashMap
import java.util.Random

/** Kotlin port of Windows/Core's first-credited artist sequencer; linear ordering only. */
object ArtistSeparation {
    fun validateMetadata(items: List<OutputPlaylistItem>) {
        if (items.any { it.primaryArtistId.isNullOrBlank() }) {
            throw OutputPlanningException("Artist Separation requires a usable first-credited Spotify artist ID for every selected track. Disable Artist Separation to use ordinary True Random.")
        }
    }

    fun separate(items: List<OutputPlaylistItem>, rng: Random = SecureRandom()): List<OutputPlaylistItem> {
        validateMetadata(items)
        val buckets = linkedMapOf<String, ArrayDeque<OutputPlaylistItem>>()
        items.forEach { buckets.getOrPut(it.primaryArtistId!!) { ArrayDeque() }.addLast(it) }
        return artistOrder(buckets.mapValues { it.value.size }, rng).map {
            buckets.getValue(it).removeFirst()
        }
    }

    private fun artistOrder(originalCounts: Map<String, Int>, rng: Random): List<String> {
        val counts = originalCounts.toMutableMap()
        val total = counts.values.sum()
        if (total == 0) return emptyList()
        val dominant = counts.maxBy { it.value }.key
        val largest = counts.getValue(dominant)
        val others = total - largest
        if (largest > others + 1) {
            val separators = artistOrder(counts.filterKeys { it != dominant }, rng)
            val size = largest / (others + 1)
            val extra = largest % (others + 1)
            val blocks = MutableList(others + 1) { size + if (it < extra) 1 else 0 }
            java.util.Collections.shuffle(blocks, rng)
            return buildList {
                blocks.forEachIndexed { index, block ->
                    repeat(block) { add(dominant) }
                    if (index < others) add(separators[index])
                }
            }
        }
        val lastPosition = mutableMapOf<String, Int>()
        var previous: String? = null
        return buildList {
            repeat(total) { position ->
                val remaining = total - position - 1
                val top = counts.maxBy { it.value }
                val second = counts.filterKeys { it != top.key }.values.maxOrNull() ?: 0
                val scores = linkedMapOf<String, Long>()
                counts.forEach { (artist, count) ->
                    val otherMax = if (artist == top.key) second else top.value
                    if (artist != previous && count - 1 <= remaining / 2 &&
                        otherMax <= (remaining + 1) / 2) {
                        scores[artist] = lastPosition[artist]?.let {
                            minOf(total.toLong(), (position - it).toLong() * originalCounts.getValue(artist))
                        } ?: total.toLong()
                    }
                }
                val best = scores.values.maxOrNull()
                    ?: throw OutputPlanningException("Artist Separation could not produce a valid order. No output was changed.")
                val choices = scores.filterValues { 3 * (best - it) <= total }.keys.toList()
                val chosen = choices[rng.nextInt(choices.size)]
                add(chosen)
                lastPosition[chosen] = position
                val nextCount = counts.getValue(chosen) - 1
                if (nextCount == 0) counts.remove(chosen) else counts[chosen] = nextCount
                previous = chosen
            }
        }
    }

    /** Identity counts protect exact occurrences, including equal values and repeated references.
     * All occurrence metadata is immutable, so preserving references also preserves metadata. */
    fun validateOccurrences(before: List<OutputPlaylistItem>, after: List<OutputPlaylistItem>) {
        val counts = IdentityHashMap<OutputPlaylistItem, Int>()
        before.forEach { counts[it] = (counts[it] ?: 0) + 1 }
        var valid = before.size == after.size
        after.forEach {
            val count = counts[it] ?: 0
            if (count <= 0) valid = false else counts[it] = count - 1
        }
        if (!valid || counts.values.any { it != 0 }) {
            throw OutputPlanningException("Selection invariant failed: exact occurrences were not preserved. No output was changed.")
        }
    }

    fun validate(before: List<OutputPlaylistItem>, after: List<OutputPlaylistItem>) {
        validateMetadata(before)
        validateOccurrences(before, after)
        val buckets = before.groupBy { it.primaryArtistId }
        val largest = buckets.values.maxOfOrNull { it.size } ?: 0
        val minimum = maxOf(0L, 2L * largest - before.size - 1)
        val adjacency = after.zipWithNext().count { (a, b) -> a.primaryArtistId == b.primaryArtistId }
        val orderPreserved = buckets.all { (artist, originals) ->
            val result = after.filter { it.primaryArtistId == artist }
            originals.indices.all { originals[it] === result[it] }
        }
        if (adjacency.toLong() != minimum || !orderPreserved) {
            throw OutputPlanningException("Artist Separation invariant failed: adjacency or within-artist order. No output was changed.")
        }
    }
}
