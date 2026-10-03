package com.royalshuffle.android.opportunity

import android.content.Context
import com.royalshuffle.android.auth.AccessTokenProvider
import com.royalshuffle.android.auth.SessionInvalidator
import com.royalshuffle.android.data.local.AtomicFileOpportunityPersistence
import com.royalshuffle.android.data.local.SharedPreferencesPlaylistPreferences
import com.royalshuffle.android.data.remote.SpotifyOpportunityPlaylistApi
import com.royalshuffle.android.data.remote.SpotifyWebApiClient
import com.royalshuffle.android.diagnostics.DiagnosticLoggerProvider
import com.royalshuffle.android.diagnostics.asWebApiDiagnostics
import com.royalshuffle.android.domain.shuffle.TrueRandomShuffle
import com.royalshuffle.android.output.OccurrenceShuffler
import com.royalshuffle.android.output.OutputPlanner

/** Production workflow shares the existing authentication and mutation coordination. */
fun createOpportunityWorkflow(context: Context, tokens: AccessTokenProvider, invalidator: SessionInvalidator): OpportunityWorkflow {
    val diagnostics = DiagnosticLoggerProvider.get(context)
    val preferences = SharedPreferencesPlaylistPreferences(context)
    return OpportunityWorkflow(tokens, SpotifyOpportunityPlaylistApi(SpotifyWebApiClient(
        diagnostics = diagnostics.asWebApiDiagnostics(), sessionInvalidator = invalidator)),
        OpportunityStore(AtomicFileOpportunityPersistence(context)),
        OpportunityPlanner(OutputPlanner(OccurrenceShuffler { TrueRandomShuffle().shuffle(it) })),
        preferences, preferences, diagnostics = diagnostics)
}
