package com.royalshuffle.android.data.remote

import android.util.Log
import com.royalshuffle.android.auth.SessionInvalidator
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.util.concurrent.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONObject

enum class WebApiFailureCategory {
    AUTHENTICATION,
    PERMISSION,
    RATE_LIMITED,
    QUOTA_EXCEEDED,
    SERVER,
    CONNECTIVITY,
    INVALID_RESPONSE,
    INVALID_PAGINATION,
    OTHER,
}

enum class WebApiOperationClass {
    READ,
    NON_IDEMPOTENT_WRITE,
}

data class WebApiOperation(
    val name: String,
    val classification: WebApiOperationClass,
)

class SpotifyWebApiException(
    val category: WebApiFailureCategory,
    val httpStatus: Int? = null,
    cause: Throwable? = null,
) : Exception(cause)

data class WebApiRequest(
    val method: String,
    val url: String,
    val accessToken: String,
    val body: String? = null,
)

data class WebApiResponse(
    val statusCode: Int,
    val headers: Map<String, List<String>>,
    val body: String,
) {
    fun header(name: String): String? = headers.entries.firstOrNull {
        it.key.equals(name, ignoreCase = true)
    }?.value?.firstOrNull()
}

fun interface WebApiTransport {
    suspend fun execute(request: WebApiRequest): WebApiResponse
}

fun interface RetryDelay {
    suspend fun wait(seconds: Double)
}

fun interface WebApiDiagnostics {
    fun record(event: WebApiDiagnosticEvent)
}

data class WebApiDiagnosticEvent(
    val operationName: String,
    val operationClass: WebApiOperationClass,
    val httpStatus: Int? = null,
    val failureCategory: WebApiFailureCategory? = null,
    val retryAfter: String? = null,
    val quotaReason: String? = null,
    val retryAttempt: Int? = null,
    val retryDelaySeconds: Double? = null,
    val exceptionClass: String? = null,
)

class SpotifyWebApiClient(
    private val transport: WebApiTransport = HttpUrlConnectionTransport(),
    private val retryDelay: RetryDelay = RetryDelay { seconds ->
        delay((seconds * MILLIS_PER_SECOND).toLong())
    },
    private val diagnostics: WebApiDiagnostics = LogcatWebApiDiagnostics,
    private val sessionInvalidator: SessionInvalidator? = null,
) {
    suspend fun <T> requestJson(
        request: WebApiRequest,
        operation: WebApiOperation,
        transform: (JSONObject) -> T,
    ): T {
        validateUrl(request.url, operation)
        var retryCount = 0

        while (true) {
            val response = try {
                transport.execute(request)
            } catch (error: CancellationException) {
                throw error
            } catch (error: IOException) {
                throw failure(operation, WebApiFailureCategory.CONNECTIVITY, cause = error)
            } catch (error: Exception) {
                throw failure(operation, WebApiFailureCategory.CONNECTIVITY, cause = error)
            }

            if (response.statusCode == HTTP_TOO_MANY_REQUESTS) {
                val retryAfter = response.header("Retry-After")
                val quotaReason = quotaReason(response.body)
                if (quotaReason == QUOTA_EXCEEDED_REASON) {
                    throw failure(
                        operation,
                        WebApiFailureCategory.QUOTA_EXCEEDED,
                        response.statusCode,
                        retryAfter,
                        quotaReason,
                    )
                }

                val retrySeconds = retryDelaySeconds(retryAfter)
                if (retrySeconds > MAX_AUTOMATIC_RETRY_SECONDS ||
                    retryCount >= MAX_RATE_LIMIT_RETRIES
                ) {
                    throw failure(
                        operation,
                        WebApiFailureCategory.RATE_LIMITED,
                        response.statusCode,
                        retryAfter,
                        quotaReason,
                    )
                }

                retryCount += 1
                diagnostics.record(
                    WebApiDiagnosticEvent(
                        operationName = operation.name,
                        operationClass = operation.classification,
                        httpStatus = response.statusCode,
                        failureCategory = WebApiFailureCategory.RATE_LIMITED,
                        retryAfter = retryAfter,
                        quotaReason = quotaReason,
                        retryAttempt = retryCount,
                        retryDelaySeconds = retrySeconds,
                    ),
                )
                retryDelay.wait(retrySeconds)
                continue
            }

            if (response.statusCode !in 200..299) {
                if (response.statusCode == HTTP_UNAUTHORIZED) {
                    sessionInvalidator?.invalidateSession()
                }
                throw failure(
                    operation,
                    categoryForStatus(response.statusCode),
                    response.statusCode,
                )
            }

            val json = try {
                if (response.body.isBlank()) throw IllegalArgumentException("Empty JSON response")
                JSONObject(response.body)
            } catch (error: Exception) {
                throw failure(
                    operation,
                    WebApiFailureCategory.INVALID_RESPONSE,
                    response.statusCode,
                    cause = error,
                )
            }

            return try {
                transform(json)
            } catch (error: CancellationException) {
                throw error
            } catch (error: SpotifyWebApiException) {
                throw error
            } catch (error: Exception) {
                throw failure(
                    operation,
                    WebApiFailureCategory.INVALID_RESPONSE,
                    response.statusCode,
                    cause = error,
                )
            }
        }
    }

    private fun validateUrl(url: String, operation: WebApiOperation) {
        val uri = try {
            URI(url)
        } catch (error: Exception) {
            throw failure(
                operation,
                WebApiFailureCategory.INVALID_PAGINATION,
                cause = error,
            )
        }
        if (!uri.scheme.equals("https", ignoreCase = true) ||
            !uri.host.equals("api.spotify.com", ignoreCase = true) ||
            uri.userInfo != null
        ) {
            throw failure(operation, WebApiFailureCategory.INVALID_PAGINATION)
        }
    }

    private fun failure(
        operation: WebApiOperation,
        category: WebApiFailureCategory,
        status: Int? = null,
        retryAfter: String? = null,
        quotaReason: String? = null,
        cause: Throwable? = null,
    ): SpotifyWebApiException {
        diagnostics.record(
            WebApiDiagnosticEvent(
                operationName = operation.name,
                operationClass = operation.classification,
                httpStatus = status,
                failureCategory = category,
                retryAfter = retryAfter,
                quotaReason = quotaReason,
                exceptionClass = cause?.javaClass?.simpleName,
            ),
        )
        return SpotifyWebApiException(category, status, cause)
    }

    private fun retryDelaySeconds(retryAfter: String?): Double {
        val parsed = retryAfter?.toDoubleOrNull()
        return if (parsed == null || !parsed.isFinite() || parsed < 0) {
            RATE_LIMIT_FALLBACK_SECONDS
        } else {
            parsed
        }
    }

    private fun quotaReason(body: String): String? = try {
        val payload = JSONObject(body)
        val error = payload.optJSONObject("error")
        sequenceOf(error, payload)
            .filterNotNull()
            .flatMap { container ->
                sequenceOf(container.optString("reason"), container.optString("category"))
            }
            .firstOrNull { it.isNotBlank() }
            ?.uppercase()
    } catch (_: Exception) {
        null
    }

    private fun categoryForStatus(status: Int): WebApiFailureCategory = when (status) {
        401 -> WebApiFailureCategory.AUTHENTICATION
        403 -> WebApiFailureCategory.PERMISSION
        in 500..599 -> WebApiFailureCategory.SERVER
        else -> WebApiFailureCategory.OTHER
    }

    private companion object {
        const val HTTP_TOO_MANY_REQUESTS = 429
        const val HTTP_UNAUTHORIZED = 401
        const val MAX_RATE_LIMIT_RETRIES = 3
        const val MAX_AUTOMATIC_RETRY_SECONDS = 60.0
        const val RATE_LIMIT_FALLBACK_SECONDS = 1.0
        const val MILLIS_PER_SECOND = 1_000.0
        const val QUOTA_EXCEEDED_REASON = "QUOTA_EXCEEDED"
    }
}

private class HttpUrlConnectionTransport : WebApiTransport {
    override suspend fun execute(request: WebApiRequest): WebApiResponse =
        withContext(Dispatchers.IO) {
            val connection = (URL(request.url).openConnection() as HttpURLConnection).apply {
                requestMethod = request.method
                connectTimeout = TIMEOUT_MILLIS
                readTimeout = TIMEOUT_MILLIS
                setRequestProperty("Authorization", "Bearer ${request.accessToken}")
                setRequestProperty("Accept", "application/json")
                if (request.body != null) {
                    doOutput = true
                    setRequestProperty("Content-Type", "application/json")
                }
            }
            try {
                request.body?.let { body ->
                    connection.outputStream.bufferedWriter(Charsets.UTF_8).use { it.write(body) }
                }
                val status = connection.responseCode
                val responseBody = (if (status in 200..299) {
                    connection.inputStream
                } else {
                    connection.errorStream
                })?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
                val headers = connection.headerFields.entries.mapNotNull { (name, values) ->
                    name?.let { it to values }
                }.toMap()
                WebApiResponse(status, headers, responseBody)
            } finally {
                connection.disconnect()
            }
        }

    private companion object {
        const val TIMEOUT_MILLIS = 15_000
    }
}

private object LogcatWebApiDiagnostics : WebApiDiagnostics {
    override fun record(event: WebApiDiagnosticEvent) {
        Log.i(
            "RoyalShuffleWebApi",
            "operation=${event.operationName}; class=${event.operationClass}; " +
                "status=${event.httpStatus}; category=${event.failureCategory}; " +
                "retryAfter=${event.retryAfter}; quotaReason=${event.quotaReason}; " +
                "retryAttempt=${event.retryAttempt}; delay=${event.retryDelaySeconds}; " +
                "exceptionClass=${event.exceptionClass}",
        )
    }
}
