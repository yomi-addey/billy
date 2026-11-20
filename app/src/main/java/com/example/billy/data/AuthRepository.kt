package com.example.billy.data

import android.content.Context
import com.example.billy.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Backend contract (the server holds the SMS provider keys, e.g. Twilio):
 *
 *  POST {API_BASE_URL}/auth/sms-link   {"phone": "+14155550123"}
 *      -> 204. Server texts: https://{AUTH_HOST}/signin?token=<one-time token>
 *
 *  POST {API_BASE_URL}/auth/verify     {"token": "<one-time token>"}
 *      -> 200 {"sessionToken": "..."}; 4xx if invalid/expired/used.
 */
interface AuthRepository {
    suspend fun requestSignInLink(e164Phone: String): Result<Unit>
    suspend fun completeSignIn(token: String): Result<Unit>
    fun isSignedIn(): Boolean
    fun signOut()
}

class HttpAuthRepository(context: Context) : AuthRepository {
    private val prefs = context.applicationContext
        .getSharedPreferences("auth", Context.MODE_PRIVATE)

    override suspend fun requestSignInLink(e164Phone: String): Result<Unit> =
        post("/auth/sms-link", JSONObject().put("phone", e164Phone)).map { }

    override suspend fun completeSignIn(token: String): Result<Unit> =
        post("/auth/verify", JSONObject().put("token", token)).mapCatching { body ->
            val session = JSONObject(body).getString("sessionToken")
            prefs.edit().putString(KEY_SESSION, session).apply()
        }

    override fun isSignedIn() = prefs.getString(KEY_SESSION, null) != null

    override fun signOut() {
        prefs.edit().remove(KEY_SESSION).apply()
    }

    private suspend fun post(path: String, json: JSONObject): Result<String> =
        withContext(Dispatchers.IO) {
            runCatching {
                val conn = URL(BuildConfig.API_BASE_URL + path).openConnection() as HttpURLConnection
                try {
                    conn.requestMethod = "POST"
                    conn.connectTimeout = 10_000
                    conn.readTimeout = 10_000
                    conn.doOutput = true
                    conn.setRequestProperty("Content-Type", "application/json")
                    conn.outputStream.use { it.write(json.toString().toByteArray()) }
                    val code = conn.responseCode
                    if (code !in 200..299) throw AuthException(code)
                    if (code == 204) "" else conn.inputStream.bufferedReader().use { it.readText() }
                } finally {
                    conn.disconnect()
                }
            }
        }

    private companion object {
        const val KEY_SESSION = "session_token"
    }
}

class AuthException(val httpCode: Int) : Exception("HTTP $httpCode")
