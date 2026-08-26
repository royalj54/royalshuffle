package com.royalshuffle.android.output

import android.content.Context
import com.royalshuffle.android.auth.AccessTokenProvider
import com.royalshuffle.android.data.local.SharedPreferencesPlaylistPreferences
import com.royalshuffle.android.data.remote.SpotifyOutputPlaylistApi
import com.royalshuffle.android.domain.shuffle.TrueRandomShuffle

fun createOutputPlaylistUseCase(
    context: Context,
    accessTokenProvider: AccessTokenProvider,
): CreateOutputPlaylist = CreateOutputPlaylist(
    accessTokenProvider = accessTokenProvider,
    api = SpotifyOutputPlaylistApi(),
    preferences = SharedPreferencesPlaylistPreferences(context),
    shuffler = UriShuffler { TrueRandomShuffle().shuffle(it) },
)
