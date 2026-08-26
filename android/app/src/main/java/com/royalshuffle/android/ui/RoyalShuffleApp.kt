package com.royalshuffle.android.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.royalshuffle.android.auth.AuthUiState
import com.royalshuffle.android.auth.AuthViewModel
import com.royalshuffle.android.ui.theme.RoyalShuffleTheme

@Composable
fun RoyalShuffleApp(
    authViewModel: AuthViewModel,
    openAuthorizationUrl: (String) -> Unit,
) {
    val authState by authViewModel.uiState.collectAsState()

    LaunchedEffect(authViewModel) {
        authViewModel.authorizationRequests.collect(openAuthorizationUrl)
    }

    RoyalShuffleTheme {
        Scaffold { contentPadding ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(contentPadding)
                    .padding(24.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    text = "RoyalShuffle",
                    style = MaterialTheme.typography.headlineLarge,
                )
                Text(
                    text = "Transparent, user-controlled Spotify shuffle",
                    modifier = Modifier.padding(top = 12.dp),
                    style = MaterialTheme.typography.bodyLarge,
                )
                AuthControls(
                    state = authState,
                    onConnect = authViewModel::connect,
                    onCancel = authViewModel::cancelAuthentication,
                    onDisconnect = authViewModel::disconnect,
                )
            }
        }
    }
}

@Composable
private fun AuthControls(
    state: AuthUiState,
    onConnect: () -> Unit,
    onCancel: () -> Unit,
    onDisconnect: () -> Unit,
) {
    when (state) {
        AuthUiState.Restoring,
        AuthUiState.Authenticating,
        -> {
            CircularProgressIndicator(modifier = Modifier.padding(top = 32.dp))
            Text(
                text = if (state == AuthUiState.Restoring) {
                    "Restoring Spotify session…"
                } else {
                    "Waiting for Spotify authorization…"
                },
                modifier = Modifier.padding(top = 12.dp),
            )
            if (state == AuthUiState.Authenticating) {
                Button(onClick = onCancel, modifier = Modifier.padding(top = 16.dp)) {
                    Text("Cancel")
                }
            }
        }

        AuthUiState.Connected -> {
            Text("Connected to Spotify", modifier = Modifier.padding(top = 32.dp))
            Button(onClick = onDisconnect, modifier = Modifier.padding(top = 16.dp)) {
                Text("Disconnect")
            }
        }

        AuthUiState.Disconnected -> {
            Text("Not connected", modifier = Modifier.padding(top = 32.dp))
            Button(onClick = onConnect, modifier = Modifier.padding(top = 16.dp)) {
                Text("Connect Spotify")
            }
        }

        is AuthUiState.Error -> {
            Text(
                text = state.message,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(top = 32.dp),
            )
            Button(onClick = onConnect, modifier = Modifier.padding(top = 16.dp)) {
                Text("Try again")
            }
        }
    }
}
