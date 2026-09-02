package com.royalshuffle.android.playlist

import android.content.Context
import com.royalshuffle.android.auth.AccessTokenProvider
import com.royalshuffle.android.auth.SessionInvalidator
import com.royalshuffle.android.data.local.SharedPreferencesPlaylistPreferences
import com.royalshuffle.android.data.remote.SpotifyPlaylistApi
import com.royalshuffle.android.data.remote.SpotifyWebApiClient
import com.royalshuffle.android.diagnostics.DiagnosticLoggerProvider
import com.royalshuffle.android.diagnostics.asWebApiDiagnostics

fun createPlaylistRepository(
    context: Context,
    accessTokenProvider: AccessTokenProvider,
    sessionInvalidator: SessionInvalidator,
): PlaylistRepository = PlaylistRepository(
    accessTokenProvider = accessTokenProvider,
    playlistApi = SpotifyPlaylistApi(
        SpotifyWebApiClient(
            diagnostics = DiagnosticLoggerProvider.get(context).asWebApiDiagnostics(),
            sessionInvalidator = sessionInvalidator,
        ),
    ),
    preferences = SharedPreferencesPlaylistPreferences(context),
    diagnostics = DiagnosticLoggerProvider.get(context),
)
