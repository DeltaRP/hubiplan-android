package de.hubiplan.app.data

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

// Verwaltung in der App, Schritt B2: Antworten wie im Browser (nur benötigte Felder)

@Serializable
data class MemberRow(
    val id: Int,
    @SerialName("last_name") val lastName: String = "",
    @SerialName("first_name") val firstName: String = "",
    @SerialName("birth_date") val birthDate: String? = null,
    @SerialName("member_type") val memberType: String? = null,
    val status: String = "",
    val departments: List<String> = emptyList(),
    @SerialName("photo_url") val photoUrl: String? = null,
    val city: String = "",
)

@Serializable
data class MemberPage(val count: Int = 0, val next: String? = null, val results: List<MemberRow> = emptyList())

@Serializable
data class TypeAssignment(
    @SerialName("member_type") val memberType: Int,
    @SerialName("member_type_name") val memberTypeName: String = "",
    @SerialName("valid_from") val validFrom: String = "",
    @SerialName("valid_until") val validUntil: String? = null,
)

@Serializable
data class DepartmentMembership(
    val id: Int,
    val department: Int,
    @SerialName("department_name") val departmentName: String = "",
    @SerialName("valid_from") val validFrom: String = "",
    @SerialName("valid_until") val validUntil: String? = null,
)

@Serializable
data class MemberPeriod(
    val id: Int,
    @SerialName("entry_date") val entryDate: String = "",
    @SerialName("exit_date") val exitDate: String? = null,
    @SerialName("exit_reason_label") val exitReasonLabel: String? = null,
)

@Serializable
data class Choice(val value: String, val label: String)

@Serializable
data class MemberPhotos(val approved: PhotoVersion? = null, val pending: PhotoVersion? = null)

@Serializable
data class MemberDetail(
    val id: Int,
    @SerialName("last_name") val lastName: String = "",
    @SerialName("first_name") val firstName: String = "",
    @SerialName("birth_date") val birthDate: String? = null,
    val gender: String = "",
    @SerialName("club_number") val clubNumber: String = "",
    @SerialName("pays_fee_self") val paysFeeSelf: Boolean = true,
    @SerialName("info_opt_out_at") val infoOptOutAt: String? = null,
    val contact: Contact? = null,
    val status: String = "",
    @SerialName("current_type") val currentType: TypeAssignment? = null,
    val periods: List<MemberPeriod> = emptyList(),
    val departments: List<DepartmentMembership> = emptyList(),
    val photo: MemberPhotos? = null,
    @SerialName("exit_reasons") val exitReasons: List<Choice> = emptyList(),
) {
    val name: String get() = "$firstName $lastName".trim()

    /** Heute laufende Zugehörigkeiten (Untereinheiten). */
    fun currentDepartments(today: String): List<DepartmentMembership> =
        departments.filter { it.validFrom <= today && (it.validUntil == null || it.validUntil >= today) }
}

@Serializable
data class MemberTypeItem(
    val id: Int,
    val name: String,
    @SerialName("is_archived") val isArchived: Boolean = false,
)

@Serializable
data class DepartmentItem(
    val id: Int,
    val name: String,
    @SerialName("display_name") val displayName: String = "",
    @SerialName("is_active") val isActive: Boolean = true,
) {
    val label: String get() = displayName.ifBlank { name }
}

@Serializable
data class Preview(val title: String = "", val consequences: List<String> = emptyList())

@Serializable
data class AdminChangeRequest(
    val id: Int,
    @SerialName("member_id") val memberId: Int,
    @SerialName("member_name") val memberName: String = "",
    val data: Map<String, JsonElement> = emptyMap(),
    val previous: Map<String, JsonElement>? = null,
    val message: String = "",
    @SerialName("created_at") val createdAt: String? = null,
)

@Serializable
data class CardCheck(
    val status: String = "",
    @SerialName("status_label") val statusLabel: String = "",
    @SerialName("first_name") val firstName: String? = null,
    @SerialName("last_name") val lastName: String? = null,
    @SerialName("member_type") val memberType: String? = null,
    val club: String? = null,
    val departments: List<String> = emptyList(),
    @SerialName("photo_url") val photoUrl: String? = null,
    val membership: Int? = null,
)

@Serializable
data class BirthdayRow(
    @SerialName("member_id") val memberId: Int,
    @SerialName("first_name") val firstName: String = "",
    @SerialName("last_name") val lastName: String = "",
    val date: String = "",
    val age: Int = 0,
)

@Serializable
data class JubileeRow(
    @SerialName("member_id") val memberId: Int,
    @SerialName("first_name") val firstName: String = "",
    @SerialName("last_name") val lastName: String = "",
    val kind: String = "",
    val years: Int = 0,
    val date: String = "",
    @SerialName("organization_short") val organization: String = "",
)

@Serializable
data class Rows<T>(val results: List<T> = emptyList())

// --- B2b: Ehrungen, Zusatzfelder, Bankverbindung, Aufnahme ---

@Serializable
data class AwardItem(
    val id: Int,
    val title: Int,
    @SerialName("title_name") val titleName: String = "",
    @SerialName("valid_from") val validFrom: String = "",
    @SerialName("valid_until") val validUntil: String? = null,
    val note: String = "",
)

@Serializable
data class TitleItem(val id: Int, val name: String, @SerialName("is_archived") val isArchived: Boolean = false)

@Serializable
data class FieldDef(
    val id: Int,
    val label: String,
    @SerialName("field_type") val fieldType: String = "text",
    val choices: List<String> = emptyList(),
    val repeatable: Boolean = false,
    @SerialName("help_text") val helpText: String = "",
)

@Serializable
data class FieldEntry(val definition: FieldDef, val value: JsonElement? = null)

@Serializable
data class MandateItem(
    val id: Int,
    val reference: String = "",
    val holder: String = "",
    val iban: String? = null,
    @SerialName("iban_masked") val ibanMasked: String = "",
    val bic: String = "",
    @SerialName("signed_on") val signedOn: String? = null,
    val status: String = "",
    @SerialName("status_label") val statusLabel: String = "",
)

@Serializable
data class DuplicateItem(
    val id: Int,
    @SerialName("last_name") val lastName: String = "",
    @SerialName("first_name") val firstName: String = "",
    @SerialName("birth_date") val birthDate: String? = null,
    @SerialName("already_member_here") val alreadyMemberHere: Boolean = false,
)

// --- B3: Termine der Einheit und Rückmeldungen ---

@Serializable
data class AdminOccurrence(
    val key: String = "",
    @SerialName("event_id") val eventId: Int,
    val title: String = "",
    @SerialName("starts_at") val startsAt: String,
    @SerialName("ends_at") val endsAt: String,
    @SerialName("original_start") val originalStart: String? = null,
    @SerialName("all_day") val allDay: Boolean = false,
    val location: String = "",
    @SerialName("is_cancelled") val isCancelled: Boolean = false,
    @SerialName("is_series") val isSeries: Boolean = false,
    val owner: String = "",
    @SerialName("can_edit") val canEdit: Boolean = false,
    @SerialName("rsvp_enabled") val rsvpEnabled: Boolean = false,
)

@Serializable
data class AdminEventList(
    val items: List<AdminOccurrence> = emptyList(),
    @SerialName("can_manage") val canManage: Boolean = false,
    @SerialName("rsvp_allowed") val rsvpAllowed: Boolean = false,
)

@Serializable
data class EventException(
    @SerialName("original_start") val originalStart: String,
    @SerialName("is_cancelled") val isCancelled: Boolean = false,
    val note: String = "",
)

@Serializable
data class RsvpCounts(val yes: Int = 0, val no: Int = 0, val guests: Int = 0, val persons: Int = 0, val open: Int? = null)

@Serializable
data class AdminRsvp(
    val enabled: Boolean = false,
    val deadline: String? = null,
    val capacity: Int? = null,
    @SerialName("guests_allowed") val guestsAllowed: Boolean = false,
    @SerialName("max_guests") val maxGuests: Int = 0,
    val free: Int? = null,
    @SerialName("can_view_list") val canViewList: Boolean = false,
    @SerialName("can_enter") val canEnter: Boolean = false,
    val summary: RsvpCounts? = null,
)

@Serializable
data class AdminEvent(
    val id: Int,
    val title: String = "",
    val description: String = "",
    val location: String = "",
    @SerialName("all_day") val allDay: Boolean = false,
    @SerialName("starts_at") val startsAt: String,
    @SerialName("ends_at") val endsAt: String,
    @SerialName("visibility_label") val visibilityLabel: String = "",
    val repeat: String = "none",
    @SerialName("repeat_label") val repeatLabel: String = "",
    @SerialName("is_cancelled") val isCancelled: Boolean = false,
    @SerialName("cancel_note") val cancelNote: String = "",
    val owner: String = "",
    @SerialName("can_edit") val canEdit: Boolean = false,
    val exceptions: List<EventException> = emptyList(),
    val rsvp: AdminRsvp = AdminRsvp(),
) {
    val isSeries: Boolean get() = repeat != "none"
}

@Serializable
data class ResponseRow(
    @SerialName("membership_id") val membershipId: Int,
    val name: String = "",
    val departments: String = "",
    val status: String? = null,
    val guests: Int = 0,
    val note: String = "",
    val source: String? = null,
)

@Serializable
data class OpenRow(
    @SerialName("membership_id") val membershipId: Int,
    val name: String = "",
    val departments: String = "",
)

@Serializable
data class AdminResponses(
    val summary: RsvpCounts = RsvpCounts(),
    val capacity: Int? = null,
    val items: List<ResponseRow> = emptyList(),
    val open: List<OpenRow> = emptyList(),
)

// --- B4: Kasse ---

@Serializable
data class FinanceAccount(
    val id: Int,
    val name: String,
    val kind: String = "",
    @SerialName("kind_label") val kindLabel: String = "",
    val balance: String? = null,
    @SerialName("is_active") val isActive: Boolean = true,
)

@Serializable
data class FinanceCategory(
    val id: Int,
    val name: String,
    val kind: String = "",
    @SerialName("is_active") val isActive: Boolean = true,
)

@Serializable
data class ReceiptInfo(val id: Int, val name: String = "", @SerialName("content_type") val contentType: String = "")

@Serializable
data class BookingItem(
    val id: Int,
    @SerialName("display_number") val displayNumber: String = "",
    val date: String = "",
    val kind: String = "",
    @SerialName("kind_label") val kindLabel: String = "",
    val amount: String = "0",
    @SerialName("account_name") val accountName: String = "",
    @SerialName("to_account_name") val toAccountName: String? = null,
    @SerialName("category_name") val categoryName: String? = null,
    val text: String = "",
    val counterparty: String = "",
    val receipts: List<ReceiptInfo> = emptyList(),
)

@Serializable
data class BookingPage(val count: Int = 0, val next: String? = null, val results: List<BookingItem> = emptyList())

/** Buchung aus der App: Entwurf auf dem Gerät (verschlüsselt), bis sie gesendet ist. */
@Serializable
data class BookingDraft(
    /** Zugleich `Idempotency-Key`: erneutes Senden bucht nicht doppelt. */
    val key: String,
    @SerialName("unit_id") val unitId: Int,
    @SerialName("unit_name") val unitName: String = "",
    val date: String,
    val kind: String,
    val amount: String,
    val account: Int,
    @SerialName("account_name") val accountName: String = "",
    val category: Int? = null,
    @SerialName("category_name") val categoryName: String = "",
    val text: String,
    val counterparty: String = "",
    /** Belegfoto (JPEG) als Base64 */
    val photo: String,
    val error: String? = null,
)

// --- B4: Untereinheit (Zugführung) ---

@Serializable
data class DeptMemberDetail(
    val id: Int,
    @SerialName("last_name") val lastName: String = "",
    @SerialName("first_name") val firstName: String = "",
    @SerialName("birth_date") val birthDate: String? = null,
    val gender: String = "",
    @SerialName("club_number") val clubNumber: String = "",
    @SerialName("member_type") val memberType: String? = null,
    @SerialName("entry_date") val entryDate: String? = null,
    val departments: List<String> = emptyList(),
    val contact: Contact? = null,
) {
    val name: String get() = "$firstName $lastName".trim()
}

// --- B5: Beiträge (Barzahlung, Quittung) ---

@Serializable
data class InvoiceLineItem(val text: String = "", val detail: String = "", val amount: String = "0")

@Serializable
data class PaymentItem(
    val id: Int,
    val date: String = "",
    val amount: String = "0",
    val method: String = "",
    @SerialName("method_label") val methodLabel: String = "",
    @SerialName("account_name") val accountName: String = "",
    @SerialName("booking_number") val bookingNumber: String = "",
)

@Serializable
data class InvoiceItem(
    val id: Int,
    val number: String? = null,
    val status: String = "",
    @SerialName("status_label") val statusLabel: String = "",
    @SerialName("recipient_name") val recipientName: String = "",
    @SerialName("department_name") val departmentName: String? = null,
    val subject: String = "",
    val date: String = "",
    @SerialName("due_date") val dueDate: String = "",
    val total: String = "0",
    val paid: String = "0",
    val outstanding: String = "0",
    val overdue: Boolean = false,
    val lines: List<InvoiceLineItem> = emptyList(),
    val payments: List<PaymentItem> = emptyList(),
) {
    /** Erste Zeile des Empfängers (darunter steht ggf. „z. Hd.“ o. Ä.). */
    val payer: String get() = recipientName.lineSequence().firstOrNull().orEmpty()
}

@Serializable
data class InvoicePage(val count: Int = 0, val next: String? = null, val results: List<InvoiceItem> = emptyList())

@Serializable
data class BillingAccount(val id: Int, val name: String, val kind: String = "")

@Serializable
data class BillingInfo(val accounts: List<BillingAccount> = emptyList(), @SerialName("can_edit") val canEdit: Boolean = false)

@Serializable
data class CashResult(val invoice: InvoiceItem, val payment: Int)

// --- Quittung mit Unterschrift (hubiplan docs/app.md, konzepte/digitale-quittung.md) ---

/** Gespeicherte Form einer Unterschrift: Breite 1000, Linienzüge als x,y,x,y,… */
@Serializable
data class SignatureShape(val w: Int = 1000, val h: Int = 0, val s: List<List<Int>> = emptyList())

/** Unterschrift aus dem Zeichenfeld: nur Linien in Bildschirmpunkten, keine Zeit oder Druck. */
@Serializable
data class SignatureStrokes(val width: Int, val height: Int, val strokes: List<List<List<Int>>>)

@Serializable
data class CashReceiptItem(
    val id: Int,
    val direction: String = "out",
    val status: String = "draft",
    @SerialName("status_label") val statusLabel: String = "",
    @SerialName("counterparty_name") val counterpartyName: String = "",
    val amount: String = "0",
    val purpose: String = "",
    val email: String = "",
    @SerialName("account_name") val accountName: String = "",
    @SerialName("invoice_number") val invoiceNumber: String? = null,
    val number: String = "",
    @SerialName("receipt_date") val receiptDate: String? = null,
    @SerialName("confirmation_text") val confirmationText: String = "",
    val missing: List<String> = emptyList(),
    @SerialName("has_stored_signature") val hasStoredSignature: Boolean = false,
    @SerialName("cancel_reason") val cancelReason: String = "",
)

@Serializable
data class CashReceiptPage(val count: Int = 0, val next: String? = null, val results: List<CashReceiptItem> = emptyList())

@Serializable
data class StoredSignature(val signature: SignatureShape? = null, @SerialName("updated_at") val updatedAt: String? = null)

/**
 * Quittung, die auf dem Telefon vorbereitet und unterschrieben wurde (auch ohne Netz).
 * Verschlüsselt gespeichert, bis sie abgeschlossen ist und ihre Belegnummer hat.
 */
@Serializable
data class ReceiptDraft(
    /** Zugleich `Idempotency-Key` für das Anlegen auf dem Server. */
    val key: String,
    /** `Idempotency-Key` für den Abschluss (ein Schlüssel gilt nur für einen Schritt). */
    @SerialName("finalize_key") val finalizeKey: String,
    @SerialName("unit_id") val unitId: Int,
    /** "in": Verein erhält Geld, "out": Verein zahlt aus */
    val direction: String,
    @SerialName("counterparty_name") val counterpartyName: String,
    val email: String = "",
    val amount: String,
    val purpose: String,
    val account: Int,
    @SerialName("account_name") val accountName: String = "",
    val category: Int,
    @SerialName("category_name") val categoryName: String = "",
    /** Kassierer: hinterlegte Unterschrift einsetzen statt [treasurer] */
    @SerialName("use_stored") val useStored: Boolean = false,
    val treasurer: SignatureStrokes? = null,
    @SerialName("treasurer_at") val treasurerAt: String = "",
    val counterparty: SignatureStrokes? = null,
    @SerialName("counterparty_at") val counterpartyAt: String = "",
    /** Zahler unterschreibt nicht (nur bei "in"): Grund */
    val declined: String = "",
    @SerialName("send_mail") val sendMail: Boolean = false,
    /** Kennung auf dem Server, sobald angelegt */
    @SerialName("server_id") val serverId: Int? = null,
    val error: String? = null,
)

/** Auswahl für Quittungen ohne Netz: zuletzt geladene Barkassen, Kategorien, eigene Unterschrift. */
@Serializable
data class ReceiptChoices(
    val accounts: List<FinanceAccount> = emptyList(),
    val categories: List<FinanceCategory> = emptyList(),
    val signature: SignatureShape? = null,
)
