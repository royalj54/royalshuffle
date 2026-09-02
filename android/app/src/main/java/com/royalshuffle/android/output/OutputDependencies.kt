package com.royalshuffle.android.output

import android.content.Context
import com.royalshuffle.android.auth.AccessTokenProvider
import com.royalshuffle.android.auth.SessionInvalidator
import com.royalshuffle.android.data.local.SharedPreferencesPlaylistPreferences
import com.royalshuffle.android.data.remote.SpotifyOutputPlaylistApi
import com.royalshuffle.android.data.remote.SpotifyWebApiClient
import com.royalshuffle.android.domain.shuffle.TrueRandomShuffle
import com.royalshuffle.android.diagnostics.DiagnosticLoggerProvider
import com.royalshuffle.android.diagnostics.asWebApiDiagnostics

fun createOutputPlaylistUseCase(
    context: Context,
    accessTokenProvider: AccessTokenProvider,
    sessionInvalidator: SessionInvalidator,
): CreateOutputPlaylist = CreateOutputPlaylist(
    accessTokenProvider = accessTokenProvider,
    api = SpotifyOutputPlaylistApi(
        SpotifyWebApiClient(
            diagnostics = DiagnosticLoggerProvider.get(context).asWebApiDiagnostics(),
            sessionInvalidator = sessionInvalidator,
        ),
    ),
    preferences = SharedPreferencesPlaylistPreferences(context),
    shuffler = UriShuffler { TrueRandomShuffle().shuffle(it) },
    diagnostics = DiagnosticLoggerProvider.get(context),
)
