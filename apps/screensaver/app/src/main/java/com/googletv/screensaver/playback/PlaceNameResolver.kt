package com.googletv.screensaver.playback

import android.annotation.TargetApi
import android.content.Context
import android.location.Address
import android.location.Geocoder
import android.os.Build
import java.util.LinkedHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicReference

/** Called only from PhotoSource.loadImage on SlideshowPlayer's worker thread. */
internal class PlaceNameResolver(private val context: Context) {
    private val geocoderAvailable by lazy { Geocoder.isPresent() }
    // A small per-preview/dream cache avoids querying again when slides or coordinates repeat.
    // Null results are cached too; a new preview/dream can retry a transient failure.
    private val cache = object : LinkedHashMap<Pair<Double, Double>, String?>(CACHE_SIZE, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Pair<Double, Double>, String?>): Boolean =
            size > CACHE_SIZE
    }

    fun lookup(latitude: Double, longitude: Double): String? {
        val key = latitude to longitude
        if (cache.containsKey(key)) return cache[key]
        val place = try {
            if (!geocoderAvailable) null
            else {
                val geocoder = Geocoder(context)
                val address = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    lookupAsync(geocoder, latitude, longitude)
                } else {
                    lookupLegacy(geocoder, latitude, longitude)
                }
                address?.let(::placeName)
            }
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            return null
        } catch (_: Exception) {
            null
        }
        cache[key] = place
        return place
    }

    @TargetApi(Build.VERSION_CODES.TIRAMISU)
    private fun lookupAsync(geocoder: Geocoder, latitude: Double, longitude: Double): Address? {
        val finished = CountDownLatch(1)
        val result = AtomicReference<Address?>()
        geocoder.getFromLocation(latitude, longitude, 1, object : Geocoder.GeocodeListener {
            override fun onGeocode(addresses: MutableList<Address>) {
                result.set(addresses.firstOrNull())
                finished.countDown()
            }

            override fun onError(errorMessage: String?) {
                finished.countDown()
            }
        })
        return if (finished.await(LOOKUP_TIMEOUT_SECONDS, TimeUnit.SECONDS)) result.get() else null
    }

    private fun lookupLegacy(geocoder: Geocoder, latitude: Double, longitude: Double): Address? {
        // API 31-32 only offer a blocking call. Isolate it so a slow provider cannot hold up playback.
        val task = legacyExecutor.submit<Address?> {
            @Suppress("DEPRECATION")
            geocoder.getFromLocation(latitude, longitude, 1)?.firstOrNull()
        }
        return try {
            task.get(LOOKUP_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        } catch (_: TimeoutException) {
            null
        } finally {
            task.cancel(true)
        }
    }

    private fun placeName(address: Address): String? {
        val area = sequenceOf(address.locality, address.subAdminArea, address.adminArea, address.countryName)
            .firstOrNull { !it.isNullOrBlank() }?.trim() ?: return null
        val region = address.adminArea?.trim()?.takeIf { it.isNotEmpty() && it != area }
        return listOfNotNull(area, region).joinToString(", ")
    }

    companion object {
        private const val CACHE_SIZE = 128
        private const val LOOKUP_TIMEOUT_SECONDS = 3L
        private val legacyExecutor = Executors.newSingleThreadExecutor { work ->
            Thread(work, "photo-geocoder").apply { isDaemon = true }
        }
    }
}
