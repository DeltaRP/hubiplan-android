package de.hubiplan.app

import de.hubiplan.app.data.errorMessage
import de.hubiplan.app.ui.formatWhen
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.ZoneId

class FormatTest {
    private val berlin = ZoneId.of("Europe/Berlin")

    @Test
    fun terminAmAbend() {
        assertEquals(
            "Fr., 13. November 2026, 19:00 – 22:00 Uhr",
            formatWhen("2026-11-13T19:00:00+01:00", "2026-11-13T22:00:00+01:00", false, berlin),
        )
    }

    @Test
    fun ganztaegig() {
        assertEquals(
            "Sa., 14. November 2026",
            formatWhen("2026-11-14T00:00:00+01:00", "2026-11-14T00:00:00+01:00", true, berlin),
        )
    }

    @Test
    fun fehlermeldungen() {
        assertEquals("Der Code ist ungültig.", errorMessage(404, """{"detail":"Der Code ist ungültig."}"""))
        assertEquals("Dieses Feld darf nicht leer sein.", errorMessage(400, """{"name":["Dieses Feld darf nicht leer sein."]}"""))
        assertEquals("Zu viele Versuche. Bitte später erneut.", errorMessage(429, ""))
        assertEquals(
            "Falsches Passwort.",
            errorMessage(400, """{"status":400,"errors":[{"message":"Falsches Passwort.","code":"x"}]}"""),
        )
    }

    @Test
    fun empfaengerzahl_in_worten() {
        assertEquals(
            "geht an 43 Mitglieder, davon 31 per App, 12 per E-Mail",
            de.hubiplan.app.ui.recipientSummary(mapOf("total" to 45, "app" to 31, "mail" to 12, "letter" to 0, "opted_out" to 2)),
        )
        assertEquals("geht an 1 Mitglied, davon 1 nur im Mitgliederbereich", de.hubiplan.app.ui.recipientSummary(mapOf("portal" to 1)))
        assertEquals("geht an niemanden", de.hubiplan.app.ui.recipientSummary(emptyMap()))
    }
}
