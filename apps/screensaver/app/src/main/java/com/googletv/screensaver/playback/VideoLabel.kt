package com.googletv.screensaver.playback

import com.googletv.screensaver.PhotoLabelMode
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/** Builds the date and place label for a video from the container's metadata, like photos do from EXIF. */
internal object VideoLabel {
    /**
     * [date] is `MediaMetadataRetriever.METADATA_KEY_DATE` (for example `20220612T180212.000Z`) and
     * [location] is `METADATA_KEY_LOCATION`, an ISO 6709 string such as `+37.7749-122.4194/`. Either may
     * be null. Pieces that are missing are left out, and a clip with neither has no label.
     */
    fun format(
        date: String?,
        location: String?,
        mode: PhotoLabelMode,
        placeNameLookup: ((Double, Double) -> String?)?,
        locale: Locale = Locale.getDefault(),
        zone: TimeZone = TimeZone.getDefault(),
    ): String? {
        if (mode == PhotoLabelMode.NONE) return null
        val place = if (mode == PhotoLabelMode.LOCATION_AND_DATE) {
            parseLocation(location)?.let { (latitude, longitude) ->
                runCatching { placeNameLookup?.invoke(latitude, longitude) }.getOrNull()
                    ?: String.format(Locale.US, "%.4f°, %.4f°", latitude, longitude)
            }
        } else null
        val day = parseDate(date)?.let { SimpleDateFormat("MMM d, yyyy", locale).apply { timeZone = zone }.format(it) }
        return listOfNotNull(place, day).joinToString(" · ").ifEmpty { null }
    }

    /** Reads `+DD.DDDD+DDD.DDDD[+alt]/` (ISO 6709); returns latitude and longitude, or null when unusable. */
    fun parseLocation(value: String?): Pair<Double, Double>? {
        val text = value?.trim()?.removeSuffix("/") ?: return null
        val match = Regex("^([+-]\\d+(?:\\.\\d+)?)([+-]\\d+(?:\\.\\d+)?)(?:[+-]\\d+(?:\\.\\d+)?)?$").find(text) ?: return null
        val latitude = match.groupValues[1].toDoubleOrNull() ?: return null
        val longitude = match.groupValues[2].toDoubleOrNull() ?: return null
        if (latitude !in -90.0..90.0 || longitude !in -180.0..180.0) return null
        if (latitude == 0.0 && longitude == 0.0) return null // the "no location" placeholder some cameras write
        return latitude to longitude
    }

    /** Reads `yyyyMMdd'T'HHmmss[.SSS][Z]` (UTC). Dates before 1990 are treated as unset (zeroed clocks). */
    fun parseDate(value: String?): Date? {
        val text = value?.trim() ?: return null
        val core = Regex("^(\\d{8}T\\d{6})").find(text)?.groupValues?.get(1) ?: return null
        val date = runCatching {
            SimpleDateFormat("yyyyMMdd'T'HHmmss", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC"); isLenient = false }.parse(core)
        }.getOrNull() ?: return null
        return date.takeIf { it.time >= EARLIEST_PLAUSIBLE_MILLIS }
    }

    private const val EARLIEST_PLAUSIBLE_MILLIS = 631_152_000_000L // 1990-01-01
}
