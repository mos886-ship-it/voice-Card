package com.example.voiceai.shared

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.CancellationSignal
import android.os.Looper
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import kotlin.coroutines.resume

/**
 * 录音的 GPS 戳记：开始录音时的位置。[accuracy] = 误差半径（公尺）；[at] = 定位时间；
 * [place] = 地名（手机用 Geocoder 反查，手表上没有就是 null）。
 */
data class GeoFix(
    val lat: Double,
    val lon: Double,
    val accuracy: Float,
    val at: Long,
    val place: String? = null,
    /** 定位来源："phone"（联动的手机，主要来源）/ "watch"（手表自己的 GPS，手机连不上时备援）。 */
    val source: String? = null,
    /** 国家代码（ISO，例如 TW、JP；Geocoder 反查，查不到 null）：饮食用当地的营养资料库。 */
    val country: String? = null,
) {
    fun toJson(): String = JSONObject()
        .put("lat", lat).put("lon", lon).put("accuracy", accuracy.toDouble()).put("at", at)
        .put("place", place)
        .put("source", source)
        .put("country", country)
        .toString()

    /** 地图连结（分享报告用）。 */
    val mapUrl: String get() = "https://maps.google.com/?q=%.6f,%.6f".format(java.util.Locale.US, lat, lon)

    companion object {
        fun fromJson(text: String?): GeoFix? = runCatching {
            val j = JSONObject(text ?: return null)
            GeoFix(j.getDouble("lat"), j.getDouble("lon"), j.optDouble("accuracy", 0.0).toFloat(), j.optLong("at"),
                if (j.isNull("place")) null else j.optString("place").takeIf { it.isNotBlank() },
                if (j.isNull("source")) null else j.optString("source").takeIf { it.isNotBlank() },
                if (j.isNull("country")) null else j.optString("country").takeIf { it.isNotBlank() })
        }.getOrNull()

        /** 手机在背景也能定位（「一律允許」）：手表录音时由手机提供位置需要这个。 */
        fun hasBackgroundPermission(context: Context) = hasPermission(context) &&
            (Build.VERSION.SDK_INT < 29 ||
                context.checkSelfPermission(Manifest.permission.ACCESS_BACKGROUND_LOCATION) == PackageManager.PERMISSION_GRANTED)

        fun hasPermission(context: Context) =
            context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
                context.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

        /**
         * 取得目前位置（最多等 [timeoutMs]）：先要一次新的定位（GPS / 融合定位），等不到就用最近 10 分钟内的最后位置。
         * 没有权限、没开定位、都拿不到 → null（录音照常，只是没有地点）。
         */
        @SuppressLint("MissingPermission")
        suspend fun current(context: Context, timeoutMs: Long = 30_000): GeoFix? {
            if (!hasPermission(context)) return null
            val lm = context.getSystemService(LocationManager::class.java) ?: return null
            val providers = buildList {
                if (Build.VERSION.SDK_INT >= 31 && lm.hasProvider(LocationManager.FUSED_PROVIDER)) add(LocationManager.FUSED_PROVIDER)
                if (lm.isProviderEnabled(LocationManager.GPS_PROVIDER)) add(LocationManager.GPS_PROVIDER)
                if (runCatching { lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER) }.getOrDefault(false)) add(LocationManager.NETWORK_PROVIDER)
            }
            if (providers.isEmpty()) return null
            // 几个定位来源同时问（融合 / GPS / 网路），谁先回就用谁；某个来源回 null 不算数，继续等别的
            val fresh = withTimeoutOrNull(timeoutMs) {
                kotlinx.coroutines.coroutineScope {
                    val ch = kotlinx.coroutines.channels.Channel<Location?>(providers.size)
                    val jobs = providers.map { p -> launch { ch.send(runCatching { requestOne(lm, p, timeoutMs) }.getOrNull()) } }
                    var got: Location? = null
                    repeat(providers.size) { if (got == null) got = ch.receive() }
                    jobs.forEach { it.cancel() }
                    got
                }
            }
            val loc = fresh ?: providers.mapNotNull { runCatching { lm.getLastKnownLocation(it) }.getOrNull() }
                .filter { System.currentTimeMillis() - it.time < 10 * 60_000 }
                .minByOrNull { it.accuracy }
            return loc?.let { GeoFix(it.latitude, it.longitude, it.accuracy, it.time) }
        }

        @SuppressLint("MissingPermission")
        private suspend fun requestOne(lm: LocationManager, provider: String, timeoutMs: Long): Location? = suspendCancellableCoroutine { cont ->
            if (Build.VERSION.SDK_INT >= 31) {
                // 要高精度（预设是 BALANCED，融合定位可能根本不开 GPS）
                val signal = CancellationSignal()
                cont.invokeOnCancellation { signal.cancel() }
                val req = android.location.LocationRequest.Builder(0L)
                    .setQuality(android.location.LocationRequest.QUALITY_HIGH_ACCURACY)
                    .setDurationMillis(timeoutMs)
                    .build()
                lm.getCurrentLocation(provider, req, signal, { it.run() }) { loc -> if (cont.isActive) cont.resume(loc) }
            } else if (Build.VERSION.SDK_INT >= 30) {
                val signal = CancellationSignal()
                cont.invokeOnCancellation { signal.cancel() }
                lm.getCurrentLocation(provider, signal, { it.run() }) { loc -> if (cont.isActive) cont.resume(loc) }
            } else {
                val listener = object : LocationListener {
                    override fun onLocationChanged(location: Location) {
                        lm.removeUpdates(this)
                        if (cont.isActive) cont.resume(location)
                    }
                    @Deprecated("Deprecated in Java")
                    override fun onStatusChanged(provider: String?, status: Int, extras: android.os.Bundle?) {}
                    override fun onProviderEnabled(provider: String) {}
                    override fun onProviderDisabled(provider: String) {}
                }
                cont.invokeOnCancellation { lm.removeUpdates(listener) }
                lm.requestLocationUpdates(provider, 0L, 0f, listener, Looper.getMainLooper())
            }
        }

    }
}
