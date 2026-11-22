package com.example.billy.data

import android.annotation.SuppressLint
import android.content.Context
import android.location.Address
import android.location.Geocoder
import android.location.Location
import android.location.LocationManager
import android.os.Build
import android.os.CancellationSignal
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Locale
import kotlin.coroutines.resume

data class DetectedLocation(val city: String, val region: String)

/** Suggests the user's city and state. Callers must hold ACCESS_COARSE_LOCATION. */
interface LocationProvider {
    suspend fun detect(): Result<DetectedLocation>
}

class LocationDetectionException(message: String) : Exception(message)

/**
 * Uses the platform [LocationManager] (no Play Services) and the on-device [Geocoder].
 * The coordinates are used only for the reverse-geocode lookup and are never stored or sent.
 */
class AndroidLocationProvider(context: Context) : LocationProvider {
    private val appContext = context.applicationContext
    private val manager = appContext.getSystemService(LocationManager::class.java)

    override suspend fun detect(): Result<DetectedLocation> = runCatching {
        val location = currentLocation() ?: throw LocationDetectionException("No location available")
        if (!Geocoder.isPresent()) throw LocationDetectionException("Geocoder unavailable")
        val address = reverseGeocode(location) ?: throw LocationDetectionException("No address found")
        val city = address.locality ?: address.subAdminArea
        val region = address.adminArea
        if (city.isNullOrBlank() || region.isNullOrBlank()) throw LocationDetectionException("Incomplete address")
        DetectedLocation(city, region)
    }

    private fun providers(): List<String> {
        val enabled = manager.getProviders(true)
        val preferred = buildList {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) add(LocationManager.FUSED_PROVIDER)
            add(LocationManager.NETWORK_PROVIDER)
            add(LocationManager.GPS_PROVIDER)
            add(LocationManager.PASSIVE_PROVIDER)
        }
        return preferred.filter { it in enabled }
    }

    @SuppressLint("MissingPermission") // caller checks; SecurityException is caught by runCatching
    private suspend fun currentLocation(): Location? {
        val providers = providers()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            for (provider in providers) {
                val fresh = withTimeoutOrNull(10_000) { fresh(provider) }
                if (fresh != null) return fresh
            }
        }
        return providers
            .mapNotNull { runCatching { manager.getLastKnownLocation(it) }.getOrNull() }
            .maxByOrNull { it.time }
    }

    @SuppressLint("MissingPermission")
    @androidx.annotation.RequiresApi(Build.VERSION_CODES.R)
    private suspend fun fresh(provider: String): Location? = suspendCancellableCoroutine { cont ->
        val signal = CancellationSignal()
        cont.invokeOnCancellation { signal.cancel() }
        try {
            manager.getCurrentLocation(provider, signal, appContext.mainExecutor) {
                if (cont.isActive) cont.resume(it)
            }
        } catch (e: Exception) { // SecurityException / IllegalArgumentException for an unusable provider
            if (cont.isActive) cont.resume(null)
        }
    }

    private suspend fun reverseGeocode(location: Location): Address? {
        val geocoder = Geocoder(appContext, Locale.getDefault())
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            withTimeoutOrNull(10_000) {
                suspendCancellableCoroutine { cont ->
                    geocoder.getFromLocation(location.latitude, location.longitude, 1, object : Geocoder.GeocodeListener {
                        override fun onGeocode(addresses: MutableList<Address>) {
                            if (cont.isActive) cont.resume(addresses.firstOrNull())
                        }

                        override fun onError(errorMessage: String?) {
                            if (cont.isActive) cont.resume(null)
                        }
                    })
                }
            }
        } else {
            withContext(Dispatchers.IO) {
                @Suppress("DEPRECATION")
                geocoder.getFromLocation(location.latitude, location.longitude, 1)?.firstOrNull()
            }
        }
    }
}
