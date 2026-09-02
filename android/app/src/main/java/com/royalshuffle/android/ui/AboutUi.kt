package com.royalshuffle.android.ui

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.royalshuffle.android.BuildConfig

data class AboutAppInfo(
    val applicationName: String,
    val versionText: String,
    val description: String,
)

internal fun androidAboutAppInfo(
    versionName: String = BuildConfig.VERSION_NAME,
    isDebug: Boolean = BuildConfig.DEBUG,
): AboutAppInfo = AboutAppInfo(
    applicationName = "RoyalShuffle",
    versionText = if (isDebug) {
        "Version $versionName (development)"
    } else {
        "Version $versionName"
    },
    description = "Transparent, user-controlled Spotify shuffle.",
)

internal class AboutDialogController {
    var isVisible by mutableStateOf(false)
        private set

    fun open() {
        isVisible = true
    }

    fun close() {
        isVisible = false
    }
}

@Composable
internal fun AboutDialog(
    appInfo: AboutAppInfo,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(appInfo.applicationName) },
        text = {
            Text("${appInfo.versionText}\n\n${appInfo.description}")
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("Close")
            }
        },
    )
}
