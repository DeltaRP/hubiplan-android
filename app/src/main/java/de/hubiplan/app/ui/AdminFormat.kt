package de.hubiplan.app.ui

import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.ResolverStyle

private val GERMAN_DATE = DateTimeFormatter.ofPattern("d.M.uuuu").withResolverStyle(ResolverStyle.STRICT)

/** „31.12.2026“ (auch „1.2.2026“) → „2026-12-31“; ungültig → null. */
fun parseGermanDate(text: String): String? {
    val t = text.trim()
    if (t.isEmpty()) return null
    return runCatching { LocalDate.parse(t, GERMAN_DATE).toString() }.getOrNull()
        ?: runCatching { LocalDate.parse(t).toString() }.getOrNull()
}

/** Felder einer Änderungsmeldung bzw. der Stammdaten in Worten. */
val FIELD_LABELS = mapOf(
    "first_name" to "Vorname",
    "last_name" to "Nachname",
    "birth_date" to "Geburtsdatum",
    "gender" to "Geschlecht",
    "club_number" to "Mitgliedsnummer",
    "email" to "E-Mail",
    "phone" to "Telefon",
    "street" to "Straße",
    "postal_code" to "PLZ",
    "city" to "Ort",
    "country" to "Land",
)

val GENDERS = listOf("" to "keine Angabe", "männlich" to "männlich", "weiblich" to "weiblich", "divers" to "divers")

fun genderLabel(value: String): String = GENDERS.firstOrNull { it.first == value }?.second ?: value

/**
 * Geänderte Stammdaten für PATCH: Text wie eingegeben (am Rand gekürzt), Geburtsdatum als ISO.
 * Gibt null zurück, wenn ein Datum ungültig ist.
 */
fun masterDataChanges(before: Map<String, String>, after: Map<String, String>): Map<String, String>? {
    val out = mutableMapOf<String, String>()
    for ((key, raw) in after) {
        val value = raw.trim()
        val old = (before[key] ?: "").trim()
        if (key == "birth_date") {
            if (value.isEmpty()) continue
            val iso = parseGermanDate(value) ?: return null
            if (iso != old) out[key] = iso
        } else if (value != old) {
            out[key] = value
        }
    }
    return out
}

/** Wert aus einer Änderungsmeldung lesbar machen (Datum, Geschlecht). */
fun displayValue(key: String, value: String?): String = when {
    value.isNullOrBlank() -> "–"
    key == "birth_date" -> formatPlainDate(value)
    key == "gender" -> genderLabel(value)
    else -> value
}

/** Beginn und Ende des Zeitraums für Geburtstage und Jubiläen: heute bis in [days] Tagen. */
fun upcomingRange(today: LocalDate, days: Long = 30): Pair<String, String> =
    today.toString() to today.plusDays(days).toString()

// --- B3: Termine ---

private val TIME = java.time.format.DateTimeFormatter.ofPattern("H:mm").withResolverStyle(ResolverStyle.STRICT)

/** „19:30“ oder „9.30“ → 19:30; ungültig → null. */
fun parseTime(text: String): java.time.LocalTime? =
    runCatching { java.time.LocalTime.parse(text.trim().replace('.', ':'), TIME) }.getOrNull()

/**
 * Beginn und Ende eines neuen Termins als ISO mit Zeitzone. Ganztägig: Beginn und Ende jeweils
 * 0:00 des (letzten) Tags in deutscher Zeit, so wie der Server ganztägige Termine speichert; das
 * Ende „Tag danach“ bilden erst Kalender und ICS (Audit 31.10. CL-03, vorher einen Tag zu lang).
 * Ohne Endzeit: zwei Stunden. Ende vor Beginn: am nächsten Tag. Ungültige Eingaben → null.
 */
fun eventTimes(
    date: String,
    start: String,
    end: String,
    allDay: Boolean,
    zone: java.time.ZoneId = java.time.ZoneId.systemDefault(),
): Pair<String, String>? {
    val day = parseGermanDate(date)?.let(LocalDate::parse) ?: return null
    val fmt = java.time.format.DateTimeFormatter.ISO_OFFSET_DATE_TIME
    if (allDay) {
        // Der Kalendertag gilt in deutscher Zeit, auch wenn das Telefon woanders ist
        val berlin = java.time.ZoneId.of("Europe/Berlin")
        val midnight = day.atStartOfDay(berlin).format(fmt)
        return midnight to midnight
    }
    val from = parseTime(start) ?: return null
    val begin = day.atTime(from).atZone(zone)
    val finish = if (end.isBlank()) {
        begin.plusHours(2)
    } else {
        val to = parseTime(end) ?: return null
        val sameDay = day.atTime(to).atZone(zone)
        if (sameDay.isAfter(begin)) sameDay else sameDay.plusDays(1)
    }
    return begin.format(fmt) to finish.format(fmt)
}

/** Stand der Rückmeldungen in Worten: „12 Zusagen (15 Personen) · 3 Absagen · 20 offen“. */
fun responseSummary(yes: Int, no: Int, persons: Int, open: Int?): String {
    val parts = mutableListOf(
        (if (yes == 1) "1 Zusage" else "$yes Zusagen") + if (persons != yes) " ($persons Personen)" else "",
        if (no == 1) "1 Absage" else "$no Absagen",
    )
    open?.let { parts += "$it offen" }
    return parts.joinToString(" · ")
}

/** Gleicher Zeitpunkt, auch bei anderer Schreibweise der Zone. */
fun sameInstant(a: String?, b: String?): Boolean {
    if (a == null || b == null) return false
    val x = runCatching { java.time.OffsetDateTime.parse(a).toInstant() }.getOrNull()
    val y = runCatching { java.time.OffsetDateTime.parse(b).toInstant() }.getOrNull()
    return if (x != null && y != null) x == y else a == b
}

val VISIBILITIES = listOf("members" to "Mitglieder", "board" to "nur Vorstand", "public" to "nach außen öffentlich")

// --- B4: Kasse ---

/** „23,40“, „23.4“ oder „1.234,50“ → „23.40“ (positiv, höchstens zwei Nachkommastellen); sonst null. */
fun parseAmount(text: String): String? {
    var t = text.trim().removeSuffix("€").trim().replace(" ", "")
    if (t.isEmpty()) return null
    if (t.contains(',')) t = t.replace(".", "").replace(',', '.')
    val value = t.toBigDecimalOrNull() ?: return null
    if (value.signum() <= 0 || value.scale() > 2 || value > java.math.BigDecimal("99999999.99")) return null
    return value.setScale(2).toPlainString()
}

/** „1234.5“ → „1.234,50 €“ */
fun formatMoney(amount: String?): String {
    val value = amount?.toBigDecimalOrNull() ?: return ""
    val symbols = java.text.DecimalFormatSymbols(java.util.Locale.GERMANY)
    return java.text.DecimalFormat("#,##0.00", symbols).format(value) + " €"
}

// --- B5: Beiträge ---

/** „60.00“ → „60,00“ (Vorgabe im Eingabefeld). */
fun amountInput(amount: String): String = amount.toBigDecimalOrNull()?.setScale(2)?.toPlainString()?.replace('.', ',') ?: ""

/** Prüft den bar kassierten Betrag gegen den offenen Rest; null = in Ordnung. */
fun cashAmountError(input: String, outstanding: String): String? {
    val value = parseAmount(input)?.toBigDecimal() ?: return "Bitte einen Betrag über 0 eingeben."
    val open = outstanding.toBigDecimalOrNull() ?: return null
    return if (value > open) "Offen sind nur noch ${formatMoney(outstanding)}." else null
}

/** Dateiname der Quittung: nur Buchstaben, Ziffern und Bindestriche. */
fun receiptFileName(invoiceNumber: String?, paymentId: Int): String {
    val number = invoiceNumber.orEmpty().replace(Regex("[^A-Za-z0-9-]"), "-").trim('-')
    return if (number.isEmpty()) "Quittung-$paymentId.pdf" else "Quittung-$number-$paymentId.pdf"
}
