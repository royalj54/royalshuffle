package com.royalshuffle.android.playlist

import android.content.Context
import com.royalshuffle.android.auth.AccessTokenProvider
import com.royalshuffle.android.auth.SessionInvalidator
import com.royalshuffle.android.data.local.SharedPreferencesPlaylistPreferences
import com.royalshuffle.android.data.remote.SpotifyPlaylistApi
import com.royalshuffle.android.data.remote.SpotifyWebApiClient
import com.royalshuffle.android.diagnostics.DiagnosticLoggerProvider
import com.royalshuffle.android.diagnostics.asWebApiDiagnostics
import com.royalshuffle.android.data.local.AtomicFileOpportunityPersistence
import com.royalshuffle.android.opportunity.OpportunityStore
import com.royalshuffle.android.opportunity.OpportunityIdentityProtection

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
    identityProtection = OpportunityIdentityProtection(OpportunityStore(AtomicFileOpportunityPersistence(context))),
)
