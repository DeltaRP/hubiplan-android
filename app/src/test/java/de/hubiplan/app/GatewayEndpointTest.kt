package de.hubiplan.app

import de.hubiplan.app.push.GatewayEndpoint
import org.junit.Assert.assertEquals
import org.junit.Test

class GatewayEndpointTest {
    // Erfundener Schlüssel: 65 Byte 0x04, Kennung wie im Gateway (sha256(raw).hex[:16])
    private val key = "BAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQ"

    @Test
    fun mitKennung() {
        assertEquals(
            "https://push.example/fcm/tok/" + GatewayEndpoint.keyId(key),
            GatewayEndpoint.of("https://push.example/", "tok", key),
        )
        assertEquals(16, GatewayEndpoint.keyId(key)!!.length)
    }

    @Test
    fun ohneSchluessel() {
        assertEquals("https://push.example/fcm/tok", GatewayEndpoint.of("https://push.example", "tok", null))
    }
}
