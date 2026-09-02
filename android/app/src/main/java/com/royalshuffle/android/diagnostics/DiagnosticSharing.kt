package com.royalshuffle.android.diagnostics

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import java.io.File

data class DiagnosticSharePayload(
    val contentUri: String,
    val mimeType: String = "text/plain",
    val subject: String = "RoyalShuffle diagnostics",
) {
    init {
        require(contentUri.startsWith("content://"))
    }
}

fun interface DiagnosticContentUriProvider {
    fun contentUri(file: File): String
}

class DiagnosticShareExporter(
    private val diagnosticsDirectory: File,
    private val exportDirectory: File,
) {
    fun createExport(): File? = try {
        exportDirectory.mkdirs()
        if (!exportDirectory.isDirectory) return null
        val export = File(exportDirectory, EXPORT_FILE_NAME)
        val rotated = File(
            diagnosticsDirectory,
            RollingFileDiagnosticLogger.ROTATED_FILE_NAME,
        )
        val active = File(
            diagnosticsDirectory,
            RollingFileDiagnosticLogger.ACTIVE_FILE_NAME,
        )
        val contents = buildString {
            appendLine("RoyalShuffle Android diagnostics")
            appendLine("This export excludes credentials, authorization data, response bodies, and playlist contents.")
            listOf(rotated, active).filter { it.isFile && it.length() > 0 }.forEach { file ->
                append(file.readText(Charsets.UTF_8))
                if (!endsWith(System.lineSeparator())) appendLine()
            }
        }
        export.writeText(
            if (contents.lines().size <= HEADER_LINE_COUNT + 1) {
                contents + "No diagnostic entries are available.${System.lineSeparator()}"
            } else {
                contents
            },
            Charsets.UTF_8,
        )
        export
    } catch (_: Exception) {
        null
    }

    private companion object {
        const val EXPORT_FILE_NAME = "royalshuffle-diagnostics.txt"
        const val HEADER_LINE_COUNT = 2
    }
}

class DiagnosticShareCoordinator(
    private val exporter: DiagnosticShareExporter,
    private val uriProvider: DiagnosticContentUriProvider,
) {
    fun prepareShare(): DiagnosticSharePayload? {
        val export = exporter.createExport() ?: return null
        return try {
            DiagnosticSharePayload(uriProvider.contentUri(export))
        } catch (_: Exception) {
            null
        }
    }
}

fun createDiagnosticShareCoordinator(context: Context): DiagnosticShareCoordinator {
    val applicationContext = context.applicationContext
    return DiagnosticShareCoordinator(
        exporter = DiagnosticShareExporter(
            diagnosticsDirectory = File(
                applicationContext.filesDir,
                DiagnosticLoggerProvider.DIAGNOSTICS_DIRECTORY,
            ),
            exportDirectory = File(applicationContext.cacheDir, "shared-diagnostics"),
        ),
        uriProvider = DiagnosticContentUriProvider { file ->
            FileProvider.getUriForFile(
                applicationContext,
                "${applicationContext.packageName}.fileprovider",
                file,
            ).toString()
        },
    )
}

fun shareDiagnostics(context: Context, coordinator: DiagnosticShareCoordinator): Boolean {
    val payload = coordinator.prepareShare() ?: return false
    return try {
        val uri = android.net.Uri.parse(payload.contentUri)
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = payload.mimeType
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, payload.subject)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(intent, "Share Diagnostics"))
        true
    } catch (_: Exception) {
        false
    }
}
