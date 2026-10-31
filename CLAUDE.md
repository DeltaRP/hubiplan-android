# Hinweise für die Arbeit an hubiplan-android

Feste Regeln von Ralf (gelten wie im Hauptprojekt `hubiplan`):

- **Nie Tests auf dem Testserver ausführen.** Gebaut und getestet wird lokal auf Ralfs PC
  mit `scripts/test.sh`, bevorzugt unter Windows in Git Bash (dort doppelt so schnell wie in
  WSL mit dem Klon auf E:; gemessen 30.10.2026: 2:12 gegen 4:11 min für einen vollen Bau).
- **Höchstens zwei Testläufe gleichzeitig, jeder höchstens 8 Kerne**, projektübergreifend für
  alle Projekte auf Ralfs PC: `scripts/test.sh` holt sich einen Platz mit `testplatz`
  (aus `hubiplan/scripts/testplatz.sh`, siehe `hubiplan/docs/testserver.md`).
- Temporäres auf Laufwerk E: (in WSL `/mnt/e/…`).
- Keine Passwörter, Signaturschlüssel oder Geheimnisse ins Repo oder in Chats.
- Nur erfundene Daten in Tests und Beispielen.
- CI (GitHub Actions) nur von Hand (workflow_dispatch); keine automatischen Auslöser.
- Sprache in Oberfläche, Doku und Commits: Deutsch.
- Variante `foss` ohne jede Google-Bibliothek (F-Droid); Google-Dienste nur in `play`.
- Die App spricht nur mit der HUBIplan-Instanz des Mitglieds (`/api/app/v1/`, `/api/portal/…`),
  Beschreibung in `hubiplan/docs/app.md`; Konzept `konzepte/android-app.md` im Projektordner.
- Cloud-Sitzungen erreichen das Android-SDK nicht (dl.google.com gesperrt): Code dort schreiben,
  bauen und testen auf Ralfs PC.
