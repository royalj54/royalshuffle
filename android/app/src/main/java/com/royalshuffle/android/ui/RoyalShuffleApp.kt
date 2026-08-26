package com.royalshuffle.android.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.clickable
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
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
import com.royalshuffle.android.playlist.PlaylistUiState
import com.royalshuffle.android.playlist.PlaylistViewModel
import com.royalshuffle.android.ui.theme.RoyalShuffleTheme

@Composable
fun RoyalShuffleApp(
    authViewModel: AuthViewModel,
    playlistViewModel: PlaylistViewModel,
    openAuthorizationUrl: (String) -> Unit,
) {
    val authState by authViewModel.uiState.collectAsState()
    val playlistState by playlistViewModel.uiState.collectAsState()

    LaunchedEffect(authViewModel) {
        authViewModel.authorizationRequests.collect(openAuthorizationUrl)
    }

    LaunchedEffect(authState) {
        if (authState == AuthUiState.Connected) {
            playlistViewModel.loadPlaylists()
        } else {
            playlistViewModel.clear()
        }
    }

    RoyalShuffleTheme {
        Scaffold { contentPadding ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(contentPadding)
                    .padding(24.dp),
                verticalArrangement = Arrangement.Top,
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
                if (authState == AuthUiState.Connected) {
                    PlaylistControls(
                        state = playlistState,
                        onRetry = playlistViewModel::loadPlaylists,
                        onSelect = playlistViewModel::selectPlaylist,
                    )
                }
            }
        }
    }
}

@Composable
private fun ColumnScope.PlaylistControls(
    state: PlaylistUiState,
    onRetry: () -> Unit,
    onSelect: (String) -> Unit,
) {
    when (state) {
        PlaylistUiState.Idle,
        PlaylistUiState.Loading,
        -> {
            CircularProgressIndicator(modifier = Modifier.padding(top = 24.dp))
            Text("Loading playlists…", modifier = Modifier.padding(top = 12.dp))
        }

        PlaylistUiState.Empty -> {
            Text("No eligible playlists found.", modifier = Modifier.padding(top = 24.dp))
            Button(onClick = onRetry, modifier = Modifier.padding(top = 12.dp)) {
                Text("Refresh")
            }
        }

        is PlaylistUiState.Error -> {
            Text(
                text = state.message,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(top = 24.dp),
            )
            Button(onClick = onRetry, modifier = Modifier.padding(top = 12.dp)) {
                Text("Retry")
            }
        }

        is PlaylistUiState.Content -> {
            Text(
                text = "Choose a playlist",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 24.dp, bottom = 8.dp),
            )
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
            ) {
                items(state.playlists, key = { it.id }) { playlist ->
                    ListItem(
                        headlineContent = { Text(playlist.name) },
                        leadingContent = {
                            RadioButton(
                                selected = state.selectedPlaylistId == playlist.id,
                                onClick = { onSelect(playlist.id) },
                            )
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onSelect(playlist.id) },
                    )
                }
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
