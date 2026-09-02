package com.royalshuffle.android.ui

import com.royalshuffle.android.data.remote.SpotifyWebApiException
import com.royalshuffle.android.data.remote.WebApiFailureCategory

internal fun spotifyWebApiMessage(error: Throwable): String? {
    val failure = error as? SpotifyWebApiException ?: return null
    return when (failure.category) {
        WebApiFailureCategory.AUTHENTICATION ->
            "Spotify authentication expired. Connect Spotify again."
        WebApiFailureCategory.PERMISSION ->
            "Spotify did not grant permission for this request."
        WebApiFailureCategory.RATE_LIMITED ->
            "Spotify is limiting requests. Try again later."
        WebApiFailureCategory.QUOTA_EXCEEDED ->
            "Spotify developer quota exceeded. RoyalShuffle cannot make additional Spotify " +
                "requests right now. Try again later."
        WebApiFailureCategory.SERVER ->
            "Spotify is having trouble right now. Try again later."
        WebApiFailureCategory.CONNECTIVITY ->
            "Could not reach Spotify. Check your connection and try again."
        WebApiFailureCategory.INVALID_RESPONSE,
        WebApiFailureCategory.INVALID_PAGINATION,
        -> "Spotify returned an unexpected response. Try again later."
        WebApiFailureCategory.OTHER ->
            "Spotify could not complete the request. Try again later."
    }
}
