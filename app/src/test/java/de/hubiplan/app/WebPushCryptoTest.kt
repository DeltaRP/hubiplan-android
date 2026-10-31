package de.hubiplan.app

import de.hubiplan.app.push.PushDecryptException
import de.hubiplan.app.push.PushKeys
import de.hubiplan.app.push.WebPushCrypto
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertThrows
import org.junit.Test

class WebPushCryptoTest {
    // Erzeugt mit dem Backend (hubiplan devices/webpush.py) mit festem Schlüssel und Salz
    private val keys =
        "MIGHAgEAMBMGByqGSM49AgEGCCqGSM49AwEHBG0wawIBAQQgEjRWeJCrze8SNFZ4kKvN7xI0VniQq83vEjRWeJCrze-hRANCAARHHD51jEkEKFu6flMRjtD1JK3rB1fSW9L457DXbfpxTN1SD3rKiouResw39R3o8Mm7462Fg4LnAtwloS0J96hY." +
            "BEccPnWMSQQoW7p-UxGO0PUkresHV9Jb0vjnsNdt-nFM3VIPesqKi5F6zDf1HejwybvjrYWDgucC3CWhLQn3qFg." +
            "AAECAwQFBgcICQoLDA0ODw"
    private val body =
        "ZGVmZ2hpamtsbW5vcHFycwAAEABBBOCylUStPA_diaF2Pd-mMs58Gx6nYaDlC1G3a_rvVxeeUV3m1tR0QIqrhT6gLlCDlW3k0Oqotizgb-ul4UOoU5NCAlEYiVuMVs9phhYNNenEule1N6YXgV1oCKR2xrRdiJ-XZKA7jF7ohef7Dvasme1jobyby3vme5EYzDwqS2CadfdjWKnEy2UwkMJk3qwZKU32gusX1CWgHfenri6-4xvBrviaLgeTXqIoHnz9"

    @Test
    fun entschluesselt_nachricht_des_backends() {
        val k = PushKeys.parse(keys)
        assertNotNull(k)
        val plain = String(WebPushCrypto.decrypt(WebPushCrypto.unb64(body), k!!))
        assertEquals("""{"v":1,"t":"circular","title":"Antreten","text":"Heute 18 Uhr am Zelt – bitte pünktlich"}""", plain)
    }

    @Test
    fun falscher_schluessel_scheitert() {
        val other = PushKeys.generate()
        assertThrows(PushDecryptException::class.java) {
            WebPushCrypto.decrypt(WebPushCrypto.unb64(body), other)
        }
    }

    @Test
    fun schluessel_speichern_und_laden() {
        val k = PushKeys.generate()
        val again = PushKeys.parse(k.serialize())!!
        assertEquals(k.p256dh, again.p256dh)
        assertEquals(k.authB64, again.authB64)
        assertEquals(65, WebPushCrypto.unb64(k.p256dh).size)
        assertEquals(16, WebPushCrypto.unb64(k.authB64).size)
    }

    @Test
    fun push_nachricht_lesen() {
        val m = de.hubiplan.app.push.PushMessage.parse("""{"v":1,"t":"circular","access":3,"id":9,"kind":"short","title":"T","text":"x","from":"Zug 1"}""")!!
        assertEquals(3, m.access)
        assertEquals(9, m.id)
        assertEquals("circular", m.type)
        assertEquals("Zug 1", m.from)
        assertEquals("sync", de.hubiplan.app.push.PushMessage.parse("""{"v":1,"t":"sync"}""")!!.type)
        assertEquals(null, de.hubiplan.app.push.PushMessage.parse("kein json"))
    }
}
