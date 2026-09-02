package com.royalshuffle.android.diagnostics

import com.royalshuffle.android.data.remote.WebApiDiagnosticEvent
import com.royalshuffle.android.data.remote.WebApiDiagnostics
import java.net.URI
import java.net.URLDecoder

fun DiagnosticLogger.asWebApiDiagnostics(): WebApiDiagnostics = WebApiDiagnostics { event ->
    recordSafely(event.toPersistentEvent())
}

private fun WebApiDiagnosticEvent.toPersistentEvent() = DiagnosticEvent(
    eventName = "spotify_web_api",
    operationName = operationName,
    operationClass = operationClass.name,
    httpStatus = httpStatus,
    failureCategory = failureCategory?.name,
    retryAfter = retryAfter,
    quotaReason = quotaReason,
    retryAttempt = retryAttempt,
    retryDelaySeconds = retryDelaySeconds,
    exceptionClass = exceptionClass,
)

fun recordAuthCallbackMetadata(logger: DiagnosticLogger, callbackUri: String) {
    val names = try {
        URI(callbackUri).rawQuery.orEmpty()
            .split('&')
            .filter(String::isNotBlank)
            .map { parameter ->
                URLDecoder.decode(parameter.substringBefore('='), Charsets.UTF_8.name())
            }
            .toSet()
    } catch (_: Exception) {
        emptySet()
    }
    logger.recordSafely(
        DiagnosticEvent(
            eventName = "spotify_auth_callback_received",
            codePresent = "code" in names,
            statePresent = "state" in names,
            authErrorPresent = "error" in names,
        ),
    )
}
