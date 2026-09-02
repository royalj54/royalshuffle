package com.royalshuffle.android.diagnostics

import android.content.Context
import android.util.Log
import java.io.File
import java.time.Instant

fun interface DiagnosticClock {
    fun timestamp(): String
}

class RollingFileDiagnosticLogger(
    private val diagnosticsDirectory: File,
    private val clock: DiagnosticClock = DiagnosticClock { Instant.now().toString() },
    private val maximumFileBytes: Long = MAXIMUM_FILE_BYTES,
) : DiagnosticLogger {
    private val lock = Any()

    override fun record(event: DiagnosticEvent) {
        val line = formatLine(clock.timestamp(), event) + System.lineSeparator()
        val bytes = line.toByteArray(Charsets.UTF_8)
        try {
            synchronized(lock) {
                diagnosticsDirectory.mkdirs()
                if (!diagnosticsDirectory.isDirectory) return
                val active = activeFile()
                if (active.exists() && active.length() + bytes.size > maximumFileBytes) {
                    val rotated = rotatedFile()
                    if (rotated.exists()) rotated.delete()
                    if (!active.renameTo(rotated)) return
                }
                active.appendBytes(bytes)
            }
        } catch (_: Exception) {
            // Persistent diagnostics are best-effort and cannot fail the workflow.
        }
    }

    fun activeFile(): File = File(diagnosticsDirectory, ACTIVE_FILE_NAME)
    fun rotatedFile(): File = File(diagnosticsDirectory, ROTATED_FILE_NAME)

    companion object {
        const val ACTIVE_FILE_NAME = "royalshuffle-diagnostics.log"
        const val ROTATED_FILE_NAME = "royalshuffle-diagnostics.log.1"
        const val MAXIMUM_FILE_BYTES = 256L * 1_024L

        internal fun formatLine(timestamp: String, event: DiagnosticEvent): String {
            val fields = linkedMapOf<String, Any?>(
                "event" to event.eventName,
                "operation" to event.operationName,
                "operationClass" to event.operationClass,
                "httpStatus" to event.httpStatus,
                "failureCategory" to event.failureCategory,
                "retryAfter" to event.retryAfter,
                "quotaReason" to event.quotaReason,
                "retryAttempt" to event.retryAttempt,
                "retryDelaySeconds" to event.retryDelaySeconds,
                "pageNumber" to event.pageNumber,
                "batchNumber" to event.batchNumber,
                "confirmedItems" to event.confirmedItems,
                "intendedItems" to event.intendedItems,
                "skippedItems" to event.skippedItems,
                "exceptionClass" to event.exceptionClass,
                "codePresent" to event.codePresent,
                "statePresent" to event.statePresent,
                "authErrorPresent" to event.authErrorPresent,
            )
            return buildString {
                append(sanitize(timestamp))
                fields.forEach { (name, value) ->
                    if (value != null) {
                        append(' ')
                        append(name)
                        append('=')
                        append(sanitize(value.toString()))
                    }
                }
            }
        }

        private fun sanitize(value: String): String = value
            .replace('\r', ' ')
            .replace('\n', ' ')
            .take(MAXIMUM_FIELD_LENGTH)

        private const val MAXIMUM_FIELD_LENGTH = 160
    }
}

private class LogcatAndFileDiagnosticLogger(
    private val fileLogger: RollingFileDiagnosticLogger,
) : DiagnosticLogger {
    override fun record(event: DiagnosticEvent) {
        fileLogger.record(event)
        try {
            Log.i(
                "RoyalShuffleDiagnostics",
                RollingFileDiagnosticLogger.formatLine("", event).trimStart(),
            )
        } catch (_: Exception) {
            // Logcat availability must not affect persistence or workflow behavior.
        }
    }
}

object DiagnosticLoggerProvider {
    @Volatile
    private var logger: DiagnosticLogger? = null

    fun get(context: Context): DiagnosticLogger = logger ?: synchronized(this) {
        logger ?: LogcatAndFileDiagnosticLogger(
            RollingFileDiagnosticLogger(File(context.filesDir, DIAGNOSTICS_DIRECTORY)),
        ).also { logger = it }
    }

    internal const val DIAGNOSTICS_DIRECTORY = "diagnostics"
}
