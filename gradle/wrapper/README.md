# Gradle-Wrapper

`gradlew`, `gradlew.bat` und `gradle-wrapper.jar` sind nicht Teil von HUBIplan, sondern
werden von Gradle selbst erzeugt (`gradle wrapper`, Gradle 9.8.2). Sie stehen unter der
**Apache License 2.0**, Copyright © 2015 the original authors (Gradle); der Volltext liegt in
[LICENSE-Apache-2.0.txt](LICENSE-Apache-2.0.txt). Die Lizenzköpfe in `gradlew` und
`gradlew.bat` bleiben unverändert.

- `gradle-wrapper.jar`, SHA-256 `3b8a25775a69158b5ad2b1d17a80a88dc7b40a352a73eb27d06c612a7ce68e98`
  (Stand 30.10.2026). Prüfen lässt sich die Datei gegen die Prüfsummen auf
  https://gradle.org/release-checksums/.
- Die eigentliche Gradle-Verteilung lädt der Wrapper beim ersten Aufruf von
  `services.gradle.org` (`gradle-wrapper.properties`); sie liegt nicht im Repository.

Lizenzaudit 31.10.2026, L09.
