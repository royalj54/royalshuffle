package com.royalshuffle.android.diagnostics

data class DiagnosticEvent(
    val eventName: String,
    val operationName: String? = null,
    val operationClass: String? = null,
    val httpStatus: Int? = null,
    val failureCategory: String? = null,
    val retryAfter: String? = null,
    val quotaReason: String? = null,
    val retryAttempt: Int? = null,
    val retryDelaySeconds: Double? = null,
    val pageNumber: Int? = null,
    val batchNumber: Int? = null,
    val confirmedItems: Int? = null,
    val intendedItems: Int? = null,
    val skippedItems: Int? = null,
    val exceptionClass: String? = null,
    val codePresent: Boolean? = null,
    val statePresent: Boolean? = null,
    val authErrorPresent: Boolean? = null,
)

fun interface DiagnosticLogger {
    fun record(event: DiagnosticEvent)
}

object NoOpDiagnosticLogger : DiagnosticLogger {
    override fun record(event: DiagnosticEvent) = Unit
}

internal fun DiagnosticLogger.recordSafely(event: DiagnosticEvent) {
    try {
        record(event)
    } catch (_: Exception) {
        // Diagnostics must never break the user workflow.
    }
}
