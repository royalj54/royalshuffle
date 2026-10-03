package com.royalshuffle.android.ui

import java.io.File
import org.junit.Assert.*
import org.junit.Test

/** Static Compose layout contracts; these do not substitute for rendered device acceptance. */
class RoyalShufflePresentationTest {
    private val source = File("src/main/java/com/royalshuffle/android/ui/RoyalShuffleApp.kt").readText()

    @Test fun `saved pending actions remain outside playlist and connection eligibility branches`() {
        val app = block(source, "fun RoyalShuffleApp")
        val pending = app.indexOf("PendingOpportunityControls(")
        assertTrue(pending >= 0)
        assertTrue(pending < app.indexOf("if (authState == AuthUiState.Connected)"))
        assertTrue(pending < app.indexOf("PlaylistControls("))
        assertTrue(app.contains("onResume = outputViewModel::resumePendingSession"))
        assertTrue(app.contains("onAbandon = outputViewModel::abandonPendingSession"))
        val controls = block(source, "private fun PendingOpportunityControls")
        assertTrue(controls.contains("state.pendingSessions.forEach"))
        assertTrue(controls.contains("onResume(session)"))
        assertTrue(controls.contains("onAbandon(session)"))
        assertFalse(controls.contains("selectedPlaylist"))
        assertFalse(controls.contains("canSubmit"))
        assertTrue(controls.contains("Text(\"Resume Pending Session\")"))
        assertTrue(controls.contains("Text(\"Abandon Pending Session\")"))
    }

    private fun block(text: String, marker: String): String {
        val markerIndex = text.indexOf(marker)
        check(markerIndex >= 0) { "Missing layout marker: $marker" }
        val start = text.indexOf('{', markerIndex)
        var depth = 0
        for (index in start until text.length) {
            if (text[index] == '{') depth++
            if (text[index] == '}') {
                depth--
                if (depth == 0) return text.substring(start, index + 1)
            }
        }
        error("Unclosed layout block")
    }

    @Test fun `compact title and companion rows retain actions and readable typography`() {
        val header = block(source, "private fun CompactHeader")
        val titleRow = block(header, "Row(")
        val companionRow = block(header.substringAfter(titleRow), "Row(")
        assertTrue(titleRow.contains("Text(\"RoyalShuffle\""))
        assertTrue(titleRow.contains("headlineLarge"))
        assertTrue(titleRow.contains("onClick = onAbout"))
        assertTrue(titleRow.contains("Text(\"About\")"))
        assertTrue(companionRow.contains("Text(\"Spotify Companion\""))
        assertTrue(companionRow.contains("bodyLarge"))
        assertTrue(companionRow.contains("onClick = onShareDiagnostics"))
        assertTrue(companionRow.contains("Text(\"Share Diagnostics\")"))
        assertFalse(header.contains("fontSize"))
    }

    @Test fun `connection status and disconnect share a compact row`() {
        val connected = block(source, "AuthUiState.Connected ->")
        val row = block(connected, "Row(")
        assertTrue(row.contains("Text(\"Connected to Spotify\")"))
        assertTrue(row.contains("onClick = onDisconnect"))
        assertTrue(row.contains("Text(\"Disconnect\")"))
        assertFalse(connected.contains("padding(top"))
    }

    @Test fun `durable status belongs to Opportunity before independent artist controls`() {
        val options = block(source, "private fun OutputOptionsControls")
        val opportunity = block(options, "if (settings.opportunityEnabled)")
        assertTrue(opportunity.contains("Fixed 60 minutes. Ordinary settings are saved."))
        assertTrue(opportunity.contains("Text(opportunityState.status"))
        assertFalse(opportunity.contains("Artist Separation"))
        assertTrue(options.indexOf("Text(opportunityState.status") < options.indexOf("Text(\"Artist Separation\")"))
        val playlist = block(source, "private fun ColumnScope.PlaylistControls")
        assertFalse(playlist.contains("Text(opportunityState.status"))
    }

    @Test fun `entire ordinary length section is conditional on Opportunity being off`() {
        val options = block(source, "private fun OutputOptionsControls")
        val ordinary = block(options, "if (!settings.opportunityEnabled)")
        assertTrue(ordinary.contains("Text(\"Session Length\""))
        assertTrue(ordinary.contains("SessionLengthMode.entries"))
        assertTrue(ordinary.contains("selected = settings.mode == mode"))
        assertTrue(ordinary.contains("value = settings.customMinutes"))
        val remainder = options.replace(ordinary, "")
        assertFalse(remainder.contains("SessionLengthMode.entries"))
        assertFalse(remainder.contains("Text(\"Session Length\""))
        assertFalse(remainder.contains("value = settings.customMinutes"))
        assertTrue(remainder.contains("Text(\"Artist Separation\")"))
        assertTrue(remainder.contains("onCheckedChange = onArtistSeparation"))
    }

    @Test fun `header stays outside scrollable options with standard touch targets`() {
        val app = block(source, "fun RoyalShuffleApp")
        assertTrue(app.contains("CompactHeader(onAbout = aboutController::open"))
        assertTrue(app.contains("HorizontalDivider"))
        val scrolling = block(source, "LazyColumn(")
        assertTrue(scrolling.contains("OutputOptionsControls("))
        assertFalse(scrolling.contains("CompactHeader("))
        assertFalse(source.contains("minimumInteractiveComponentSize"))
    }
}
