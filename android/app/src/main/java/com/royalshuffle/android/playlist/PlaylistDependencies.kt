package com.royalshuffle.android.playlist

import android.content.Context
import com.royalshuffle.android.auth.AccessTokenProvider
import com.royalshuffle.android.data.local.SharedPreferencesPlaylistPreferences
import com.royalshuffle.android.data.remote.SpotifyPlaylistApi

fun createPlaylistRepository(
    context: Context,
    accessTokenProvider: AccessTokenProvider,
): PlaylistRepository = PlaylistRepository(
    accessTokenProvider = accessTokenProvider,
    playlistApi = SpotifyPlaylistApi(),
    preferences = SharedPreferencesPlaylistPreferences(context),
)
