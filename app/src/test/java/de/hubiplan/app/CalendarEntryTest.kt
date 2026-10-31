package de.hubiplan.app

import de.hubiplan.app.calendar.needsRsvpReminder
import de.hubiplan.app.calendar.planSync
import de.hubiplan.app.calendar.toCalendarEntry
import de.hubiplan.app.data.Occurrence
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.OffsetDateTime
import java.time.ZoneId

class CalendarEntryTest {
    private val berlin = ZoneId.of("Europe/Berlin")

    private fun occ(
        key: String = "12",
        allDay: Boolean = false,
        start: String = "2026-11-13T19:00:00+01:00",
        end: String = "2026-11-13T22:00:00+01:00",
        response: String? = null,
        cancelled: Boolean = false,
        deadline: String? = null,
    ) = Occurrence(
        key = key, eventId = 12, title = "Generalversammlung", startsAt = start, endsAt = end, allDay = allDay,
        location = "Vereinsheim", address = "Musterweg 1, 41468 Neuss", owner = "Testverein", rsvpEnabled = true,
        myResponse = response, isCancelled = cancelled, rsvpDeadline = deadline,
    )

    @Test
    fun terminMitUhrzeit() {
        val e = occ().toCalendarEntry("Testverein", 1440, berlin)
        assertEquals(OffsetDateTime.parse("2026-11-13T18:00:00Z").toInstant().toEpochMilli(), e.startMillis)
        assertEquals("Europe/Berlin", e.timeZone)
        assertEquals("Vereinsheim, Musterweg 1, 41468 Neuss", e.location)
        assertEquals(1440, e.reminderMinutes)
        assertTrue(e.description.contains("Rückmeldung offen"))
    }

    @Test
    fun ganztaegigInUtc() {
        // HUBIplan: Ende = letzter Tag 0:00; Android: Mitternacht des Folgetags in UTC
        val e = occ(allDay = true, start = "2026-07-11T00:00:00+02:00", end = "2026-07-13T00:00:00+02:00")
            .toCalendarEntry("Testverein", null, berlin)
        assertEquals(OffsetDateTime.parse("2026-07-11T00:00:00Z").toInstant().toEpochMilli(), e.startMillis)
        assertEquals(OffsetDateTime.parse("2026-07-14T00:00:00Z").toInstant().toEpochMilli(), e.endMillis)
        assertEquals("UTC", e.timeZone)
    }

    @Test
    fun rueckmeldungImTitel() {
        assertEquals("✓ Generalversammlung", occ(response = "yes").toCalendarEntry("V", 60, berlin).title)
        val no = occ(response = "no").toCalendarEntry("V", 60, berlin)
        assertEquals("✗ Generalversammlung", no.title)
        assertNull(no.reminderMinutes)
        val cancelled = occ(cancelled = true).toCalendarEntry("V", 60, berlin)
        assertEquals("Abgesagt: Generalversammlung", cancelled.title)
        assertTrue(cancelled.cancelled)
    }

    @Test
    fun abgleichsplan() {
        val a = occ(key = "a").toCalendarEntry("V", 60, berlin)
        val b = occ(key = "b").toCalendarEntry("V", 60, berlin)
        val bChanged = occ(key = "b", response = "yes").toCalendarEntry("V", 60, berlin)
        val plan = planSync(listOf(a, bChanged), mapOf("b" to b.fingerprint, "alt" to "x"))
        assertEquals(listOf("a"), plan.insert.map { it.key })
        assertEquals(listOf("b"), plan.update.map { it.key })
        assertEquals(listOf("alt"), plan.delete)
        assertTrue(planSync(listOf(a), mapOf("a" to a.fingerprint)).update.isEmpty())
    }

    @Test
    fun hinweisRueckmeldungFehlt() {
        val now = OffsetDateTime.parse("2026-11-10T12:00:00+01:00")
        assertTrue(occ(deadline = "2026-11-11T12:00:00+01:00").needsRsvpReminder(now))
        assertFalse(occ(deadline = "2026-11-13T12:00:00+01:00").needsRsvpReminder(now))
        assertFalse(occ(deadline = "2026-11-09T12:00:00+01:00").needsRsvpReminder(now))
        assertFalse(occ(deadline = "2026-11-11T12:00:00+01:00", response = "yes").needsRsvpReminder(now))
        assertFalse(occ(deadline = null).needsRsvpReminder(now))
    }
}
