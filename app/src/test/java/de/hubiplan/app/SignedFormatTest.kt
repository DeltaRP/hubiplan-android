package de.hubiplan.app

import de.hubiplan.app.data.DeviceTokens
import de.hubiplan.app.data.SignedFormat
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigInteger
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec
import java.security.spec.ECPoint
import java.security.spec.ECPublicKeySpec
import java.util.Base64

/** Härtung H7: Formate wie im Server (`devices/devicekey.py`), erfundene Werte. */
class SignedFormatTest {
    @Test
    fun vorstandsanfrageWieDerServer() {
        val message = SignedFormat.adminMessage("post", "/api/app/v1/admin/u/5/members/?q=M%C3%BCller", "1700000000", """{"a":1}""".toByteArray())
        assertEquals(
            "hubiplan-admin\nPOST\n/api/app/v1/admin/u/5/members/?q=M%C3%BCller\n1700000000\n" +
                "015abd7f5cc57a2dd94b7590f04ad8084273905ee33ec5cebeae62276a97f862",
            String(message),
        )
        val empty = SignedFormat.adminMessage("GET", "/x/", "1", ByteArray(0))
        assertTrue(String(empty).endsWith("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"))
    }

    @Test
    fun format2MitKennungUndIdempotencyKey() {
        // Gesamtaudit 30.10. (BSEC-02): wie devices/devicekey.py admin_message mit nonce
        val message = SignedFormat.adminMessage("post", "/x/", "1700000000", ByteArray(0), "abcdefghijklmnopqrstuv", "k-1")
        assertEquals(
            "hubiplan-admin-2\nPOST\n/x/\n1700000000\nabcdefghijklmnopqrstuv\nk-1\n" +
                "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
            String(message),
        )
        val nonce = SignedFormat.newNonce()
        assertEquals(22, nonce.length)
        assertTrue(nonce.all { it.isLetterOrDigit() || it == '-' || it == '_' })
    }

    @Test
    fun erneuerung() {
        assertEquals("hubiplan-renew\n12\nabc", String(SignedFormat.renewMessage(12, "abc")))
    }

    @Test
    fun punktMitFesterLaenge() {
        val point = SignedFormat.uncompressedPoint(BigInteger.ONE, BigInteger("ff".repeat(32), 16))
        assertEquals(65, point.size)
        assertEquals(4.toByte(), point[0])
        assertEquals(1.toByte(), point[32])
        assertEquals(0xff.toByte(), point[33])
    }

    @Test
    fun unterschriftPasstZumPunkt() {
        val gen = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }
        val pair = gen.generateKeyPair()
        val pub = pair.public as ECPublicKey
        val encoded = SignedFormat.b64(SignedFormat.uncompressedPoint(pub.w.affineX, pub.w.affineY))
        assertTrue(!encoded.contains("="))
        // Aus dem Punkt wieder einen Schlüssel bauen (wie der Server) und prüfen
        val raw = Base64.getUrlDecoder().decode(encoded)
        val x = BigInteger(1, raw.copyOfRange(1, 33))
        val y = BigInteger(1, raw.copyOfRange(33, 65))
        val rebuilt = KeyFactory.getInstance("EC").generatePublic(ECPublicKeySpec(ECPoint(x, y), pub.params))
        val message = SignedFormat.renewMessage(3, "aufgabe")
        val sig = Signature.getInstance("SHA256withECDSA").apply { initSign(pair.private); update(message) }.sign()
        val check = Signature.getInstance("SHA256withECDSA").apply { initVerify(rebuilt); update(message) }
        assertTrue(check.verify(sig))
    }

    @Test
    fun gleichzeitigeErneuerungNurEinmal() = runBlocking {
        var calls = 0
        DeviceTokens.renewer = { _, token -> calls++; "${token}x" }
        val first = DeviceTokens.renew("https://verein.example", "hpd_a")
        // Zweiter Aufruf mit demselben (alten) Schlüssel: kein zweites Erneuern
        val second = DeviceTokens.renew("https://verein.example", "hpd_a")
        assertEquals("hpd_ax", first)
        assertEquals("hpd_ax", second)
        assertEquals(1, calls)
        assertEquals("hpd_ax", DeviceTokens.current("hpd_a"))
        DeviceTokens.renewer = null
    }
}
