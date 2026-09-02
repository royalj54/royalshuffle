package com.royalshuffle.android.auth

import android.content.Context
import com.royalshuffle.android.BuildConfig
import com.royalshuffle.android.data.local.SharedPreferencesAuthStorage
import com.royalshuffle.android.data.remote.SpotifyTokenEndpointClient
import com.royalshuffle.android.diagnostics.DiagnosticLoggerProvider

fun createSpotifyAuthRepository(context: Context): SpotifyAuthRepository = SpotifyAuthRepository(
    clientId = BuildConfig.SPOTIFY_CLIENT_ID,
    redirectUri = AuthViewModel.REDIRECT_URI,
    scopes = listOf("playlist-read-private", "playlist-modify-private"),
    storage = SharedPreferencesAuthStorage(context),
    tokenClient = SpotifyTokenEndpointClient(),
    pkceProvider = PkceGenerator(),
    clock = EpochClock { System.currentTimeMillis() / 1_000L },
    diagnostics = DiagnosticLoggerProvider.get(context),
)
