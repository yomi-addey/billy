package com.example.billy.data

import org.json.JSONObject

/**
 * What other people see about a user. Deliberately minimal: no birthday, address, email,
 * gender or precise location. Location is city + state only.
 */
data class Profile(
    val firstName: String,
    val lastName: String = "",
    val city: String,
    val region: String,
    val bio: String = "",
    val hasPhoto: Boolean = false,
) {
    val displayName get() = listOf(firstName, lastName).filter { it.isNotBlank() }.joinToString(" ")
}

object ProfileLimits {
    const val NAME_MAX = 50
    const val PLACE_MAX = 80
    const val BIO_MAX = 280
}

/**
 * Backend contract (all calls need `Authorization: Bearer <session>`; 401 if missing/expired):
 *
 *  GET    /profile        -> 200 profile JSON, 404 if not created yet
 *  PUT    /profile        {firstName, lastName, city, region, bio} -> 200 profile JSON
 *  PUT    /profile/photo  raw image/jpeg (≤ 1 MB) -> 204
 *  GET    /profile/photo  -> 200 image/jpeg, 404 if none
 *  DELETE /profile/photo  -> 204
 */
interface ProfileRepository {
    /** `null` means the user hasn't created a profile yet. */
    suspend fun getProfile(): Result<Profile?>
    suspend fun saveProfile(profile: Profile): Result<Profile>
    suspend fun uploadPhoto(jpeg: ByteArray): Result<Unit>
    suspend fun deletePhoto(): Result<Unit>
    /** `null` means no photo. */
    suspend fun getPhoto(): Result<ByteArray?>
}

class HttpProfileRepository(private val auth: AuthRepository) : ProfileRepository {

    override suspend fun getProfile(): Result<Profile?> = runCatching {
        try {
            parse(httpRequest("GET", "/profile", bearer = token()))
        } catch (e: AuthException) {
            if (e.httpCode == 404) null else throw e
        }
    }

    override suspend fun saveProfile(profile: Profile): Result<Profile> = runCatching {
        val json = JSONObject()
            .put("firstName", profile.firstName)
            .put("lastName", profile.lastName)
            .put("city", profile.city)
            .put("region", profile.region)
            .put("bio", profile.bio)
        parse(httpRequest("PUT", "/profile", json.toString().toByteArray(), bearer = token()))
    }

    override suspend fun uploadPhoto(jpeg: ByteArray): Result<Unit> = runCatching {
        httpRequest("PUT", "/profile/photo", jpeg, contentType = "image/jpeg", bearer = token())
        Unit
    }

    override suspend fun deletePhoto(): Result<Unit> = runCatching {
        httpRequest("DELETE", "/profile/photo", bearer = token())
        Unit
    }

    override suspend fun getPhoto(): Result<ByteArray?> = runCatching {
        try {
            httpRequest("GET", "/profile/photo", bearer = token())
        } catch (e: AuthException) {
            if (e.httpCode == 404) null else throw e
        }
    }

    private suspend fun token() = auth.sessionToken() ?: throw AuthException(401)

    private fun parse(body: ByteArray): Profile = JSONObject(body.decodeToString()).let {
        Profile(
            firstName = it.getString("firstName"),
            lastName = it.optString("lastName"),
            city = it.getString("city"),
            region = it.getString("region"),
            bio = it.optString("bio"),
            hasPhoto = it.optBoolean("hasPhoto"),
        )
    }
}
