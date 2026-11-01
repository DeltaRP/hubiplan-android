package de.hubiplan.app.calendar

import android.Manifest
import android.content.ContentResolver
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.CalendarContract
import android.provider.CalendarContract.Calendars
import android.provider.CalendarContract.Events
import android.provider.CalendarContract.Reminders
import androidx.core.content.ContextCompat
import de.hubiplan.app.tr

/**
 * Lokale Kalender der App im Gerätekalender (Konto-Typ „lokal“): Sie werden nicht in ein
 * Google- oder Samsung-Konto hochgeladen und sind für den Nutzer schreibgeschützt.
 */
class DeviceCalendar(private val context: Context) {
    private val resolver: ContentResolver get() = context.contentResolver

    fun hasPermission(): Boolean =
        listOf(Manifest.permission.READ_CALENDAR, Manifest.permission.WRITE_CALENDAR).all {
            ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
        }

    private fun asSyncAdapter(uri: Uri): Uri = uri.buildUpon()
        .appendQueryParameter(CalendarContract.CALLER_IS_SYNCADAPTER, "true")
        .appendQueryParameter(Calendars.ACCOUNT_NAME, ACCOUNT_NAME)
        .appendQueryParameter(Calendars.ACCOUNT_TYPE, CalendarContract.ACCOUNT_TYPE_LOCAL)
        .build()

    /** Kalender je Einheit: interne Kennung → Kalender-ID. */
    private fun calendars(): Map<String, Long> {
        val result = mutableMapOf<String, Long>()
        resolver.query(
            Calendars.CONTENT_URI,
            arrayOf(Calendars._ID, Calendars.NAME),
            "${Calendars.ACCOUNT_NAME} = ? AND ${Calendars.ACCOUNT_TYPE} = ?",
            arrayOf(ACCOUNT_NAME, CalendarContract.ACCOUNT_TYPE_LOCAL),
            null,
        )?.use { c ->
            while (c.moveToNext()) result[c.getString(1) ?: ""] = c.getLong(0)
        }
        return result
    }

    /** Kalender anlegen oder Name und Farbe aktualisieren. */
    fun ensureCalendar(name: String, displayName: String, color: Int): Long {
        val values = ContentValues().apply {
            put(Calendars.CALENDAR_DISPLAY_NAME, displayName)
            put(Calendars.CALENDAR_COLOR, color)
        }
        calendars()[name]?.let { id ->
            resolver.update(asSyncAdapter(ContentUris.withAppendedId(Calendars.CONTENT_URI, id)), values, null, null)
            return id
        }
        values.apply {
            put(Calendars.ACCOUNT_NAME, ACCOUNT_NAME)
            put(Calendars.ACCOUNT_TYPE, CalendarContract.ACCOUNT_TYPE_LOCAL)
            put(Calendars.NAME, name)
            put(Calendars.OWNER_ACCOUNT, ACCOUNT_NAME)
            put(Calendars.CALENDAR_ACCESS_LEVEL, Calendars.CAL_ACCESS_READ)
            put(Calendars.VISIBLE, 1)
            put(Calendars.SYNC_EVENTS, 1)
            put(Calendars.CALENDAR_TIME_ZONE, java.util.TimeZone.getDefault().id)
        }
        val uri = resolver.insert(asSyncAdapter(Calendars.CONTENT_URI), values)
            ?: error(tr("Kalender konnte nicht angelegt werden."))
        return ContentUris.parseId(uri)
    }

    /** Termine eines Kalenders abgleichen. */
    fun sync(calendarId: Long, wanted: List<CalendarEntry>) {
        val existing = mutableMapOf<String, Pair<Long, String>>()
        resolver.query(
            asSyncAdapter(Events.CONTENT_URI),
            arrayOf(Events._ID, Events._SYNC_ID, Events.SYNC_DATA1),
            "${Events.CALENDAR_ID} = ?",
            arrayOf(calendarId.toString()),
            null,
        )?.use { c ->
            while (c.moveToNext()) {
                val key = c.getString(1) ?: continue
                existing[key] = c.getLong(0) to (c.getString(2) ?: "")
            }
        }
        val plan = planSync(wanted, existing.mapValues { it.value.second })
        for (entry in plan.insert) {
            val uri = resolver.insert(asSyncAdapter(Events.CONTENT_URI), values(calendarId, entry)) ?: continue
            setReminder(ContentUris.parseId(uri), entry.reminderMinutes)
        }
        for (entry in plan.update) {
            val id = existing.getValue(entry.key).first
            resolver.update(asSyncAdapter(ContentUris.withAppendedId(Events.CONTENT_URI, id)), values(calendarId, entry), null, null)
            setReminder(id, entry.reminderMinutes)
        }
        for (key in plan.delete) {
            val id = existing.getValue(key).first
            resolver.delete(asSyncAdapter(ContentUris.withAppendedId(Events.CONTENT_URI, id)), null, null)
        }
    }

    private fun values(calendarId: Long, e: CalendarEntry) = ContentValues().apply {
        put(Events.CALENDAR_ID, calendarId)
        put(Events._SYNC_ID, e.key)
        put(Events.SYNC_DATA1, e.fingerprint)
        put(Events.SYNC_DATA2, e.eventId.toString())
        put(Events.TITLE, e.title)
        put(Events.DTSTART, e.startMillis)
        put(Events.DTEND, e.endMillis)
        put(Events.ALL_DAY, if (e.allDay) 1 else 0)
        put(Events.EVENT_TIMEZONE, e.timeZone)
        put(Events.EVENT_LOCATION, e.location)
        put(Events.DESCRIPTION, e.description)
        put(Events.STATUS, if (e.cancelled) Events.STATUS_CANCELED else Events.STATUS_CONFIRMED)
        put(Events.AVAILABILITY, Events.AVAILABILITY_BUSY)
        put(Events.HAS_ALARM, if (e.reminderMinutes != null) 1 else 0)
    }

    private fun setReminder(eventId: Long, minutes: Int?) {
        resolver.delete(asSyncAdapter(Reminders.CONTENT_URI), "${Reminders.EVENT_ID} = ?", arrayOf(eventId.toString()))
        if (minutes == null) return
        resolver.insert(
            asSyncAdapter(Reminders.CONTENT_URI),
            ContentValues().apply {
                put(Reminders.EVENT_ID, eventId)
                put(Reminders.MINUTES, minutes)
                put(Reminders.METHOD, Reminders.METHOD_ALERT)
            },
        )
    }

    /**
     * Kalender von Einheiten entfernen, die es nicht mehr gibt (oder alle beim Abmelden).
     * Mit [server] nur die Kalender dieser Instanz (die anderen Konten bleiben).
     */
    fun removeCalendarsExcept(keep: Set<String>, server: String? = null) {
        if (!hasPermission()) return
        val prefix = server?.let { serverPrefix(it) }
        for ((name, id) in calendars()) {
            if (name !in keep && (prefix == null || name.startsWith(prefix))) {
                resolver.delete(asSyncAdapter(ContentUris.withAppendedId(Calendars.CONTENT_URI, id)), null, null)
            }
        }
    }

    companion object {
        const val ACCOUNT_NAME = "HUBIplan"

        /** Interne Kennung des Kalenders einer Einheit auf einer Instanz. */
        fun calendarName(server: String, accessId: Int): String = serverPrefix(server) + accessId

        private fun serverPrefix(server: String) = "hubiplan:" + server.removePrefix("https://") + ":"
    }
}
