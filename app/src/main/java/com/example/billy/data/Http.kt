package com.example.billy.data

import com.example.billy.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL

/** Non-2xx response from the backend. 401 means the session is missing/expired. */
class AuthException(val httpCode: Int) : Exception("HTTP $httpCode")

/**
 * Minimal JSON/bytes HTTP client against [BuildConfig.API_BASE_URL].
 * Returns the response body (empty for 204). Throws [AuthException] for non-2xx.
 */
internal suspend fun httpRequest(
    method: String,
    path: String,
    body: ByteArray? = null,
    contentType: String = "application/json",
    bearer: String? = null,
): ByteArray = withContext(Dispatchers.IO) {
    val conn = URL(BuildConfig.API_BASE_URL + path).openConnection() as HttpURLConnection
    try {
        conn.requestMethod = method
        conn.connectTimeout = 10_000
        conn.readTimeout = 15_000
        bearer?.let { conn.setRequestProperty("Authorization", "Bearer $it") }
        if (body != null) {
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", contentType)
            conn.setFixedLengthStreamingMode(body.size)
            conn.outputStream.use { it.write(body) }
        }
        val code = conn.responseCode
        if (code !in 200..299) throw AuthException(code)
        if (code == 204) ByteArray(0) else conn.inputStream.use { it.readBytes() }
    } finally {
        conn.disconnect()
    }
}
