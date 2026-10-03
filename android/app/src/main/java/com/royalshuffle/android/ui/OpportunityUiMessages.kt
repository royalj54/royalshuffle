package com.royalshuffle.android.ui

import com.royalshuffle.android.opportunity.*
import com.royalshuffle.android.output.OutputPlaylistException
import com.royalshuffle.android.output.OutputPlanningException

internal fun opportunityUiMessage(error: Throwable): String = when {
    error is OpportunityUnknownCreationException ->
        "Spotify playlist creation is unresolved. Automatic recreation is blocked. Review Spotify, or confirm Start New Rotation; any created playlist will remain untouched."
    error is OpportunityPendingException ->
        "Opportunity recovery or confirmation state changed. Review the saved state, then resume or confirm Start New Rotation again."
    error is OutputPlaylistException && error.reason == OutputPlaylistException.Reason.NOT_AUTHENTICATED ->
        "Spotify authentication expired. Connect Spotify again; the saved session is preserved."
    error is OutputPlanningException -> error.message ?: "Could not prepare the session."
    error is OpportunityStateException -> when {
        error.message?.contains("restart", ignoreCase = true) == true ->
            "Could not durably save Opportunity state. Close RoyalShuffle completely and reopen it before continuing. Saved recovery data was preserved."
        error.message?.contains("Cannot read") == true ->
            "Could not read saved Opportunity state. Existing data was preserved. Share Diagnostics before changing app data."
        error.message?.contains("duration", ignoreCase = true) == true || error.message == "Invalid Opportunity track." ->
            "The source contains a track without a valid duration. Opportunity could not prepare a session."
        error.message == "No eligible Opportunity tracks." -> "No eligible tracks for Balanced Opportunity."
        else -> "Opportunity state could not be safely used. Saved data was preserved. Share Diagnostics."
    }
    else -> spotifyWebApiMessage(error) ?: "Could not finish the Opportunity session. Review saved status before resuming."
}
