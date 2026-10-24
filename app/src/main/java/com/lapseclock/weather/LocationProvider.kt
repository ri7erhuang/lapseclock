package com.lapseclock.weather

import android.annotation.SuppressLint
import android.content.Context
import android.location.Location
import android.location.LocationManager
import android.os.CancellationSignal
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.Executors
import kotlin.coroutines.resume

/** 网络定位优先，GPS 兜底；超时返回 null。调用方需已持有定位权限。 */
class LocationProvider(private val context: Context) {

    /** 复用单线程，避免每次定位都新建一个 Executor 造成线程泄漏 */
    private val executor = Executors.newSingleThreadExecutor()

    @SuppressLint("MissingPermission")
    suspend fun getLocation(): Pair<Double, Double>? {
        val lm = context.getSystemService(LocationManager::class.java)
        val providers = listOf(LocationManager.NETWORK_PROVIDER, LocationManager.GPS_PROVIDER)
            .filter { it in lm.allProviders }
        for (provider in providers) {
            val loc = withTimeoutOrNull(10_000) { awaitLocation(lm, provider) }
            if (loc != null) return loc.latitude to loc.longitude
        }
        return null
    }

    @SuppressLint("MissingPermission")
    private suspend fun awaitLocation(lm: LocationManager, provider: String): Location? =
        suspendCancellableCoroutine { cont ->
            val signal = CancellationSignal()
            cont.invokeOnCancellation { signal.cancel() }
            try {
                lm.getCurrentLocation(provider, signal, executor) { loc ->
                    if (cont.isActive) cont.resume(loc)
                }
            } catch (t: Throwable) {
                if (cont.isActive) cont.resume(null)
            }
        }
}
