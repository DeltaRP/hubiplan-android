# Sicherheit

Die HUBIplan-App greift auf Mitgliederdaten, Bankverbindungen und die Kasse eines Vereins
zu und speichert Teile davon auf dem Telefon. Hinweise auf Sicherheitslücken nehmen wir deshalb sehr ernst und sind für jede
Meldung dankbar.

*Reports in English are welcome.*

## Lücke melden

Bitte **kein öffentliches Issue**, sondern vertraulich über GitHub:
[Report a vulnerability](https://github.com/DeltaRP/hubiplan-android/security/advisories/new)
(im Repository unter **Security › Report a vulnerability**).

Hilfreich sind:

- betroffene Fassung (Version oder Commit, Variante `foss` oder `play`), Android-Version
  und betroffener Teil (z. B. Bildschirm, Speicher, Push),
- Schritte zum Nachvollziehen, am besten gegen ein eigenes HUBIplan mit Demodaten
  (`seed_testdata`),
- eingeschätzte Auswirkung: Wer kann was sehen oder ändern, was er nicht dürfte?

## Was dann passiert

- Wir bestätigen den Eingang in der Regel innerhalb von 7 Tagen.
- Wir prüfen die Meldung, halten dich auf dem Laufenden und stimmen eine Korrektur mit dir ab.
- Die Korrektur erscheint zuerst; Einzelheiten veröffentlichen wir danach als
  Sicherheitshinweis (GitHub Security Advisory), auf Wunsch mit Nennung deines Namens.
- Bitte gib uns bis zur Korrektur, höchstens 90 Tage ab Meldung, Zeit, bevor du Einzelheiten
  veröffentlichst.

## Unterstützte Fassungen

Korrekturen gibt es für die jeweils neueste veröffentlichte Version und den aktuellen Stand
von `main`; es gibt keine Release-Zweige.

## Umfang

Gemeint ist der Code in diesem Repository, beide Varianten. Besonders interessant sind:

- Zugriff anderer Apps auf Daten, Geräteschlüssel oder Entwürfe der App,
- unverschlüsselt gespeicherte Daten (Mitgliedsausweis, Kassenentwürfe, Belegfotos),
- Umgehung der Sperre des Reiters „Verwaltung“ (Fingerabdruck, PIN, Bildschirmfotos),
- Schwächen bei Verbinden per QR-Code, Push-Entschlüsselung oder Zertifikatsprüfung,
- Verbindungen zu anderen Diensten als der HUBIplan-Instanz (außer Push in `play`).

Liegt der Fehler in der Schnittstelle von HUBIplan, bitte
[dort melden](https://github.com/DeltaRP/hubiplan/security/advisories/new).

Nicht gemeint sind Lücken in Fremdsoftware (bitte dort melden), reine Last- oder
Überlastungsangriffe, Social Engineering sowie Befunde ohne Auswirkung (z. B. Befunde, die ein
entsperrtes Telefon mit Root-Rechten voraussetzen).

## Regeln fürs Prüfen

- Prüfe **nur gegen eine eigene HUBIplan-Installation** mit erfundenen Daten, z. B. mit
  `docker compose up` und `seed_testdata` (siehe
  [hubiplan](https://github.com/DeltaRP/hubiplan#ausprobieren-mit-docker)).
- Keine Angriffe auf fremde HUBIplan-Instanzen oder echte Vereinsdaten, keine
  Überlastungsangriffe.
- Stößt du doch auf echte personenbezogene Daten, greife nicht weiter darauf zu, speichere
  nichts und melde es uns.

Wer sich an diese Regeln hält und in gutem Glauben handelt, hat von uns keine rechtlichen
Schritte zu erwarten.
