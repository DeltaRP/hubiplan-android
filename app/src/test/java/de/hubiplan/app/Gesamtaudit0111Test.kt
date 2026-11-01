package de.hubiplan.app

import de.hubiplan.app.data.ReceiptCache
import de.hubiplan.app.photo.PhotoPrep
import de.hubiplan.app.ui.pendingCountText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

class Gesamtaudit0111Test {
    private fun rotateCw(m: List<List<Int>>): List<List<Int>> =
        List(m[0].size) { i -> List(m.size) { j -> m[m.size - 1 - j][i] } }

    private fun mirror(m: List<List<Int>>) = m.map { it.reversed() }

    private fun apply(m: List<List<Int>>, o: PhotoPrep.Orientation): List<List<Int>> {
        var r = m
        repeat(o.degrees / 90) { r = rotateCw(r) }
        return if (o.mirror) mirror(r) else r
    }

    /** GA-F05: Jede der acht EXIF-Ausrichtungen ergibt wieder das richtige Bild. */
    @Test
    fun alle_acht_exif_ausrichtungen() {
        val shown = listOf(listOf(1, 2, 3), listOf(4, 5, 6))
        val transpose = List(3) { i -> List(2) { j -> shown[j][i] } }
        // So liegt das Bild in der Datei, wenn die Kamera die jeweilige Ausrichtung meldet
        val stored = mapOf(
            1 to shown,
            2 to mirror(shown),
            3 to rotateCw(rotateCw(shown)),
            4 to shown.reversed(),
            5 to transpose,
            6 to rotateCw(rotateCw(rotateCw(shown))),
            7 to rotateCw(rotateCw(transpose)),
            8 to rotateCw(shown),
        )
        for ((exif, file) in stored) {
            assertEquals("EXIF $exif", shown, apply(file, PhotoPrep.Orientation.of(exif)))
        }
    }

    /** GA-S06: gleiche Quittungsnummer in zwei Konten ergibt zwei Dateien; Abmelden trifft nur eines. */
    @Test
    fun quittungen_je_konto_und_ausgabe_getrennt() {
        val cache = Files.createTempDirectory("cache").toFile()
        val a = ReceiptCache.write(cache, "https://a.example-1", "Quittung-1.pdf", byteArrayOf(1))
        val b = ReceiptCache.write(cache, "https://b.example-1", "Quittung-1.pdf", byteArrayOf(2))
        val a2 = ReceiptCache.write(cache, "https://a.example-1", "Quittung-1.pdf", byteArrayOf(3))
        assertNotEquals(a, b)
        assertNotEquals(a, a2)
        assertEquals(1.toByte(), a.readBytes()[0])
        ReceiptCache.forget(cache, "https://a.example-1")
        assertFalse(a.exists() || a2.exists())
        assertTrue(b.exists())
        // Nach Ablauf räumt clear auch die leeren Ordner weg
        ReceiptCache.clear(cache, olderThanMillis = 0, now = System.currentTimeMillis() + 1000)
        assertFalse(b.exists())
        assertTrue(ReceiptCache.root(cache).listFiles().isNullOrEmpty())
        cache.deleteRecursively()
    }

    /** GA-S10: Ohne Verwaltungsfreigabe nur die Anzahl, keine Einzelheiten. */
    @Test
    fun offline_nur_anzahl() {
        assertEquals("1 Kassenvorgang wartet auf Versand", pendingCountText(1))
        assertEquals("3 Kassenvorgänge warten auf Versand", pendingCountText(3))
    }
}
