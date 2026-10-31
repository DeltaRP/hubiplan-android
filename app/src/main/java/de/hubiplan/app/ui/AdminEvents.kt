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
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import de.hubiplan.app.data.AdminClient
import de.hubiplan.app.data.AdminEvent
import de.hubiplan.app.data.AdminEventList
import de.hubiplan.app.data.AdminOccurrence
import de.hubiplan.app.data.AdminResponses
import de.hubiplan.app.data.ApiException
import de.hubiplan.app.data.obj
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.launch

// Verwaltung in der App, Schritt B3: Termine der Einheit und Rückmeldungen

const val EVENTS_MANAGE = "events.manage"

/** Kommende Termine (ein halbes Jahr), mit „Neuer Termin“ für alle mit Terminrecht. */
@Composable
fun EventsSection(
    client: AdminClient,
    errors: AdminErrors,
    reload: Int,
    /** Neuer Termin; Wert: ob Zu- und Absagen möglich sind. */
    onCreate: (Boolean) -> Unit,
    onOpen: (AdminOccurrence) -> Unit,
) {
    var data by remember(client.unitId) { mutableStateOf<AdminEventList?>(null) }
    var error by remember(client.unitId) { mutableStateOf<String?>(null) }
    var refresh by remember { mutableIntStateOf(0) }
    LaunchedEffect(client.unitId, reload, refresh) {
        val today = LocalDate.now()
        try {
            data = client.events(today.toString(), today.plusDays(183).toString())
            error = null
        } catch (e: ApiException) {
            error = errors.message(e)
        }
    }
    Column(Modifier.fillMaxSize()) {
        error?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.announce(urgent = true).padding(16.dp)) }
        val list = data
        if (list == null) {
            if (error == null) Loading()
            return@Column
        }
        LazyColumn(Modifier.fillMaxSize()) {
            item {
                Row(
                    Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("Kommende Termine", style = MaterialTheme.typography.titleMedium, modifier = Modifier.asHeading().weight(1f))
                    TextButton(onClick = { refresh++ }) { Text("Aktualisieren") }
                    if (list.canManage) Button(onClick = { onCreate(list.rsvpAllowed) }) { Text("Neuer Termin") }
                }
            }
            if (list.items.isEmpty()) item { Text("Keine Termine im nächsten halben Jahr.", Modifier.padding(16.dp)) }
            items(list.items, key = { it.key.ifBlank { "${it.eventId}-${it.startsAt}" } }) { o ->
                ListItem(
                    overlineContent = { Text(formatWhen(o.startsAt, o.endsAt, o.allDay)) },
                    headlineContent = { Text(if (o.isCancelled) "${o.title} (abgesagt)" else o.title) },
                    supportingContent = {
                        Text(
                            listOfNotNull(
                                o.location.ifBlank { null },
                                o.owner.ifBlank { null },
                                if (o.rsvpEnabled) "Zu-/Absagen" else null,
                                if (o.isSeries) "Serie" else null,
                            ).joinToString(" · "),
                        )
                    },
                    modifier = Modifier.clickable { onOpen(o) },
                )
                HorizontalDivider()
            }
        }
    }
}

private sealed interface EventDialog {
    data object Cancel : EventDialog
    data class Respond(val membership: Int, val name: String, val status: String?, val guests: Int, val note: String) : EventDialog
}

/**
 * Ein Termin: Angaben, Stand der Rückmeldungen mit Listen, Eintragen für ein Mitglied und
 * Absagen. [occurrenceStart]: bei Serien der gewählte Einzeltermin (`original_start`).
 */
@Composable
fun EventScreen(
    client: AdminClient,
    id: Int,
    occurrenceStart: String?,
    errors: AdminErrors,
    onBack: () -> Unit,
    onChanged: OnChanged,
    reload: Int,
) {
    val scope = rememberCoroutineScope()
    var event by remember(id) { mutableStateOf<AdminEvent?>(null) }
    var responses by remember(id) { mutableStateOf<AdminResponses?>(null) }
    var error by remember(id) { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var dialog by remember { mutableStateOf<EventDialog?>(null) }
    var dialogError by remember { mutableStateOf<String?>(null) }
    var refresh by remember { mutableIntStateOf(0) }
    LaunchedEffect(id, reload, refresh) {
        try {
            val e = client.event(id)
            event = e
            responses = if (e.rsvp.canViewList) client.responses(id) else null
            error = null
        } catch (ex: ApiException) {
            error = errors.message(ex)
        }
    }

    /** Ausführen; aus einem Dialog heraus schließt er erst nach Erfolg (UX-Audit 31.10. A03). */
    fun change(inDialog: Boolean = false, block: suspend () -> Pair<String, String?>) {
        scope.launch {
            busy = true
            dialogError = null
            try {
                val (text, changeSet) = block()
                if (inDialog) dialog = null
                onChanged(text, changeSet)
                refresh++
                error = null
            } catch (ex: ApiException) {
                if (inDialog) dialogError = errors.message(ex) else error = errors.message(ex)
            }
            busy = false
        }
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        TextButton(onClick = onBack) { Text("‹ Zurück") }
        if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        error?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.announce(urgent = true)) }
        val e = event ?: return@Column
        // Bei Serien gilt die Absage je Einzeltermin
        val single = if (e.isSeries) occurrenceStart else null
        val exception = single?.let { s -> e.exceptions.firstOrNull { sameInstant(it.originalStart, s) } }
        val cancelled = if (single != null) exception?.isCancelled == true else e.isCancelled
        Text(e.title, style = MaterialTheme.typography.headlineSmall, modifier = Modifier.asHeading())
        val start = single ?: e.startsAt
        val end = if (single != null) shiftEnd(e.startsAt, e.endsAt, single) else e.endsAt
        Text(formatWhen(start, end, e.allDay))
        Text(
            listOfNotNull(
                e.location.ifBlank { null },
                e.visibilityLabel.ifBlank { null },
                if (e.isSeries) e.repeatLabel else null,
                e.owner.ifBlank { null },
            ).joinToString(" · "),
            style = MaterialTheme.typography.bodySmall,
        )
        if (cancelled) {
            val note = if (single != null) exception?.note.orEmpty() else e.cancelNote
            Text("Abgesagt" + if (note.isNotBlank()) ": $note" else "", color = MaterialTheme.colorScheme.error)
        }
        if (e.description.isNotBlank()) Text(e.description)
        if (e.canEdit) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (!cancelled) {
                    OutlinedButton(onClick = { dialog = EventDialog.Cancel }, enabled = !busy) {
                        Text(if (single != null) "Diesen Termin absagen" else "Absagen")
                    }
                } else {
                    OutlinedButton(enabled = !busy, onClick = {
                        change {
                            val r = if (single != null) {
                                client.cancelOccurrence(id, single, cancelled = false, note = "")
                            } else {
                                client.cancelEvent(id, cancelled = false, note = "", notify = false)
                            }
                            "Absage zurückgenommen" to r.changeSet
                        }
                    }) { Text("Absage zurücknehmen") }
                }
            }
        }

        // Rückmeldungen
        val rsvp = e.rsvp
        if (rsvp.enabled || responses != null) {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("Zu- und Absagen", style = MaterialTheme.typography.titleSmall, modifier = Modifier.asHeading())
                    val s = responses?.summary ?: rsvp.summary
                    s?.let { Text(responseSummary(it.yes, it.no, it.persons, it.open)) }
                    rsvp.capacity?.let { cap -> Text("Höchstens $cap Personen" + (rsvp.free?.let { ", noch $it frei" } ?: "")) }
                    rsvp.deadline?.let { Text("Anmeldeschluss ${formatDate(it)}", style = MaterialTheme.typography.bodySmall) }
                    if (e.isSeries) Text("Bei Serien gelten Rückmeldungen für die ganze Serie.", style = MaterialTheme.typography.bodySmall)
                }
            }
        }
        responses?.let { r -> ResponseLists(r, canEnter = rsvp.canEnter && e.canEdit) { dialog = it } }
    }

    val e = event
    when (val d = dialog) {
        null -> {}
        EventDialog.Cancel -> if (e != null) {
            val single = if (e.isSeries) occurrenceStart else null
            CancelDialog(
                series = single != null,
                saving = busy,
                saveError = dialogError,
                onDismiss = { dialog = null; dialogError = null },
            ) { note, notify ->
                change(inDialog = true) {
                    val r = if (single != null) {
                        client.cancelOccurrence(id, single, cancelled = true, note = note)
                    } else {
                        client.cancelEvent(id, cancelled = true, note = note, notify = notify)
                    }
                    "„${e.title}“ abgesagt" to r.changeSet
                }
            }
        }
        is EventDialog.Respond -> if (e != null) {
            RespondDialog(
                d,
                guestsAllowed = e.rsvp.guestsAllowed,
                maxGuests = e.rsvp.maxGuests,
                saving = busy,
                saveError = dialogError,
                onDismiss = { dialog = null; dialogError = null },
                onRemove = {
                    change(inDialog = true) { "Rückmeldung von ${d.name} entfernt" to client.deleteResponse(id, d.membership).changeSet }
                },
            ) { status, guests, note ->
                change(inDialog = true) {
                    val word = if (status == "yes") "Zusage" else "Absage"
                    "$word für ${d.name} eingetragen" to client.setResponse(id, d.membership, status, guests, note).changeSet
                }
            }
        }
    }
}

/** Ende eines Einzeltermins einer Serie: gleiche Dauer wie der erste Termin. */
private fun shiftEnd(start: String, end: String, occurrence: String): String = runCatching {
    val s = java.time.OffsetDateTime.parse(start)
    val e = java.time.OffsetDateTime.parse(end)
    java.time.OffsetDateTime.parse(occurrence).plus(java.time.Duration.between(s, e)).toString()
}.getOrDefault(occurrence)

private enum class ListTab(val label: String) { YES("Zusagen"), NO("Absagen"), OPEN("Offen") }

@Composable
private fun ResponseLists(r: AdminResponses, canEnter: Boolean, onPick: (EventDialog.Respond) -> Unit) {
    var tab by remember { mutableStateOf(ListTab.YES) }
    var search by remember { mutableStateOf("") }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        ListTab.entries.forEach { t ->
            val count = when (t) {
                ListTab.YES -> r.summary.yes
                ListTab.NO -> r.summary.no
                ListTab.OPEN -> r.open.size
            }
            FilterChip(selected = tab == t, onClick = { tab = t }, label = { Text("${t.label} ($count)") })
        }
    }
    OutlinedTextField(
        value = search,
        onValueChange = { search = it },
        label = { Text("Name suchen") },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )
    val q = search.trim().lowercase()
    fun hit(name: String) = q.isEmpty() || name.lowercase().contains(q)
    if (canEnter) Text("Antippen, um für ein Mitglied einzutragen (z. B. nach einem Anruf).", style = MaterialTheme.typography.bodySmall)
    when (tab) {
        ListTab.OPEN -> r.open.filter { hit(it.name) }.forEach { o ->
            ListItem(
                headlineContent = { Text(o.name) },
                supportingContent = { if (o.departments.isNotBlank()) Text(o.departments) },
                modifier = if (canEnter) Modifier.clickable { onPick(EventDialog.Respond(o.membershipId, o.name, null, 0, "")) } else Modifier,
            )
            HorizontalDivider()
        }
        else -> r.items.filter { (it.status == "yes") == (tab == ListTab.YES) && hit(it.name) }.forEach { row ->
            ListItem(
                headlineContent = { Text(if (row.guests > 0) "${row.name} + ${row.guests}" else row.name) },
                supportingContent = {
                    Text(
                        listOfNotNull(
                            row.departments.ifBlank { null },
                            row.note.ifBlank { null },
                            if (row.source == "board") "vom Vorstand eingetragen" else null,
                        ).joinToString(" · "),
                    )
                },
                modifier = if (canEnter) {
                    Modifier.clickable { onPick(EventDialog.Respond(row.membershipId, row.name, row.status, row.guests, row.note)) }
                } else {
                    Modifier
                },
            )
            HorizontalDivider()
        }
    }
}

@Composable
private fun CancelDialog(series: Boolean, saving: Boolean, saveError: String?, onDismiss: () -> Unit, onConfirm: (String, Boolean) -> Unit) {
    var note by remember { mutableStateOf("") }
    var notify by remember { mutableStateOf(true) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (series) "Diesen Termin absagen?" else "Termin absagen?") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                saveError?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.announce(urgent = true)) }
                if (saving) LinearProgressIndicator(Modifier.fillMaxWidth())
                Text(
                    if (series) {
                        "Nur dieser Termin der Serie wird abgesagt; die übrigen bleiben."
                    } else {
                        "Der Termin bleibt im Kalender, als abgesagt gekennzeichnet. Rückmeldungen bleiben erhalten."
                    },
                )
                OutlinedTextField(value = note, onValueChange = { note = it.take(300) }, label = { Text("Hinweis (optional)") }, modifier = Modifier.fillMaxWidth())
                if (!series) {
                    LabeledSwitch("Eingeladene per E-Mail informieren", notify, { notify = it })
                }
            }
        },
        confirmButton = { Button(enabled = !saving, onClick = { onConfirm(note.trim(), notify && !series) }) { Text("Absagen") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Abbrechen") } },
    )
}

@Composable
private fun RespondDialog(
    d: EventDialog.Respond,
    guestsAllowed: Boolean,
    maxGuests: Int,
    saving: Boolean,
    saveError: String?,
    onDismiss: () -> Unit,
    onRemove: () -> Unit,
    onSave: (String, Int, String) -> Unit,
) {
    var status by remember { mutableStateOf(d.status ?: "yes") }
    var guests by remember { mutableStateOf(d.guests) }
    var note by remember { mutableStateOf(d.note) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(d.name) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                saveError?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.announce(urgent = true)) }
                if (saving) LinearProgressIndicator(Modifier.fillMaxWidth())
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(selected = status == "yes", onClick = { status = "yes" }, label = { Text("Zusage") })
                    FilterChip(selected = status == "no", onClick = { status = "no" }, label = { Text("Absage") })
                }
                if (guestsAllowed && status == "yes") {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Begleitung", Modifier.weight(1f))
                        TextButton(
                            onClick = { guests = (guests - 1).coerceAtLeast(0) },
                            enabled = guests > 0,
                            modifier = Modifier.semantics { contentDescription = "Begleitperson entfernen" },
                        ) { Text("−") }
                        Text("$guests")
                        TextButton(
                            onClick = { guests = (guests + 1).coerceAtMost(maxGuests) },
                            enabled = guests < maxGuests,
                            modifier = Modifier.semantics { contentDescription = "Begleitperson hinzufügen" },
                        ) { Text("+") }
                    }
                }
                OutlinedTextField(value = note, onValueChange = { note = it.take(200) }, label = { Text("Notiz (optional)") }, modifier = Modifier.fillMaxWidth())
                if (d.status != null) TextButton(onClick = onRemove, enabled = !saving) { Text("Rückmeldung entfernen") }
            }
        },
        confirmButton = {
            Button(enabled = !saving, onClick = { onSave(status, if (status == "yes" && guestsAllowed) guests else 0, note.trim()) }) { Text("Eintragen") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Abbrechen") } },
    )
}

private val DAY_INPUT: DateTimeFormatter = DateTimeFormatter.ofPattern("dd.MM.yyyy")

/** Einfachen Termin anlegen: einmalig, Titel, Tag, Zeit, Ort, Sichtbarkeit, Zu-/Absagen. */
@Composable
fun EventCreateScreen(client: AdminClient, errors: AdminErrors, rsvpAllowed: Boolean, onBack: () -> Unit, onDone: (Int, String, String?) -> Unit) {
    val scope = rememberCoroutineScope()
    // Eingaben überstehen Drehen und kurze Unterbrechungen (UX-Audit 31.10. A02)
    var title by rememberSaveable { mutableStateOf("") }
    var date by rememberSaveable { mutableStateOf(LocalDate.now().plusDays(7).format(DAY_INPUT)) }
    var start by rememberSaveable { mutableStateOf("19:00") }
    var end by rememberSaveable { mutableStateOf("") }
    var allDay by rememberSaveable { mutableStateOf(false) }
    var location by rememberSaveable { mutableStateOf("") }
    var description by rememberSaveable { mutableStateOf("") }
    var visibility by rememberSaveable { mutableStateOf("members") }
    var rsvp by rememberSaveable { mutableStateOf(false) }
    var guests by rememberSaveable { mutableStateOf(false) }
    var deadline by rememberSaveable { mutableStateOf("") }
    var notify by rememberSaveable { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val times = eventTimes(date, start, end, allDay)
    val deadlineIso = deadline.takeIf { it.isNotBlank() }?.let { parseGermanDate(it) }
    val deadlineOk = deadline.isBlank() || deadlineIso != null
    // Zu- und Absagen nur bei Terminen für Mitglieder (wie im Browser)
    val canRsvp = rsvpAllowed && visibility != "board"

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        TextButton(onClick = onBack) { Text("‹ Zurück") }
        Text("Neuer Termin", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.asHeading())
        Text("Einmaliger Termin. Serien, Anhänge und Weitergabe an andere Einheiten im Browser.", style = MaterialTheme.typography.bodySmall)
        if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        error?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.announce(urgent = true)) }
        OutlinedTextField(value = title, onValueChange = { title = it.take(200) }, label = { Text("Titel") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(
            value = date,
            onValueChange = { date = it },
            label = { Text("Tag (TT.MM.JJJJ)") },
            singleLine = true,
            isError = date.isNotBlank() && parseGermanDate(date) == null,
            trailingIcon = { DatePickButton(date) { date = it } },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            modifier = Modifier.fillMaxWidth(),
        )
        LabeledSwitch("Ganztägig", allDay, { allDay = it })
        if (!allDay) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = start,
                    onValueChange = { start = it },
                    label = { Text("Beginn (HH:MM)") },
                    singleLine = true,
                    isError = start.isNotBlank() && parseTime(start) == null,
                    trailingIcon = { TimePickButton(start) { start = it } },
                    modifier = Modifier.weight(1f),
                )
                OutlinedTextField(
                    value = end,
                    onValueChange = { end = it },
                    label = { Text("Ende (optional)") },
                    singleLine = true,
                    isError = end.isNotBlank() && parseTime(end) == null,
                    trailingIcon = { TimePickButton(end.ifBlank { start }) { end = it } },
                    modifier = Modifier.weight(1f),
                )
            }
        }
        if (times == null) Text("Bitte Tag als TT.MM.JJJJ und Zeit als HH:MM eingeben.", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall, modifier = Modifier.announce(urgent = true))
        OutlinedTextField(value = location, onValueChange = { location = it.take(200) }, label = { Text("Ort (optional)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(value = description, onValueChange = { description = it }, label = { Text("Beschreibung (optional)") }, minLines = 2, modifier = Modifier.fillMaxWidth())
        ChoicePicker("Wer sieht den Termin?", VISIBILITIES, visibility, { v -> VISIBILITIES.first { it.first == v }.second }) { visibility = it }
        if (canRsvp) {
            LabeledSwitch("Zu- und Absagen", rsvp, { rsvp = it })
            if (rsvp) {
                LabeledSwitch("Begleitung erlaubt", guests, { guests = it })
                OutlinedTextField(
                    value = deadline,
                    onValueChange = { deadline = it },
                    label = { Text("Anmeldeschluss (optional, TT.MM.JJJJ)") },
                    singleLine = true,
                    isError = !deadlineOk,
                    trailingIcon = { DatePickButton(deadline.ifBlank { date }) { deadline = it } },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth(),
                )
                LabeledSwitch("Eingeladene per E-Mail einladen", notify, { notify = it })
            }
        }
        Button(
            enabled = !busy && title.isNotBlank() && times != null && deadlineOk,
            modifier = Modifier.fillMaxWidth(),
            onClick = {
                val (from, to) = times ?: return@Button
                val body = buildMap<String, Any?> {
                    put("title", title.trim())
                    put("starts_at", from)
                    put("ends_at", to)
                    put("all_day", allDay)
                    put("location", location.trim())
                    put("description", description.trim())
                    put("visibility", visibility)
                    put("rsvp_enabled", rsvp && canRsvp)
                    if (rsvp && canRsvp) {
                        put("rsvp_guests", guests)
                        // Anmeldeschluss am Ende des Tages
                        deadlineIso?.let { put("rsvp_deadline", eventTimes(deadline, "23:59", "", false)?.first) }
                        put("notify", notify)
                    }
                }
                scope.launch {
                    busy = true
                    try {
                        val r = client.createEvent(obj(*body.toList().toTypedArray()))
                        onDone(r.value.id, "„${r.value.title}“ angelegt", r.changeSet)
                    } catch (e: ApiException) {
                        error = errors.message(e)
                    }
                    busy = false
                }
            },
        ) { Text("Anlegen") }
    }
}
