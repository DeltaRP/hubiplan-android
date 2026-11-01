package de.hubiplan.app

import android.app.LocaleManager
import android.content.Context
import android.os.Build
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Sprache der App (hubiplan/konzepte/englisch.md, 7). Deutsch ist Quelle und Schlüssel wie im
 * Browser: `tr("Erneut laden")` liefert auf Englisch den Text aus `assets/texte/en.json`, sonst den
 * deutschen. Platzhalter in geschweiften Klammern: `tr("Hallo {name}", "name" to n)`.
 *
 * Welche Sprache gilt: eine in den Android-Einstellungen gewählte App-Sprache, sonst die Sprache
 * des Kontos (beim Abgleich gelesen und gemerkt), sonst Deutsch.
 */
object Texte {
    const val DEUTSCH = "de"
    val SPRACHEN = listOf("de", "en")

    /** Compose liest den Wert mit; ein Sprachwechsel zeichnet die Oberfläche neu. */
    var sprache by mutableStateOf(DEUTSCH)
        private set

    private var englisch: Map<String, String> = emptyMap()
    private const val PREFS = "texte"
    private const val KEY = "sprache_konto"

    /** Beim Start der App: Katalog laden und Sprache bestimmen. */
    fun init(context: Context) {
        if (englisch.isEmpty()) {
            englisch = runCatching {
                context.assets.open("texte/en.json").bufferedReader().use { parse(it.readText()) }
            }.getOrDefault(emptyMap())
        }
        val konto = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, null)
        sprache = systemSprache(context) ?: normalize(konto)
    }

    /** Sprache des Kontos übernehmen (beim Abgleich oder nach Änderung im Profil). */
    fun kontoSprache(context: Context, code: String?) {
        val lang = normalize(code)
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, lang).apply()
        sprache = systemSprache(context) ?: lang
    }

    /** In den Android-Einstellungen gewählte App-Sprache (ab Android 13), sonst null. */
    fun systemSprache(context: Context): String? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return null
        val locales = context.getSystemService(LocaleManager::class.java)?.applicationLocales
        if (locales == null || locales.isEmpty) return null
        return locales[0].language.takeIf { it in SPRACHEN }
    }

    fun normalize(code: String?): String {
        val lang = code?.trim()?.lowercase()?.take(2)
        return if (lang != null && lang in SPRACHEN) lang else DEUTSCH
    }

    fun text(de: String): String = if (sprache == DEUTSCH) de else englisch[de] ?: de

    /** Für Tests: Sprache und Katalog direkt setzen. */
    fun setzeFuerTest(lang: String, katalog: Map<String, String> = englisch) {
        englisch = katalog
        sprache = normalize(lang)
    }

    fun parse(json: String): Map<String, String> =
        (Json.parseToJsonElement(json) as JsonObject).mapValues { it.value.jsonPrimitive.content }
}

/** Text in der Sprache der App; Platzhalter `{name}` werden ersetzt. */
fun tr(de: String, vararg args: Pair<String, Any?>): String {
    var s = Texte.text(de)
    for ((key, value) in args) s = s.replace("{$key}", value?.toString() ?: "")
    return s
}

/** Mehrzahl: `trn("{n} Mitglied", "{n} Mitglieder", n)`. */
fun trn(one: String, other: String, n: Int, vararg args: Pair<String, Any?>): String =
    tr(if (n == 1) one else other, "n" to n, *args)
