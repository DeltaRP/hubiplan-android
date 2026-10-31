# HUBIplan-App für Android

Begleit-App zu [HUBIplan](https://github.com/DeltaRP/hubiplan), der webbasierten
Vereinsverwaltung. Sie ergänzt den Mitgliederbereich im Browser und bringt Termine,
Mitteilungen und die wichtigsten Verwaltungsaufgaben aufs Telefon. Die App spricht nur mit
der HUBIplan-Instanz des Mitglieds, nie mit einem fremden Dienst.

Paketname `de.hubiplan.app`, Version 0.17.0, ab Android 9, für Telefon und Tablet.

## Für Mitglieder

- Termine der eigenen Einheiten (Verein, Untereinheit, weitergegebene Termine des
  Dachverbands), Zu- und Absage mit einem Fingertipp
- Termine im Gerätekalender mit Erinnerungen und Hinweis „Rückmeldung fehlt“
- Mitteilungen und Rundschreiben lesen
- Push ohne Google über eine UnifiedPush-App (z. B. ntfy), Inhalt Ende-zu-Ende
  verschlüsselt (Web-Push, RFC 8291); ohne Push-App holt die App alle 15 Minuten ab
- Mitgliedsausweis mit QR-Code im Reiter Profil, auch ohne Netz (verschlüsselt gespeichert);
  zum Vorzeigen bildschirmfüllend und mit voller Helligkeit
- eigenes Profil ansehen und Änderungen melden (der Verein prüft sie)
- Mitgliederfoto mit der Kamera aufnehmen oder aus der Galerie wählen, mit Einwilligung;
  der Verein gibt es frei, Foto und Einwilligung lassen sich in der App widerrufen
- Farben und Wappen des Vereins aus HUBIplan
- mehrere Konten bei verschiedenen HUBIplan-Instanzen, jedes mit eigenem Geräteschlüssel,
  eigenen Kalendern und eigenem Push

## Für Vorstand, Untereinheiten und Kasse

Der Reiter „Verwaltung“ erscheint nur mit den passenden Rechten. Er wird einmal mit
Passwort und Zwei-Faktor-Code freigeschaltet, danach mit Fingerabdruck oder PIN geöffnet;
Bildschirmfotos sind dort gesperrt.

- Kurzmitteilung an alle Mitglieder der Einheit, mit Empfängerzahl vorab
- offene Aufgaben, Geburtstage und Jubiläen
- Mitglieder suchen, anrufen und bearbeiten (Stammdaten, Mitgliedsart, Untereinheit,
  Beitrag, Austritt, Wiedereintritt, Ehrungen, Zusatzfelder, Bankverbindung) mit
  Bestätigung der Folgen und „Rückgängig“; neue Mitglieder aufnehmen
- Änderungsmeldungen und Fotos freigeben, Mitgliedsausweis per QR-Code prüfen
- Termine mit Zu- und Absagen, Rückmeldung für Mitglieder eintragen, einfache Termine
  anlegen und absagen
- Kasse mit Belegfoto (ohne Netz als verschlüsselter Entwurf)
- offene Rechnungen suchen, Barzahlung verbuchen, Quittung als PDF teilen
- Quittung mit Unterschrift auf dem Display (Gegenseite im Vollbild, ohne Netz speicherbar,
  Belegnummer aus dem Kassenbuch)

## Verbinden

Im Browser unter **Mein Konto › App & andere Instanzen › App auf dem Telefon** auf
„App verbinden“ tippen und den QR-Code in der App scannen. Alternativ mit E-Mail, Passwort
und ggf. Zwei-Faktor-Code anmelden. Die App erhält einen eigenen Geräteschlüssel, der nur
für den Mitgliederbereich gilt und sich jederzeit unter „Mein Konto“ abmelden lässt. Die
Schnittstelle beschreibt `docs/app.md` in hubiplan.

## Varianten

| Variante | Vertrieb | Push |
| --- | --- | --- |
| `foss` | F-Droid, Google Play, APK zum Herunterladen | UnifiedPush oder Abruf; **ohne Google-Dienste** (kein Firebase, keine Play-Dienste) |
| `play` | derzeit nicht veröffentlicht | zusätzlich Firebase Cloud Messaging über das Push-Gateway von HUBIplan |

`scripts/test.sh` prüft bei jedem Lauf, dass `foss` weder Firebase noch Play-Dienste enthält.
Freie Bibliotheken, die von Google stammen (z. B. ZXing, AndroidX), sind erlaubt; sie stehen
unter der Apache-Lizenz.

Lizenzen: Die App zeigt unter „Lizenzen“ (Startseite und Konto) den AGPL-Text und alle
enthaltenen Bibliotheken mit Lizenztext; die Liste entsteht beim Bauen aus dem
Abhängigkeitsgraphen der Variante. Einzelheiten: [docs/lizenzen.md](docs/lizenzen.md).

## Technik

Kotlin, Jetpack Compose mit Material 3, OkHttp, kotlinx.serialization, WorkManager,
DataStore, ZXing (QR-Codes), AndroidX Biometric. Gebaut mit Gradle und dem Android Gradle
Plugin 9, Ziel- und Kompilierversion SDK 37, Mindestversion SDK 26.

```text
app/src/main/java/de/hubiplan/app/
  data/       Schnittstelle zu HUBIplan, Konten, Geräteschlüssel
  ui/         Oberfläche (Compose), Reiter und Verwaltung
  calendar/   Gerätekalender und Erinnerungen
  push/       UnifiedPush und Web-Push-Entschlüsselung
  sync/       Abruf im Hintergrund
  photo/      Kamera und Fotos
app/src/foss/, app/src/play/   Unterschiede der Varianten
app/src/test/                  Unit-Tests
fastlane/metadata/             Texte und Bilder für F-Droid und Play
docs/veroeffentlichen.md       Veröffentlichen
```

## Bauen und testen

Voraussetzungen: JDK 17 oder neuer, Android-SDK (Plattform 37), `ANDROID_HOME` gesetzt.

```bash
scripts/test.sh            # Unit-Tests, Lint, Debug-APKs, Prüfung „foss ohne Google“
./gradlew assembleFossRelease bundlePlayRelease
```

Feste Regeln:

- Gebaut und getestet wird lokal auf Ralfs PC, bevorzugt unter Windows in Git Bash
  (`scripts/test.sh` findet dort das SDK und das JDK von Android Studio selbst). Nie auf
  dem Testserver.
- **Höchstens zwei Testläufe gleichzeitig, jeder höchstens 8 Kerne**, für alle Projekte
  auf Ralfs PC zusammen. `scripts/test.sh` holt sich dafür einen Platz mit `testplatz`
  (aus `hubiplan/scripts/testplatz.sh`).
- Nur erfundene Daten in Tests und Beispielen.
- GitHub Actions läuft nur von Hand (workflow_dispatch), nie automatisch.

Nur auf dem Rechner des Herausgebers, nie im Repository:

- `keystore.properties` (`storeFile`, `storePassword`, `keyAlias`, `keyPassword`):
  Signatur der Veröffentlichung. Ohne die Datei bleibt `release` unsigniert (F-Droid
  signiert selbst).
- `firebase.properties` (`app_id`, `project_id`, `api_key`, `sender_id`): nur für `play`.
  Ohne die Datei hat `play` keinen Google-Push.

## Veröffentlichen

Herausgeber ist Philipsen EDV. F-Droid, Google Play, APK zum Herunterladen und der
Textbaustein für die Datenschutzhinweise des Vereins: [docs/veroeffentlichen.md](docs/veroeffentlichen.md).

## Lizenz

Copyright © 2026 Ralf Philipsen (Philipsen EDV)

Die HUBIplan-App ist freie Software: Sie dürfen sie unter den Bedingungen der **GNU Affero
General Public License**, wie von der Free Software Foundation veröffentlicht, weitergeben
und/oder ändern, entweder gemäß Version 3 der Lizenz oder (nach Ihrer Wahl) jeder späteren
Version (`AGPL-3.0-or-later`), wie HUBIplan selbst.

Die App wird in der Hoffnung verbreitet, dass sie nützlich ist, aber **ohne jede
Gewährleistung**, sogar ohne die implizite Gewährleistung der Marktreife oder der Eignung
für einen bestimmten Zweck. Einzelheiten stehen in der Lizenz. Den vollständigen
Lizenztext (englisch, maßgeblich) enthält die Datei [LICENSE](LICENSE).

### Zusätzliche Erlaubnis für Google-Bibliotheken (AGPL § 7)

Die Variante `play` bindet Bibliotheken von Google ein (Google Play-Dienste, Firebase), von
denen einige nur unter Googles Android-SDK-Lizenz stehen. Damit auch diese Variante
weitergegeben werden darf, erteilt der Rechteinhaber folgende Zusatzerlaubnis. Maßgeblich ist
der englische Wortlaut:

> **Additional permission under GNU AGPL version 3 section 7**
>
> If you modify this Program, or any covered work, by linking or combining it with the
> Google Play services libraries or the Firebase libraries (or modified versions of those
> libraries), containing parts covered by the terms of the Android Software Development Kit
> License Agreement, the licensors of this Program grant you additional permission to convey
> the resulting work. Corresponding Source for a non-source form of such a combination shall
> not include the source code for the parts of those libraries used as well as that of the
> covered work.

Die Erlaubnis gilt nur für diese Google-Bibliotheken. Für den eigenen Code der App bleibt
alles wie in der AGPL: Wer die App weitergibt, muss ihren Quellcode anbieten. Wer die App
verändert, darf diese Zusatzerlaubnis für seine Fassung beibehalten oder streichen
(AGPL § 7 Absatz 3).
