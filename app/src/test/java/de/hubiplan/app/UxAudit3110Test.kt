package de.hubiplan.app

import de.hubiplan.app.data.FinanceCategory
import de.hubiplan.app.ui.WHITE
import de.hubiplan.app.ui.categoriesFor
import de.hubiplan.app.ui.contrast
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** UX-Audit 31.10.2026 (A13, A16). */
class UxAudit3110Test {
    @Test
    fun kategorienFuerBeideArtenErscheinen() {
        val all = listOf(
            FinanceCategory(1, "Spenden", "income"),
            FinanceCategory(2, "Material", "expense"),
            FinanceCategory(3, "Sonstiges", "both"),
            FinanceCategory(4, "Alt", "both", isActive = false),
        )
        assertEquals(listOf("Spenden", "Sonstiges"), categoriesFor(all, "income").map { it.name })
        assertEquals(listOf("Material", "Sonstiges"), categoriesFor(all, "expense").map { it.name })
    }

    @Test
    fun unterschriftHinweisLesbar() {
        assertTrue(contrast(0xFF666666L, WHITE) >= 4.5)
    }
}
