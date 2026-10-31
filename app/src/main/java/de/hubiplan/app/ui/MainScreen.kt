package de.hubiplan.app.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.VerticalDivider
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteScaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.unit.dp

private enum class Tab(val label: String) {
    TERMINE("Termine"), MITTEILUNGEN("Mitteilungen"), PROFIL("Profil"), VERWALTUNG("Verwaltung"), KONTO("Konto"),
}

/** Ab dieser Breite stehen Liste und Detail nebeneinander (Tablet, Querformat). */
val TWO_PANE_MIN_WIDTH = 720.dp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(main: Screen.Main, vm: AppViewModel) {
    var tab by rememberSaveable { mutableStateOf(Tab.TERMINE) }
    val openRequest by vm.openRequest.collectAsStateWithLifecycle()
    LaunchedEffect(openRequest) { if (openRequest != null) tab = Tab.MITTEILUNGEN }
    // „Verwaltung“ nur für Konten mit Verwaltungsrechten (Schritt A8)
    val admin by vm.admin.collectAsStateWithLifecycle()
    val tabs = Tab.entries.filter { it != Tab.VERWALTUNG || admin.visible }
    LaunchedEffect(admin.visible) { if (tab == Tab.VERWALTUNG && !admin.visible) tab = Tab.TERMINE }
    // Navigationsleiste unten auf dem Telefon, seitlich auf dem Tablet (passt sich selbst an)
    NavigationSuiteScaffold(
        navigationSuiteItems = {
            tabs.forEach { t ->
                item(
                    selected = tab == t,
                    onClick = { tab = t },
                    icon = {
                        Icon(
                            when (t) {
                                Tab.TERMINE -> Icons.Filled.DateRange
                                Tab.MITTEILUNGEN -> Icons.Filled.Email
                                Tab.PROFIL -> Icons.Filled.Person
                                Tab.VERWALTUNG -> Icons.Filled.Build
                                Tab.KONTO -> Icons.Filled.Settings
                            },
                            contentDescription = null,
                        )
                    },
                    label = { Text(t.label) },
                )
            }
        },
    ) {
        Column(Modifier.fillMaxSize()) {
            TopAppBar(
                // In der Verwaltung nicht den persönlichen Zugang als Kopf zeigen; welche Einheit
                // verwaltet wird, steht darunter (UX-Audit 31.10. A04)
                title = { if (tab == Tab.VERWALTUNG) Text("Verwaltung") else AccessPicker(main, vm) },
                // Wappen der gewählten Einheit vor dem Namen (Schmuck; der Name steht daneben)
                navigationIcon = {
                    val logo by vm.logo.collectAsStateWithLifecycle()
                    val image = remember(logo) { logo?.asImageBitmap() }
                    if (image != null && tab != Tab.VERWALTUNG) {
                        Image(image, contentDescription = null, modifier = Modifier.padding(start = 12.dp).size(36.dp))
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = LocalBrand.current.brand,
                    titleContentColor = LocalBrand.current.onBrand,
                    actionIconContentColor = LocalBrand.current.onBrand,
                ),
            )
            if (main.offline) {
                Text(
                    "Keine Verbindung zu HUBIplan. Bitte später erneut versuchen.",
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.announce().padding(16.dp),
                )
                TextButton(onClick = vm::reload, modifier = Modifier.padding(horizontal = 8.dp)) { Text("Erneut laden") }
            }
            val access = main.access
            val offer by vm.shortcutOffer.collectAsStateWithLifecycle()
            if (offer && access != null && tab == Tab.TERMINE) ShortcutOffer(access.title, vm)
            Box(Modifier.fillMaxSize()) {
                when {
                    tab == Tab.KONTO -> AccountPane(main, vm)
                    tab == Tab.VERWALTUNG -> AdminPane(main, vm)
                    main.me == null && !main.offline -> Loading()
                    access == null && main.me != null -> Message(
                        "Für dieses Konto ist kein Mitgliederbereich freigeschaltet. Bitte wende dich an den Vorstand.",
                    )
                    access != null && tab == Tab.TERMINE -> EventsPane(access, vm)
                    access != null && tab == Tab.MITTEILUNGEN -> CircularsPane(access, vm)
                    access != null && tab == Tab.PROFIL -> ProfilePane(access, main, vm)
                    // Ohne Netz: wenigstens den gespeicherten Ausweis zeigen
                    main.offline && tab == Tab.PROFIL -> OfflineCardPane(main)
                    // Ohne Netz beim Kaltstart: Hinweis statt leerer Fläche, dazu wartende
                    // Kassenvorgänge dieses Telefons (UX-Audit 31.10. A15, F03)
                    main.offline -> OfflinePane(main, events = tab == Tab.TERMINE)
                }
            }
        }
    }
}

/** Einmaliges Angebot nach dem Verbinden: Verknüpfung mit Wappen und Namen anlegen. */
@Composable
private fun ShortcutOffer(name: String, vm: AppViewModel) {
    Card(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("„$name“ auf den Startbildschirm legen?", style = MaterialTheme.typography.titleSmall)
            Text(
                "Eine Verknüpfung mit Wappen und Namen öffnet die App direkt hier. Im App-Menü heißt die App weiter HUBIplan.",
                style = MaterialTheme.typography.bodySmall,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = { vm.pinShortcut() }) { Text("Ja, ablegen") }
                TextButton(onClick = vm::dismissShortcutOffer) { Text("Nein, danke") }
            }
        }
    }
}

@Composable
private fun AccessPicker(main: Screen.Main, vm: AppViewModel) {
    val accesses = main.me?.portal.orEmpty()
    val current = main.access
    var open by rememberSaveable { mutableStateOf(false) }
    if (accesses.size <= 1) {
        Text(current?.title ?: "HUBIplan")
        return
    }
    Box {
        TextButton(onClick = { open = true }, modifier = Modifier.dropdown("Einheit", current?.title ?: "keine gewählt", open)) {
            Text((current?.title ?: "Einheit wählen") + " ▾", color = LocalBrand.current.onBrand)
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            accesses.forEach { a ->
                DropdownMenuItem(text = { Text(a.title) }, onClick = {
                    open = false
                    vm.selectAccess(a)
                })
            }
        }
    }
}

/**
 * Liste und Detail: auf schmalen Bildschirmen nacheinander, auf breiten nebeneinander.
 * [detail] ist null, solange nichts gewählt ist.
 */
@Composable
fun ListDetail(
    hasDetail: Boolean,
    onBack: () -> Unit,
    list: @Composable () -> Unit,
    detail: @Composable () -> Unit,
    emptyDetail: String,
) {
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val boxWidth = maxWidth
        if (boxWidth >= TWO_PANE_MIN_WIDTH) {
            Row(Modifier.fillMaxSize()) {
                Box(Modifier.width(boxWidth * 0.4f).fillMaxHeight()) { list() }
                VerticalDivider()
                Box(Modifier.fillMaxSize()) {
                    if (hasDetail) detail() else Message(emptyDetail)
                }
            }
        } else {
            if (hasDetail) {
                androidx.activity.compose.BackHandler(onBack = onBack)
                detail()
            } else {
                list()
            }
        }
    }
}

@Composable
fun Message(text: String) {
    Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
        Text(text, style = MaterialTheme.typography.bodyLarge)
    }
}

@Composable
fun Loading() {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        androidx.compose.material3.CircularProgressIndicator(
            Modifier.semantics { contentDescription = "Lädt" }.announce(),
        )
    }
}

@Composable
private fun OfflineCardPane(main: Screen.Main) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val store = remember(main.account.server, main.account.deviceId) {
        de.hubiplan.app.data.CardStore(context, de.hubiplan.app.data.CardStore.key(main.account.server, main.account.deviceId))
    }
    val accessId = main.account.selectedAccess
    Column(
        Modifier.fillMaxSize().verticalScroll(androidx.compose.foundation.rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (accessId == null) {
            Text("Ohne Netz ist noch kein Ausweis gespeichert.")
        } else {
            MemberCardSection(store, accessId, null)
        }
    }
}

@Composable
private fun OfflinePane(main: Screen.Main, events: Boolean) {
    Column(
        Modifier.fillMaxSize().verticalScroll(androidx.compose.foundation.rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            if (events) {
                "Ohne Verbindung lassen sich die Termine nicht laden. Ist der Abgleich mit dem Gerätekalender " +
                    "eingeschaltet, stehen sie dort."
            } else {
                "Ohne Verbindung lassen sich die Mitteilungen nicht laden. Sie erscheinen, sobald wieder Verbindung besteht."
            },
        )
        PendingLocalCard(main)
    }
}

/** Noch nicht gesendete Buchungen und Quittungen dieses Kontos, auch ohne Netz sichtbar (F03). */
@Composable
fun PendingLocalCard(main: Screen.Main) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val key = de.hubiplan.app.data.LocalAccountData.key(main.account.server, main.account.deviceId)
    var lines by remember(key) { mutableStateOf<List<String>>(emptyList()) }
    LaunchedEffect(key) {
        lines = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            runCatching {
                de.hubiplan.app.data.DraftStore(context, key).list().map {
                    "Buchung ${formatPlainDate(it.date)} · ${formatMoney(it.amount)} · ${it.text}" +
                        (it.unitName.takeIf { n -> n.isNotBlank() }?.let { n -> " ($n)" } ?: "")
                } + de.hubiplan.app.data.ReceiptStore(context, key).list().map {
                    "Quittung ${formatMoney(it.amount)} · ${it.counterpartyName}"
                }
            }.getOrDefault(emptyList())
        }
    }
    if (lines.isEmpty()) return
    androidx.compose.material3.Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                "Kasse: noch nicht gesendet (${lines.size})",
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.asHeading(),
            )
            Text(
                "Liegen verschlüsselt auf diesem Telefon. Sie werden gesendet, sobald Verbindung besteht und " +
                    "du die Verwaltung öffnest.",
                style = MaterialTheme.typography.bodySmall,
            )
            lines.forEach { Text(it) }
        }
    }
}
