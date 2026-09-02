package com.royalshuffle.android.ui

import com.royalshuffle.android.data.remote.SpotifyWebApiException
import com.royalshuffle.android.data.remote.WebApiFailureCategory
import org.junit.Assert.assertEquals
import org.junit.Test

class SpotifyWebApiMessagesTest {
    @Test
    fun `quota message explicitly identifies Spotify developer quota exhaustion`() {
        val message = spotifyWebApiMessage(
            SpotifyWebApiException(WebApiFailureCategory.QUOTA_EXCEEDED),
        )

        assertEquals(
            "Spotify developer quota exceeded. RoyalShuffle cannot make additional Spotify " +
                "requests right now. Try again later.",
            message,
        )
    }

    @Test
    fun `failure categories have concise distinct user messages`() {
        val categories = listOf(
            WebApiFailureCategory.AUTHENTICATION,
            WebApiFailureCategory.PERMISSION,
            WebApiFailureCategory.RATE_LIMITED,
            WebApiFailureCategory.SERVER,
            WebApiFailureCategory.CONNECTIVITY,
            WebApiFailureCategory.INVALID_RESPONSE,
        )

        val messages = categories.map {
            spotifyWebApiMessage(SpotifyWebApiException(it))
        }

        assertEquals(categories.size, messages.filterNotNull().distinct().size)
    }
}
