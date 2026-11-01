package de.hubiplan.app

import de.hubiplan.app.data.MyGrant
import de.hubiplan.app.data.MyPlacing
import de.hubiplan.app.data.MyResults
import de.hubiplan.app.data.json
import de.hubiplan.app.ui.grantLine
import de.hubiplan.app.ui.placingLine
import org.junit.Assert.assertEquals
import org.junit.Test

/** „Meine Ergebnisse“ (Wettbewerbe Schritt 5); alle Daten erfunden. */
class MeineErgebnisseTest {
    @Test
    fun platzUndWert() {
        assertEquals("Einzel: 2. Platz von 14 · 28 Ringe", placingLine(MyPlacing("Einzel", 2, "28 Ringe", 14)))
        assertEquals("Einzel: 1. Platz", placingLine(MyPlacing("Einzel", 1, "", null)))
        assertEquals("Einzel: ohne Wertung", placingLine(MyPlacing("Einzel", null, "", null)))
    }

    @Test
    fun titelMitJahr() {
        assertEquals("Vereinsmeister 2027", grantLine(MyGrant(1, 2027, fullTitle = "Vereinsmeister 2027")))
        assertEquals("Zugpokal 2026", grantLine(MyGrant(2, 2026, trophyName = "Zugpokal")))
    }

    @Test
    fun antwortLesen() {
        val text = """
            {"starts":[{"competition":3,"title":"Vereinsmeisterschaft 2027","date":"2027-06-14",
              "status":"endgueltig","status_label":"Endgültig","own":true,"unit_name":"Zug 1",
              "results":[{"scoring":"Einzel","place":1,"value_text":"30","field":4}]}],
             "grants":[{"id":7,"year":2027,"title":"Vereinsmeister","full_title":"Vereinsmeister 2027",
              "scoring_name":"Einzel","place":1,"competition_title":"Vereinsmeisterschaft 2027"}]}
        """.trimIndent()
        val r = json.decodeFromString<MyResults>(text)
        assertEquals("Zug 1", r.starts[0].unitName)
        assertEquals(1, r.starts[0].results[0].place)
        assertEquals("Vereinsmeister 2027", r.grants[0].fullTitle)
    }
}
