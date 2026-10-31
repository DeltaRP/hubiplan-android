package de.hubiplan.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import de.hubiplan.app.data.AdminClient
import de.hubiplan.app.data.ApiException
import de.hubiplan.app.data.AwardItem
import de.hubiplan.app.data.DepartmentItem
import de.hubiplan.app.data.DuplicateItem
import de.hubiplan.app.data.FieldEntry
import de.hubiplan.app.data.MandateItem
import de.hubiplan.app.data.MemberDetail
import de.hubiplan.app.data.MemberTypeItem
import de.hubiplan.app.data.Preview
import de.hubiplan.app.data.TitleItem
import de.hubiplan.app.data.obj
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.UUID
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull

const val BILLING_VIEW = "billing.view"
const val BILLING_EDIT = "billing.edit"

private fun todayInput(): String = LocalDate.now().format(DateTimeFormatter.ofPattern("dd.MM.yyyy"))

@Composable
private fun DateInput(label: String, value: String, onChange: (String) -> Unit) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text("$label (TT.MM.JJJJ)") },
        singleLine = true,
        isError = value.isNotBlank() && parseGermanDate(value) == null,
        trailingIcon = { DatePickButton(value, onChange) },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun SectionTitle(text: String) {
    HorizontalDivider()
    Text(text, style = MaterialTheme.typography.titleMedium, modifier = Modifier.asHeading())
}

// --- Ehrungen und Würden ---

@Composable
fun AwardsSection(client: AdminClient, id: Int, canEdit: Boolean, errors: AdminErrors, onChanged: OnChanged, reload: Int) {
    val scope = rememberCoroutineScope()
    var awards by remember(id) { mutableStateOf<List<AwardItem>?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var adding by remember { mutableStateOf(false) }
    var ending by remember { mutableStateOf<AwardItem?>(null) }
    var refresh by remember { mutableIntStateOf(0) }
    LaunchedEffect(id, reload, refresh) {
        try {
            awards = client.awards(id)
        } catch (e: ApiException) {
            error = errors.message(e)
        }
    }

    var saving by remember { mutableStateOf(false) }
    var dialogError by remember { mutableStateOf<String?>(null) }

    /** Speichern; der Dialog schließt erst nach Erfolg, Fehler stehen im Dialog (UX-Audit 31.10. A03). */
    fun change(text: String, onDone: () -> Unit = {}, block: suspend () -> de.hubiplan.app.data.Changed<Unit>) {
        scope.launch {
            saving = true
            dialogError = null
            try {
                val r = block()
                refresh++
                onDone()
                onChanged(text, r.changeSet)
            } catch (e: ApiException) {
                dialogError = errors.message(e)
            }
            saving = false
        }
    }

    SectionTitle("Ehrungen und Würden")
    error?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.announce(urgent = true)) }
    val list = awards
    if (list != null && list.isEmpty()) Text("Keine.", style = MaterialTheme.typography.bodySmall)
    list?.forEach { award ->
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.weight(1f)) {
                Text(award.titleName)
                Text(
                    "seit ${formatPlainDate(award.validFrom)}" + (award.validUntil?.let { " bis ${formatPlainDate(it)}" } ?: ""),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            if (canEdit && award.validUntil == null) TextButton(onClick = { ending = award }) { Text("Beenden") }
        }
    }
    if (canEdit) OutlinedButton(onClick = { adding = true }, modifier = Modifier.fillMaxWidth()) { Text("Ehrung hinzufügen") }

    if (adding) {
        AddAwardDialog(client, errors, saving, dialogError, onDismiss = { adding = false; dialogError = null }) { title, from, note ->
            change("${title.name} eingetragen", onDone = { adding = false }) { client.addAward(id, title.id, from, note) }
        }
    }
    ending?.let { award ->
        var until by remember(award.id) { mutableStateOf(todayInput()) }
        var problem by remember(award.id) { mutableStateOf<String?>(null) }
        AlertDialog(
            onDismissRequest = { ending = null },
            title = { Text("„${award.titleName}“ beenden") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    (problem ?: dialogError)?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.announce(urgent = true)) }
                    if (saving) LinearProgressIndicator(Modifier.fillMaxWidth())
                    DateInput("Endet am", until) { until = it }
                }
            },
            confirmButton = {
                Button(enabled = !saving, onClick = {
                    val iso = parseGermanDate(until)
                    if (iso == null) {
                        problem = "Bitte das Datum als TT.MM.JJJJ eingeben."
                    } else {
                        problem = null
                        change("„${award.titleName}“ beendet", onDone = { ending = null }) { client.endAward(id, award.id, iso) }
                    }
                }) { Text("Beenden") }
            },
            dismissButton = { TextButton(onClick = { ending = null; dialogError = null }) { Text("Abbrechen") } },
        )
    }
}

@Composable
private fun AddAwardDialog(
    client: AdminClient,
    errors: AdminErrors,
    saving: Boolean,
    saveError: String?,
    onDismiss: () -> Unit,
    onSave: (TitleItem, String, String) -> Unit,
) {
    var titles by remember { mutableStateOf<List<TitleItem>>(emptyList()) }
    var chosen by remember { mutableStateOf<TitleItem?>(null) }
    var from by remember { mutableStateOf(todayInput()) }
    var note by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) {
        try {
            titles = client.titles()
        } catch (e: ApiException) {
            error = errors.message(e)
        }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Ehrung hinzufügen") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                (error ?: saveError)?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.announce(urgent = true)) }
                if (saving) LinearProgressIndicator(Modifier.fillMaxWidth())
                ChoicePicker("Ehrung", titles.map { it to it.name }, chosen, { it.name }) { chosen = it }
                DateInput("Seit", from) { from = it }
                OutlinedTextField(value = note, onValueChange = { note = it.take(200) }, label = { Text("Notiz (optional)") }, modifier = Modifier.fillMaxWidth())
            }
        },
        confirmButton = {
            Button(enabled = !saving, onClick = {
                val iso = parseGermanDate(from)
                val t = chosen
                when {
                    t == null -> error = "Bitte eine Ehrung wählen."
                    iso == null -> error = "Bitte das Datum als TT.MM.JJJJ eingeben."
                    else -> onSave(t, iso, note.trim())
                }
            }) { Text("Speichern") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Abbrechen") } },
    )
}

// --- Zusatzfelder ---

/** Wert eines einfachen Zusatzfelds als Eingabetext. */
fun fieldText(def: de.hubiplan.app.data.FieldDef, value: JsonElement?): String {
    val p = value as? JsonPrimitive ?: return ""
    if (p is JsonNull) return ""
    return when (def.fieldType) {
        "date" -> formatPlainDate(p.contentOrNull)
        // Drei Zustände wie im Browser: ja, nein, nicht angegeben (Audit 31.10. CL-04)
        "bool" -> when (p.booleanOrNull) {
            true -> "ja"
            false -> "nein"
            null -> ""
        }
        "number" -> p.contentOrNull?.replace(".", ",") ?: ""
        else -> p.contentOrNull ?: ""
    }
}

/**
 * Eingaben in Werte für den Server: Datum als ISO, „ja“/„nein“ als Wahrheitswert, leer = löschen.
 * Gibt die geänderten Felder zurück oder einen Fehlertext.
 */
fun fieldChanges(entries: List<FieldEntry>, input: Map<Int, String>): Pair<Map<String, JsonElement>, String?> {
    val out = mutableMapOf<String, JsonElement>()
    for (entry in entries) {
        val def = entry.definition
        if (def.repeatable) continue
        val text = (input[def.id] ?: continue).trim()
        if (text == fieldText(def, entry.value).trim()) continue
        out[def.id.toString()] = when {
            text.isEmpty() -> JsonNull
            def.fieldType == "date" -> JsonPrimitive(parseGermanDate(text) ?: return out to "${def.label}: bitte als TT.MM.JJJJ eingeben.")
            def.fieldType == "bool" -> when (text) {
                "ja" -> JsonPrimitive(true)
                "nein" -> JsonPrimitive(false)
                else -> return out to "${def.label}: bitte ja, nein oder – wählen."
            }
            def.fieldType == "number" -> JsonPrimitive(text.replace(",", "."))
            else -> JsonPrimitive(text)
        }
    }
    return out to null
}

@Composable
fun FieldsButton(client: AdminClient, id: Int, canEdit: Boolean, errors: AdminErrors, onChanged: OnChanged, reload: Int) {
    val scope = rememberCoroutineScope()
    var entries by remember(id) { mutableStateOf<List<FieldEntry>?>(null) }
    var loadError by remember(id) { mutableStateOf<String?>(null) }
    var saving by remember { mutableStateOf(false) }
    var open by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var refresh by remember { mutableIntStateOf(0) }
    LaunchedEffect(id, reload, refresh) {
        try {
            entries = client.fields(id)
            loadError = null
        } catch (e: ApiException) {
            // Zusatzfelder gibt es nicht überall (z. B. ohne Mitgliederverwaltung): dann still
            // weglassen; andere Fehler sichtbar mit erneutem Versuch (UX-Audit 31.10. A07)
            val message = errors.message(e)
            loadError = if (e.status == 403 || e.status == 404) null else message
            entries = emptyList()
        }
    }
    loadError?.let {
        SectionTitle("Zusatzfelder")
        Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.announce(urgent = true))
        TextButton(onClick = { refresh++ }) { Text("Erneut versuchen") }
        return
    }
    val list = entries ?: return
    if (list.isEmpty()) return
    SectionTitle("Zusatzfelder")
    list.forEach { entry ->
        val shown = if (entry.definition.repeatable) "(mehrere Einträge, im Browser)" else fieldText(entry.definition, entry.value).ifBlank { "–" }
        Text("${entry.definition.label}: $shown", style = MaterialTheme.typography.bodyMedium)
    }
    error?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.announce(urgent = true)) }
    if (canEdit) OutlinedButton(onClick = { open = true }, modifier = Modifier.fillMaxWidth()) { Text("Zusatzfelder bearbeiten") }
    if (open) {
        var input by remember { mutableStateOf(list.associate { it.definition.id to fieldText(it.definition, it.value) }) }
        var problem by remember { mutableStateOf<String?>(null) }
        AlertDialog(
            onDismissRequest = { open = false },
            title = { Text("Zusatzfelder") },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    problem?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.announce(urgent = true)) }
                    if (saving) LinearProgressIndicator(Modifier.fillMaxWidth())
                    list.filterNot { it.definition.repeatable }.forEach { entry ->
                        val def = entry.definition
                        val value = input[def.id] ?: ""
                        when (def.fieldType) {
                            "bool" -> ChoicePicker(
                                def.label,
                                listOf("" to "–", "ja" to "ja", "nein" to "nein"),
                                value,
                                { it.ifBlank { "–" } },
                            ) { input = input + (def.id to it) }
                            "choice" -> ChoicePicker(
                                def.label,
                                listOf("" to "–") + def.choices.map { it to it },
                                value,
                                { it.ifBlank { "–" } },
                            ) { input = input + (def.id to it) }
                            else -> OutlinedTextField(
                                value = value,
                                onValueChange = { input = input + (def.id to it) },
                                label = { Text(if (def.fieldType == "date") "${def.label} (TT.MM.JJJJ)" else def.label) },
                                supportingText = if (def.helpText.isNotBlank()) {
                                    { Text(def.helpText) }
                                } else {
                                    null
                                },
                                keyboardOptions = KeyboardOptions(
                                    keyboardType = if (def.fieldType == "number" || def.fieldType == "date") KeyboardType.Number else KeyboardType.Text,
                                ),
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                    }
                }
            },
            confirmButton = {
                Button(enabled = !saving, onClick = {
                    val (changes, fault) = fieldChanges(list, input)
                    when {
                        fault != null -> problem = fault
                        changes.isEmpty() -> open = false
                        else -> {
                            // Offen lassen, bis gespeichert; bei Fehler bleiben die Eingaben (A03)
                            problem = null
                            saving = true
                            scope.launch {
                                try {
                                    val r = client.saveFields(id, changes)
                                    open = false
                                    refresh++
                                    onChanged("Zusatzfelder gespeichert", r.changeSet)
                                } catch (e: ApiException) {
                                    problem = errors.message(e)
                                }
                                saving = false
                            }
                        }
                    }
                }) { Text("Speichern") }
            },
            dismissButton = { TextButton(onClick = { open = false }) { Text("Abbrechen") } },
        )
    }
}

// --- Bankverbindung (SEPA-Mandat) ---

@Composable
fun BankSection(client: AdminClient, d: MemberDetail, permissions: List<String>, errors: AdminErrors, onChanged: OnChanged, reload: Int) {
    if (BILLING_VIEW !in permissions) return
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var mandates by remember(d.id) { mutableStateOf<List<MandateItem>?>(null) }
    var full by remember(d.id) { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var editing by remember { mutableStateOf(false) }
    var bankDraft by remember { mutableStateOf<Array<String>?>(null) }
    var bankError by remember { mutableStateOf<String?>(null) }
    var confirm by remember { mutableStateOf<Pair<Preview, Array<String>>?>(null) }
    var refresh by remember { mutableIntStateOf(0) }
    LaunchedEffect(d.id, reload, refresh, full) {
        try {
            mandates = client.mandates(d.id, full)
            error = null
        } catch (e: ApiException) {
            // 404: SEPA in dieser Einheit ausgeschaltet
            if (e.status == 404) mandates = emptyList() else error = errors.message(e)
        }
    }
    val list = mandates ?: return
    SectionTitle("Bankverbindung")
    error?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.announce(urgent = true)) }
    val active = list.filter { it.status == "active" }
    if (active.isEmpty()) Text("Kein gültiges SEPA-Mandat.", style = MaterialTheme.typography.bodySmall)
    active.forEach { m ->
        Text("${m.holder}: ${if (full && m.iban != null) formatIban(m.iban) else m.ibanMasked}")
        Text("Mandat ${m.reference}, unterschrieben ${formatPlainDate(m.signedOn)}", style = MaterialTheme.typography.bodySmall)
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        if (active.isNotEmpty() && !full) {
            TextButton(onClick = {
                // Ganze IBAN nur nach erneuter Gerätesperre
                DeviceUnlock.ask(context, "IBAN anzeigen", onSuccess = { full = true }, onError = { error = it })
            }) { Text("IBAN ganz anzeigen") }
        }
        if (BILLING_EDIT in permissions) OutlinedButton(onClick = { editing = true }) { Text("Bankverbindung ändern") }
    }
    if (editing) {
        BankDialog(d, bankDraft, bankError, onDismiss = { editing = false; bankDraft = null; bankError = null }) { holder, iban, bic, signed ->
            // Eingaben merken: bei einem Fehler öffnet der Dialog wieder mit ihnen (UX-Audit 31.10. A03)
            bankDraft = arrayOf(holder, iban, bic, signed)
            bankError = null
            scope.launch {
                try {
                    confirm = client.preview(d.id, obj("action" to "bank")) to arrayOf(holder, iban, bic, signed)
                    editing = false
                } catch (e: ApiException) {
                    bankError = errors.message(e)
                }
            }
        }
    }
    confirm?.let { (preview, v) ->
        ConfirmChangeDialog(preview, onDismiss = { confirm = null }) {
            confirm = null
            scope.launch {
                try {
                    val r = client.addMandate(d.id, v[0], v[1], v[2], v[3])
                    bankDraft = null
                    refresh++
                    onChanged("Bankverbindung geändert", r.changeSet)
                } catch (e: ApiException) {
                    // Eingaben nicht verlieren: Dialog mit ihnen und dem Fehler wieder öffnen
                    bankError = errors.message(e)
                    editing = true
                }
            }
        }
    }
}

/** IBAN in Vierergruppen. */
fun formatIban(iban: String): String = iban.replace(" ", "").chunked(4).joinToString(" ")

@Composable
private fun BankDialog(
    d: MemberDetail,
    initial: Array<String>?,
    saveError: String?,
    onDismiss: () -> Unit,
    onNext: (String, String, String, String) -> Unit,
) {
    var holder by remember { mutableStateOf(initial?.get(0) ?: d.name) }
    var iban by remember { mutableStateOf(initial?.get(1) ?: "") }
    var bic by remember { mutableStateOf(initial?.get(2) ?: "") }
    var signed by remember { mutableStateOf(initial?.get(3)?.let { formatPlainDate(it) } ?: todayInput()) }
    var error by remember { mutableStateOf<String?>(null) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Neue Bankverbindung") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Neues SEPA-Mandat auf Papier (vom Mitglied unterschrieben).", style = MaterialTheme.typography.bodySmall)
                (error ?: saveError)?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.announce(urgent = true)) }
                OutlinedTextField(value = holder, onValueChange = { holder = it.take(70) }, label = { Text("Kontoinhaber") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(value = iban, onValueChange = { iban = it.uppercase().take(42) }, label = { Text("IBAN") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(value = bic, onValueChange = { bic = it.uppercase().take(14) }, label = { Text("BIC (optional)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                DateInput("Unterschrieben am", signed) { signed = it }
            }
        },
        confirmButton = {
            Button(onClick = {
                val iso = parseGermanDate(signed)
                when {
                    holder.isBlank() -> error = "Bitte den Kontoinhaber angeben."
                    iban.replace(" ", "").length < 15 -> error = "Bitte eine vollständige IBAN angeben."
                    iso == null -> error = "Bitte das Datum als TT.MM.JJJJ eingeben."
                    else -> onNext(holder.trim(), iban.replace(" ", ""), bic.trim(), iso)
                }
            }) { Text("Weiter") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Abbrechen") } },
    )
}

@Composable
fun ConfirmChangeDialog(preview: Preview, onDismiss: () -> Unit, onConfirm: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(preview.title.ifBlank { "Änderung bestätigen" }) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Das passiert:", style = MaterialTheme.typography.labelLarge)
                preview.consequences.forEach { Text("• $it") }
                Text("Danach lässt sich die Änderung rückgängig machen.", style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = { Button(onClick = onConfirm) { Text("Bestätigen") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Abbrechen") } },
    )
}

/** Auswahl aus einer kurzen Liste (Wert, Beschriftung). */
@Composable
fun <T> ChoicePicker(label: String, options: List<Pair<T, String>>, selected: T?, show: (T) -> String, onPick: (T) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Column {
        Text(label, style = MaterialTheme.typography.labelMedium)
        androidx.compose.foundation.layout.Box {
            OutlinedButton(
                onClick = { open = true },
                modifier = Modifier.fillMaxWidth().dropdown(label, selected?.let(show) ?: "nichts gewählt", open),
            ) {
                Text((selected?.let(show) ?: "Bitte wählen") + " ▾")
            }
            androidx.compose.material3.DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                options.forEach { (value, text) ->
                    androidx.compose.material3.DropdownMenuItem(text = { Text(text) }, onClick = {
                        open = false
                        onPick(value)
                    })
                }
            }
        }
    }
}

// --- Wiedereintritt ---

@Composable
fun ReentryDialog(client: AdminClient, d: MemberDetail, errors: AdminErrors, onDismiss: () -> Unit, onNext: (MemberTypeItem, String) -> Unit) {
    var types by remember { mutableStateOf<List<MemberTypeItem>>(emptyList()) }
    var chosen by remember { mutableStateOf<MemberTypeItem?>(null) }
    var from by remember { mutableStateOf(todayInput()) }
    var error by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) {
        try {
            types = client.memberTypes()
        } catch (e: ApiException) {
            error = errors.message(e)
        }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Wiedereintritt von ${d.name}") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Neuer Zeitraum; frühere Zeiten und Nummern bleiben erhalten.", style = MaterialTheme.typography.bodySmall)
                error?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.announce(urgent = true)) }
                ChoicePicker("Mitgliedsart", types.map { it to it.name }, chosen, { it.name }) { chosen = it }
                DateInput("Eintritt am", from) { from = it }
            }
        },
        confirmButton = {
            Button(onClick = {
                val iso = parseGermanDate(from)
                val t = chosen
                when {
                    t == null -> error = "Bitte eine Mitgliedsart wählen."
                    iso == null -> error = "Bitte das Datum als TT.MM.JJJJ eingeben."
                    else -> onNext(t, iso)
                }
            }) { Text("Weiter") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Abbrechen") } },
    )
}

// --- Neues Mitglied ---

@Composable
fun AdmitScreen(client: AdminClient, errors: AdminErrors, onBack: () -> Unit, onDone: (Int, String) -> Unit) {
    val scope = rememberCoroutineScope()
    // Ein Schlüssel je Formular: erneutes Senden nimmt nicht doppelt auf
    // Eingaben überstehen Drehen und kurze Unterbrechungen (UX-Audit 31.10. A02)
    val key = rememberSaveable { UUID.randomUUID().toString() }
    var values by rememberSaveable {
        mutableStateOf(
            mapOf(
                "first_name" to "", "last_name" to "", "birth_date" to "", "gender" to "",
                "email" to "", "phone" to "", "street" to "", "postal_code" to "", "city" to "",
                "entry_date" to todayInput(),
            ),
        )
    }
    var types by remember { mutableStateOf<List<MemberTypeItem>>(emptyList()) }
    var units by remember { mutableStateOf<List<DepartmentItem>>(emptyList()) }
    var type by remember { mutableStateOf<MemberTypeItem?>(null) }
    var unit by remember { mutableStateOf<DepartmentItem?>(null) }
    var duplicates by remember { mutableStateOf<List<DuplicateItem>?>(null) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) {
        try {
            types = client.memberTypes()
            units = runCatching { client.departments() }.getOrDefault(emptyList())
        } catch (e: ApiException) {
            error = errors.message(e)
        }
    }

    fun admit(person: Int?) {
        val birth = parseGermanDate(values["birth_date"] ?: "")
        val entry = parseGermanDate(values["entry_date"] ?: "")!!
        val body = buildMap<String, Any?> {
            put("member_type", type!!.id)
            put("entry_date", entry)
            if (person != null) put("person", person) else {
                put("first_name", values["first_name"]!!.trim())
                put("last_name", values["last_name"]!!.trim())
                put("birth_date", birth)
                put("gender", values["gender"])
            }
            for (k in listOf("email", "phone", "street", "postal_code", "city")) put(k, values[k]!!.trim())
            unit?.let { put("department_ids", kotlinx.serialization.json.JsonArray(listOf(JsonPrimitive(it.id)))) }
        }
        scope.launch {
            busy = true
            try {
                val created = client.admit(obj(*body.toList().toTypedArray()), key)
                onDone(created.id, "${created.name} aufgenommen")
            } catch (e: ApiException) {
                error = errors.message(e)
            }
            busy = false
        }
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        TextButton(onClick = onBack) { Text("‹ Zurück") }
        Text("Neues Mitglied", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.asHeading())
        if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        error?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.announce(urgent = true)) }
        for (k in listOf("first_name", "last_name", "birth_date", "street", "postal_code", "city", "email", "phone")) {
            OutlinedTextField(
                value = values[k] ?: "",
                onValueChange = { values = values + (k to it) },
                label = { Text(if (k == "birth_date") "Geburtsdatum (TT.MM.JJJJ)" else FIELD_LABELS[k] ?: k) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(
                    keyboardType = when (k) {
                        "email" -> KeyboardType.Email
                        "phone" -> KeyboardType.Phone
                        "postal_code", "birth_date" -> KeyboardType.Number
                        else -> KeyboardType.Text
                    },
                ),
                modifier = Modifier.fillMaxWidth(),
            )
        }
        ChoicePicker("Geschlecht", GENDERS, values["gender"] ?: "", { genderLabel(it) }) { values = values + ("gender" to it) }
        ChoicePicker("Mitgliedsart", types.map { it to it.name }, type, { it.name }) { type = it }
        if (units.isNotEmpty()) ChoicePicker("Untereinheit (optional)", listOf<DepartmentItem?>(null).map { it to "keine" } + units.map { it to it.label }, unit, { it?.label ?: "keine" }) { unit = it }
        DateInput("Eintritt am", values["entry_date"] ?: "") { values = values + ("entry_date" to it) }
        Button(
            enabled = !busy,
            onClick = {
                val birth = parseGermanDate(values["birth_date"] ?: "")
                error = when {
                    values["first_name"].isNullOrBlank() || values["last_name"].isNullOrBlank() -> "Bitte Vor- und Nachname angeben."
                    birth == null -> "Bitte das Geburtsdatum als TT.MM.JJJJ eingeben."
                    listOf("street", "postal_code", "city").any { values[it].isNullOrBlank() } -> "Bitte die vollständige Anschrift angeben."
                    type == null -> "Bitte eine Mitgliedsart wählen."
                    parseGermanDate(values["entry_date"] ?: "") == null -> "Bitte das Eintrittsdatum als TT.MM.JJJJ eingeben."
                    else -> null
                }
                if (error == null && birth != null) {
                    // Erst nach möglichen Dubletten suchen (gleicher Name und Geburtstag)
                    scope.launch {
                        busy = true
                        try {
                            val found = client.duplicates(values["last_name"]!!, values["first_name"]!!, birth)
                            if (found.isEmpty()) admit(null) else duplicates = found
                        } catch (e: ApiException) {
                            error = errors.message(e)
                        }
                        busy = false
                    }
                }
            },
            modifier = Modifier.fillMaxWidth(),
        ) { Text("Aufnehmen") }
    }

    duplicates?.let { found ->
        AlertDialog(
            onDismissRequest = { duplicates = null },
            title = { Text("Gibt es die Person schon?") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    found.forEach { p ->
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                            Column(Modifier.weight(1f)) {
                                Text("${p.firstName} ${p.lastName}")
                                Text(
                                    formatPlainDate(p.birthDate) + if (p.alreadyMemberHere) " · schon Mitglied hier" else "",
                                    style = MaterialTheme.typography.bodySmall,
                                )
                            }
                            if (!p.alreadyMemberHere) {
                                TextButton(onClick = {
                                    duplicates = null
                                    admit(p.id)
                                }) { Text("Diese") }
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    duplicates = null
                    admit(null)
                }) { Text("Neue Person anlegen") }
            },
            dismissButton = { TextButton(onClick = { duplicates = null }) { Text("Abbrechen") } },
        )
    }
}
