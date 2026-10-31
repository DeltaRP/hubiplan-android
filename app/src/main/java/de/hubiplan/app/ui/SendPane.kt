package de.hubiplan.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import de.hubiplan.app.data.ApiException
import de.hubiplan.app.data.Sender
import kotlinx.coroutines.launch

/** Vorstand oder Zugführung: Kurzmitteilung an alle Mitglieder der Einheit. */
@Composable
fun SendPane(vm: AppViewModel, onDone: (String) -> Unit, onCancel: () -> Unit) {
    val scope = rememberCoroutineScope()
    var senders by remember { mutableStateOf<List<Sender>?>(null) }
    var maxBody by remember { mutableStateOf(500) }
    var senderKey by rememberSaveable { mutableStateOf<String?>(null) }
    var subject by rememberSaveable { mutableStateOf("") }
    var body by rememberSaveable { mutableStateOf("") }
    var pick by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var preview by remember { mutableStateOf<Map<String, Int>?>(null) }

    LaunchedEffect(Unit) {
        val api = vm.api() ?: return@LaunchedEffect
        try {
            val data = api.senders()
            senders = data.senders
            maxBody = data.maxBody
            if (senderKey == null) senderKey = data.senders.firstOrNull()?.key
        } catch (e: ApiException) {
            if (e.status == 401) vm.deviceRevoked(e.message) else error = e.message
        }
    }
    val sender = senders?.firstOrNull { it.key == senderKey }

    fun call(confirm: Boolean) {
        val api = vm.api() ?: return
        val s = sender ?: return
        busy = true
        error = null
        scope.launch {
            try {
                val result = api.sendShort(s, subject.trim(), body.trim(), confirm)
                if (confirm) {
                    preview = null
                    onDone("Kurzmitteilung an ${s.name} ist unterwegs.")
                } else {
                    preview = result.counts
                }
            } catch (e: ApiException) {
                if (e.status == 401) vm.deviceRevoked(e.message) else error = e.message
            } finally {
                busy = false
            }
        }
    }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        TextButton(onClick = onCancel) { Text("‹ Zurück") }
        Text("Kurzmitteilung senden", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.asHeading())
        val list = senders
        when {
            list == null && error == null -> Loading()
            list != null && list.isEmpty() -> Text("Für dieses Konto gibt es keine Einheit, an die du senden darfst.")
        }
        if (!list.isNullOrEmpty()) {
            Box {
                val senderName = sender?.let { s -> s.name + if (s.club.isNotBlank()) " (${s.club})" else "" } ?: "–"
                OutlinedButton(
                    onClick = { pick = list.size > 1 },
                    modifier = if (list.size > 1) Modifier.dropdown("An", senderName, pick) else Modifier,
                ) {
                    Text("An: " + (sender?.let { s -> s.name + if (s.club.isNotBlank()) " (${s.club})" else "" } ?: "–") + if (list.size > 1) " ▾" else "")
                }
                DropdownMenu(expanded = pick, onDismissRequest = { pick = false }) {
                    list.forEach { s ->
                        DropdownMenuItem(
                            text = { Text(s.name + if (s.club.isNotBlank()) " (${s.club})" else "") },
                            onClick = {
                                pick = false
                                senderKey = s.key
                            },
                        )
                    }
                }
            }
            Text("Geht an alle Mitglieder dieser Einheit: per App, sonst per E-Mail.", style = MaterialTheme.typography.bodySmall)
            OutlinedTextField(
                value = subject,
                onValueChange = { subject = it.take(200) },
                label = { Text("Betreff") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = body,
                onValueChange = { body = it.take(maxBody) },
                label = { Text("Text") },
                supportingText = { Text("${body.length} von $maxBody Zeichen") },
                modifier = Modifier.fillMaxWidth().heightIn(min = 140.dp),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = { call(confirm = false) },
                    enabled = !busy && sender != null && subject.isNotBlank() && body.isNotBlank(),
                ) { Text("Weiter") }
            }
        }
        error?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.announce(urgent = true)) }
    }

    preview?.let { counts ->
        AlertDialog(
            onDismissRequest = { preview = null },
            title = { Text("Kurzmitteilung senden?") },
            text = { Text("„${subject.trim()}“ ${recipientSummary(counts)}. Gesendete Mitteilungen lassen sich nicht zurückholen.") },
            confirmButton = {
                TextButton(onClick = { call(confirm = true) }, enabled = !busy && recipientTotal(counts) > 0) { Text("Jetzt senden") }
            },
            dismissButton = { TextButton(onClick = { preview = null }) { Text("Abbrechen") } },
        )
    }
}
