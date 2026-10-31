package de.hubiplan.app

import de.hubiplan.app.data.Licenses
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Lizenzaudit 31.10.2026: Lizenzseite der App (L01) und Lizenzbeilagen (L09). */
class LicensesTest {
    @Test
    fun liestDieBibliotheksliste() {
        val libs = Licenses.parse(
            "com.squareup.okhttp3:okhttp:4.12.0\tApache-2.0\n" +
                "com.google.android.gms:play-services-base:18.0.1\tAndroid-SDK\n" +
                "kaputt\n\n" +
                "androidx.core:core:1.15.0\tApache-2.0\n",
        )
        assertEquals(3, libs.size)
        val grouped = Licenses.byLicense(libs)
        assertEquals(listOf("Apache-2.0", "Android-SDK"), grouped.map { it.first })
        assertEquals("androidx.core:core:1.15.0", grouped[0].second.first().coordinate)
    }

    @Test
    fun lizenztexteLiegenBei() {
        // Unit-Tests laufen im Ordner app/
        for ((id, info) in Licenses.KNOWN) {
            val asset = info.asset ?: continue
            val file = File("src/main/assets/$asset")
            assertTrue("Lizenztext fehlt für $id: $file", file.isFile)
        }
        assertTrue(File("src/main/assets/lizenzen/Apache-2.0.txt").readText().contains("Version 2.0, January 2004"))
        assertTrue(File("src/main/assets/lizenzen/MPL-2.0.txt").readText().contains("Mozilla Public License Version 2.0"))
        assertTrue(File("../gradle/wrapper/LICENSE-Apache-2.0.txt").isFile)
        assertNotNull(Licenses.KNOWN["Android-SDK"])
    }
}
