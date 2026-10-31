package de.hubiplan.app.calendar

import de.hubiplan.app.data.Occurrence
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.ZoneOffset

/** Ein Termin, so wie er in den Gerätekalender geschrieben wird. */
data class CalendarEntry(
    val key: String,
    val eventId: Int,
    val title: String,
    val startMillis: Long,
    val endMillis: Long,
    val allDay: Boolean,
    val timeZone: String,
    val location: String,
    val description: String,
    val cancelled: Boolean,
    val reminderMinutes: Int?,
) {
    /** Kurzform aller Werte: nur geänderte Termine werden neu geschrieben. */
    val fingerprint: String
        get() = listOf(title, startMillis, endMillis, allDay, timeZone, location, description, cancelled, reminderMinutes)
            .joinToString("\u001f")
            .hashCode()
            .toString(16)
}

/**
 * Termin aus HUBIplan → Kalendereintrag. Ganztägige Termine verlangt Android in UTC von
 * Mitternacht bis Mitternacht des Folgetags; HUBIplan liefert als Ende den letzten Tag 0:00.
 */
fun Occurrence.toCalendarEntry(unitTitle: String, reminderMinutes: Int?, zone: ZoneId = ZoneId.systemDefault()): CalendarEntry {
    val start = OffsetDateTime.parse(startsAt)
    val end = OffsetDateTime.parse(endsAt)
    val startMillis: Long
    val endMillis: Long
    val tz: String
    if (allDay) {
        val first: LocalDate = start.atZoneSameInstant(zone).toLocalDate()
        val last: LocalDate = maxOf(end.atZoneSameInstant(zone).toLocalDate(), first)
        startMillis = first.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
        endMillis = last.plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
        tz = "UTC"
    } else {
        startMillis = start.toInstant().toEpochMilli()
        endMillis = maxOf(end.toInstant().toEpochMilli(), startMillis)
        tz = zone.id
    }
    val prefix = when {
        isCancelled -> "Abgesagt: "
        myResponse == "yes" -> "✓ "
        myResponse == "no" -> "✗ "
        else -> ""
    }
    val lines = buildList {
        if (owner.isNotBlank()) add(owner)
        if (note.isNotBlank()) add(note)
        if (rsvpEnabled) {
            add(
                when (myResponse) {
                    "yes" -> "Du hast zugesagt."
                    "no" -> "Du hast abgesagt."
                    else -> "Rückmeldung offen – Zu- oder Absage in der HUBIplan-App."
                },
            )
        }
        add("Aus HUBIplan ($unitTitle)")
    }
    return CalendarEntry(
        key = key,
        eventId = eventId,
        title = prefix + title,
        startMillis = startMillis,
        endMillis = endMillis,
        allDay = allDay,
        timeZone = tz,
        location = listOf(location, address).filter { it.isNotBlank() }.joinToString(", "),
        description = lines.joinToString("\n"),
        cancelled = isCancelled,
        // Abgesagte und abgesagte eigene Termine ohne Erinnerung
        reminderMinutes = if (isCancelled || myResponse == "no") null else reminderMinutes,
    )
}

/** Abgleich: was neu, geändert oder weg ist (vorhanden = Schlüssel → Fingerabdruck). */
data class SyncPlan(val insert: List<CalendarEntry>, val update: List<CalendarEntry>, val delete: List<String>)

fun planSync(wanted: List<CalendarEntry>, existing: Map<String, String>): SyncPlan {
    val byKey = wanted.associateBy { it.key }
    val insert = wanted.filter { it.key !in existing }
    val update = wanted.filter { it.key in existing && existing[it.key] != it.fingerprint }
    val delete = existing.keys.filter { it !in byKey }
    return SyncPlan(insert, update, delete)
}

/** Termine, für die ein Hinweis „Rückmeldung fehlt“ fällig ist (Frist in den nächsten [hours] Stunden). */
fun Occurrence.needsRsvpReminder(now: OffsetDateTime, hours: Long = 30): Boolean {
    if (!rsvpEnabled || myResponse != null || isCancelled) return false
    val deadline = rsvpDeadline?.let { runCatching { OffsetDateTime.parse(it) }.getOrNull() } ?: return false
    return deadline.isAfter(now) && deadline.isBefore(now.plusHours(hours))
}
