package com.googletv.screensaver.playback

import java.util.Locale

/**
 * Accumulates wall-clock time (and optionally bytes) per named phase of an album refresh so one
 * log line can show where the time went. Names are fixed labels; never put photo filenames or host
 * addresses in them.
 */
internal class PhaseTimings(private val nanoTime: () -> Long = System::nanoTime) {
    private class Phase {
        var nanos = 0L
        var bytes = 0L
    }

    private val phases = linkedMapOf<String, Phase>()
    private val notes = linkedMapOf<String, Long>()
    private val startedAt = nanoTime()

    fun <T> time(name: String, block: () -> T): T {
        val phase = phases.getOrPut(name) { Phase() }
        val start = nanoTime()
        try {
            return block()
        } finally {
            phase.nanos += nanoTime() - start
        }
    }

    fun addBytes(name: String, bytes: Long) {
        phases.getOrPut(name) { Phase() }.bytes += bytes
    }

    /** Records a plain count (rows, albums, ...) alongside the timings. */
    fun note(label: String, value: Long) {
        notes[label] = value
    }

    fun summary(): String {
        val total = (nanoTime() - startedAt) / NANOS_PER_MILLI
        val parts = phases.map { (name, phase) ->
            val millis = phase.nanos / NANOS_PER_MILLI
            buildString {
                append(name).append('=').append(millis).append("ms")
                if (phase.bytes > 0) {
                    append(" bytes=").append(phase.bytes)
                    if (phase.nanos > 0) {
                        val rate = phase.bytes / (phase.nanos / 1e9) / 1_000_000.0
                        append(" rate=").append(String.format(Locale.US, "%.1f", rate)).append("MB/s")
                    }
                }
            }
        }
        return (listOf("total=${total}ms") + parts + notes.map { (label, value) -> "$label=$value" }).joinToString(" | ")
    }

    private companion object {
        const val NANOS_PER_MILLI = 1_000_000L
    }
}
