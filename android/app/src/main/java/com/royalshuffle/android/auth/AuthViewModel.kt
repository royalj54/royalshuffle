package com.royalshuffle.android.auth

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.royalshuffle.android.BuildConfig
import com.royalshuffle.android.data.local.SharedPreferencesAuthStorage
import com.royalshuffle.android.data.remote.SpotifyTokenEndpointClient
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class AuthViewModel(
    private val repository: SpotifyAuthRepository,
) : ViewModel() {
    private val mutableUiState = MutableStateFlow<AuthUiState>(AuthUiState.Restoring)
    val uiState: StateFlow<AuthUiState> = mutableUiState.asStateFlow()

    private val mutableAuthorizationRequests = MutableSharedFlow<String>(extraBufferCapacity = 1)
    val authorizationRequests: SharedFlow<String> = mutableAuthorizationRequests.asSharedFlow()

    init {
        viewModelScope.launch {
            mutableUiState.value = try {
                if (repository.restoreSession()) AuthUiState.Connected else AuthUiState.Disconnected
            } catch (error: AuthException) {
                AuthUiState.Error(error.toUserMessage())
            }
        }
    }

    fun connect() {
        try {
            val url = repository.beginAuthorization()
            mutableUiState.value = AuthUiState.Authenticating
            mutableAuthorizationRequests.tryEmit(url)
        } catch (error: AuthException) {
            mutableUiState.value = AuthUiState.Error(error.toUserMessage())
        }
    }

    fun handleCallback(callbackUri: String) {
        mutableUiState.value = AuthUiState.Authenticating
        viewModelScope.launch {
            mutableUiState.value = try {
                repository.handleCallback(callbackUri)
                AuthUiState.Connected
            } catch (error: AuthException) {
                AuthUiState.Error(error.toUserMessage())
            }
        }
    }

    fun cancelAuthentication() {
        repository.cancelAuthentication()
        mutableUiState.value = AuthUiState.Disconnected
    }

    fun disconnect() {
        repository.disconnect()
        mutableUiState.value = AuthUiState.Disconnected
    }

    private fun AuthException.toUserMessage(): String = when (reason) {
        AuthException.Reason.AUTHORIZATION_DENIED -> "Spotify authorization was not completed."
        AuthException.Reason.CALLBACK_INVALID -> "Spotify returned an invalid authorization response."
        AuthException.Reason.CONFIGURATION_MISSING -> "Spotify client ID is not configured."
        AuthException.Reason.NETWORK -> "Could not restore the Spotify session. Check your connection."
        AuthException.Reason.SESSION_EXPIRED -> "The Spotify session expired. Connect again."
        AuthException.Reason.STATE_MISMATCH -> "Spotify authorization could not be verified. Try again."
        AuthException.Reason.TOKEN_EXCHANGE_FAILED -> "Spotify connection failed. Try again."
    }

    companion object {
        const val CALLBACK_SCHEME = "com.royalshuffle.android.auth"
        const val CALLBACK_HOST = "callback"
        const val REDIRECT_URI = "$CALLBACK_SCHEME://$CALLBACK_HOST"

        fun factory(context: Context): ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                val repository = SpotifyAuthRepository(
                    clientId = BuildConfig.SPOTIFY_CLIENT_ID,
                    redirectUri = REDIRECT_URI,
                    scopes = listOf("playlist-read-private", "playlist-modify-private"),
                    storage = SharedPreferencesAuthStorage(context),
                    tokenClient = SpotifyTokenEndpointClient(),
                    pkceProvider = PkceGenerator(),
                    clock = EpochClock { System.currentTimeMillis() / 1_000L },
                )
                return AuthViewModel(repository) as T
            }
        }
    }
}
