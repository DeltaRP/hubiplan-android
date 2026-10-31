package de.hubiplan.app.push

import de.hubiplan.app.data.json
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Inhalt einer Push-Nachricht von HUBIplan (nach dem Entschlüsseln). */
@Serializable
data class PushMessage(
    val v: Int = 1,
    @SerialName("t") val type: String,
    val access: Int? = null,
    val id: Int? = null,
    val kind: String = "",
    val title: String = "",
    val text: String = "",
    val from: String = "",
) {
    companion object {
        fun parse(text: String): PushMessage? =
            runCatching { json.decodeFromString(serializer(), text) }.getOrNull()
    }
}
