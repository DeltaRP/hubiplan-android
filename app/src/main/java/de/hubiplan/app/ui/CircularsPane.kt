package de.hubiplan.app.ui

import android.content.Intent
import android.net.Uri
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import de.hubiplan.app.data.ApiException
import de.hubiplan.app.data.CircularItem
import de.hubiplan.app.data.PortalAccess
import de.hubiplan.app.tr

/** Rundschreiben und Mitteilungen der Einheit (wie „Rundschreiben“ im Mitgliederbereich). */
@Composable
fun CircularsPane(access: PortalAccess, vm: AppViewModel) {
    var items by remember(access.id) { mutableStateOf<List<CircularItem>?>(null) }
    var error by remember(access.id) { mutableStateOf<String?>(null) }
    var selected by rememberSaveable(access.id) { mutableStateOf<Int?>(null) }
    val canSend by vm.canSend.collectAsStateWithLifecycle()
    var notice by remember { mutableStateOf<String?>(null) }
    var reload by remember { mutableStateOf(0) }
    val openRequest by vm.openRequest.collectAsStateWithLifecycle()
    LaunchedEffect(openRequest, access.id) {
        val request = openRequest ?: return@LaunchedEffect
        if (request.first == access.id) {
            selected = request.second
            vm.openHandled()
        }
    }

    LaunchedEffect(access.id, reload) {
        val api = vm.api() ?: return@LaunchedEffect
        error = null
        try {
            items = api.circulars(access.id)
        } catch (e: ApiException) {
            if (e.status == 401) vm.deviceRevoked(e.message) else error = e.message
        }
    }

    ListDetail(
        hasDetail = selected != null,
        onBack = { selected = null },
        emptyDetail = tr("Mitteilung auswählen"),
        list = {
            val list = items
            Column(Modifier.fillMaxSize()) {
                if (canSend) {
                    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Button(onClick = { selected = SEND }) { Text(tr("Kurzmitteilung senden")) }
                    }
                }
                notice?.let { Text(it, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(horizontal = 16.dp)) }
                when {
                    // Fehler mit erneutem Versuch statt leerer Liste (UX-Audit 31.10. A07)
                    error != null -> Column(Modifier.padding(16.dp)) {
                        Text(error ?: "", color = MaterialTheme.colorScheme.error, modifier = Modifier.announce(urgent = true))
                        TextButton(onClick = { reload++ }) { Text(tr("Erneut laden")) }
                    }
                    list == null -> Loading()
                    list.isEmpty() -> Message(tr("Noch keine Mitteilungen."))
                    else -> LazyColumn(Modifier.fillMaxSize()) {
                        items(list, key = { it.id }) { c ->
                            ListItem(
                                modifier = Modifier.clickable { selected = c.id },
                                colors = if (c.id == selected) {
                                    ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.primaryContainer)
                                } else {
                                    ListItemDefaults.colors()
                                },
                                overlineContent = { Text(listOf(c.kindLabel, formatDate(c.sentAt)).filter { it.isNotBlank() }.joinToString(" · ")) },
                                headlineContent = { Text(c.subject.ifBlank { tr("(ohne Betreff)") }) },
                                supportingContent = { if (c.sender.isNotBlank()) Text(c.sender) },
                            )
                            HorizontalDivider()
                        }
                    }
                }
            }
        },
        detail = {
            when (val id = selected) {
                null -> {}
                SEND -> SendPane(
                    vm,
                    onDone = { text ->
                        notice = text
                        selected = null
                        reload++
                    },
                    onCancel = { selected = null },
                )
                else -> CircularDetail(access, id, vm) { selected = null }
            }
        },
    )
}

// Statt einer Nummer: Kurzmitteilung verfassen (Vorstand, Zugführung)
private const val SEND = -1

@Composable
private fun CircularDetail(access: PortalAccess, id: Int, vm: AppViewModel, onBack: () -> Unit) {
    var item by remember(id) { mutableStateOf<CircularItem?>(null) }
    var error by remember(id) { mutableStateOf<String?>(null) }
    var attempt by remember(id) { mutableStateOf(0) }
    val context = LocalContext.current
    LaunchedEffect(access.id, id, attempt) {
        val api = vm.api() ?: return@LaunchedEffect
        error = null
        try {
            item = api.circular(access.id, id)
        } catch (e: ApiException) {
            if (e.status == 401) vm.deviceRevoked(e.message) else error = e.message
        }
    }
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        TextButton(onClick = onBack) { Text(tr("‹ Zurück")) }
        error?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.announce(urgent = true)) }
        val c = item
        if (c == null) {
            if (error == null) Loading() else TextButton(onClick = { attempt++ }) { Text(tr("Erneut laden")) }
            return@Column
        }
        Text(c.subject, style = MaterialTheme.typography.headlineSmall, modifier = Modifier.asHeading())
        Text(listOf(c.sender, formatDate(c.sentAt)).filter { it.isNotBlank() }.joinToString(" · "), style = MaterialTheme.typography.bodySmall)
        // Einfache Auszeichnung (**fett**, Listen) vorerst als Text; Anhänge im Browser
        Text(c.body.orEmpty())
        // Anhänge und Gestaltung gibt es im Mitgliederbereich im Browser (UX-Audit 31.10. A06)
        val server = (vm.screen.value as? Screen.Main)?.account?.server
        if (!server.isNullOrBlank()) {
            OutlinedButton(onClick = {
                runCatching {
                    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(server.trimEnd('/') + "/mein-bereich/rundschreiben")))
                }
            }) { Text(tr("Mit Anhängen im Browser öffnen")) }
        }
    }
}
