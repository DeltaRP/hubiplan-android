package de.hubiplan.app.ui

import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import de.hubiplan.app.Texte
import de.hubiplan.app.tr

// Formate je Sprache der App (Englisch britisch, Konzept F4); als Getter, damit ein
// Sprachwechsel sofort gilt
private val english: Boolean get() = Texte.sprache == "en"
private val DAY: DateTimeFormatter
    get() = if (english) {
        DateTimeFormatter.ofPattern("EEE d MMMM yyyy", Locale.UK)
    } else {
        DateTimeFormatter.ofPattern("EE, d. MMMM yyyy", Locale.GERMAN)
    }
private val SHORT_DAY: DateTimeFormatter
    get() = if (english) DateTimeFormatter.ofPattern("EEE d/M", Locale.UK) else DateTimeFormatter.ofPattern("EE, d.M.", Locale.GERMAN)
private val TIME = DateTimeFormatter.ofPattern("HH:mm", Locale.GERMAN)
private val UHR: String get() = if (english) "" else " Uhr"

private fun local(iso: String, zone: ZoneId): java.time.ZonedDateTime =
    OffsetDateTime.parse(iso).atZoneSameInstant(zone)

/** „Fr, 14. November 2026, 19:00 – 22:00 Uhr“ bzw. ganztägig nur Datum. */
fun formatWhen(start: String, end: String, allDay: Boolean, zone: ZoneId = ZoneId.systemDefault()): String {
    val s = local(start, zone)
    val e = local(end, zone)
    if (allDay) {
        // Ganztägig: Ende ist der letzte Tag 0:00
        return if (s.toLocalDate() == e.toLocalDate()) s.format(DAY) else "${s.format(SHORT_DAY)} – ${e.format(DAY)}"
    }
    return if (s.toLocalDate() == e.toLocalDate()) {
        "${s.format(DAY)}, ${s.format(TIME)} – ${e.format(TIME)}$UHR"
    } else {
        "${s.format(SHORT_DAY)} ${s.format(TIME)} – ${e.format(SHORT_DAY)} ${e.format(TIME)}$UHR"
    }
}

fun formatDate(iso: String?, zone: ZoneId = ZoneId.systemDefault()): String =
    iso?.let { runCatching { local(it, zone).format(DAY) }.getOrNull() } ?: ""

fun recipientTotal(counts: Map<String, Int>): Int =
    listOf("app", "mail", "portal", "letter").sumOf { counts[it] ?: 0 }

/** Empfängerzahl in Worten: „43 Mitglieder, davon 31 per App, 12 per E-Mail“. */
fun recipientSummary(counts: Map<String, Int>): String {
    val app = counts["app"] ?: 0
    val mail = counts["mail"] ?: 0
    val portal = counts["portal"] ?: 0
    val total = recipientTotal(counts)
    val parts = buildList {
        if (app > 0) add(tr("{app} per App", "app" to app))
        if (mail > 0) add(tr("{mail} per E-Mail", "mail" to mail))
        if (portal > 0) add(tr("{portal} nur im Mitgliederbereich", "portal" to portal))
    }
    val who = if (total == 1) tr("1 Mitglied") else "$total Mitglieder"
    return if (parts.isEmpty()) tr("geht an niemanden") else tr("geht an {who}, davon {parts}", "who" to who, "parts" to parts.joinToString(", "))
}

/** Nur geänderte Felder melden (Leerzeichen am Rand zählen nicht). */
fun changedFields(before: Map<String, String>, after: Map<String, String>): Map<String, String> =
    after.mapValues { it.value.trim() }.filter { (k, v) -> v != (before[k] ?: "").trim() }

/** Datum ohne Uhrzeit („2020-05-01“) als „01.05.2020“. */
fun formatPlainDate(date: String?): String =
    date?.let { runCatching { java.time.LocalDate.parse(it.take(10)).format(java.time.format.DateTimeFormatter.ofPattern(if (english) "dd/MM/yyyy" else "dd.MM.yyyy")) }.getOrNull() } ?: ""
