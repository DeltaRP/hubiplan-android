package de.hubiplan.app.ui

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import de.hubiplan.app.BuildConfig
import kotlinx.coroutines.launch
import de.hubiplan.app.tr

@Composable
fun AccountPane(main: Screen.Main, vm: AppViewModel) {
    val context = LocalContext.current
    fun open(url: String) = context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
    var deleteInfo by remember { mutableStateOf(false) }
    var licenses by remember { mutableStateOf(false) }
    // Vor dem Abmelden warnen, wenn Buchungen oder Quittungen noch nicht gesendet sind (A14, F07)
    var pendingWarning by remember { mutableIntStateOf(0) }
    var sendingPending by remember { mutableStateOf(false) }
    var pendingError by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    fun askLogout() {
        scope.launch {
            val pending = vm.pendingLocalItems()
            pendingError = null
            if (pending > 0) pendingWarning = pending else vm.logout()
        }
    }
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(main.account.display.ifBlank { main.account.email }, style = MaterialTheme.typography.headlineSmall, modifier = Modifier.asHeading())
        Text(main.account.email)
        Text(tr("Verbunden mit {x1}", "x1" to main.account.server.removePrefix("https://")), style = MaterialTheme.typography.bodySmall)
        TextButton(onClick = { open("${main.account.server}/mein-konto") }) { Text(tr("Mein Konto im Browser öffnen")) }
        main.access?.let { a ->
            val key = a.imprintKey?.let { "/$it" } ?: ""
            TextButton(onClick = { open("${main.account.server}/datenschutz$key") }) { Text(tr("Datenschutzhinweise")) }
            TextButton(onClick = { open("${main.account.server}/impressum$key") }) { Text(tr("Impressum")) }
            Text(tr("Angemeldet als Mitglied von {title}", "title" to a.title), style = MaterialTheme.typography.bodySmall)
        }
        ShortcutSection(main, vm)
        androidx.compose.material3.HorizontalDivider()
        AccountsSection(main, vm)
        androidx.compose.material3.HorizontalDivider()
        SettingsSection(vm)
        androidx.compose.material3.HorizontalDivider()
        OutlinedButton(onClick = { askLogout() }) { Text(tr("Dieses Konto vom Gerät abmelden")) }
        // Google Play verlangt einen Weg zum Löschen von Konto und Daten in der App
        TextButton(onClick = { deleteInfo = true }) { Text(tr("Konto und Daten löschen")) }
        Text(
            tr("HUBIplan-App {VERSION_NAME} · freie Software unter der GNU AGPL 3.0 oder neuer", "VERSION_NAME" to BuildConfig.VERSION_NAME),
            style = MaterialTheme.typography.bodySmall,
        )
        TextButton(onClick = { licenses = true }) { Text(tr("Lizenzen")) }
        TextButton(onClick = { open(SOURCE_URL) }) { Text(tr("Quellcode")) }
    }

    if (licenses) LicensesDialog(onClose = { licenses = false })

    if (deleteInfo) {
        val key = main.access?.imprintKey?.let { "/$it" } ?: ""
        AlertDialog(
            onDismissRequest = { deleteInfo = false },
            title = { Text(tr("Konto und Daten löschen")) },
            text = {
                Text(
                    tr("Deine Mitgliedsdaten verwaltet dein Verein in seiner HUBIplan-Instanz ({x1}), nicht die App.\n\n• Abmelden löscht den Geräteschlüssel dieses Telefons beim Verein und alle Daten dieses Kontos in der App, auch die eingetragenen Kalendertermine und noch nicht gesendete Buchungen und Quittungen der Verwaltung.\n• Dein Foto kannst du unter Profil selbst löschen.\n• Die Löschung deines Zugangs und deiner Mitgliedsdaten beantragst du beim Verein. Wen du ansprichst, steht in seinen Datenschutzhinweisen.", "x1" to main.account.server.removePrefix("https://")),
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    deleteInfo = false
                    open("${main.account.server}/datenschutz$key")
                }) { Text(tr("Datenschutzhinweise öffnen")) }
            },
            dismissButton = {
                TextButton(onClick = {
                    deleteInfo = false
                    askLogout()
                }) { Text(tr("Jetzt abmelden")) }
            },
        )
    }

    if (pendingWarning > 0) {
        // Fachfrage 8 (31.10.b): erst senden oder bewusst verwerfen; behalten ohne Konto gibt es nicht
        val n = pendingWarning
        AlertDialog(
            onDismissRequest = { if (!sendingPending) pendingWarning = 0 },
            title = {
                Text(
                    if (n == 1) tr("1 Buchung oder Quittung noch nicht gesendet")
                    else tr("{n} Buchungen oder Quittungen noch nicht gesendet", "n" to n),
                )
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        tr("Auf diesem Telefon liegen noch nicht gesendete Vorgänge der Kasse. Erst senden oder verwerfen? Beim Abmelden ohne Senden werden sie gelöscht und lassen sich nicht wiederherstellen."),
                    )
                    pendingError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                }
            },
            confirmButton = {
                TextButton(
                    enabled = !sendingPending,
                    onClick = {
                        scope.launch {
                            sendingPending = true
                            pendingError = vm.sendPendingBeforeLogout()
                            sendingPending = false
                            if (pendingError == null) {
                                pendingWarning = 0
                                vm.logout()
                            }
                        }
                    },
                ) { Text(if (sendingPending) tr("Wird gesendet …") else tr("Erst senden, dann abmelden")) }
            },
            dismissButton = {
                Row {
                    TextButton(enabled = !sendingPending, onClick = {
                        pendingWarning = 0
                        pendingError = null
                    }) { Text(tr("Abbrechen")) }
                    TextButton(enabled = !sendingPending, onClick = {
                        pendingWarning = 0
                        vm.logout()
                    }) { Text(tr("Verwerfen und abmelden"), color = MaterialTheme.colorScheme.error) }
                }
            },
        )
    }
}

/** Verknüpfung mit Wappen und Namen der gewählten Einheit auf dem Startbildschirm. */
@Composable
private fun ShortcutSection(main: Screen.Main, vm: AppViewModel) {
    val context = LocalContext.current
    val access = main.access ?: return
    if (!remember { de.hubiplan.app.shortcut.ClubShortcut.supported(context) }) return
    var unsupported by remember { mutableStateOf(false) }
    androidx.compose.material3.HorizontalDivider()
    Text(tr("Startbildschirm"), style = MaterialTheme.typography.titleMedium, modifier = Modifier.asHeading())
    Text(
        tr("Lege „{title}“ mit Wappen als Verknüpfung auf den Startbildschirm. Ein Tipp darauf öffnet die App direkt bei diesem Konto. Name und Wappen passen sich an, wenn der Verein sie ändert.", "title" to access.title),
        style = MaterialTheme.typography.bodySmall,
    )
    OutlinedButton(onClick = { unsupported = !vm.pinShortcut() }) { Text(tr("Auf den Startbildschirm legen")) }
    if (unsupported) {
        Text(tr("Dein Startbildschirm unterstützt keine Verknüpfungen."), color = MaterialTheme.colorScheme.error)
    }
}

/** Weitere Konten (andere HUBIplan-Adressen): wechseln oder hinzufügen. */
@Composable
private fun AccountsSection(main: Screen.Main, vm: AppViewModel) {
    val list by vm.accountList.collectAsStateWithLifecycle()
    val active by vm.activeSlot.collectAsStateWithLifecycle()
    Text(tr("Konten"), style = MaterialTheme.typography.titleMedium, modifier = Modifier.asHeading())
    val others = list.filter { it.first != active }
    if (others.isEmpty()) {
        Text(
            tr("Bist du auch in einem Verein mit eigener HUBIplan-Adresse, kannst du ihn hier als weiteres Konto verbinden."),
            style = MaterialTheme.typography.bodySmall,
        )
    }
    others.forEach { (slot, account) ->
        TextButton(onClick = { vm.switchAccount(slot) }) {
            Text(tr("Wechseln zu {x1} · {x2}", "x1" to account.display.ifBlank { account.email }, "x2" to account.server.removePrefix("https://")))
        }
    }
    OutlinedButton(onClick = vm::addAccount) { Text(tr("Weiteres Konto verbinden")) }
}
