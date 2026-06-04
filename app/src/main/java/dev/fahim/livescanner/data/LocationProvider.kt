package dev.fahim.livescanner.data

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.Looper
import androidx.core.content.ContextCompat
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

/** Thin wrapper over the platform LocationManager — no Google Play Services dependency. */
class LocationProvider(private val context: Context) {

    private val manager = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager

    fun hasPermission(): Boolean {
        val fine = ContextCompat.checkSelfPermission(
            context, Manifest.permission.ACCESS_FINE_LOCATION,
        ) == PackageManager.PERMISSION_GRANTED
        val coarse = ContextCompat.checkSelfPermission(
            context, Manifest.permission.ACCESS_COARSE_LOCATION,
        ) == PackageManager.PERMISSION_GRANTED
        return fine || coarse
    }

    @SuppressLint("MissingPermission")
    fun lastKnownLocation(): LatLng? {
        if (!hasPermission()) return null
        val mgr = manager ?: return null
        var newest: Location? = null
        for (provider in mgr.getProviders(true)) {
            val loc = try {
                mgr.getLastKnownLocation(provider)
            } catch (_: SecurityException) {
                null
            } ?: continue
            if (newest == null || loc.time > newest.time) newest = loc
        }
        return newest?.let { LatLng(it.latitude, it.longitude) }
    }

    /** Returns a last-known fix immediately, or requests a single fresh update (with timeout). */
    @SuppressLint("MissingPermission")
    suspend fun awaitLocation(timeoutMs: Long = 8_000L): LatLng? {
        if (!hasPermission()) return null
        lastKnownLocation()?.let { return it }
        val mgr = manager ?: return null
        val provider = pickProvider(mgr) ?: return null
        return withTimeoutOrNull(timeoutMs) {
            suspendCancellableCoroutine { cont ->
                val listener = object : LocationListener {
                    override fun onLocationChanged(location: Location) {
                        mgr.removeUpdates(this)
                        if (cont.isActive) cont.resume(LatLng(location.latitude, location.longitude))
                    }

                    override fun onProviderDisabled(provider: String) {}
                    override fun onProviderEnabled(provider: String) {}

                    @Deprecated("Required by LocationListener on older APIs")
                    override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
                }
                mgr.requestLocationUpdates(provider, 1_000L, 0f, listener, Looper.getMainLooper())
                cont.invokeOnCancellation { mgr.removeUpdates(listener) }
            }
        }
    }

    private fun pickProvider(mgr: LocationManager): String? {
        val enabled = mgr.getProviders(true)
        return when {
            LocationManager.NETWORK_PROVIDER in enabled -> LocationManager.NETWORK_PROVIDER
            LocationManager.GPS_PROVIDER in enabled -> LocationManager.GPS_PROVIDER
            else -> enabled.firstOrNull()
        }
    }
}
