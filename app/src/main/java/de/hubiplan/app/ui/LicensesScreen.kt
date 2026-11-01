package de.hubiplan.app.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import de.hubiplan.app.BuildConfig
import de.hubiplan.app.data.Licenses

const val SOURCE_URL = "https://github.com/hubiplan/hubiplan-android"

private fun Context.asset(path: String): String? =
    runCatching { assets.open(path).bufferedReader().use { it.readText() } }.getOrNull()

/**
 * Lizenzen der App und aller enthaltenen Bibliotheken (Lizenzaudit 31.10.2026, L01).
 * Erreichbar ohne Anmeldung (Startseite) und unter Konto.
 */
@Composable
fun LicensesDialog(onClose: () -> Unit) {
    val context = LocalContext.current
    val libraries = remember { Licenses.parse(context.asset(Licenses.LIBRARIES_ASSET).orEmpty()) }
    // Angezeigter Volltext: Titel und Inhalt
    var shown by remember { mutableStateOf<Pair<String, String>?>(null) }
    fun open(url: String) = context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))

    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize()) {
            val text = shown
            if (text != null) {
                Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = { shown = null }) { Text("‹ Zurück zu den Lizenzen") }
                    Text(text.first, style = MaterialTheme.typography.titleMedium, modifier = Modifier.asHeading())
                    Text(
                        text.second,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 11.sp,
                        lineHeight = 14.sp,
                        modifier = Modifier.weight(1f).verticalScroll(rememberScrollState())
                            .horizontalScroll(rememberScrollState()),
                    )
                }
                return@Surface
            }
            Column(
                Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text("Lizenzen", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.asHeading())
                Text(
                    "HUBIplan-App ${BuildConfig.VERSION_NAME}, Copyright © 2026 Ralf Philipsen (Philipsen EDV). " +
                        "Freie Software unter der GNU Affero General Public License, Version 3 oder neuer. " +
                        "Sie wird ohne jede Gewährleistung bereitgestellt.",
                )
                TextButton(onClick = {
                    shown = "GNU Affero General Public License 3.0" to
                        (context.asset(Licenses.APP_LICENSE_ASSET) ?: "Text nicht gefunden: https://www.gnu.org/licenses/agpl-3.0.txt")
                }) { Text("Lizenztext der App (AGPL)") }
                TextButton(onClick = { open(SOURCE_URL) }) { Text("Quellcode") }

                HorizontalDivider()
                Text("Enthaltene Bibliotheken", style = MaterialTheme.typography.titleMedium, modifier = Modifier.asHeading())
                if (libraries.isEmpty()) Text("Die Liste fehlt in dieser Fassung der App.")
                for ((id, list) in Licenses.byLicense(libraries)) {
                    val info = Licenses.KNOWN[id]
                    Text(info?.title ?: id, style = MaterialTheme.typography.titleSmall, modifier = Modifier.asHeading())
                    if (info != null) {
                        val licenseText = info.asset?.let { context.asset(it) }
                        if (licenseText != null) {
                            TextButton(onClick = { shown = info.title to licenseText }) { Text("Lizenztext anzeigen") }
                        } else {
                            TextButton(onClick = { open(info.url) }) { Text("Bedingungen im Browser öffnen") }
                        }
                    }
                    Text(
                        list.joinToString("\n") { it.coordinate },
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                    )
                }

                HorizontalDivider()
                Text("Hinweise der Bibliotheken", style = MaterialTheme.typography.titleMedium, modifier = Modifier.asHeading())
                for ((name, notice) in Licenses.NOTICES) {
                    Text(name, style = MaterialTheme.typography.titleSmall)
                    Text(notice, style = MaterialTheme.typography.bodySmall)
                }
                Licenses.KNOWN["MPL-2.0"]?.let { mpl ->
                    TextButton(onClick = {
                        shown = mpl.title to (mpl.asset?.let { context.asset(it) } ?: mpl.url)
                    }) { Text("Lizenztext MPL 2.0 anzeigen") }
                }
                TextButton(onClick = onClose) { Text("Schließen") }
            }
        }
    }
}
