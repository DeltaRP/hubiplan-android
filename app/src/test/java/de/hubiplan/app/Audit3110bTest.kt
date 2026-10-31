package de.hubiplan.app

import de.hubiplan.app.data.Account
import de.hubiplan.app.sync.RsvpActionReceiver
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/** Gesamtaudit 31.10.b, C-F02: Rückmeldungen gehören zu einem Konto, nicht zu einem Platz. */
class Audit3110bTest {
    private fun account(server: String, device: Int) =
        Account(server = server, token = "hpd_erfunden", deviceId = device, email = "a@example.org", display = "A", selectedAccess = null)

    @Test
    fun kontokennungUnterscheidetServerUndGeraet() {
        val a = RsvpActionReceiver.accountId(account("https://verein.example", 1))
        assertEquals(a, RsvpActionReceiver.accountId(account("https://verein.example", 1)))
        assertNotEquals(a, RsvpActionReceiver.accountId(account("https://verein.example", 2)))
        assertNotEquals(a, RsvpActionReceiver.accountId(account("https://verein-example", 1)))
        assertEquals("konto-$a", RsvpActionReceiver.tag(a))
    }
}
