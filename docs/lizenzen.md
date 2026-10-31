# Lizenzen

Die App steht unter der **AGPL-3.0-or-later** ([LICENSE](../LICENSE)). Grundlage dieses
Dokuments ist das Lizenzaudit vom 31.10.2026 (Bericht im Projektordner unter
`audit/lizenzaudit-2026-10-31.md`); es ist keine anwaltliche Prüfung.

## Lizenzseite in der App

„Lizenzen“ steht auf der Startseite (ohne Anmeldung) und unter Konto. Dort:

- AGPL-Text der App (beim Bauen aus `LICENSE` kopiert) und Link zum Quellcode,
- alle Bibliotheken der jeweiligen Variante mit Version und Lizenz, dazu die Lizenztexte
  (`app/src/main/assets/lizenzen/`: Apache 2.0, MPL 2.0),
- Hinweise der Bibliotheken: OkHttp enthält die Public Suffix List (MPL 2.0).

Die Bibliotheksliste schreibt die Gradle-Aufgabe `lizenzliste<Variante>` (in
`app/build.gradle.kts`) beim Bauen aus dem tatsächlichen Laufzeit-Abhängigkeitsgraphen der
Variante nach `lizenzen/bibliotheken.tsv` in die Assets. Die Zuordnung Bibliothek → Lizenz
steht in `LizenzlisteTask.licenseOf`. Kommt eine Bibliothek hinzu, deren Gruppe dort fehlt,
**bricht der Bau ab**: dann den Originaltext der Lizenz in genau dieser Version prüfen und
die Zuordnung ergänzen; für eine neue Lizenzart auch den Volltext in die Assets legen und in
`data/Licenses.kt` eintragen. Verlangt eine Bibliothek eine NOTICE-Datei, deren Text in
`Licenses.NOTICES` aufnehmen.

## Varianten

| Variante | Google | Lizenzlage |
|---|---|---|
| `foss` | keine Google-Dienste; freie Bibliotheken von Google (AndroidX, ZXing, Guava-Teil) unter Apache 2.0 | nur freie Lizenzen (Apache 2.0, MPL 2.0 für die Public Suffix List) |
| `play` | zusätzlich Firebase Messaging (Apache 2.0) | sieben Bausteine unter der proprietären Android-SDK-Lizenz: play-services-base, -basement, -cloud-messaging, -stats, -tasks, firebase-iid-interop, firebase-measurement-connector |

Die Variante `play` wird nicht veröffentlicht (Entscheidung 30.10.2026, siehe
[veroeffentlichen.md](veroeffentlichen.md)). Vor einer Veröffentlichung müsste geklärt
werden, ob und wie die AGPL-App mit diesen Bausteinen weitergegeben werden darf.

## Gradle-Wrapper

`gradlew`, `gradlew.bat` und `gradle/wrapper/gradle-wrapper.jar` stammen von Gradle
(Apache 2.0); Volltext und Herkunft in `gradle/wrapper/`.

## Offen

- **Quellcode:** Der Link führt auf das private Repository. Vor einer Verteilung der App
  (Play Store, F-Droid, APK) muss der passende Quellcode für jede ausgelieferte Version
  frei erreichbar sein (öffentliches Repository mit Tags oder Quellarchiv je Release).
- **Symbole:** Die Material-Symbole stammen aus der Bibliothek material-icons-core (Apache 2.0).
- **Storematerial:** Nur erfundene oder freigegebene Inhalte (keine echten Vereinsdaten).
