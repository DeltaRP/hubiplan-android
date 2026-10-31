package de.hubiplan.app.ui

import android.app.DatePickerDialog
import android.app.TimePickerDialog
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp

// Bausteine für TalkBack und Schalterbedienung (Barrierefreiheit 30.10.2026, A-01 bis A-04).

/** Markiert einen Text als Überschrift: TalkBack kann von Abschnitt zu Abschnitt springen. */
fun Modifier.asHeading(): Modifier = semantics { heading() }

/**
 * Aufklapp-Auswahl („Wert ▾“): TalkBack liest „Beschriftung: Wert, Auswahlliste,
 * zugeklappt“ statt des Pfeilzeichens (A-07).
 */
fun Modifier.dropdown(label: String, value: String, open: Boolean): Modifier = semantics {
    contentDescription = if (label.isBlank()) value else "$label: $value"
    stateDescription = if (open) "aufgeklappt" else "zugeklappt"
    role = Role.DropdownList
}

/** Statusmeldung, die TalkBack von selbst vorliest (Fehler dringlicher als Hinweise). */
fun Modifier.announce(urgent: Boolean = false): Modifier =
    semantics { liveRegion = if (urgent) LiveRegionMode.Assertive else LiveRegionMode.Polite }

/**
 * Schalter mit Beschriftung: Die ganze Zeile ist ein Bedienelement. TalkBack liest
 * „Beschriftung, Schalter, ein/aus“ statt eines namenlosen Schalters.
 */
@Composable
fun LabeledSwitch(
    label: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    Row(
        modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .toggleable(value = checked, enabled = enabled, role = Role.Switch, onValueChange = onCheckedChange),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(label, Modifier.weight(1f).padding(end = 12.dp))
        Switch(checked = checked, onCheckedChange = null, enabled = enabled)
    }
}

/** Häkchen mit Beschriftung; der ganze Text ist antippbar und Name des Häkchens. */
@Composable
fun LabeledCheckbox(
    label: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    style: TextStyle? = null,
) {
    Row(
        modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .toggleable(value = checked, enabled = enabled, role = Role.Checkbox, onValueChange = onCheckedChange),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(checked = checked, onCheckedChange = null, enabled = enabled, modifier = Modifier.padding(end = 12.dp))
        Text(label, style = style ?: MaterialTheme.typography.bodyLarge)
    }
}

/** „TT.MM.JJJJ“ aus Tag, Monat (1–12) und Jahr. */
fun germanDate(year: Int, month: Int, day: Int): String = "%02d.%02d.%04d".format(day, month, year)

/**
 * Knopf am Datumsfeld: öffnet den Kalender des Systems (A-05). Die Eingabe per Tastatur
 * bleibt; der Kalender erspart das genaue Tippen (Zittern, Sprachsteuerung).
 */
@Composable
fun DatePickButton(value: String, onPick: (String) -> Unit) {
    val context = LocalContext.current
    IconButton(onClick = {
        val current = parseGermanDate(value)?.let(java.time.LocalDate::parse) ?: java.time.LocalDate.now()
        DatePickerDialog(
            context,
            { _, y, m, d -> onPick(germanDate(y, m + 1, d)) },
            current.year,
            current.monthValue - 1,
            current.dayOfMonth,
        ).show()
    }) {
        Icon(Icons.Filled.DateRange, contentDescription = "Datum im Kalender wählen")
    }
}

/** Knopf am Zeitfeld: öffnet die Uhrzeitauswahl des Systems (24 Stunden). */
@Composable
fun TimePickButton(value: String, onPick: (String) -> Unit) {
    val context = LocalContext.current
    IconButton(onClick = {
        val current = parseTime(value) ?: java.time.LocalTime.of(19, 0)
        TimePickerDialog(
            context,
            { _, h, min -> onPick("%02d:%02d".format(h, min)) },
            current.hour,
            current.minute,
            true,
        ).show()
    }) {
        Icon(Icons.Filled.Edit, contentDescription = "Uhrzeit auswählen")
    }
}
