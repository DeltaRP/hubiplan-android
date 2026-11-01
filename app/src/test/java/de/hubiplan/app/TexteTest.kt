package de.hubiplan.app

import de.hubiplan.app.ui.formatPlainDate
import de.hubiplan.app.ui.formatWhen
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.time.ZoneId

/** Sprache der App (hubiplan/konzepte/englisch.md, 7): Katalog vollständig, Umschalten wirkt. */
class TexteTest {
    private val katalog = Texte.parse(File("src/main/assets/texte/en.json").readText())

    @After
    fun deutsch() = Texte.setzeFuerTest("de", katalog)

    /** Alle Texte aus tr(…) und trn(…) im Quelltext (Escapes wie zur Laufzeit aufgelöst). */
    private fun schluessel(): Set<String> {
        val lit = "\"((?:[^\"\\\\]|\\\\.)*)\""
        val tr = Regex("""\btr\(\s*$lit""")
        val trn = Regex("""\btrn\(\s*$lit\s*,\s*$lit""")
        val out = mutableSetOf<String>()
        File("src").walkTopDown().filter { it.extension == "kt" && it.name != "Texte.kt" && "/test/" !in it.invariantSeparatorsPath }
            .forEach { f ->
                val s = f.readText()
                tr.findAll(s).forEach { out += unescape(it.groupValues[1]) }
                trn.findAll(s).forEach { out += unescape(it.groupValues[1]); out += unescape(it.groupValues[2]) }
            }
        return out
    }

    private fun unescape(s: String): String = buildString {
        var i = 0
        while (i < s.length) {
            if (s[i] == '\\' && i + 1 < s.length) {
                when (val c = s[i + 1]) {
                    'n' -> append('\n')
                    't' -> append('\t')
                    'u' -> { append(s.substring(i + 2, i + 6).toInt(16).toChar()); i += 6; continue }
                    else -> append(c)
                }
                i += 2
            } else {
                append(s[i]); i++
            }
        }
    }

    private fun platzhalter(s: String) = Regex("\\{\\w+\\}").findAll(s).map { it.value }.sorted().toList()

    @Test
    fun jeder_text_hat_englisch() {
        val keys = schluessel()
        assertTrue("zu wenige Texte gefunden: ${keys.size}", keys.size > 400)
        val fehlen = keys.filter { it !in katalog }
        assertEquals("ohne Englisch: $fehlen", emptyList<String>(), fehlen)
    }

    @Test
    fun platzhalter_gleich() {
        val falsch = katalog.filter { (de, en) -> platzhalter(de) != platzhalter(en) }
        assertEquals(emptyMap<String, String>(), falsch)
    }

    @Test
    fun umschalten() {
        assertEquals("Erneut laden", tr("Erneut laden"))
        assertEquals("Mitglied seit 2020", tr("Mitglied seit {x1}", "x1" to 2020))
        Texte.setzeFuerTest("en", katalog)
        assertEquals("Member since 2020", tr("Mitglied seit {x1}", "x1" to 2020))
        assertEquals("3 receipts completed", trn("1 Quittung abgeschlossen", "{n} Quittungen abgeschlossen", 3))
        // Unbekanntes bleibt deutsch statt leer
        assertEquals("Gibt es nicht", tr("Gibt es nicht"))
    }

    @Test
    fun formate_englisch() {
        val berlin = ZoneId.of("Europe/Berlin")
        Texte.setzeFuerTest("en", katalog)
        assertEquals(
            "Fri 13 November 2026, 19:00 – 22:00",
            formatWhen("2026-11-13T19:00:00+01:00", "2026-11-13T22:00:00+01:00", false, berlin),
        )
        assertEquals("01/05/2020", formatPlainDate("2020-05-01"))
        Texte.setzeFuerTest("de", katalog)
        assertEquals("01.05.2020", formatPlainDate("2020-05-01"))
    }

    @Test
    fun sprache_normalisiert() {
        assertEquals("en", Texte.normalize("en-GB"))
        assertEquals("de", Texte.normalize("fr"))
        assertEquals("de", Texte.normalize(null))
    }
}
