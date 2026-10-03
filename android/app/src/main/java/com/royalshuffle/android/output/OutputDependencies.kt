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
import com.royalshuffle.android.data.local.AtomicFileOpportunityPersistence
import com.royalshuffle.android.opportunity.OpportunityStore
import com.royalshuffle.android.opportunity.OpportunityIdentityProtection

fun createOutputPlaylistUseCase(
    context: Context,
    accessTokenProvider: AccessTokenProvider,
    sessionInvalidator: SessionInvalidator,
): CreateOutputPlaylist {
    val preferences = SharedPreferencesPlaylistPreferences(context)
    return CreateOutputPlaylist(
        accessTokenProvider = accessTokenProvider,
        api = SpotifyOutputPlaylistApi(
            SpotifyWebApiClient(
                diagnostics = DiagnosticLoggerProvider.get(context).asWebApiDiagnostics(),
                sessionInvalidator = sessionInvalidator,
            ),
        ),
        preferences = preferences,
        registry = preferences,
        shuffler = OccurrenceShuffler { TrueRandomShuffle().shuffle(it) },
        diagnostics = DiagnosticLoggerProvider.get(context),
        identityProtection = OpportunityIdentityProtection(OpportunityStore(AtomicFileOpportunityPersistence(context))),
    )
}
