package com.example.billy

import com.example.billy.data.AuthException
import com.example.billy.data.AuthRepository
import com.example.billy.data.DetectedLocation
import com.example.billy.data.LocationProvider
import com.example.billy.data.Profile
import com.example.billy.data.ProfileRepository
import com.example.billy.data.TokenStore
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.rules.TestWatcher
import org.junit.runner.Description

@OptIn(ExperimentalCoroutinesApi::class)
class MainDispatcherRule : TestWatcher() {
    override fun starting(description: Description) = Dispatchers.setMain(UnconfinedTestDispatcher())
    override fun finished(description: Description) = Dispatchers.resetMain()
}

class FakeTokenStore(var token: String? = null) : TokenStore {
    override suspend fun get() = token
    override suspend fun set(token: String) { this.token = token }
    override suspend fun clear() { token = null }
}

class FakeAuthRepository(private val tokens: TokenStore = FakeTokenStore()) : AuthRepository {
    var signOutCalls = 0
    override suspend fun requestSignInLink(e164Phone: String) = Result.success(Unit)
    override suspend fun completeSignIn(token: String): Result<Unit> { tokens.set("session-$token"); return Result.success(Unit) }
    override suspend fun isSignedIn() = tokens.get() != null
    override suspend fun sessionToken() = tokens.get()
    override suspend fun signOut() { signOutCalls++; tokens.clear() }
}

class FakeProfileRepository(
    var profile: Profile? = null,
    var photo: ByteArray? = null,
) : ProfileRepository {
    var failWith: Throwable? = null
    var failPhotoWith: Throwable? = null
    val saved = mutableListOf<Profile>()
    var uploads = 0
    var deletes = 0

    private fun <T> result(block: () -> T): Result<T> = failWith?.let { Result.failure(it) } ?: Result.success(block())

    override suspend fun getProfile() = result { profile }
    override suspend fun saveProfile(profile: Profile) = result {
        saved += profile
        this.profile = profile.copy(hasPhoto = photo != null)
        this.profile!!
    }
    override suspend fun uploadPhoto(jpeg: ByteArray): Result<Unit> =
        failPhotoWith?.let { Result.failure(it) } ?: result { uploads++; photo = jpeg }
    override suspend fun deletePhoto() = result { deletes++; photo = null }
    override suspend fun getPhoto() = result { photo }
}

class FakeLocationProvider(var result: Result<DetectedLocation> = Result.success(DetectedLocation("Boston", "Massachusetts"))) :
    LocationProvider {
    /** When set, detect() suspends until completed — lets tests act while detection is in flight. */
    var gate: CompletableDeferred<Unit>? = null
    var calls = 0
    override suspend fun detect(): Result<DetectedLocation> {
        calls++
        gate?.await()
        return result
    }
}

val unauthorized = AuthException(401)
