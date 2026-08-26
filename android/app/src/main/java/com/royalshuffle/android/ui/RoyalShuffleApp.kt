package com.royalshuffle.android.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.royalshuffle.android.ui.theme.RoyalShuffleTheme

@Composable
fun RoyalShuffleApp() {
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
                Button(
                    onClick = {},
                    enabled = false,
                    modifier = Modifier.padding(top = 32.dp),
                ) {
                    Text("Connect Spotify — coming next")
                }
            }
        }
    }
}
