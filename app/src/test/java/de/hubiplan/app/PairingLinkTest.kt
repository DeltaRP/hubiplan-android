package de.hubiplan.app

import de.hubiplan.app.data.PairingLink
import de.hubiplan.app.data.normalizeServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PairingLinkTest {
    @Test
    fun qrCodeAusMeinKonto() {
        val link = PairingLink.parse("https://verein.example.de/app/verbinden#code=abc-123_X")
        assertEquals(PairingLink("https://verein.example.de", "abc-123_X"), link)
    }

    @Test
    fun mitPort() {
        assertEquals("https://test.example:8443", PairingLink.parse("https://test.example:8443/app/verbinden#code=x")?.server)
    }

    @Test
    fun fremdeCodesAblehnen() {
        assertNull(PairingLink.parse("http://verein.example.de/app/verbinden#code=abc"))
        assertNull(PairingLink.parse("https://verein.example.de/anderes#code=abc"))
        assertNull(PairingLink.parse("https://verein.example.de/app/verbinden"))
        assertNull(PairingLink.parse("https://verein.example.de/app/verbinden#code="))
        assertNull(PairingLink.parse("WIFI:S:netz;T:WPA;P:geheim;;"))
    }

    @Test
    fun serveradresse() {
        assertEquals("https://verein.example.de", normalizeServer(" verein.example.de/ "))
        assertEquals("https://verein.example.de", normalizeServer("https://verein.example.de/mein-konto"))
        assertNull(normalizeServer("http://verein.example.de"))
        assertNull(normalizeServer(""))
    }
}
