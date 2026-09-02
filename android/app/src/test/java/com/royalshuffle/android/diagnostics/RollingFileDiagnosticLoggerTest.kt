package com.royalshuffle.android.diagnostics

import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RollingFileDiagnosticLoggerTest {
    @Test
    fun `diagnostics persist across logger recreation`() {
        val directory = temporaryDirectory()
        RollingFileDiagnosticLogger(directory, fixedClock()).record(
            DiagnosticEvent(eventName = "first_run", pageNumber = 2),
        )

        RollingFileDiagnosticLogger(directory, fixedClock()).record(
            DiagnosticEvent(eventName = "second_run", confirmedItems = 100),
        )

        val text = File(directory, RollingFileDiagnosticLogger.ACTIVE_FILE_NAME).readText()
        assertTrue(text.contains("event=first_run"))
        assertTrue(text.contains("event=second_run"))
    }

    @Test
    fun `logger retains only active and one bounded rotation`() {
        val directory = temporaryDirectory()
        val logger = RollingFileDiagnosticLogger(
            directory,
            fixedClock(),
            maximumFileBytes = 240,
        )

        repeat(30) { logger.record(DiagnosticEvent(eventName = "bounded_event_$it")) }

        val files = directory.listFiles().orEmpty()
        assertTrue(files.size <= 2)
        assertTrue(files.all { it.name in setOf(
            RollingFileDiagnosticLogger.ACTIVE_FILE_NAME,
            RollingFileDiagnosticLogger.ROTATED_FILE_NAME,
        ) })
        assertTrue(files.all { it.length() <= 240 })
    }

    @Test
    fun `write failure is harmless`() {
        val regularFile = File(temporaryDirectory(), "not-a-directory").apply { writeText("x") }
        RollingFileDiagnosticLogger(regularFile, fixedClock()).record(
            DiagnosticEvent(eventName = "cannot_write"),
        )
        assertTrue(regularFile.isFile)
    }

    @Test
    fun `auth callback metadata excludes callback code and values`() {
        val directory = temporaryDirectory()
        val logger = RollingFileDiagnosticLogger(directory, fixedClock())
        val secretCode = "secret-callback-code"

        recordAuthCallbackMetadata(
            logger,
            "com.royalshuffle.android.auth://callback?code=$secretCode&state=secret-state",
        )

        val text = logger.activeFile().readText()
        assertTrue(text.contains("codePresent=true"))
        assertTrue(text.contains("statePresent=true"))
        assertFalse(text.contains(secretCode))
        assertFalse(text.contains("secret-state"))
    }

    @Test
    fun `share export is readable redacted and uses content URI`() {
        val root = temporaryDirectory()
        val diagnostics = File(root, "diagnostics")
        val logger = RollingFileDiagnosticLogger(diagnostics, fixedClock())
        logger.record(DiagnosticEvent(eventName = "safe_event", intendedItems = 12))
        val coordinator = DiagnosticShareCoordinator(
            DiagnosticShareExporter(diagnostics, File(root, "export")),
            DiagnosticContentUriProvider { "content://royalshuffle/${it.name}" },
        )

        val payload = coordinator.prepareShare()

        assertNotNull(payload)
        assertTrue(payload!!.contentUri.startsWith("content://"))
        val exported = File(root, "export/royalshuffle-diagnostics.txt").readText()
        assertTrue(exported.contains("event=safe_event"))
        assertTrue(exported.contains("intendedItems=12"))
        assertTrue(exported.contains("excludes credentials"))
    }

    @Test
    fun `missing and empty diagnostics produce a safe readable export`() {
        listOf(false, true).forEach { createEmpty ->
            val root = temporaryDirectory()
            val diagnostics = File(root, "diagnostics")
            if (createEmpty) {
                diagnostics.mkdirs()
                File(diagnostics, RollingFileDiagnosticLogger.ACTIVE_FILE_NAME).createNewFile()
            }
            val export = DiagnosticShareExporter(diagnostics, File(root, "export")).createExport()
            assertNotNull(export)
            assertTrue(export!!.readText().contains("No diagnostic entries are available."))
        }
    }

    @Test
    fun `export failure returns null`() {
        val root = temporaryDirectory()
        val invalidExportDirectory = File(root, "file").apply { writeText("x") }
        assertTrue(DiagnosticShareExporter(root, invalidExportDirectory).createExport() == null)
    }

    private fun temporaryDirectory(): File = Files.createTempDirectory("diagnostics-test").toFile()
    private fun fixedClock() = DiagnosticClock { "2026-09-01T12:00:00Z" }
}
