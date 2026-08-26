package com.royalshuffle.android.data.remote

import com.royalshuffle.android.auth.TokenEndpointClient
import com.royalshuffle.android.auth.TokenEndpointException
import com.royalshuffle.android.auth.TokenResponse
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

class SpotifyTokenEndpointClient : TokenEndpointClient {
    override suspend fun exchangeAuthorizationCode(
        clientId: String,
        code: String,
        codeVerifier: String,
        redirectUri: String,
    ): TokenResponse = requestToken(
        mapOf(
            "client_id" to clientId,
            "grant_type" to "authorization_code",
            "code" to code,
            "redirect_uri" to redirectUri,
            "code_verifier" to codeVerifier,
        ),
    )

    override suspend fun refreshAccessToken(
        clientId: String,
        refreshToken: String,
    ): TokenResponse = requestToken(
        mapOf(
            "client_id" to clientId,
            "grant_type" to "refresh_token",
            "refresh_token" to refreshToken,
        ),
    )

    private suspend fun requestToken(parameters: Map<String, String>): TokenResponse =
        withContext(Dispatchers.IO) {
            val connection = (URL(TOKEN_URL).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = TIMEOUT_MILLIS
                readTimeout = TIMEOUT_MILLIS
                doOutput = true
                setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
                setRequestProperty("Accept", "application/json")
            }

            try {
                connection.outputStream.bufferedWriter(Charsets.UTF_8).use { writer ->
                    writer.write(parameters.toFormBody())
                }
                val responseCode = connection.responseCode
                val responseBody = (if (responseCode in 200..299) {
                    connection.inputStream
                } else {
                    connection.errorStream
                })?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
                val json = JSONObject(responseBody)
                if (responseCode !in 200..299) {
                    throw TokenEndpointException(json.optString("error").takeIf { it.isNotBlank() })
                }
                TokenResponse(
                    accessToken = json.getString("access_token"),
                    refreshToken = json.optString("refresh_token").takeIf { it.isNotBlank() },
                    expiresInSeconds = json.getLong("expires_in"),
                )
            } catch (error: TokenEndpointException) {
                throw error
            } catch (error: Exception) {
                throw TokenEndpointException(errorCode = null)
            } finally {
                connection.disconnect()
            }
        }

    private fun Map<String, String>.toFormBody(): String = entries.joinToString("&") {
        "${encode(it.key)}=${encode(it.value)}"
    }

    private fun encode(value: String): String = URLEncoder.encode(value, Charsets.UTF_8.name())

    private companion object {
        const val TOKEN_URL = "https://accounts.spotify.com/api/token"
        const val TIMEOUT_MILLIS = 15_000
    }
}
