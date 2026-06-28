// MayakBackend — клиент клиентского API ядра (SPEC-0004) поверх JDK HttpURLConnection (без okhttp/
// retrofit: ноль внешних зависимостей → переносимо и легко собирается). Сетевые вызовы уходят в
// Dispatchers.IO. Поддерживает фейловер по списку резервных доменов ядра (ADR-0013): при сетевой
// ошибке домена пробуем следующий и «залипаем» на рабочем (sticky), как cpclient у агента.
package org.amnezia.awg.mayak.core

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/** Ошибка вызова API: HTTP-код + сообщение (из тела {"error":...}, если ядро его прислало). */
class MayakApiException(val status: Int, message: String) : IOException(message)

/** Все резервные домены недоступны (фейловер исчерпан). */
class NoReachableHostException(message: String) : IOException(message)

/**
 * Список базовых URL ядра с «липким» выбором рабочего. Потокобезопасность не нужна:
 * один коннектор работает последовательно. baseUrls — напр. ["https://a.example", "https://b.example"].
 */
class HostProvider(baseUrls: List<String>) {
    private val hosts: List<String> = baseUrls.map { it.trimEnd('/') }
    private var idx: Int = 0

    init {
        require(hosts.isNotEmpty()) { "HostProvider: нужен хотя бы один домен ядра" }
    }

    fun current(): String = hosts[idx]

    /** Перейти к следующему домену по кругу; true, пока не обошли все начиная с текущего. */
    fun rotate(): Boolean {
        idx = (idx + 1) % hosts.size
        return idx != 0 || hosts.size == 1
    }

    val size: Int get() = hosts.size
}

class MayakBackend(
    private val hosts: HostProvider,
    private val json: Json = defaultJson,
    private val connectTimeoutMs: Int = 10_000,
    private val readTimeoutMs: Int = 15_000,
) {
    companion object {
        val defaultJson = Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
        }
    }

    /**
     * Вход по email (новая email-авторизация ядра). POST /v1/auth/login {email,password} → {token}.
     * 403 email_not_verified / 401 неверные данные приходят как MayakApiException (фейловера нет —
     * это ответ ядра). Регистрация и подтверждение email — в веб-кабинете.
     */
    suspend fun login(email: String, password: String): LoginResponse {
        val body = json.encodeToString(LoginRequest.serializer(), LoginRequest(email, password))
        val resp = call("POST", "/v1/auth/login", token = null, body = body)
        return json.decodeFromString(LoginResponse.serializer(), resp)
    }

    suspend fun registerDevice(
        token: String,
        pubkey: String,
        label: String,
        hwid: String = "",
    ): DeviceResponse {
        val body = json.encodeToString(DeviceRequest.serializer(), DeviceRequest(pubkey, label, hwid))
        val resp = call("POST", "/v1/client/devices", token = token, body = body)
        return json.decodeFromString(DeviceResponse.serializer(), resp)
    }

    suspend fun directions(token: String): List<Direction> {
        val resp = call("GET", "/v1/client/directions", token = token, body = null)
        return json.decodeFromString(kotlinx.serialization.builtins.ListSerializer(Direction.serializer()), resp)
    }

    suspend fun connect(token: String, deviceId: Long, directionId: Long): ConnectResult {
        val body = json.encodeToString(ConnectRequest.serializer(), ConnectRequest(deviceId, directionId))
        val resp = call("POST", "/v1/client/connect", token = token, body = body)
        return json.decodeFromString(ConnectResult.serializer(), resp)
    }

    /**
     * Отправка диагностического лога на сервер (POST /v1/client/diag-log). Лог + контекст устройства/
     * сети ловятся на ядре для анализа (152-ФЗ: под согласие/политику). Возвращает {status,id}.
     */
    suspend fun sendDiagLog(token: String, req: DiagLogRequest): DiagLogResponse {
        val body = json.encodeToString(DiagLogRequest.serializer(), req)
        val resp = call("POST", "/v1/client/diag-log", token = token, body = body)
        return json.decodeFromString(DiagLogResponse.serializer(), resp)
    }

    /**
     * Один HTTP-вызов с фейловером по доменам. На сетевой ошибке (домен недоступен/заблокирован)
     * крутим HostProvider и повторяем; на HTTP-ответе (в т.ч. 4xx/5xx) — НЕ переключаемся, а
     * возвращаем тело / кидаем MayakApiException (это ответ ядра, а не недоступность канала).
     */
    private suspend fun call(method: String, path: String, token: String?, body: String?): String =
        withContext(Dispatchers.IO) {
            var lastError: IOException? = null
            repeat(hosts.size) {
                val base = hosts.current()
                try {
                    return@withContext doRequest("$base$path", method, token, body)
                } catch (e: MayakApiException) {
                    throw e // ответ ядра — фейловер не нужен
                } catch (e: IOException) {
                    lastError = e
                    hosts.rotate()
                }
            }
            throw NoReachableHostException(
                "ни один домен ядра недоступен (${hosts.size}): ${lastError?.message ?: "сетевая ошибка"}"
            )
        }

    private fun doRequest(url: String, method: String, token: String?, body: String?): String {
        // только https: иначе Bearer-токен и данные ушли бы plaintext (напр. если резерв-домен задан http).
        require(url.startsWith("https://")) { "небезопасная схема (нужен https): $url" }
        val conn = URL(url).openConnection() as HttpURLConnection
        try {
            conn.requestMethod = method
            conn.connectTimeout = connectTimeoutMs
            conn.readTimeout = readTimeoutMs
            conn.setRequestProperty("Accept", "application/json")
            token?.let { conn.setRequestProperty("Authorization", "Bearer $it") }
            if (body != null) {
                conn.doOutput = true
                conn.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            }

            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else (conn.errorStream ?: conn.inputStream)
            val text = stream.use { it.readBytes().toString(Charsets.UTF_8) }
            if (code !in 200..299) {
                val msg = runCatching { json.decodeFromString(ApiError.serializer(), text).error }
                    .getOrNull()?.takeIf { it.isNotBlank() } ?: "HTTP $code"
                throw MayakApiException(code, msg)
            }
            return text
        } finally {
            conn.disconnect()
        }
    }
}
