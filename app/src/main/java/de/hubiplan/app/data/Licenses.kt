package de.hubiplan.app.data

/**
 * Lizenzangaben für die Lizenzseite der App (Lizenzaudit 31.10.2026, L01).
 *
 * `lizenzen/bibliotheken.tsv` erzeugt der Bau aus dem tatsächlichen Abhängigkeitsgraphen der
 * Variante (app/build.gradle.kts, LizenzlisteTask): je Zeile „Gruppe:Name:Version“, Tab, Lizenz.
 */
data class Library(val group: String, val name: String, val version: String, val license: String) {
    val coordinate get() = "$group:$name:$version"
}

data class LicenseInfo(val title: String, val asset: String?, val url: String)

object Licenses {
    const val LIBRARIES_ASSET = "lizenzen/bibliotheken.tsv"
    const val APP_LICENSE_ASSET = "lizenzen/LICENSE.txt"

    val KNOWN = mapOf(
        "Apache-2.0" to LicenseInfo(
            "Apache License 2.0",
            "lizenzen/Apache-2.0.txt",
            "https://www.apache.org/licenses/LICENSE-2.0",
        ),
        "MPL-2.0" to LicenseInfo(
            "Mozilla Public License 2.0",
            "lizenzen/MPL-2.0.txt",
            "https://mozilla.org/MPL/2.0/",
        ),
        // Proprietär, nur in der Variante play (Google-Push); der Text gehört Google
        "Android-SDK" to LicenseInfo(
            "Android Software Development Kit License (Google)",
            null,
            "https://developer.android.com/studio/terms",
        ),
    )

    /**
     * Hinweise, die Bibliotheken über ihre Lizenz hinaus verlangen. OkHttp enthält die
     * Public Suffix List unter der MPL 2.0 (NOTICE in okhttp3/internal/publicsuffix).
     */
    val NOTICES = listOf(
        "OkHttp" to "publicsuffixes.gz is compiled from The Public Suffix List:\n" +
            "https://publicsuffix.org/list/public_suffix_list.dat\n\n" +
            "It is subject to the terms of the Mozilla Public License, v. 2.0:\n" +
            "https://mozilla.org/MPL/2.0/",
    )

    fun parse(tsv: String): List<Library> = tsv.lineSequence()
        .map { it.trim() }
        .filter { it.isNotEmpty() }
        .mapNotNull { line ->
            val (coordinate, license) = line.split('\t').takeIf { it.size == 2 } ?: return@mapNotNull null
            val parts = coordinate.split(':')
            if (parts.size != 3) null else Library(parts[0], parts[1], parts[2], license)
        }
        .toList()

    /** Bibliotheken nach Lizenz, bekannte Lizenzen in fester Reihenfolge. */
    fun byLicense(libraries: List<Library>): List<Pair<String, List<Library>>> =
        libraries.groupBy { it.license }
            .toList()
            .sortedBy { (id, _) -> KNOWN.keys.indexOf(id).let { if (it < 0) Int.MAX_VALUE else it } }
            .map { (id, list) -> id to list.sortedBy { it.coordinate } }
}
