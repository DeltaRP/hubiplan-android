package de.hubiplan.app

import de.hubiplan.app.ui.BLACK
import de.hubiplan.app.ui.WHITE
import de.hubiplan.app.ui.mix
import de.hubiplan.app.ui.parseHexColor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ColorsTest {
    @Test
    fun vereinsfarbe() {
        assertEquals(0xFF1F4E79L, parseHexColor("#1F4E79"))
        assertEquals(0xFF1F4E79L, parseHexColor("1f4e79"))
        assertNull(parseHexColor("#12345"))
        assertNull(parseHexColor("#GGGGGG"))
        assertNull(parseHexColor(null))
    }

    @Test
    fun mischen() {
        assertEquals(BLACK, mix(BLACK, WHITE, 0f))
        assertEquals(WHITE, mix(BLACK, WHITE, 1f))
        assertEquals(0xFF7F7F7FL, mix(BLACK, WHITE, 0.5f))
    }
}
