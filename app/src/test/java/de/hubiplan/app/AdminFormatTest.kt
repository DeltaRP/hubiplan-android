package de.hubiplan.app

import de.hubiplan.app.data.MemberDetail
import de.hubiplan.app.data.DepartmentMembership
import de.hubiplan.app.data.adminPath
import de.hubiplan.app.data.json
import de.hubiplan.app.data.obj
import de.hubiplan.app.data.FieldDef
import de.hubiplan.app.data.FieldEntry
import de.hubiplan.app.ui.displayValue
import de.hubiplan.app.ui.fieldChanges
import de.hubiplan.app.ui.fieldText
import de.hubiplan.app.ui.formatIban
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import de.hubiplan.app.ui.masterDataChanges
import de.hubiplan.app.ui.parseGermanDate
import de.hubiplan.app.ui.amountInput
import de.hubiplan.app.ui.cashAmountError
import de.hubiplan.app.ui.receiptFileName
import de.hubiplan.app.data.CashResult
import de.hubiplan.app.ui.eventTimes
import de.hubiplan.app.ui.parseTime
import de.hubiplan.app.ui.responseSummary
import de.hubiplan.app.ui.sameInstant
import java.time.ZoneId
import de.hubiplan.app.data.BookingDraft
import de.hubiplan.app.data.decodeDraft
import de.hubiplan.app.data.encodeDraft
import de.hubiplan.app.ui.formatMoney
import de.hubiplan.app.ui.parseAmount
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class AdminFormatTest {
    @Test
    fun datumEingeben() {
        assertEquals("2026-12-31", parseGermanDate("31.12.2026"))
        assertEquals("2026-02-01", parseGermanDate(" 1.2.2026 "))
        assertEquals("2026-02-01", parseGermanDate("2026-02-01"))
        assertNull(parseGermanDate("30.02.2026"))
        assertNull(parseGermanDate(""))
    }

    @Test
    fun nurGeaenderteStammdaten() {
        val before = mapOf("city" to "Neuss", "birth_date" to "1980-05-17", "phone" to "")
        val after = mapOf("city" to " Neuss ", "birth_date" to "17.05.1980", "phone" to "0211 1")
        assertEquals(mapOf("phone" to "0211 1"), masterDataChanges(before, after))
        assertEquals(mapOf("birth_date" to "1981-05-17"), masterDataChanges(before, after + mapOf("phone" to "", "birth_date" to "17.5.1981")))
        assertNull(masterDataChanges(before, mapOf("birth_date" to "17.13.1980")))
    }

    @Test
    fun werteLesbar() {
        assertEquals("17.05.1980", displayValue("birth_date", "1980-05-17"))
        assertEquals("–", displayValue("city", ""))
    }

    @Test
    fun bildpfadFuerVerwaltung() {
        assertEquals(
            "/api/app/v1/admin/u/7/members/3/photo/?status=pending&v=1",
            adminPath("/api/orgs/7/members/3/photo/?status=pending&v=1", 7),
        )
        // fremde Einheit bleibt unverändert (Server lehnt ab)
        assertEquals("/api/orgs/8/members/3/photo/", adminPath("/api/orgs/8/members/3/photo/", 7))
    }

    @Test
    fun laufendeUntereinheiten() {
        val d = MemberDetail(
            id = 1,
            departments = listOf(
                DepartmentMembership(1, 10, "Jägerzug", "2020-01-01", "2026-10-31"),
                DepartmentMembership(2, 11, "Grenadiere", "2026-11-01", null),
            ),
        )
        assertEquals(listOf("Jägerzug"), d.currentDepartments("2026-10-30").map { it.departmentName })
        assertEquals(listOf("Grenadiere"), d.currentDepartments("2026-11-01").map { it.departmentName })
    }

    @Test
    fun akteLesen() {
        val d = json.decodeFromString(
            MemberDetail.serializer(),
            """{"id":5,"first_name":"Anna","last_name":"Beispiel","current_type":{"id":1,"member_type":2,"member_type_name":"aktiv","valid_from":"2015-01-01"},"photo":{"approved":null,"pending":{"url":"/x","thumbnail_url":"/y"}},"exit_reasons":[{"value":"resignation","label":"Kündigung"}],"unbekannt":1}""",
        )
        assertEquals("Anna Beispiel", d.name)
        assertEquals("aktiv", d.currentType?.memberTypeName)
        assertEquals("/x", d.photo?.pending?.url)
    }

    @Test
    fun jsonBauen() {
        assertEquals("""{"a":1,"b":true,"c":"x","d":null}""", obj("a" to 1, "b" to true, "c" to "x", "d" to null).toString())
    }

    @Test
    fun zusatzfelder() {
        val date = FieldEntry(FieldDef(1, "Taufe", "date"), JsonPrimitive("2001-04-03"))
        val number = FieldEntry(FieldDef(2, "Schuh", "number"), JsonPrimitive(43.5))
        val flag = FieldEntry(FieldDef(3, "Fahnenträger", "bool"), JsonPrimitive(true))
        val list = FieldEntry(FieldDef(4, "Orden", repeatable = true), null)
        assertEquals("03.04.2001", fieldText(date.definition, date.value))
        assertEquals("43,5", fieldText(number.definition, number.value))
        assertEquals("ja", fieldText(flag.definition, flag.value))
        val entries = listOf(date, number, flag, list)
        val unchanged = mapOf(1 to "03.04.2001", 2 to "43,5", 3 to "ja", 4 to "x")
        assertEquals(emptyMap<String, Any>(), fieldChanges(entries, unchanged).first)
        val (changes, fault) = fieldChanges(entries, mapOf(1 to "4.4.2001", 2 to "44", 3 to ""))
        assertNull(fault)
        assertEquals(mapOf("1" to JsonPrimitive("2001-04-04"), "2" to JsonPrimitive("44"), "3" to JsonNull), changes)
        // „nein“ bleibt nein und ist nicht „nicht angegeben“ (Audit 31.10. CL-04)
        assertEquals(mapOf("3" to JsonPrimitive(false)), fieldChanges(entries, mapOf(3 to "nein")).first)
        val no = FieldEntry(FieldDef(3, "Fahnenträger", "bool"), JsonPrimitive(false))
        assertEquals("nein", fieldText(no.definition, no.value))
        assertEquals("", fieldText(no.definition, JsonNull))
        assertEquals("Taufe: bitte als TT.MM.JJJJ eingeben.", fieldChanges(entries, mapOf(1 to "4.13.2001")).second)
    }

    @Test
    fun ibanInGruppen() {
        assertEquals("DE89 3704 0044 0532 0130 00", formatIban("DE89370400440532013000"))
    }

    @Test
    fun terminZeiten() {
        val berlin = ZoneId.of("Europe/Berlin")
        assertEquals("19:30", parseTime("19.30").toString())
        assertNull(parseTime("25:00"))
        assertEquals(
            "2026-12-01T19:00:00+01:00" to "2026-12-01T21:00:00+01:00",
            eventTimes("1.12.2026", "19:00", "", false, berlin),
        )
        // Ende vor Beginn: am nächsten Tag
        assertEquals("2026-06-14T01:00:00+02:00", eventTimes("13.06.2026", "20:00", "1:00", false, berlin)?.second)
        // Ganztägig: ein Tag = Beginn und Ende am selben Tag 0:00 (Audit 31.10. CL-03),
        // auch auf einem Telefon in anderer Zeitzone
        assertEquals(
            "2026-06-13T00:00:00+02:00" to "2026-06-13T00:00:00+02:00",
            eventTimes("13.06.2026", "", "", true, berlin),
        )
        assertEquals(
            "2026-06-13T00:00:00+02:00" to "2026-06-13T00:00:00+02:00",
            eventTimes("13.06.2026", "", "", true, ZoneId.of("America/New_York")),
        )
        assertNull(eventTimes("31.02.2026", "19:00", "", false, berlin))
        assertNull(eventTimes("13.06.2026", "abends", "", false, berlin))
    }

    @Test
    fun rueckmeldungenInWorten() {
        assertEquals("12 Zusagen (15 Personen) · 1 Absage · 20 offen", responseSummary(12, 1, 15, 20))
        assertEquals("1 Zusage · 0 Absagen", responseSummary(1, 0, 1, null))
    }

    @Test
    fun zeitpunktVergleichen() {
        assertTrue(sameInstant("2026-06-13T12:00:00Z", "2026-06-13T14:00:00+02:00"))
        assertFalse(sameInstant("2026-06-13T12:00:00Z", "2026-06-13T12:00:00+02:00"))
        assertFalse(sameInstant(null, "2026-06-13T12:00:00Z"))
    }

    @Test
    fun betraege() {
        assertEquals("23.40", parseAmount("23,40"))
        assertEquals("23.40", parseAmount(" 23.4 € "))
        assertEquals("1234.50", parseAmount("1.234,50"))
        assertNull(parseAmount("0"))
        assertNull(parseAmount("-5"))
        assertNull(parseAmount("1,234"))
        assertNull(parseAmount("zehn"))
        assertEquals("1.234,50 €", formatMoney("1234.5"))
        assertEquals("", formatMoney(null))
    }

    @Test
    fun entwurfHinUndZurueck() {
        val draft = BookingDraft(
            key = "0f8e", unitId = 3, date = "2026-06-13", kind = "expense", amount = "23.40",
            account = 7, category = null, text = "Getränke", photo = "AAEC",
        )
        assertEquals(draft, decodeDraft(encodeDraft(draft)))
    }

    @Test
    fun barzahlung() {
        assertEquals("60,00", amountInput("60.00"))
        assertEquals("5,50", amountInput("5.5"))
        assertEquals("", amountInput("x"))
        assertNull(cashAmountError("60,00", "60.00"))
        assertNull(cashAmountError("20", "60.00"))
        assertEquals("Offen sind nur noch 60,00 €.", cashAmountError("60,01", "60.00"))
        assertEquals("Bitte einen Betrag über 0 eingeben.", cashAmountError("0", "60.00"))
        assertEquals("Quittung-R2025-0002-7.pdf", receiptFileName("R2025-0002", 7))
        assertEquals("Quittung-R2025-0002-7.pdf", receiptFileName("R2025/0002", 7))
        assertEquals("Quittung-7.pdf", receiptFileName(null, 7))
    }

    @Test
    fun barzahlungAntwort() {
        val text = """{"invoice": {"id": 4, "number": "R2025-0002", "status": "open", "status_label": "offen",
            "recipient_name": "Erika Muster\nz. Hd. Kasse", "subject": "Beitrag 2025", "date": "2025-02-01",
            "due_date": "2025-02-15", "total": "60.00", "paid": "20.00", "outstanding": "40.00", "overdue": true,
            "lines": [{"text": "Beitrag", "detail": "", "amount": "60.00", "vat_rate": null}],
            "payments": [{"id": 9, "date": "2026-06-13", "amount": "20.00", "method": "cash", "method_label": "bar",
            "account_name": "Barkasse", "booking_id": 3, "booking_number": "2026-0003"}], "vat": []}, "payment": 9}"""
        val r = json.decodeFromString(CashResult.serializer(), text)
        assertEquals(9, r.payment)
        assertEquals("Erika Muster", r.invoice.payer)
        assertEquals("40.00", r.invoice.outstanding)
        assertEquals("bar", r.invoice.payments.single().methodLabel)
    }
}
