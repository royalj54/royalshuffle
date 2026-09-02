package com.royalshuffle.android.output

import com.royalshuffle.android.data.remote.SpotifyWebApiException
import com.royalshuffle.android.data.remote.WebApiFailureCategory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OutputUiStateTest {
    @Test
    fun `managed registration failure explains created but unpopulated playlist`() {
        assertEquals(
            "RoyalShuffle created the Spotify playlist, but could not safely register it on " +
                "this device. No tracks were added.",
            OutputViewModel.MANAGED_REGISTRATION_FAILURE_MESSAGE,
        )
    }

    @Test
    fun `success reports excluded local items`() {
        val state = OutputUiState.Success(
            playlistName = "Source - RANDOM",
            itemCount = 2,
            skippedLocalItemCount = 3,
        )

        assertEquals(
            "Created Source - RANDOM with 2 items. 3 local items were excluded.",
            state.message(),
        )
    }

    @Test
    fun `success message is unchanged when no local items were skipped`() {
        val state = OutputUiState.Success(
            playlistName = "Source - RANDOM",
            itemCount = 2,
            skippedLocalItemCount = 0,
        )

        assertEquals("Created Source - RANDOM with 2 items.", state.message())
    }

    @Test
    fun `partial failure message uses confirmed counts and states no retry or rollback`() {
        val error = PartialPlaylistWriteException(
            outputPlaylistId = "output-id",
            outputPlaylistName = "Source - RANDOM",
            confirmedItemsWritten = 100,
            totalItemsIntended = 205,
            underlyingReason = OutputPlaylistException.Reason.NETWORK,
            underlyingFailureCategory = null,
            cause = OutputPlaylistException(OutputPlaylistException.Reason.NETWORK),
        )

        val message = error.toUserMessage()

        assertTrue(message.contains("created Source - RANDOM"))
        assertTrue(message.contains("100 of 205 items confirmed written"))
        assertTrue(message.contains("partial private playlist remains in Spotify"))
        assertTrue(message.contains("did not automatically retry or roll it back"))
        assertFalse(message.contains("deleted", ignoreCase = true))
        assertFalse(message.contains("removed", ignoreCase = true))
    }

    @Test
    fun `partial quota failure explicitly identifies Spotify developer quota`() {
        val error = PartialPlaylistWriteException(
            outputPlaylistId = "output-id",
            outputPlaylistName = "Source - RANDOM",
            confirmedItemsWritten = 0,
            totalItemsIntended = 100,
            underlyingReason = null,
            underlyingFailureCategory = WebApiFailureCategory.QUOTA_EXCEEDED,
            cause = SpotifyWebApiException(WebApiFailureCategory.QUOTA_EXCEEDED),
        )

        val message = error.toUserMessage()

        assertTrue(message.contains("Spotify developer quota was exceeded"))
        assertTrue(message.contains("0 of 100 items confirmed written"))
    }
}
