package de.hubiplan.app.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import de.hubiplan.app.data.ApiException
import de.hubiplan.app.data.EventDetail
import de.hubiplan.app.data.Occurrence
import de.hubiplan.app.data.PortalAccess
import java.time.LocalDate
import kotlinx.coroutines.launch

/** Termine wie im Kalender-Abo: von heute bis gut ein Jahr voraus. */
private const val DAYS_AHEAD = 400L

@Composable
fun EventsPane(access: PortalAccess, vm: AppViewModel) {
    var items by remember(access.id) { mutableStateOf<List<Occurrence>?>(null) }
    var error by remember(access.id) { mutableStateOf<String?>(null) }
    // Gewählt ist der Einzeltermin (Schlüssel der Wiederholung), nicht die ganze Serie (UX-Audit 31.10. A05)
    var selected by rememberSaveable(access.id) { mutableStateOf<String?>(null) }
    var reloadKey by remember { mutableIntStateOf(0) }

    LaunchedEffect(access.id, reloadKey) {
        val api = vm.api() ?: return@LaunchedEffect
        error = null
        try {
            val today = LocalDate.now()
            items = api.events(access.id, today.toString(), today.plusDays(DAYS_AHEAD).toString())
        } catch (e: ApiException) {
            if (e.status == 401) vm.deviceRevoked(e.message) else error = e.message
        }
    }

    ListDetail(
        hasDetail = selected != null,
        onBack = { selected = null },
        emptyDetail = "Termin auswählen",
        list = {
            when {
                error != null -> Column(Modifier.padding(16.dp)) {
                    Text(error ?: "", color = MaterialTheme.colorScheme.error)
                    TextButton(onClick = { reloadKey++ }) { Text("Erneut laden") }
                }
                items == null -> Loading()
                items!!.isEmpty() -> Message("Keine anstehenden Termine.")
                else -> LazyColumn(Modifier.fillMaxSize()) {
                    items(items!!, key = { it.key }) { occ ->
                        EventRow(occ, occ.key == selected) { selected = occ.key }
                        HorizontalDivider()
                    }
                }
            }
        },
        detail = {
            val list = items.orEmpty()
            list.firstOrNull { it.key == selected }?.let { occ ->
                val series = list.count { it.eventId == occ.eventId } > 1
                EventDetailView(access, occ, series, vm, onBack = { selected = null }) { reloadKey++ }
            }
        },
    )
}

@Composable
private fun EventRow(occ: Occurrence, isSelected: Boolean, onClick: () -> Unit) {
    ListItem(
        // Gewählter Termin (Tablet) nicht nur farbig: TalkBack sagt „ausgewählt“ (A-06)
        modifier = Modifier.clickable(onClick = onClick).semantics { if (isSelected) selected = true },
        colors = if (isSelected) {
            ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.primaryContainer)
        } else {
            ListItemDefaults.colors()
        },
        headlineContent = {
            Text(
                occ.title,
                textDecoration = if (occ.isCancelled) TextDecoration.LineThrough else null,
            )
        },
        supportingContent = {
            Column {
                Text(formatWhen(occ.startsAt, occ.endsAt, occ.allDay))
                val place = listOf(occ.location, occ.owner).filter { it.isNotBlank() }.joinToString(" · ")
                if (place.isNotBlank()) Text(place, style = MaterialTheme.typography.bodySmall)
                if (occ.isCancelled) Text("Abgesagt", color = MaterialTheme.colorScheme.error)
            }
        },
        trailingContent = {
            if (occ.rsvpEnabled) {
                Text(
                    when (occ.myResponse) {
                        "yes" -> "✓ Zusage"
                        "no" -> "✗ Absage"
                        else -> "offen"
                    },
                    color = if (occ.myResponse == null) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.onSurface,
                )
            }
        },
    )
}

@Composable
private fun EventDetailView(
    access: PortalAccess,
    occ: Occurrence,
    series: Boolean,
    vm: AppViewModel,
    onBack: () -> Unit,
    onChanged: () -> Unit,
) {
    val eventId = occ.eventId
    var event by remember(eventId) { mutableStateOf<EventDetail?>(null) }
    var error by remember(eventId) { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var attempt by remember { mutableIntStateOf(0) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(access.id, eventId, attempt) {
        val api = vm.api() ?: return@LaunchedEffect
        error = null
        try {
            event = api.event(access.id, eventId)
        } catch (e: ApiException) {
            if (e.status == 401) vm.deviceRevoked(e.message) else error = e.message
        }
    }

    fun change(action: suspend () -> EventDetail) {
        busy = true
        error = null
        scope.launch {
            try {
                event = action()
                onChanged()
                vm.syncSoon()
            } catch (e: ApiException) {
                if (e.status == 401) vm.deviceRevoked(e.message) else error = e.message
            } finally {
                busy = false
            }
        }
    }

    val e = event
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        TextButton(onClick = onBack) { Text("‹ Zurück") }
        error?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.announce(urgent = true)) }
        if (e == null) {
            if (error == null) Loading() else TextButton(onClick = { attempt++ }) { Text("Erneut laden") }
            return@Column
        }
        // Datum, Ort und Absage des gewählten Einzeltermins, nicht des ersten der Serie (A05)
        Text(e.title, style = MaterialTheme.typography.headlineSmall, modifier = Modifier.asHeading())
        Text(formatWhen(occ.startsAt, occ.endsAt, occ.allDay), style = MaterialTheme.typography.titleMedium)
        if (series) Text("Termin einer Serie", style = MaterialTheme.typography.bodySmall)
        if (occ.isCancelled || (!series && e.isCancelled)) {
            val note = occ.note.ifBlank { if (series) "" else e.cancelNote }
            Text("Abgesagt" + if (note.isNotBlank()) ": $note" else "", color = MaterialTheme.colorScheme.error)
        } else if (occ.note.isNotBlank()) {
            Text(occ.note)
        }
        listOf(occ.location.ifBlank { e.location }, occ.address.ifBlank { e.address }).filter { it.isNotBlank() }.takeIf { it.isNotEmpty() }?.let {
            Text(it.joinToString("\n"))
        }
        if (e.owner.isNotBlank()) Text(e.owner, style = MaterialTheme.typography.bodySmall)
        if (e.description.isNotBlank()) Text(e.description)

        if (e.rsvp.enabled) {
            HorizontalDivider()
            if (series) Text("Bei Serien gilt die Rückmeldung für alle Termine der Serie.", style = MaterialTheme.typography.bodySmall)
            RsvpSection(e, busy) { status, guests ->
                val api = vm.api() ?: return@RsvpSection
                change {
                    if (status == null) api.withdraw(access.id, e.id) else api.respond(access.id, e.id, status, guests)
                }
            }
        }
    }
}

@Composable
private fun RsvpSection(e: EventDetail, busy: Boolean, onRespond: (String?, Int) -> Unit) {
    val rsvp = e.rsvp
    val mine = rsvp.mine
    var guests by remember(e.id, mine?.guests) { mutableIntStateOf(mine?.guests ?: 0) }
    Text("Rückmeldung", style = MaterialTheme.typography.titleMedium, modifier = Modifier.asHeading())
    rsvp.deadline?.let { Text("Anmeldeschluss: ${formatDate(it)}") }
    rsvp.free?.let { Text("Noch $it Plätze frei") }
    Text(
        when (mine?.status) {
            "yes" -> {
                val count = mine?.guests ?: 0
                "Du hast zugesagt" + if (count > 0) " (mit $count Begleitung)." else "."
            }
            "no" -> "Du hast abgesagt."
            else -> "Du hast dich noch nicht zurückgemeldet."
        },
    )
    if (!rsvp.canRespond) {
        rsvp.reason?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
        return
    }
    if (rsvp.guestsAllowed) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            AssistChip(
                onClick = { if (guests > 0) guests-- },
                label = { Text("−") },
                enabled = !busy,
                modifier = Modifier.semantics { contentDescription = "Begleitperson entfernen" },
            )
            Text("Begleitpersonen: $guests", Modifier.padding(top = 14.dp))
            AssistChip(
                onClick = { if (guests < rsvp.maxGuests) guests++ },
                label = { Text("+") },
                enabled = !busy,
                modifier = Modifier.semantics { contentDescription = "Begleitperson hinzufügen" },
            )
        }
    }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Button(onClick = { onRespond("yes", guests) }, enabled = !busy, modifier = Modifier.weight(1f)) { Text("Zusage") }
        OutlinedButton(onClick = { onRespond("no", 0) }, enabled = !busy, modifier = Modifier.weight(1f)) { Text("Absage") }
    }
    if (mine != null) {
        TextButton(onClick = { onRespond(null, 0) }, enabled = !busy) { Text("Rückmeldung zurücknehmen") }
    }
}
