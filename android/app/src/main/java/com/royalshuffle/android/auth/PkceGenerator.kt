package com.royalshuffle.android.auth

import android.util.Base64
import java.security.MessageDigest
import java.security.SecureRandom

class PkceGenerator(
    private val secureRandom: SecureRandom = SecureRandom(),
) : PkceProvider {
    override fun createPendingAuthorization(): PendingAuthorization = PendingAuthorization(
        state = randomBase64Url(byteCount = 24),
        codeVerifier = randomBase64Url(byteCount = 64),
    )

    override fun createCodeChallenge(codeVerifier: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(codeVerifier.toByteArray(Charsets.US_ASCII))
        return base64Url(digest)
    }

    private fun randomBase64Url(byteCount: Int): String {
        val bytes = ByteArray(byteCount)
        secureRandom.nextBytes(bytes)
        return base64Url(bytes)
    }

    private fun base64Url(bytes: ByteArray): String = Base64.encodeToString(
        bytes,
        Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP,
    )
}
