package de.hubiplan.app.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.TimeUnit

/** Fehler mit Meldung für das Mitglied. [status] 401 mit Geräteschlüssel: neu verbinden. */
class ApiException(val status: Int, message: String, val body: String = "") : Exception(message)

/** Antwort der Verwaltung mit Änderungspaket (für „Rückgängig“). */
data class AdminResponse(val text: String, val changeSet: String?, val undoable: Boolean, val label: String?)

val json = Json {
    ignoreUnknownKeys = true
    explicitNulls = false
    coerceInputValues = true
}

private val JSON_TYPE = "application/json".toMediaType()

/** Geräteschlüssel neben dem Vorstandsschlüssel (Härtung H3). */
const val DEVICE_HEADER = "X-Hubiplan-Device"

private const val TOKEN_PREFIX = "hpd_"
// Unterschrieben wird jede Anfrage mit dem Vorstandsschlüssel (hpa_…), egal unter welchem Pfad:
// eine einzige Regel statt einer Pfadliste, die von der des Servers abweichen kann (AND-F02)
private const val ADMIN_TOKEN_PREFIX = "hpa_"
// Server, die Unterschriftsformat 2 angekündigt haben (Kopfzeile in jeder App-Antwort)
private val SIGNATURE_V2 = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
private val KEY_PATHS = setOf("/api/app/v1/devices/current/renew/", "/api/app/v1/devices/current/key/")
private const val OFFLINE = "Keine Verbindung zum Server. Bitte die Internetverbindung prüfen."

/** `code` aus einer Fehlerantwort (z. B. `device_renew`, `admin_signature`). */
fun errorCode(text: String): String? = try {
    (json.parseToJsonElement(text) as? JsonObject)?.get("code")?.jsonPrimitive?.contentOrNull
} catch (e: Exception) {
    null
}

private val http: OkHttpClient = OkHttpClient.Builder()
    .connectTimeout(15, TimeUnit.SECONDS)
    .readTimeout(30, TimeUnit.SECONDS)
    .followRedirects(false)
    .build()

/** Ergebnis der Passwort-Anmeldung: fertig oder zweiter Faktor nötig. */
sealed interface LoginStep {
    data class Done(val sessionToken: String) : LoginStep
    data class NeedsCode(val sessionToken: String) : LoginStep
}

/**
 * Zugriff auf eine HUBIplan-Instanz. [token] ist der Geräteschlüssel (hpd_…),
 * fehlt er, sind nur die öffentlichen Aufrufe möglich. Für die Verwaltung ist [token] der
 * Vorstandsschlüssel (hpa_…) und [deviceToken] der Geräteschlüssel desselben Geräts: der Server
 * nimmt den Vorstandsschlüssel nur zusammen mit ihm an (Kopf `X-Hubiplan-Device`).
 */
class Api(
    val server: String,
    private val token: String? = null,
    private val deviceToken: String? = null,
    /** Härtung H7: Vorstands-Schlüsselpaar; gesetzt, wenn Vorstandsanfragen unterschrieben werden. */
    private val adminKeyAlias: String? = null,
) {

    private class Raw(val code: Int, val body: ByteArray, val headers: okhttp3.Headers) {
        val text: String get() = String(body)
    }

    /** Geräteschlüssel, mit dem dieses Objekt angemeldet ist (direkt oder neben dem Vorstandsschlüssel). */
    private fun deviceKeyToken(): String? =
        (if (token?.startsWith(TOKEN_PREFIX) == true) token else deviceToken)?.let(DeviceTokens::current)

    private fun request(
        method: String,
        path: String,
        body: ByteArray?,
        contentType: okhttp3.MediaType?,
        headers: Map<String, String>,
        accept: Boolean,
    ): Request {
        val url = (server + path).toHttpUrl()
        val builder = Request.Builder().url(url)
        if (accept) builder.header("Accept", "application/json")
        token?.let { builder.header("Authorization", "Bearer ${if (it.startsWith(TOKEN_PREFIX)) DeviceTokens.current(it) else it}") }
        deviceToken?.let { builder.header(DEVICE_HEADER, DeviceTokens.current(it)) }
        if (adminKeyAlias != null && token?.startsWith(ADMIN_TOKEN_PREFIX) == true) {
            val signedPath = url.encodedPath + (url.encodedQuery?.let { "?$it" } ?: "")
            val time = (System.currentTimeMillis() / 1000).toString()
            val nonce = if (server in SIGNATURE_V2) SignedFormat.newNonce() else ""
            val idempotency = headers[SignedFormat.IDEMPOTENCY_HEADER].orEmpty()
            val message = SignedFormat.adminMessage(method, signedPath, time, body ?: ByteArray(0), nonce, idempotency)
            val signature = try {
                DeviceKeys.sign(adminKeyAlias, message)
            } catch (e: DeviceKeys.Unavailable) {
                // Wie „Verwaltung gesperrt“: die App schaltet neu frei
                throw ApiException(401, "Bitte die Verwaltung neu freischalten.")
            }
            builder.header(SignedFormat.TIME_HEADER, time).header(SignedFormat.SIGNATURE_HEADER, signature)
            if (nonce.isNotEmpty()) builder.header(SignedFormat.NONCE_HEADER, nonce)
        }
        headers.forEach { (k, v) -> builder.header(k, v) }
        val requestBody = body?.toRequestBody(contentType)
        builder.method(method, requestBody ?: if (method == "GET" || method == "DELETE") null else ByteArray(0).toRequestBody(JSON_TYPE))
        return builder.build()
    }

    /**
     * Eine Anfrage; erneuert bei `401 device_renew` den Geräteschlüssel und wiederholt einmal.
     * Ohne Erneuerung nie 401 nach oben geben (das hieße „Gerät abgemeldet“).
     */
    private suspend fun send(
        method: String,
        path: String,
        body: okhttp3.RequestBody? = null,
        headers: Map<String, String> = emptyMap(),
        accept: Boolean = true,
        retry: Boolean = true,
    ): Raw = withContext(Dispatchers.IO) {
        val bytes = body?.let { b -> okio.Buffer().also { b.writeTo(it) }.readByteArray() }
        val used = deviceKeyToken()
        val raw = try {
            http.newCall(request(method, path, bytes, body?.contentType(), headers, accept)).execute().use { resp ->
                Raw(resp.code, resp.body?.bytes() ?: ByteArray(0), resp.headers)
            }
        } catch (e: IOException) {
            throw ApiException(0, OFFLINE)
        }
        if (raw.headers[SignedFormat.FORMAT_HEADER] == "2") SIGNATURE_V2.add(server)
        // Erneuern und Nachmelden selbst nie erneut erneuern (das wäre eine Schleife)
        if (raw.code == 401 && errorCode(raw.text) == "device_renew" && used != null && path !in KEY_PATHS) {
            if (!retry || DeviceTokens.renew(server, used) == null) {
                throw ApiException(0, "Der Geräteschlüssel ließ sich gerade nicht erneuern. Bitte später erneut versuchen.")
            }
            return@withContext send(method, path, body?.let { bytes!!.toRequestBody(it.contentType()) }, headers, accept, retry = false)
        }
        raw
    }

    private suspend fun call(
        method: String,
        path: String,
        body: String? = null,
        headers: Map<String, String> = emptyMap(),
    ): Pair<Int, String> {
        val raw = send(method, path, body?.toRequestBody(JSON_TYPE), headers)
        return raw.code to raw.text
    }

    private suspend fun expect(
        method: String,
        path: String,
        body: String? = null,
        headers: Map<String, String> = emptyMap(),
    ): String {
        val (status, text) = call(method, path, body, headers)
        if (status in 200..299) return text
        throw ApiException(status, errorMessage(status, text))
    }

    suspend fun instance(): Instance = json.decodeFromString(expect("GET", "/api/app/v1/instance/"))

    suspend fun pair(code: String = "", loginLink: String = "", deviceName: String, appVersion: String, deviceKey: String = ""): Paired =
        json.decodeFromString(
            expect(
                "POST",
                "/api/app/v1/pair/",
                json.encodeToString(
                    JsonObject.serializer(),
                    jsonObjectOf(
                        "code" to code,
                        "login_link" to loginLink,
                        "name" to deviceName,
                        "platform" to "android",
                        "app_version" to appVersion,
                        "device_key" to deviceKey,
                    ),
                ),
            ),
        )

    /** Anmeldung mit Passwort über allauth (Client „app“). */
    suspend fun login(email: String, password: String): LoginStep {
        val (status, text) = call(
            "POST",
            "/_allauth/app/v1/auth/login",
            json.encodeToString(JsonObject.serializer(), jsonObjectOf("email" to email, "password" to password)),
        )
        return authStep(status, text, null)
    }

    suspend fun secondFactor(sessionToken: String, code: String): LoginStep {
        val (status, text) = call(
            "POST",
            "/_allauth/app/v1/auth/2fa/authenticate",
            json.encodeToString(JsonObject.serializer(), jsonObjectOf("code" to code)),
            mapOf("X-Session-Token" to sessionToken),
        )
        return authStep(status, text, sessionToken)
    }

    private fun authStep(status: Int, text: String, previous: String?): LoginStep {
        val root = try {
            json.parseToJsonElement(text).jsonObject
        } catch (e: Exception) {
            throw ApiException(status, errorMessage(status, text))
        }
        val session = root["meta"]?.jsonObject?.get("session_token")?.jsonPrimitive?.contentOrNull ?: previous
        if (status == 200 && session != null) return LoginStep.Done(session)
        val pendingMfa = root["data"]?.jsonObject?.get("flows")?.jsonArray?.any {
            val flow = it.jsonObject
            flow["id"]?.jsonPrimitive?.contentOrNull == "mfa_authenticate" &&
                flow["is_pending"]?.jsonPrimitive?.booleanOrNull == true
        } ?: false
        if (status == 401 && pendingMfa && session != null) return LoginStep.NeedsCode(session)
        throw ApiException(status, runCatching { allauthMessage(root) }.getOrNull() ?: "Anmeldung fehlgeschlagen. Bitte E-Mail und Passwort prüfen.")
    }

    suspend fun createDevice(sessionToken: String, deviceName: String, appVersion: String, deviceKey: String = ""): Paired =
        json.decodeFromString(
            expect(
                "POST",
                "/api/app/v1/devices/",
                json.encodeToString(
                    JsonObject.serializer(),
                    jsonObjectOf("name" to deviceName, "platform" to "android", "app_version" to appVersion, "device_key" to deviceKey),
                ),
                mapOf("X-Session-Token" to sessionToken),
            ),
        )

    suspend fun me(): Me = json.decodeFromString(expect("GET", "/api/app/v1/me/"))

    // --- Geräteschlüssel erneuern (Härtung H7) ---

    suspend fun renewChallenge(): String =
        json.decodeFromString<RenewChallenge>(expect("POST", "/api/app/v1/devices/current/renew/")).challenge

    suspend fun renew(challenge: String, signature: String): Renewed = json.decodeFromString(
        expect(
            "POST",
            "/api/app/v1/devices/current/renew/",
            """{"challenge":${quote(challenge)},"signature":${quote(signature)}}""",
        ),
    )

    /** Schlüsselpaar einer älteren Verbindung nachmelden. */
    suspend fun registerDeviceKey(publicKey: String): DeviceInfo = json.decodeFromString(
        expect("PUT", "/api/app/v1/devices/current/key/", """{"device_key":${quote(publicKey)}}"""),
    )

    suspend fun logout(deviceId: Int) {
        expect("DELETE", "/api/app/v1/devices/$deviceId/")
    }

    suspend fun events(accessId: Int, from: String, to: String): List<Occurrence> =
        json.decodeFromString<EventList>(expect("GET", "/api/portal/$accessId/events/?from=$from&to=$to")).items

    suspend fun event(accessId: Int, eventId: Int): EventDetail =
        json.decodeFromString(expect("GET", "/api/portal/$accessId/events/$eventId/"))

    suspend fun respond(accessId: Int, eventId: Int, status: String, guests: Int = 0, note: String = ""): EventDetail =
        json.decodeFromString(
            expect(
                "PUT",
                "/api/portal/$accessId/events/$eventId/response/",
                """{"status":${quote(status)},"guests":$guests,"note":${quote(note)}}""",
            ),
        )

    suspend fun withdraw(accessId: Int, eventId: Int): EventDetail =
        json.decodeFromString(expect("DELETE", "/api/portal/$accessId/events/$eventId/response/"))

    /** Push-Endpunkt dieses Geräts melden (UnifiedPush). */
    suspend fun registerPush(endpoint: String, p256dh: String, auth: String, kind: String = "unifiedpush") {
        expect(
            "PUT",
            "/api/app/v1/devices/current/push/",
            json.encodeToString(
                JsonObject.serializer(),
                jsonObjectOf("kind" to kind, "endpoint" to endpoint, "p256dh" to p256dh, "auth" to auth),
            ),
        )
    }

    // --- Verwaltung (Schritt A8) ---

    suspend fun adminOverview(): AdminOverview = json.decodeFromString(expect("GET", "/api/app/v1/admin/"))

    /** Mit Passwort und Zwei-Faktor-Code freischalten; liefert den Vorstandsschlüssel. */
    suspend fun unlockAdmin(password: String, code: String, adminKey: String = ""): AdminUnlock = json.decodeFromString(
        expect(
            "POST",
            "/api/app/v1/admin/unlock/",
            """{"password":${quote(password)},"code":${quote(code)},"admin_key":${quote(adminKey)}}""",
        ),
    )

    suspend fun lockAdmin() {
        expect("DELETE", "/api/app/v1/admin/unlock/")
    }

    /** Mit dem Vorstandsschlüssel als [token]. */
    suspend fun adminTasks(unitId: Int): AdminTasks =
        json.decodeFromString(expect("GET", "/api/app/v1/admin/u/$unitId/tasks/"))

    /**
     * Anfrage der Verwaltung (Schritt B2) unter `/api/app/v1/admin/u/<Einheit>/`. Liefert den
     * Text und das Änderungspaket für „Rückgängig“ (Kopfzeile `X-Change-Set`).
     */
    suspend fun admin(
        method: String,
        path: String,
        body: String? = null,
        headers: Map<String, String> = emptyMap(),
    ): AdminResponse = adminSend(
        method,
        path,
        body?.toRequestBody(JSON_TYPE) ?: if (method == "GET" || method == "DELETE") null else "".toRequestBody(JSON_TYPE),
        headers,
    )

    /** Formular mit Datei (Schritt B4: Beleg als JPEG), sonst wie [admin]. */
    suspend fun adminForm(
        path: String,
        fields: Map<String, String>,
        fileName: String,
        jpeg: ByteArray,
        headers: Map<String, String> = emptyMap(),
    ): AdminResponse {
        val form = MultipartBody.Builder().setType(MultipartBody.FORM).apply {
            fields.forEach { (k, v) -> addFormDataPart(k, v) }
            addFormDataPart("file", fileName, jpeg.toRequestBody("image/jpeg".toMediaType()))
        }.build()
        return adminSend("POST", path, form, headers)
    }

    private suspend fun adminSend(
        method: String,
        path: String,
        body: okhttp3.RequestBody?,
        headers: Map<String, String>,
    ): AdminResponse {
        val resp = send(method, path, body, headers)
        val text = resp.text
        if (resp.code !in 200..299) throw ApiException(resp.code, errorMessage(resp.code, text), text)
        return AdminResponse(
            text,
            resp.headers["X-Change-Set"],
            resp.headers["X-Change-Undoable"] == "1",
            resp.headers["X-Change-Label"]?.let { java.net.URLDecoder.decode(it, "UTF-8") },
        )
    }

    suspend fun unregisterPush() {
        expect("DELETE", "/api/app/v1/devices/current/push/")
    }

    suspend fun senders(): Senders = json.decodeFromString(expect("GET", "/api/app/v1/send/"))

    /** Kurzmitteilung: [confirm] false zählt nur die Empfänger, true sendet. */
    suspend fun sendShort(sender: Sender, subject: String, body: String, confirm: Boolean): SendResult =
        json.decodeFromString(
            expect(
                "POST",
                "/api/app/v1/send/${sender.type}/${sender.id}/",
                """{"subject":${quote(subject)},"body":${quote(body)},"confirm":$confirm}""",
            ),
        )

    suspend fun profile(accessId: Int): Profile = json.decodeFromString(expect("GET", "/api/portal/$accessId/"))

    /** Änderung melden; Felder wie im Browser (email, phone, street, postal_code, city, …). */
    suspend fun reportChange(accessId: Int, values: Map<String, String>, message: String): Boolean {
        val body = JsonObject((values + ("message" to message)).mapValues { JsonPrimitive(it.value) })
        val text = expect("POST", "/api/portal/$accessId/change-requests/", json.encodeToString(JsonObject.serializer(), body))
        // true: wartet auf den Vorstand · false: sofort übernommen
        return json.parseToJsonElement(text).jsonObject["request"].let { it != null && it !is kotlinx.serialization.json.JsonNull }
    }

    suspend fun card(accessId: Int): CardState = json.decodeFromString(expect("GET", "/api/portal/$accessId/card/"))

    suspend fun photo(accessId: Int): PhotoState = json.decodeFromString(expect("GET", "/api/portal/$accessId/photo/"))

    /** Foto hochladen (JPEG, auf dem Gerät verkleinert und ohne EXIF) mit Einwilligung. */
    suspend fun uploadPhoto(accessId: Int, jpeg: ByteArray): PhotoState {
        val form = MultipartBody.Builder().setType(MultipartBody.FORM)
            .addFormDataPart("consent", "true")
            .addFormDataPart("file", "foto.jpg", jpeg.toRequestBody("image/jpeg".toMediaType()))
            .build()
        val resp = send("POST", "/api/portal/$accessId/photo/", form)
        if (resp.code !in 200..299) throw ApiException(resp.code, errorMessage(resp.code, resp.text))
        return json.decodeFromString(PhotoState.serializer(), resp.text)
    }

    suspend fun deletePhoto(accessId: Int): PhotoState = json.decodeFromString(expect("DELETE", "/api/portal/$accessId/photo/"))

    /** Bild mit Geräteschlüssel laden (Pfad aus der Schnittstelle). */
    suspend fun image(path: String): ByteArray? = try {
        send("GET", path, accept = false).takeIf { it.code in 200..299 }?.body
    } catch (e: ApiException) {
        null
    }

    /** „Meine Ergebnisse“ (Wettbewerbe Schritt 5). */
    suspend fun myResults(accessId: Int): MyResults =
        json.decodeFromString(expect("GET", "/api/portal/$accessId/results/"))

    suspend fun circulars(accessId: Int): List<CircularItem> =
        json.decodeFromString(expect("GET", "/api/portal/$accessId/circulars/"))

    suspend fun circular(accessId: Int, id: Int): CircularItem =
        json.decodeFromString(expect("GET", "/api/portal/$accessId/circulars/$id/"))
}

private fun quote(value: String): String =
    json.encodeToString(JsonPrimitive.serializer(), JsonPrimitive(value))

private fun jsonObjectOf(vararg pairs: Pair<String, String>): JsonObject =
    JsonObject(pairs.associate { (k, v) -> k to JsonPrimitive(v) })

private fun allauthMessage(root: JsonObject): String? =
    root["errors"]?.jsonArray?.firstOrNull()?.jsonObject?.get("message")?.jsonPrimitive?.contentOrNull

/** Meldung aus einer Fehlerantwort von HUBIplan (detail oder Feldfehler). */
fun errorMessage(status: Int, text: String): String {
    val parsed = try {
        json.parseToJsonElement(text)
    } catch (e: Exception) {
        null
    }
    if (parsed is JsonObject) {
        (parsed["detail"] as? JsonPrimitive)?.contentOrNull?.let { return it }
        runCatching { allauthMessage(parsed) }.getOrNull()?.let { return it }
        val fields = parsed.values.mapNotNull { v ->
            when (v) {
                is JsonArray -> (v.firstOrNull() as? JsonPrimitive)?.contentOrNull
                is JsonPrimitive -> v.contentOrNull
                else -> null
            }
        }
        if (fields.isNotEmpty()) return fields.joinToString(" ")
    }
    return when (status) {
        401 -> "Dieses Gerät ist nicht mehr angemeldet. Bitte die App neu verbinden."
        403 -> "Dafür fehlt die Berechtigung."
        404 -> "Nicht gefunden."
        429 -> "Zu viele Versuche. Bitte später erneut."
        in 500..599 -> "Der Server hat gerade ein Problem. Bitte später erneut versuchen."
        else -> "Unerwartete Antwort vom Server ($status)."
    }
}
