package de.hubiplan.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import de.hubiplan.app.data.ApiException
import de.hubiplan.app.data.MyGrant
import de.hubiplan.app.data.MyPlacing
import de.hubiplan.app.data.MyResults
import de.hubiplan.app.data.PortalAccess
import de.hubiplan.app.tr

/**
 * „Meine Ergebnisse“ im Profil (Wettbewerbe Schritt 5): eigene Starts mit Platz und Wert,
 * Titel und Auszeichnungen über alle Jahre. Nur eigene Daten, keine fremden Namen.
 */
@Composable
fun MyResultsSection(access: PortalAccess, vm: AppViewModel) {
    var data by remember(access.id) { mutableStateOf<MyResults?>(null) }
    var error by remember(access.id) { mutableStateOf<String?>(null) }
    var attempt by remember { mutableIntStateOf(0) }
    var all by remember(access.id) { mutableStateOf(false) }
    LaunchedEffect(access.id, attempt) {
        val api = vm.api() ?: return@LaunchedEffect
        error = null
        try {
            data = api.myResults(access.id)
        } catch (e: ApiException) {
            if (e.status == 401) vm.deviceRevoked(e.message) else error = e.message
        }
    }
    Text(tr("Meine Ergebnisse"), style = MaterialTheme.typography.titleMedium, modifier = Modifier.asHeading())
    val d = data
    if (d == null) {
        error?.let {
            Text(it, color = MaterialTheme.colorScheme.error)
            TextButton(onClick = { attempt++ }) { Text(tr("Erneut laden")) }
        }
        return
    }
    if (d.starts.isEmpty() && d.grants.isEmpty()) {
        Text(tr("Noch keine Ergebnisse."), style = MaterialTheme.typography.bodySmall)
        return
    }
    if (d.grants.isNotEmpty()) {
        Text(tr("Titel und Auszeichnungen"), style = MaterialTheme.typography.titleSmall, modifier = Modifier.asHeading())
        d.grants.forEach { Text(grantLine(it)) }
    }
    val shown = if (all) d.starts else d.starts.take(SHORT_LIST)
    shown.forEach { s ->
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(s.title, style = MaterialTheme.typography.titleSmall)
            val meta = listOf(formatPlainDate(s.date), s.unitName, if (s.status == "endgueltig") "" else s.statusLabel)
                .filter { it.isNotBlank() }
                .joinToString(" · ")
            Text(meta, style = MaterialTheme.typography.bodySmall)
            s.results.forEach { Text(placingLine(it), style = MaterialTheme.typography.bodyMedium) }
        }
    }
    if (!all && d.starts.size > SHORT_LIST) {
        TextButton(onClick = { all = true }) { Text(tr("Alle {size} Wettbewerbe zeigen", "size" to d.starts.size)) }
    }
}

private const val SHORT_LIST = 5

/** „Einzel: 2. Platz von 14 · 28 Ringe“; ohne Platz nur der Wert. */
fun placingLine(p: MyPlacing): String {
    val place = p.place?.let { n -> "$n. Platz" + (p.field?.let { tr(" von {it}", "it" to it) } ?: "") }
    val parts = listOfNotNull(place, p.valueText.ifBlank { null })
    return "${p.scoring}: " + parts.ifEmpty { listOf(tr("ohne Wertung")) }.joinToString(" · ")
}

/** „Vereinsmeister 2027“; ein Preis ohne Jahr im Titel bekommt das Jahr dazu. */
fun grantLine(g: MyGrant): String {
    val title = g.fullTitle.ifBlank { g.trophyName }
    return if (title.contains(g.year.toString())) title else "$title ${g.year}"
}
