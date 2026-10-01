package com.royalshuffle.android.output

import java.math.BigInteger

data class OutputPlan(
    val items: List<OutputPlaylistItem>,
    val skippedLocalItemCount: Int,
    val skippedUnsupportedItemCount: Int,
    val requestedDurationMs: BigInteger?,
    val durationMs: BigInteger?,
    val sourceShorterThanTarget: Boolean,
)

class OutputPlanner(
    private val shuffler: OccurrenceShuffler,
    private val separator: (List<OutputPlaylistItem>) -> List<OutputPlaylistItem> =
        { ArtistSeparation.separate(it) },
) {
    fun plan(loaded: List<OutputPlaylistItem>, options: OutputOptions): OutputPlan {
        options.validate()
        val local = loaded.count { it.isLocal || it.uri?.startsWith("spotify:local:") == true }
        val eligible = loaded.filter {
            !it.isLocal && it.uri?.startsWith("spotify:local:") != true &&
                (it.itemType == null || it.itemType == "track") && isUsableTrackUri(it.uri)
        }
        if (eligible.isEmpty()) {
            throw OutputPlanningException("No eligible tracks remain. Choose a source with supported, non-local Spotify tracks.")
        }
        val randomized = shuffler.shuffle(eligible.toList())
        ArtistSeparation.validateOccurrences(eligible, randomized)
        val target = options.sessionMinutes?.multiply(BigInteger.valueOf(60_000))
        if (target != null && eligible.any { it.durationMs == null || it.durationMs <= 0 }) {
            throw OutputPlanningException("Timed sessions require a valid positive duration for every eligible track. Full remains available.")
        }
        var total = BigInteger.ZERO
        val selected = if (target == null) randomized else buildList {
            for (item in randomized) {
                add(item)
                total = total.add(BigInteger.valueOf(item.durationMs!!))
                if (total >= target) break
            }
        }
        val actual = if (target != null) total else if (selected.all {
                it.durationMs != null && it.durationMs > 0
            }) selected.fold(BigInteger.ZERO) { sum, item ->
                sum.add(BigInteger.valueOf(item.durationMs!!))
            } else null
        val ordered = if (options.artistSeparation) {
            ArtistSeparation.validateMetadata(selected)
            // Copy the list so a faulty sequencer cannot erase the guard's original membership.
            separator(selected.toList()).also { ArtistSeparation.validate(selected, it) }
        } else selected
        return OutputPlan(ordered.toList(), local, loaded.size - local - eligible.size,
            target, actual, target != null && actual!! < target)
    }
}

internal fun isUsableTrackUri(uri: String?): Boolean = uri != null &&
    uri.startsWith("spotify:track:") && uri.removePrefix("spotify:track:").let { id ->
        id.isNotEmpty() && id.all { it in 'a'..'z' || it in 'A'..'Z' || it in '0'..'9' }
    }
