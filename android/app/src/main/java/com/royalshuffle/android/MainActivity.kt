package com.royalshuffle.android

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.browser.customtabs.CustomTabsIntent
import com.royalshuffle.android.auth.AuthViewModel
import com.royalshuffle.android.auth.createSpotifyAuthRepository
import com.royalshuffle.android.playlist.PlaylistViewModel
import com.royalshuffle.android.playlist.createPlaylistRepository
import com.royalshuffle.android.output.OutputViewModel
import com.royalshuffle.android.output.createOutputPlaylistUseCase
import com.royalshuffle.android.ui.RoyalShuffleApp

class MainActivity : ComponentActivity() {
    private val authRepository by lazy {
        createSpotifyAuthRepository(applicationContext)
    }
    private val authViewModel: AuthViewModel by viewModels {
        AuthViewModel.factory(authRepository)
    }
    private val playlistViewModel: PlaylistViewModel by viewModels {
        PlaylistViewModel.factory(
            createPlaylistRepository(applicationContext, authRepository),
        )
    }
    private val outputViewModel: OutputViewModel by viewModels {
        OutputViewModel.factory(
            createOutputPlaylistUseCase(applicationContext, authRepository),
        )
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        handleAuthCallback(intent)

        setContent {
            RoyalShuffleApp(
                authViewModel = authViewModel,
                playlistViewModel = playlistViewModel,
                outputViewModel = outputViewModel,
                openAuthorizationUrl = ::openAuthorizationUrl,
            )
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleAuthCallback(intent)
    }

    private fun handleAuthCallback(intent: Intent) {
        val callbackUri = intent.data
        Log.i(TAG, "Intent received; dataPresent=${callbackUri != null}")
        callbackUri ?: return

        val queryNames = callbackUri.queryParameterNames
        Log.i(
            TAG,
            "Callback metadata; scheme=${callbackUri.scheme}; host=${callbackUri.host}; " +
                "codePresent=${"code" in queryNames}; statePresent=${"state" in queryNames}; " +
                "errorPresent=${"error" in queryNames}; " +
                "errorDescriptionPresent=${"error_description" in queryNames}",
        )

        if (
            callbackUri.scheme == AuthViewModel.CALLBACK_SCHEME &&
            callbackUri.host == AuthViewModel.CALLBACK_HOST
        ) {
            // Remove the callback before dispatch so activity recreation cannot replay it.
            intent.data = null
            authViewModel.handleCallback(callbackUri.toString())
        }
    }

    private fun openAuthorizationUrl(url: String) {
        CustomTabsIntent.Builder()
            .setShowTitle(true)
            .build()
            .launchUrl(this, Uri.parse(url))
    }

    private companion object {
        const val TAG = "RoyalShuffleAuth"
    }
}
