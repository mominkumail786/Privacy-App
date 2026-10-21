package com.example.privacyguard.data

import android.content.Context
import com.example.privacyguard.logic.EventLogFormat
import com.example.privacyguard.logic.EventType
import com.example.privacyguard.logic.GuardEvent
import java.io.File

/**
 * A small text file with the last events (alerts, camera busy, ...).
 * It holds only time, kind and a similarity number. No pictures and no face data.
 */
class EventLogStore(context: Context) {
    private val file = File(context.applicationContext.filesDir, "events.log")

    @Synchronized
    fun add(type: EventType, score: Float? = null) {
        try {
            val lines = readLines() + EventLogFormat.encode(GuardEvent(System.currentTimeMillis(), type, score))
            file.writeText(EventLogFormat.trim(lines, MAX_EVENTS).joinToString("\n"))
        } catch (e: Exception) {
            // Logging must never break protection.
        }
    }

    /** Newest event first. */
    @Synchronized
    fun all(): List<GuardEvent> = readLines().mapNotNull { EventLogFormat.decode(it) }.reversed()

    @Synchronized
    fun clear() {
        file.delete()
    }

    private fun readLines(): List<String> =
        if (file.exists()) file.readLines().filter { it.isNotBlank() } else emptyList()

    companion object {
        private const val MAX_EVENTS = 200
    }
}
