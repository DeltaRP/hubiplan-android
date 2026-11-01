package de.hubiplan.app.ui

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
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
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import de.hubiplan.app.data.AdminChangeRequest
import de.hubiplan.app.data.AdminClient
import de.hubiplan.app.data.ApiException
import de.hubiplan.app.data.BirthdayRow
import de.hubiplan.app.data.CardCheck
import de.hubiplan.app.data.Changed
import de.hubiplan.app.data.DepartmentItem
import de.hubiplan.app.data.JubileeRow
import de.hubiplan.app.data.MemberDetail
import de.hubiplan.app.data.MemberRow
import de.hubiplan.app.data.MemberTypeItem
import de.hubiplan.app.data.Preview
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import de.hubiplan.app.tr

const val MEMBERS_EDIT = "members.edit"
const val PHOTOS_MANAGE = "photos.manage"

/** Nach einer Änderung: Hinweis mit „Rückgängig“ zeigen. */
typealias OnChanged = (text: String, changeSet: String?) -> Unit

/** Fehler der Verwaltung: 401 heißt gesperrt (neu entsperren/freischalten). */
class AdminErrors(val onLocked: () -> Unit) {
    fun message(e: ApiException): String {
        if (e.status == 401) onLocked()
        return e.message ?: tr("Fehler")
    }
}

private fun today(): LocalDate = LocalDate.now()
private val INPUT_DATE: DateTimeFormatter = DateTimeFormatter.ofPattern("dd.MM.yyyy")
private fun todayText(): String = today().format(INPUT_DATE)

@Composable
fun RemoteImage(path: String?, load: suspend (String) -> ByteArray?, description: String, modifier: Modifier) {
    var bitmap by remember(path) { mutableStateOf<Bitmap?>(null) }
    LaunchedEffect(path) {
        bitmap = path?.let { p ->
            load(p)?.let { bytes -> withContext(Dispatchers.Default) { BitmapFactory.decodeByteArray(bytes, 0, bytes.size) } }
        }
    }
    bitmap?.let { Image(it.asImageBitmap(), contentDescription = description, contentScale = ContentScale.Crop, modifier = modifier) }
}

// --- Mitgliederliste ---

@Composable
fun MemberList(
    client: AdminClient,
    errors: AdminErrors,
    onAdmit: (() -> Unit)?,
    initialSearch: String = "",
    onSearch: (String) -> Unit = {},
    onOpen: (Int) -> Unit,
) {
    // Suchbegriff bleibt nach dem Öffnen einer Akte erhalten (UX-Audit 31.10. A02)
    var search by remember { mutableStateOf(initialSearch) }
    var former by remember { mutableStateOf(false) }
    var rows by remember { mutableStateOf<List<MemberRow>>(emptyList()) }
    var page by remember { mutableIntStateOf(1) }
    var hasMore by remember { mutableStateOf(false) }
    var count by remember { mutableIntStateOf(0) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(search, former, page) {
        if (page == 1) delay(300) // erst nach einer kurzen Tipp-Pause suchen
        loading = true
        try {
            val result = client.members(search, page, if (former) "ehemalig" else "aktiv")
            rows = if (page == 1) result.results else rows + result.results
            hasMore = result.next != null
            count = result.count
            error = null
        } catch (e: ApiException) {
            error = errors.message(e)
        }
        loading = false
    }
    Column(Modifier.fillMaxSize()) {
        OutlinedTextField(
            value = search,
            onValueChange = {
                search = it
                onSearch(it)
                page = 1
            },
            label = { Text(tr("Suchen (Name, Nummer, E-Mail)")) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        )
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(if (loading && page == 1) tr("Lädt …") else "$count gefunden", style = MaterialTheme.typography.bodySmall)
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.toggleable(value = former, role = Role.Switch, onValueChange = {
                    former = it
                    page = 1
                }),
            ) {
                Text(tr("Ehemalige"), style = MaterialTheme.typography.bodySmall)
                Switch(checked = former, onCheckedChange = null, modifier = Modifier.padding(start = 8.dp))
            }
        }
        onAdmit?.let { admit ->
            TextButton(onClick = admit, modifier = Modifier.padding(horizontal = 8.dp)) { Text(tr("+ Neues Mitglied")) }
        }
        if (loading) LinearProgressIndicator(Modifier.fillMaxWidth())
        error?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.announce(urgent = true).padding(16.dp)) }
        LazyColumn(Modifier.fillMaxSize()) {
            items(rows, key = { it.id }) { row ->
                ListItem(
                    headlineContent = { Text("${row.lastName}, ${row.firstName}") },
                    supportingContent = {
                        Text(listOfNotNull(row.memberType, row.departments.joinToString(", ").ifBlank { null }, row.city.ifBlank { null }).joinToString(" · "))
                    },
                    modifier = Modifier.clickable { onOpen(row.id) },
                )
                HorizontalDivider()
            }
            if (hasMore) {
                item {
                    TextButton(onClick = { page += 1 }, enabled = !loading, modifier = Modifier.padding(16.dp)) { Text(tr("Weitere laden")) }
                }
            }
        }
    }
}

// --- Mitgliedsakte ---

private sealed interface Dialog {
    data object Edit : Dialog
    data object ChangeType : Dialog
    data object Move : Dialog
    data object Exit : Dialog
    data object Reentry : Dialog
    data class Confirm(val preview: Preview, val action: suspend () -> Changed<MemberDetail>) : Dialog
}

@Composable
fun MemberScreen(
    client: AdminClient,
    permissions: List<String>,
    id: Int,
    errors: AdminErrors,
    onBack: () -> Unit,
    onChanged: OnChanged,
    reload: Int,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var detail by remember(id) { mutableStateOf<MemberDetail?>(null) }
    var error by remember(id) { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var dialog by remember { mutableStateOf<Dialog?>(null) }
    var dialogError by remember { mutableStateOf<String?>(null) }
    var attempt by remember(id) { mutableIntStateOf(0) }
    val canEdit = MEMBERS_EDIT in permissions
    LaunchedEffect(id, reload, attempt) {
        try {
            detail = client.member(id)
            error = null
        } catch (e: ApiException) {
            error = errors.message(e)
        }
    }

    fun applyChange(change: Changed<MemberDetail>, text: String) {
        detail = change.value
        onChanged(text, change.changeSet)
    }

    fun launchChange(block: suspend () -> Unit) {
        scope.launch {
            busy = true
            try {
                block()
                error = null
            } catch (e: ApiException) {
                error = errors.message(e)
            }
            busy = false
        }
    }

    /** Folgen zeigen, nach Bestätigung ausführen. */
    fun confirm(values: Map<String, Any?>, action: suspend () -> Changed<MemberDetail>) {
        launchChange {
            val preview = client.preview(id, de.hubiplan.app.data.obj(*values.toList().toTypedArray()))
            dialog = Dialog.Confirm(preview, action)
        }
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        TextButton(onClick = onBack) { Text(tr("‹ Zurück")) }
        if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        error?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.announce(urgent = true)) }
        // Erstes Laden sichtbar machen, Fehler mit erneutem Versuch (UX-Audit 31.10. A07)
        val d = detail ?: run {
            if (error == null) {
                if (!busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            } else {
                TextButton(onClick = { attempt++ }) { Text(tr("Erneut laden")) }
            }
            return@Column
        }
        val todayIso = today().toString()
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            RemoteImage(d.photo?.approved?.thumbnailUrl, client::image, tr("Foto"), Modifier.width(84.dp).height(108.dp))
            Column {
                Text(d.name, style = MaterialTheme.typography.headlineSmall, modifier = Modifier.asHeading())
                Text(
                    listOfNotNull(
                        d.currentType?.memberTypeName,
                        if (d.status == "former") "ehemalig" else null,
                        d.clubNumber.ifBlank { null }?.let { "Nr. $it" },
                    ).joinToString(" · "),
                )
                d.currentDepartments(todayIso).takeIf { it.isNotEmpty() }?.let { list ->
                    Text(list.joinToString(", ") { it.departmentName }, style = MaterialTheme.typography.bodySmall)
                }
                d.birthDate?.let { Text(tr("geboren {date}", "date" to formatPlainDate(it)), style = MaterialTheme.typography.bodySmall) }
            }
        }
        // Kontakt: Anrufen, Mail, Karte
        d.contact?.let { c ->
            if (c.phone.isNotBlank()) {
                ListItem(
                    headlineContent = { Text(c.phone) },
                    overlineContent = { Text(tr("Telefon")) },
                    modifier = Modifier.clickable { context.startActivity(Intent(Intent.ACTION_DIAL, Uri.parse("tel:${c.phone}"))) },
                )
            }
            if (c.email.isNotBlank()) {
                ListItem(
                    headlineContent = { Text(c.email) },
                    overlineContent = { Text("E-Mail") },
                    modifier = Modifier.clickable { context.startActivity(Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:${c.email}"))) },
                )
            }
            val address = listOf(c.street, "${c.postalCode} ${c.city}".trim()).filter { it.isNotBlank() }.joinToString(", ")
            if (address.isNotBlank()) {
                ListItem(
                    headlineContent = { Text(address) },
                    overlineContent = { Text(tr("Anschrift")) },
                    modifier = Modifier.clickable {
                        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("geo:0,0?q=" + Uri.encode(address))))
                    },
                )
            }
        }
        // Vom Mitglied hochgeladenes Foto freigeben
        d.photo?.pending?.let { pending ->
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(tr("Neues Foto vom Mitglied"), style = MaterialTheme.typography.titleSmall, modifier = Modifier.asHeading())
                    RemoteImage(pending.url, client::image, tr("Neues Foto"), Modifier.width(140.dp).height(180.dp))
                    if (PHOTOS_MANAGE in permissions) {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(onClick = {
                                launchChange {
                                    val r = client.decidePhoto(id, true)
                                    detail = client.member(id)
                                    onChanged(tr("Foto freigegeben"), r.changeSet)
                                }
                            }) { Text(tr("Freigeben")) }
                            OutlinedButton(onClick = {
                                launchChange {
                                    val r = client.decidePhoto(id, false)
                                    detail = client.member(id)
                                    onChanged(tr("Foto abgelehnt"), r.changeSet)
                                }
                            }) { Text(tr("Ablehnen")) }
                        }
                    } else {
                        Text(tr("Freigeben darf, wer Fotos verwalten darf."), style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
        if (canEdit && d.status != "former") {
            HorizontalDivider()
            Text(tr("Bearbeiten"), style = MaterialTheme.typography.titleMedium, modifier = Modifier.asHeading())
            OutlinedButton(onClick = { dialog = Dialog.Edit }, modifier = Modifier.fillMaxWidth()) { Text(tr("Stammdaten und Kontakt")) }
            OutlinedButton(onClick = { dialog = Dialog.ChangeType }, modifier = Modifier.fillMaxWidth()) { Text(tr("Mitgliedsart wechseln")) }
            OutlinedButton(onClick = { dialog = Dialog.Move }, modifier = Modifier.fillMaxWidth()) { Text(tr("Untereinheit wechseln")) }
            LabeledSwitch(tr("Zahlt Beitrag selbst"), d.paysFeeSelf, { value ->
                confirm(mapOf("action" to "fee_self", "pays_fee_self" to value)) {
                    client.update(id, mapOf("pays_fee_self" to JsonPrimitive(value)))
                }
            })
            OutlinedButton(onClick = { dialog = Dialog.Exit }, modifier = Modifier.fillMaxWidth()) {
                Text(tr("Austritt eintragen"), color = MaterialTheme.colorScheme.error)
            }
        }
        if (canEdit && d.status == "former") {
            HorizontalDivider()
            OutlinedButton(onClick = { dialog = Dialog.Reentry }, modifier = Modifier.fillMaxWidth()) { Text(tr("Wiedereintritt eintragen")) }
        }
        AwardsSection(client, id, canEdit, errors, onChanged, reload)
        FieldsButton(client, id, canEdit, errors, onChanged, reload)
        BankSection(client, d, permissions, errors, onChanged, reload)
    }

    val d = detail
    when (val current = dialog) {
        null -> {}
        // Dialog bleibt offen, bis der Server bestätigt; bei Fehler bleiben die Eingaben (UX-Audit 31.10. A03)
        Dialog.Edit -> if (d != null) EditDialog(d, busy, dialogError, onDismiss = { dialog = null; dialogError = null }) { values ->
            scope.launch {
                busy = true
                dialogError = null
                try {
                    applyChange(client.update(id, values.mapValues { JsonPrimitive(it.value) }), tr("Stammdaten gespeichert"))
                    dialog = null
                } catch (e: ApiException) {
                    dialogError = errors.message(e)
                }
                busy = false
            }
        }
        Dialog.ChangeType -> if (d != null) ChangeTypeDialog(client, d, errors, onDismiss = { dialog = null }) { type, from ->
            dialog = null
            confirm(mapOf("action" to "change_type", "member_type" to type.id, "valid_from" to from)) {
                client.changeType(id, type.id, from)
            }
        }
        Dialog.Move -> if (d != null) MoveDialog(client, d, errors, onDismiss = { dialog = null }) { dept, from ->
            dialog = null
            confirm(mapOf("action" to "move", "department" to dept.id, "valid_from" to from)) {
                client.move(id, dept.id, from)
            }
        }
        Dialog.Exit -> if (d != null) ExitDialog(d, onDismiss = { dialog = null }) { date, reason, note ->
            dialog = null
            confirm(mapOf("action" to "exit", "exit_date" to date, "exit_reason" to reason)) {
                client.exit(id, date, reason, note)
            }
        }
        Dialog.Reentry -> if (d != null) ReentryDialog(client, d, errors, onDismiss = { dialog = null }) { type, from ->
            dialog = null
            val preview = Preview(
                tr("Wiedereintritt"),
                listOf(tr("Ab {x1} wieder Mitglied als {name}.", "x1" to formatPlainDate(from), "name" to type.name), tr("Frühere Zeiten und Nummern bleiben erhalten.")),
            )
            dialog = Dialog.Confirm(preview) { client.reentry(id, from, type.id) }
        }
        is Dialog.Confirm -> ConfirmDialog(current.preview, onDismiss = { dialog = null }) {
            dialog = null
            launchChange { applyChange(current.action(), current.preview.title.ifBlank { tr("Gespeichert") }) }
        }
    }
}

@Composable
private fun ConfirmDialog(preview: Preview, onDismiss: () -> Unit, onConfirm: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(preview.title.ifBlank { tr("Änderung bestätigen") }) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(tr("Das passiert:"), style = MaterialTheme.typography.labelLarge)
                preview.consequences.forEach { Text("• $it") }
                Text(tr("Danach lässt sich die Änderung rückgängig machen."), style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = { Button(onClick = onConfirm) { Text(tr("Bestätigen")) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(tr("Abbrechen")) } },
    )
}

@Composable
private fun EditDialog(d: MemberDetail, saving: Boolean, saveError: String?, onDismiss: () -> Unit, onSave: (Map<String, String>) -> Unit) {
    val before = remember(d) {
        mapOf(
            "first_name" to d.firstName,
            "last_name" to d.lastName,
            "birth_date" to (d.birthDate ?: ""),
            "gender" to d.gender,
            "club_number" to d.clubNumber,
            "email" to (d.contact?.email ?: ""),
            "phone" to (d.contact?.phone ?: ""),
            "street" to (d.contact?.street ?: ""),
            "postal_code" to (d.contact?.postalCode ?: ""),
            "city" to (d.contact?.city ?: ""),
            "country" to (d.contact?.country ?: ""),
        )
    }
    var values by remember(d) {
        mutableStateOf(before + ("birth_date" to formatPlainDate(d.birthDate)))
    }
    var error by remember { mutableStateOf<String?>(null) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(tr("Stammdaten und Kontakt")) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                (error ?: saveError)?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.announce(urgent = true)) }
                if (saving) LinearProgressIndicator(Modifier.fillMaxWidth())
                for (key in listOf("first_name", "last_name", "birth_date", "club_number", "email", "phone", "street", "postal_code", "city", "country")) {
                    OutlinedTextField(
                        value = values[key] ?: "",
                        onValueChange = { values = values + (key to it) },
                        label = { Text(if (key == "birth_date") tr("Geburtsdatum (TT.MM.JJJJ)") else FIELD_LABELS[key] ?: key) },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(
                            keyboardType = when (key) {
                                "email" -> KeyboardType.Email
                                "phone" -> KeyboardType.Phone
                                "postal_code" -> KeyboardType.Number
                                else -> KeyboardType.Text
                            },
                        ),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                Picker(tr("Geschlecht"), GENDERS, values["gender"] ?: "", { genderLabel(it) }) { values = values + ("gender" to it) }
            }
        },
        confirmButton = {
            Button(enabled = !saving, onClick = {
                error = null
                val changes = masterDataChanges(before, values)
                when {
                    changes == null -> error = tr("Bitte das Geburtsdatum als TT.MM.JJJJ eingeben.")
                    changes.isEmpty() -> onDismiss()
                    else -> onSave(changes)
                }
            }) { Text(tr("Speichern")) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(tr("Abbrechen")) } },
    )
}

/** Auswahl aus einer kurzen Liste (Wert, Beschriftung). */
@Composable
private fun <T> Picker(label: String, options: List<Pair<T, String>>, selected: T?, show: (T) -> String, onPick: (T) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Column {
        Text(label, style = MaterialTheme.typography.labelMedium)
        Box {
            OutlinedButton(
                onClick = { open = true },
                modifier = Modifier.fillMaxWidth().dropdown(label, selected?.let(show) ?: tr("nichts gewählt"), open),
            ) {
                Text((selected?.let(show) ?: tr("Bitte wählen")) + " ▾")
            }
            DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                options.forEach { (value, text) ->
                    DropdownMenuItem(text = { Text(text) }, onClick = {
                        open = false
                        onPick(value)
                    })
                }
            }
        }
    }
}

@Composable
private fun DateField(label: String, value: String, onChange: (String) -> Unit) {
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
private fun ChangeTypeDialog(
    client: AdminClient,
    d: MemberDetail,
    errors: AdminErrors,
    onDismiss: () -> Unit,
    onNext: (MemberTypeItem, String) -> Unit,
) {
    var types by remember { mutableStateOf<List<MemberTypeItem>>(emptyList()) }
    var chosen by remember { mutableStateOf<MemberTypeItem?>(null) }
    var from by remember { mutableStateOf(todayText()) }
    var error by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) {
        try {
            types = client.memberTypes().filter { it.id != d.currentType?.memberType }
        } catch (e: ApiException) {
            error = errors.message(e)
        }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(tr("Mitgliedsart wechseln")) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(tr("Bisher: {x1}", "x1" to d.currentType?.memberTypeName ?: "keine"))
                error?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.announce(urgent = true)) }
                Picker(tr("Neue Mitgliedsart"), types.map { it to it.name }, chosen, { it.name }) { chosen = it }
                DateField(tr("Ab"), from) { from = it }
            }
        },
        confirmButton = {
            Button(onClick = {
                val iso = parseGermanDate(from)
                val type = chosen
                when {
                    type == null -> error = tr("Bitte eine Mitgliedsart wählen.")
                    iso == null -> error = tr("Bitte das Datum als TT.MM.JJJJ eingeben.")
                    else -> onNext(type, iso)
                }
            }) { Text(tr("Weiter")) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(tr("Abbrechen")) } },
    )
}

@Composable
private fun MoveDialog(
    client: AdminClient,
    d: MemberDetail,
    errors: AdminErrors,
    onDismiss: () -> Unit,
    onNext: (DepartmentItem, String) -> Unit,
) {
    val currentIds = d.currentDepartments(today().toString()).map { it.department }.toSet()
    var units by remember { mutableStateOf<List<DepartmentItem>>(emptyList()) }
    var chosen by remember { mutableStateOf<DepartmentItem?>(null) }
    var from by remember { mutableStateOf(todayText()) }
    var error by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) {
        try {
            units = client.departments().filter { it.id !in currentIds }
        } catch (e: ApiException) {
            error = errors.message(e)
        }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(tr("Untereinheit wechseln")) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(tr("Bisher: ") + d.currentDepartments(today().toString()).joinToString(", ") { it.departmentName }.ifBlank { "keine" })
                error?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.announce(urgent = true)) }
                Picker(tr("Neu"), units.map { it to it.label }, chosen, { it.label }) { chosen = it }
                DateField(tr("Ab"), from) { from = it }
            }
        },
        confirmButton = {
            Button(onClick = {
                val iso = parseGermanDate(from)
                val unit = chosen
                when {
                    unit == null -> error = tr("Bitte eine Untereinheit wählen.")
                    iso == null -> error = tr("Bitte das Datum als TT.MM.JJJJ eingeben.")
                    else -> onNext(unit, iso)
                }
            }) { Text(tr("Weiter")) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(tr("Abbrechen")) } },
    )
}

@Composable
private fun ExitDialog(d: MemberDetail, onDismiss: () -> Unit, onNext: (String, String, String) -> Unit) {
    var date by remember { mutableStateOf(todayText()) }
    var reason by remember { mutableStateOf<String?>(null) }
    var note by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(tr("Austritt von {name}", "name" to d.name)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                error?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.announce(urgent = true)) }
                DateField(tr("Austritt zum"), date) { date = it }
                Picker(tr("Grund"), d.exitReasons.map { it.value to it.label }, reason, { v -> d.exitReasons.first { it.value == v }.label }) { reason = it }
                OutlinedTextField(value = note, onValueChange = { note = it.take(200) }, label = { Text(tr("Notiz (optional)")) }, modifier = Modifier.fillMaxWidth())
            }
        },
        confirmButton = {
            Button(onClick = {
                val iso = parseGermanDate(date)
                val r = reason
                when {
                    iso == null -> error = tr("Bitte das Datum als TT.MM.JJJJ eingeben.")
                    r == null -> error = tr("Bitte einen Grund wählen.")
                    else -> onNext(iso, r, note.trim())
                }
            }) { Text(tr("Weiter")) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(tr("Abbrechen")) } },
    )
}

// --- Änderungsmeldungen ---

@Composable
fun ChangeRequestsScreen(client: AdminClient, errors: AdminErrors, onBack: () -> Unit, onOpen: (Int) -> Unit, onChanged: OnChanged) {
    val scope = rememberCoroutineScope()
    var items by remember { mutableStateOf<List<AdminChangeRequest>?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var conflict by remember { mutableStateOf<Pair<AdminChangeRequest, String>?>(null) }
    var reload by remember { mutableIntStateOf(0) }
    LaunchedEffect(reload) {
        try {
            items = client.changeRequests()
            error = null
        } catch (e: ApiException) {
            error = errors.message(e)
        }
    }

    fun decide(cr: AdminChangeRequest, accept: Boolean, overwrite: Boolean = false) {
        scope.launch {
            try {
                val r = client.decide(cr.id, accept, "", overwrite)
                onChanged(if (accept) tr("Änderung von {memberName} übernommen", "memberName" to cr.memberName) else tr("Änderung von {memberName} abgelehnt", "memberName" to cr.memberName), r.changeSet)
                reload++
            } catch (e: ApiException) {
                if (e.status == 409) conflict = cr to (e.message ?: "") else error = errors.message(e)
            }
        }
    }

    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item { TextButton(onClick = onBack) { Text(tr("‹ Zurück")) } }
        item { Text(tr("Änderungsmeldungen"), style = MaterialTheme.typography.titleMedium, modifier = Modifier.asHeading()) }
        error?.let { item { Text(it, color = MaterialTheme.colorScheme.error) } }
        val list = items
        if (list == null) {
            item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
        } else if (list.isEmpty()) {
            item { Text(tr("Keine offenen Meldungen.")) }
        }
        items(list ?: emptyList(), key = { it.id }) { cr ->
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(cr.memberName, style = MaterialTheme.typography.titleSmall, modifier = Modifier.clickable { onOpen(cr.memberId) })
                    cr.createdAt?.let { Text(formatDate(it), style = MaterialTheme.typography.bodySmall) }
                    cr.data.forEach { (key, value) ->
                        val new = (value as? JsonPrimitive)?.contentOrNull
                        val old = (cr.previous?.get(key) as? JsonPrimitive)?.contentOrNull
                        Text("${FIELD_LABELS[key] ?: key}: ${displayValue(key, old)} → ${displayValue(key, new)}")
                    }
                    if (cr.message.isNotBlank()) Text("„${cr.message}“", style = MaterialTheme.typography.bodySmall)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { decide(cr, true) }) { Text(tr("Übernehmen")) }
                        OutlinedButton(onClick = { decide(cr, false) }) { Text(tr("Ablehnen")) }
                    }
                }
            }
        }
    }
    conflict?.let { (cr, text) ->
        AlertDialog(
            onDismissRequest = { conflict = null },
            title = { Text(tr("Inzwischen geändert")) },
            text = { Text(text) },
            confirmButton = {
                Button(onClick = {
                    conflict = null
                    decide(cr, true, overwrite = true)
                }) { Text(tr("Trotzdem übernehmen")) }
            },
            dismissButton = { TextButton(onClick = { conflict = null }) { Text(tr("Abbrechen")) } },
        )
    }
}

// --- Geburtstage und Jubiläen ---

@Composable
fun OccasionsSection(client: AdminClient, errors: AdminErrors, onOpen: (Int) -> Unit) {
    var birthdays by remember { mutableStateOf<List<BirthdayRow>?>(null) }
    var jubilees by remember { mutableStateOf<List<JubileeRow>?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(client.unitId) {
        val (from, to) = upcomingRange(today(), 30)
        val (_, jubileeTo) = upcomingRange(today(), 90)
        try {
            birthdays = client.birthdays(from, to)
            jubilees = client.jubilees(from, jubileeTo)
        } catch (e: ApiException) {
            error = errors.message(e)
        }
    }
    LazyColumn(Modifier.fillMaxSize()) {
        error?.let { item { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(16.dp)) } }
        item { Header(tr("Geburtstage (30 Tage)")) }
        val b = birthdays
        if (b == null && error == null) item { LinearProgressIndicator(Modifier.fillMaxWidth().padding(16.dp)) }
        if (b != null && b.isEmpty()) item { Text(tr("Keine."), modifier = Modifier.padding(horizontal = 16.dp)) }
        items(b ?: emptyList()) { row ->
            ListItem(
                headlineContent = { Text("${row.firstName} ${row.lastName}") },
                supportingContent = { Text(tr("{x1} · wird {age}", "x1" to formatPlainDate(row.date), "age" to row.age)) },
                modifier = Modifier.clickable { onOpen(row.memberId) },
            )
        }
        item { Header(tr("Jubiläen (90 Tage)")) }
        val j = jubilees
        if (j != null && j.isEmpty()) item { Text(tr("Keine."), modifier = Modifier.padding(horizontal = 16.dp)) }
        items(j ?: emptyList()) { row ->
            ListItem(
                headlineContent = { Text("${row.firstName} ${row.lastName}") },
                supportingContent = { Text(tr("{date} · {years} Jahre {organization}", "date" to formatPlainDate(row.date), "years" to row.years, "organization" to row.organization).trim()) },
                modifier = Modifier.clickable { onOpen(row.memberId) },
            )
        }
    }
}

@Composable
private fun Header(text: String) {
    Text(text, style = MaterialTheme.typography.titleMedium, modifier = Modifier.asHeading().padding(start = 16.dp, top = 16.dp, end = 16.dp, bottom = 4.dp))
}

// --- Ausweis prüfen ---

@Composable
fun CardResultDialog(check: CardCheck, loadPublic: suspend (String) -> ByteArray?, onOpen: (Int) -> Unit, onDismiss: () -> Unit) {
    val valid = check.status == "active"
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                if (valid) "✓ ${check.statusLabel}" else "✗ ${check.statusLabel}",
                color = if (valid) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                RemoteImage(check.photoUrl, loadPublic, tr("Foto"), Modifier.width(140.dp).height(180.dp))
                listOfNotNull(check.firstName, check.lastName).joinToString(" ").takeIf { it.isNotBlank() }?.let {
                    Text(it, style = MaterialTheme.typography.titleLarge)
                }
                listOfNotNull(check.memberType, check.club).joinToString(" · ").takeIf { it.isNotBlank() }?.let { Text(it) }
                if (check.departments.isNotEmpty()) Text(check.departments.joinToString(", "), style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(tr("Schließen")) } },
        dismissButton = {
            val id = check.membership
            if (id != null) TextButton(onClick = { onOpen(id) }) { Text(tr("Akte öffnen")) }
        },
    )
}
