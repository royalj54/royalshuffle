package com.royalshuffle.android.ui

import com.royalshuffle.android.data.remote.SpotifyWebApiException
import com.royalshuffle.android.data.remote.WebApiFailureCategory
import com.royalshuffle.android.opportunity.*
import org.junit.Assert.*
import org.junit.Test

class OpportunityUiMessagesTest {
    @Test fun `Spotify categories retain accepted user facing distinctions`() {
        listOf(WebApiFailureCategory.AUTHENTICATION, WebApiFailureCategory.PERMISSION,
            WebApiFailureCategory.RATE_LIMITED, WebApiFailureCategory.CONNECTIVITY,
            WebApiFailureCategory.SERVER, WebApiFailureCategory.QUOTA_EXCEEDED).forEach { category ->
            val error = SpotifyWebApiException(category)
            assertEquals(spotifyWebApiMessage(error), opportunityUiMessage(error))
        }
    }

    @Test fun `recovery corruption persistence and stale confirmation have distinct messages`() {
        val errors = listOf(OpportunityUnknownCreationException(), OpportunityPendingException("stale"),
            OpportunityStateException("Cannot read Opportunity state; existing data was preserved."),
            OpportunityStateException("Cannot durably save Opportunity state; restart before continuing."))
        assertEquals(4, errors.map(::opportunityUiMessage).toSet().size)
        assertTrue(opportunityUiMessage(errors[0]).contains("blocked"))
        assertTrue(opportunityUiMessage(errors[1]).contains("confirm"))
        assertTrue(opportunityUiMessage(errors[2]).contains("read saved"))
        assertTrue(opportunityUiMessage(errors[3]).contains("reopen"))
    }
}
