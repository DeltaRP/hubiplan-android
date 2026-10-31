package de.hubiplan.app

import de.hubiplan.app.ui.BLACK
import de.hubiplan.app.ui.DARK_SURFACE
import de.hubiplan.app.ui.LIGHT_SURFACE
import de.hubiplan.app.ui.WHITE
import de.hubiplan.app.ui.contrast
import de.hubiplan.app.ui.germanDate
import de.hubiplan.app.ui.mix
import de.hubiplan.app.ui.parseHexColor
import de.hubiplan.app.ui.readableOn
import de.hubiplan.app.ui.withContrast
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Barrierefreiheit A-03: Vereinsfarben behalten in der App genug Kontrast. */
class KontrastTest {
    private val vereinsfarben = listOf("#ffd700", "#e0a526", "#7fb3d5", "#4caf50", "#00a0e0", "#1f4e79", "#560000")

    @Test
    fun kontrastWieWcag() {
        assertEquals(21.0, contrast(BLACK, WHITE), 0.01)
        assertEquals(1.0, contrast(WHITE, WHITE), 0.001)
    }

    @Test
    fun hellesSchemaDunkeltAb() {
        for (hex in vereinsfarben) {
            val c = withContrast(parseHexColor(hex)!!, listOf(WHITE, LIGHT_SURFACE), BLACK)
            assertTrue("$hex auf Weiß", contrast(c, WHITE) >= 4.5)
            assertTrue("$hex auf Fläche", contrast(c, LIGHT_SURFACE) >= 4.5)
            assertTrue("Schrift auf $hex", contrast(readableOn(c), c) >= 4.5)
        }
    }

    @Test
    fun dunklesSchemaHelltAuf() {
        for (hex in vereinsfarben) {
            val c = withContrast(mix(parseHexColor(hex)!!, WHITE, 0.45f), listOf(DARK_SURFACE), WHITE)
            assertTrue("$hex im Dunkeln", contrast(c, DARK_SURFACE) >= 4.5)
        }
    }

    @Test
    fun dunkleFarbeBleibtUnveraendert() {
        val c = parseHexColor("#1f4e79")!!
        assertEquals(c, withContrast(c, listOf(WHITE, LIGHT_SURFACE), BLACK))
    }

    @Test
    fun datumAusKalender() {
        assertEquals("05.03.2027", germanDate(2027, 3, 5))
    }
}
