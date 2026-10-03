package com.royalshuffle.android.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.clickable
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.OutlinedTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import com.royalshuffle.android.output.CreationNameRequest
import com.royalshuffle.android.output.OpportunityUiState
import com.royalshuffle.android.output.OpportunityConfirmationRequest
import com.royalshuffle.android.output.PendingOpportunitySession
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.royalshuffle.android.auth.AuthUiState
import com.royalshuffle.android.auth.AuthViewModel
import com.royalshuffle.android.domain.model.Playlist
import com.royalshuffle.android.output.OutputUiState
import com.royalshuffle.android.output.OutputViewModel
import com.royalshuffle.android.output.message
import com.royalshuffle.android.output.OutputSettings
import com.royalshuffle.android.output.SessionLengthMode
import com.royalshuffle.android.playlist.PlaylistUiState
import com.royalshuffle.android.playlist.PlaylistViewModel
import com.royalshuffle.android.ui.theme.RoyalShuffleTheme

@Composable
fun RoyalShuffleApp(
    authViewModel: AuthViewModel,
    playlistViewModel: PlaylistViewModel,
    outputViewModel: OutputViewModel,
    openAuthorizationUrl: (String) -> Unit,
    onShareDiagnostics: () -> Unit,
) {
    val authState by authViewModel.uiState.collectAsState()
    val playlistState by playlistViewModel.uiState.collectAsState()
    val outputState by outputViewModel.uiState.collectAsState()
    val outputSettings by outputViewModel.settings.collectAsState()
    val outputRunning by outputViewModel.isRunning.collectAsState()
    val nameRequest by outputViewModel.nameRequest.collectAsState()
    val opportunityState by outputViewModel.opportunityState.collectAsState()
    val confirmationRequest by outputViewModel.confirmationRequest.collectAsState()
    val aboutController = remember { AboutDialogController() }

    LaunchedEffect(authViewModel) {
        authViewModel.authorizationRequests.collect(openAuthorizationUrl)
    }

    LaunchedEffect(authState) {
        val currentAuthState = authState
        if (currentAuthState == AuthUiState.Connected) {
            playlistViewModel.loadPlaylists()
        } else {
            if (currentAuthState is AuthUiState.Disconnected &&
                currentAuthState.message != null
            ) {
                playlistViewModel.clearForSessionInvalidation()
                outputViewModel.clearForSessionInvalidation()
            } else {
                playlistViewModel.clear()
                outputViewModel.clear()
            }
        }
    }

    LaunchedEffect(outputState) {
        if (outputState is OutputUiState.Success) {
            playlistViewModel.loadPlaylists()
        }
    }

    LaunchedEffect(playlistState) {
        (playlistState as? PlaylistUiState.Content)?.let {
            outputViewModel.selectOpportunitySource(it.selectedPlaylistId)
        }
    }

    RoyalShuffleTheme {
        Scaffold { contentPadding ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(contentPadding)
                    .padding(horizontal = 24.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.Top,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                CompactHeader(onAbout = aboutController::open, onShareDiagnostics = onShareDiagnostics)
                AuthControls(
                    state = authState,
                    onConnect = authViewModel::connect,
                    onCancel = authViewModel::cancelAuthentication,
                    onDisconnect = {
                        outputViewModel.clear()
                        authViewModel.disconnect()
                    },
                )
                HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
                if (outputSettings.opportunityEnabled) {
                    PendingOpportunityControls(opportunityState, outputRunning,
                        connected = authState == AuthUiState.Connected,
                        onResume = outputViewModel::resumePendingSession,
                        onAbandon = outputViewModel::abandonPendingSession)
                    if (playlistState !is PlaylistUiState.Content) {
                        when (val state = outputState) {
                            is OutputUiState.Working -> Text(state.message)
                            is OutputUiState.Error -> Text(state.message, color = MaterialTheme.colorScheme.error)
                            is OutputUiState.Success -> Text(state.message())
                            else -> Unit
                        }
                    }
                }
                if (authState == AuthUiState.Connected) {
                    if (outputSettings.opportunityEnabled && playlistState !is PlaylistUiState.Content) {
                        Text(opportunityState.status, modifier = Modifier.padding(top = 12.dp))
                    }
                    PlaylistControls(
                        state = playlistState,
                        onRetry = playlistViewModel::loadPlaylists,
                        onSelect = playlistViewModel::selectPlaylist,
                        outputState = outputState,
                        onCreateOutput = outputViewModel::create,
                        settings = outputSettings,
                        outputRunning = outputRunning,
                        onSessionLength = outputViewModel::setSessionLength,
                        onCustomMinutes = outputViewModel::setCustomMinutes,
                        onArtistSeparation = outputViewModel::setArtistSeparation,
                        opportunityState = opportunityState,
                        onOpportunityEnabled = outputViewModel::setOpportunityEnabled,
                        onNewRotation = outputViewModel::startNewRotation,
                    )
                } else if (outputSettings.opportunityEnabled) {
                    Text(opportunityState.status, modifier = Modifier.padding(top = 12.dp))
                }
            }
        }
        val recoveryState = playlistState as? PlaylistUiState.Recovery
        confirmationRequest?.let { request ->
            OpportunityRotationDialog(request,
                onConfirm = { outputViewModel.confirmNewRotation(request.requestId) },
                onCancel = { outputViewModel.cancelNewRotation(request.requestId) })
        }
        nameRequest?.let { request ->
            OutputNameDialog(request,
                onConfirm = { outputViewModel.confirmName(request.requestId, it) },
                onCancel = { outputViewModel.cancelName(request.requestId) })
        }
        if (recoveryState != null) {
            RecoveryDialog(
                state = recoveryState,
                onRecover = playlistViewModel::recoverCandidates,
                onLeaveUnmanaged = playlistViewModel::leaveCandidatesUnmanaged,
            )
        }
        if (aboutController.isVisible) {
            AboutDialog(
                appInfo = androidAboutAppInfo(),
                onDismiss = aboutController::close,
            )
        }
    }
}

@Composable
private fun CompactHeader(onAbout: () -> Unit, onShareDiagnostics: () -> Unit) {
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween) {
        Text("RoyalShuffle", style = MaterialTheme.typography.headlineLarge, modifier = Modifier.weight(1f))
        TextButton(onClick = onAbout) { Text("About") }
    }
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween) {
        Text("Spotify Companion", style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        TextButton(onClick = onShareDiagnostics) { Text("Share Diagnostics") }
    }
}

@Composable
private fun ColumnScope.PlaylistControls(
    state: PlaylistUiState,
    onRetry: () -> Unit,
    onSelect: (String) -> Unit,
    outputState: OutputUiState,
    onCreateOutput: (Playlist) -> Unit,
    settings: OutputSettings,
    outputRunning: Boolean,
    onSessionLength: (SessionLengthMode) -> Unit,
    onCustomMinutes: (String) -> Unit,
    onArtistSeparation: (Boolean) -> Unit,
    opportunityState: OpportunityUiState,
    onOpportunityEnabled: (Boolean) -> Unit,
    onNewRotation: (Playlist) -> Unit,
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

        is PlaylistUiState.Recovery -> {
            CircularProgressIndicator(modifier = Modifier.padding(top = 24.dp))
            Text("Reviewing managed playlists…", modifier = Modifier.padding(top = 12.dp))
        }

        is PlaylistUiState.Content -> {
            val keyboardController = LocalSoftwareKeyboardController.current
            val selectedPlaylist = state.playlists.firstOrNull {
                it.id == state.selectedPlaylistId
            }
            val canSubmit = !outputRunning && selectedPlaylist != null &&
                if (settings.opportunityEnabled) opportunityState.canSubmit &&
                    opportunityState.sourceId == selectedPlaylist.id else settings.validationMessage == null
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
            ) {
                item {
                    OutputOptionsControls(settings, onSessionLength, onCustomMinutes, onArtistSeparation,
                        outputRunning, onOpportunityEnabled, opportunityState)
                    if (settings.opportunityEnabled) {
                        TextButton(onClick = { selectedPlaylist?.let(onNewRotation) }, enabled = canSubmit) {
                            Text("Start New Rotation")
                        }
                    }
                    OutputStatus(outputState, selectedPlaylist, onCreateOutput, canSubmit)
                    Text(
                        text = "Choose a playlist",
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.fillMaxWidth().padding(top = 16.dp, bottom = 8.dp),
                    )
                }
                items(state.playlists, key = { it.id }) { playlist ->
                    ListItem(
                        headlineContent = { Text(playlist.name) },
                        leadingContent = {
                            RadioButton(
                                selected = state.selectedPlaylistId == playlist.id,
                                enabled = !outputRunning,
                                onClick = { onSelect(playlist.id) },
                            )
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable(enabled = !outputRunning) { onSelect(playlist.id) },
                    )
                }
            }
            Button(
                onClick = {
                    keyboardController?.hide()
                    selectedPlaylist?.let(onCreateOutput)
                },
                enabled = canSubmit,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 16.dp),
            ) {
                Text(if (settings.opportunityEnabled) opportunityState.primaryActionText else "Generate shuffled playlist")
            }
        }
    }
}

@Composable
private fun OutputNameDialog(request: CreationNameRequest, onConfirm: (String) -> Unit, onCancel: () -> Unit) {
    val focusRequester = remember(request.requestId) { FocusRequester() }
    var name by rememberSaveable(request.requestId, stateSaver = TextFieldValue.Saver) {
        mutableStateOf(TextFieldValue(request.defaultName, TextRange(0, request.defaultName.length)))
    }
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text("Name new playlist") },
        text = {
            OutlinedTextField(
                value = name, onValueChange = { name = it }, singleLine = true,
                label = { Text("Playlist name") },
                isError = request.errorMessage != null,
                supportingText = { request.errorMessage?.let { Text(it) } },
                modifier = Modifier.focusRequester(focusRequester),
            )
            LaunchedEffect(request.requestId) { focusRequester.requestFocus() }
        },
        confirmButton = { TextButton(onClick = { onConfirm(name.text) }) { Text("Create") } },
        dismissButton = { TextButton(onClick = onCancel) { Text("Cancel") } },
    )
}

@Composable
private fun OpportunityRotationDialog(request: OpportunityConfirmationRequest, onConfirm: () -> Unit, onCancel: () -> Unit) {
    AlertDialog(onDismissRequest = onCancel,
        title = { Text(request.title) },
        text = { Text(request.message) },
        confirmButton = { TextButton(onClick = onConfirm) { Text("Continue") } },
        dismissButton = { TextButton(onClick = onCancel) { Text("Cancel") } })
}

@Composable
private fun PendingOpportunityControls(state: OpportunityUiState, running: Boolean, connected: Boolean,
    onResume: (PendingOpportunitySession) -> Unit, onAbandon: (PendingOpportunitySession) -> Unit) {
    state.pendingSessions.forEach { session ->
        Text("Saved pending session: ${session.pending.creationName}")
        Row {
            TextButton(onClick = { onResume(session) },
                enabled = !running && !state.isLoading && state.errorMessage == null && connected) {
                Text("Resume Pending Session")
            }
            TextButton(onClick = { onAbandon(session) },
                enabled = !running && !state.isLoading && state.errorMessage == null) {
                Text("Abandon Pending Session")
            }
        }
    }
}

@Composable
private fun OutputOptionsControls(
    settings: OutputSettings,
    onSessionLength: (SessionLengthMode) -> Unit,
    onCustomMinutes: (String) -> Unit,
    onArtistSeparation: (Boolean) -> Unit,
    outputRunning: Boolean,
    onOpportunityEnabled: (Boolean) -> Unit,
    opportunityState: OpportunityUiState,
) {
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween) {
        Text("Balanced Opportunity", style = MaterialTheme.typography.titleMedium)
        Switch(checked = settings.opportunityEnabled, onCheckedChange = onOpportunityEnabled, enabled = !outputRunning)
    }
    if (settings.opportunityEnabled) {
        Text("Fixed 60 minutes. Ordinary settings are saved.")
        Text(opportunityState.status, modifier = Modifier.padding(vertical = 8.dp))
    }
    if (!settings.opportunityEnabled) {
        Text("Session Length", style = MaterialTheme.typography.titleMedium)
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            SessionLengthMode.entries.forEach { mode ->
                Row(verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.clickable(enabled = !outputRunning) { onSessionLength(mode) }) {
                    RadioButton(selected = settings.mode == mode,
                        enabled = !outputRunning, onClick = { onSessionLength(mode) })
                    Text(when (mode) {
                        SessionLengthMode.FULL -> "Full"
                        SessionLengthMode.SIXTY_MINUTES -> "60M"
                        SessionLengthMode.CUSTOM -> "Custom"
                    })
                }
            }
        }
        if (settings.mode == SessionLengthMode.CUSTOM) {
            OutlinedTextField(
                value = settings.customMinutes,
                onValueChange = onCustomMinutes,
                label = { Text("Minutes") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                singleLine = true,
                enabled = !outputRunning,
                isError = settings.validationMessage != null,
                supportingText = { settings.validationMessage?.let { Text(it) } },
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween) {
        Text("Artist Separation")
        Switch(checked = settings.artistSeparation, onCheckedChange = onArtistSeparation, enabled = !outputRunning)
    }
}

@Composable
private fun OutputStatus(
    state: OutputUiState,
    selectedPlaylist: Playlist?,
    onCreateOutput: (Playlist) -> Unit,
    canSubmit: Boolean,
) {
    when (state) {
        OutputUiState.Idle -> Unit
        is OutputUiState.Working -> {
            CircularProgressIndicator(modifier = Modifier.padding(top = 12.dp))
            Text(state.message, modifier = Modifier.padding(top = 8.dp))
        }

        is OutputUiState.Success -> Text(
            state.message(),
            modifier = Modifier.padding(top = 12.dp),
            color = MaterialTheme.colorScheme.primary,
        )
        is OutputUiState.PartialFailure -> Text(
            state.message,
            modifier = Modifier.padding(top = 12.dp),
            color = MaterialTheme.colorScheme.error,
        )
        is OutputUiState.Error -> {
            Text(
                state.message,
                modifier = Modifier.padding(top = 12.dp),
                color = MaterialTheme.colorScheme.error,
            )
            Button(
                onClick = { selectedPlaylist?.let(onCreateOutput) },
                enabled = selectedPlaylist != null && canSubmit,
                modifier = Modifier.padding(top = 8.dp),
            ) {
                Text("Try again")
            }
        }
    }
}

@Composable
private fun RecoveryDialog(
    state: PlaylistUiState.Recovery,
    onRecover: () -> Unit,
    onLeaveUnmanaged: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = {},
        title = { Text("Recover managed playlists?") },
        text = {
            Column {
                Text(
                    "RoyalShuffle found ${state.candidates.size} ${if (state.candidates.size == 1) "playlist" else "playlists"} " +
                        "that appear to have been created by RoyalShuffle on another installation or before app data was reset.",
                )
                Text(
                    "Recover them to keep them excluded as source playlists, or leave them unmanaged.",
                    modifier = Modifier.padding(top = 8.dp),
                )
                state.errorMessage?.let {
                    Text(
                        it,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onRecover, enabled = !state.isPersisting) {
                Text("Recover")
            }
        },
        dismissButton = {
            TextButton(onClick = onLeaveUnmanaged, enabled = !state.isPersisting) {
                Text("Leave Unmanaged")
            }
        },
    )
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
            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween) {
                Text("Connected to Spotify")
                TextButton(onClick = onDisconnect) { Text("Disconnect") }
            }
        }

        is AuthUiState.Disconnected -> {
            Text(
                state.message ?: "Not connected",
                modifier = Modifier.padding(top = 32.dp),
                color = if (state.message != null) {
                    MaterialTheme.colorScheme.error
                } else {
                    MaterialTheme.colorScheme.onSurface
                },
            )
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
