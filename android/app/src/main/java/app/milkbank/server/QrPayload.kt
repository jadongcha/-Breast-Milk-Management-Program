package app.milkbank.server

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.net.URI

sealed interface QrParse {
    data class Ok(val config: ServerConfig) : QrParse
    data object NotServerQr : QrParse
}

@Serializable
private data class QrDto(
    val type: String,
    val v: Int,
    val url: String,
    val pin: String,
)

private val json = Json { ignoreUnknownKeys = true }
private val PIN_REGEX = Regex("^sha256/[A-Za-z0-9+/]{43}=$")

fun parseQr(raw: String): QrParse = try {
    val dto = json.decodeFromString<QrDto>(raw)
    val host = if (dto.url.startsWith("https://")) URI(dto.url).host else null
    if (dto.type == "milkbank-server" && dto.v == 1 && !host.isNullOrEmpty() && PIN_REGEX.matches(dto.pin)) {
        QrParse.Ok(ServerConfig(dto.url, dto.pin))
    } else {
        QrParse.NotServerQr
    }
} catch (e: Exception) {
    QrParse.NotServerQr
}
