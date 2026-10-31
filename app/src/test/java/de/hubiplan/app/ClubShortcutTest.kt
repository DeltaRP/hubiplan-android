package de.hubiplan.app

import de.hubiplan.app.data.Account
import de.hubiplan.app.shortcut.ClubShortcut
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ClubShortcutTest {
    private fun account(server: String, device: Int) = Account(server, "hpd_x", device, "a@example.org", "Erika Muster", null)

    @Test
    fun idIstStabilUndOhneKlartext() {
        val a = account("https://verein.example.org", 7)
        assertEquals(ClubShortcut.id(a, 3), ClubShortcut.id(a.copy(token = "hpd_neu"), 3))
        assertFalse(ClubShortcut.id(a, 3).contains("example"))
        assertTrue(ClubShortcut.id(a, 3).startsWith("verein-"))
    }

    @Test
    fun kontenUndZugaengeGetrennt() {
        val a = account("https://verein.example.org", 7)
        val b = account("https://anderer.example.org", 7)
        assertNotEquals(ClubShortcut.id(a, 3), ClubShortcut.id(b, 3))
        assertNotEquals(ClubShortcut.id(a, 3), ClubShortcut.id(a, 4))
        assertEquals(ClubShortcut.accountId(a), ClubShortcut.accountId(a.copy(display = "Neu")))
    }
}
