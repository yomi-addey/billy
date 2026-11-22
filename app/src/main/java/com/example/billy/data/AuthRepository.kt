package com.example.billy.data

import org.json.JSONObject

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
    suspend fun isSignedIn(): Boolean
    /** The session token for authenticated calls, or null when signed out. */
    suspend fun sessionToken(): String?
    suspend fun signOut()
}

class HttpAuthRepository(private val tokens: TokenStore) : AuthRepository {

    override suspend fun requestSignInLink(e164Phone: String): Result<Unit> = runCatching {
        postJson("/auth/sms-link", JSONObject().put("phone", e164Phone))
    }

    override suspend fun completeSignIn(token: String): Result<Unit> = runCatching {
        val body = postJson("/auth/verify", JSONObject().put("token", token))
        tokens.set(JSONObject(body.decodeToString()).getString("sessionToken"))
    }

    override suspend fun isSignedIn() = tokens.get() != null

    override suspend fun sessionToken() = tokens.get()

    override suspend fun signOut() = tokens.clear()

    private suspend fun postJson(path: String, json: JSONObject) =
        httpRequest("POST", path, json.toString().toByteArray())
}
