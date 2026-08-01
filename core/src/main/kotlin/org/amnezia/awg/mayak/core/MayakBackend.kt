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

/**
 * Ошибка вызова API: HTTP-код + сообщение (из тела {"error":...}, если ядро его прислало).
 *
 * `code` — машинный признак причины из того же тела (`totp_required`, `email_not_verified`, …) или
 * пустая строка, если ядро его не прислало. Ветвиться нужно ПО НЕМУ: по одному лишь HTTP-коду
 * «нужен код 2FA» неотличим от «неверный пароль», и экран входа показывал человеку ложь про пароль.
 */
class MayakApiException(val status: Int, message: String, val code: String = "") : IOException(message)

/**
 * Разбор тела ошибки ядра в исключение. Вынесено из doRequest отдельной функцией, чтобы решение
 * «что клиент понял из ответа» можно было проверить тестом без сети и TLS (doRequest ходит только
 * по https, поднять его в юнит-тесте нечем).
 */
internal fun apiError(status: Int, body: String, json: Json): MayakApiException {
    val parsed = runCatching { json.decodeFromString(ApiError.serializer(), body) }.getOrNull()
    val msg = parsed?.error?.takeIf { it.isNotBlank() } ?: "HTTP $status"
    return MayakApiException(status, msg, parsed?.code.orEmpty())
}

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
    // connectTimeout — ТОЛЬКО TCP-хендшейк (не ответ сервера). Держим коротким для БЫСТРОГО фейловера:
    // заблокированный/недостижимый IP ядра даёт connect-таймаут, и при 10с перебор N доменов = N×10с
    // (инцидент 2026-07-05: «недоступен (2)» = ~20с зависания). 5с достаточно даже для медленной РФ-сотовой
    // (TCP-connect к ЖИВОМУ хосту обычно <2-3с), но втрое ускоряет уход с мёртвого домена (best-practice
    // fast-failover, ресёрч 2026-07-05 control-channel-resilience). readTimeout выше — ответ ядра/прокси
    // может быть медленнее хендшейка.
    private val connectTimeoutMs: Int = 5_000,
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
     *
     * totpCode — код двухфакторной аутентификации или резервный код, если 2FA включена. При пустом
     * коде и включённой 2FA ядро отвечает 401 с code=`totp_required`: это не ошибка пароля, а просьба
     * спросить код и повторить вход (одно-запросная модель, как в кабинете).
     */
    suspend fun login(email: String, password: String, totpCode: String = ""): LoginResponse {
        val body = json.encodeToString(
            LoginRequest.serializer(),
            LoginRequest(email, password, totpCode.trim()),
        )
        val resp = call("POST", "/v1/auth/login", token = null, body = body)
        return json.decodeFromString(LoginResponse.serializer(), resp)
    }

    /** Запрос сброса пароля: POST /v1/auth/password/forgot {email} → код на почту (ответ всегда 202, анти-энум). */
    suspend fun forgotPassword(email: String) {
        val body = json.encodeToString(ForgotPasswordRequest.serializer(), ForgotPasswordRequest(email))
        call("POST", "/v1/auth/password/forgot", token = null, body = body)
    }

    /** Сброс пароля кодом из письма: POST /v1/auth/password/reset {email,code,password}. 400 — неверный код/слабый пароль. */
    suspend fun resetPassword(email: String, code: String, password: String) {
        val body = json.encodeToString(
            ResetPasswordRequest.serializer(),
            ResetPasswordRequest(email, code, password),
        )
        call("POST", "/v1/auth/password/reset", token = null, body = body)
    }

    /**
     * Самоудаление аккаунта: POST /v1/client/account/delete {password, confirm}.
     * Требование Google Play — путь удаления должен быть В приложении, а не только на сайте.
     * 401 с code=wrong_password — человек ошибся в пароле; это НЕ повод разлогинивать (сессия жива).
     */
    suspend fun deleteAccount(token: String, password: String) {
        val body = json.encodeToString(DeleteAccountRequest.serializer(), DeleteAccountRequest(password))
        call("POST", "/v1/client/account/delete", token = token, body = body)
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

    suspend fun connect(token: String, deviceId: Long, directionId: Long, appVersion: String = ""): ConnectResult {
        val body = json.encodeToString(ConnectRequest.serializer(), ConnectRequest(deviceId, directionId, appVersion))
        val resp = call("POST", "/v1/client/connect", token = token, body = body)
        return json.decodeFromString(ConnectResult.serializer(), resp)
    }

    /**
     * Тихий еженедельный телеметри-бикон (POST /v1/client/telemetry). Успех = 204 No Content (тело
     * пустое, не парсим). Ошибки НЕ глотаем здесь — их глотает воркер (MayakTelemetryWorker → Result),
     * чтобы бикон не устраивал retry-шторм. Токен обязателен (ядро само проставит user_id/ip).
     */
    suspend fun telemetry(token: String, req: TelemetryRequest) {
        val body = json.encodeToString(TelemetryRequest.serializer(), req)
        call("POST", "/v1/client/telemetry", token = token, body = body)
    }

    /** keepalive аренды overlay-IP (SPEC-0015): пока туннель поднят, приложение продлевает аренду своих
     *  назначений (POST /v1/client/keepalive {device_id}), чтобы жнец их не освободил. Ответ игнорируем. */
    suspend fun keepalive(token: String, deviceId: Long) {
        val body = json.encodeToString(KeepaliveRequest.serializer(), KeepaliveRequest(deviceId))
        call("POST", "/v1/client/keepalive", token = token, body = body)
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

    /** Последняя версия приложения (самообновление, Вариант А): статический /version.json на хосте,
     *  через тот же фейловер доменов (домен→IP). Не критично: любая ошибка (нет файла/сети/парса) → null. */
    suspend fun appVersion(): AppVersionInfo? =
        runCatching {
            val resp = call("GET", "/version.json", token = null, body = null)
            json.decodeFromString(AppVersionInfo.serializer(), resp)
        }.getOrNull()

    /** OTA-список РФ-приложений для split-туннеля (BlancVPN-parity): публичный /v1/client/ru-direct
     *  через тот же фейловер доменов, БЕЗ токена. Не критично: любая ошибка → null (клиент оставит кэш/ассет). */
    suspend fun ruDirect(): RuDirectList? =
        runCatching {
            val resp = call("GET", "/v1/client/ru-direct", token = null, body = null)
            json.decodeFromString(RuDirectList.serializer(), resp)
        }.getOrNull()

    /** Адреса ядра из реестра доменов: публичный /v1/client/hosts, БЕЗ токена (список нужен и до входа,
     *  и когда основной домен уже не отвечает). Любая ошибка → null: у клиента остаётся прежний список. */
    suspend fun hosts(): HostList? =
        runCatching {
            val resp = call("GET", "/v1/client/hosts", token = null, body = null)
            json.decodeFromString(HostList.serializer(), resp)
        }.getOrNull()

    /** Пресеты split-туннеля (SPEC-0028): системные + пользователя (авторизованный). */
    suspend fun listPresets(token: String): List<Preset> {
        val resp = call("GET", "/v1/client/presets", token = token, body = null)
        return json.decodeFromString(PresetsResponse.serializer(), resp).presets
    }

    /** Создать свой пресет; возвращает id. */
    suspend fun createPreset(token: String, w: PresetWrite): Long {
        val body = json.encodeToString(PresetWrite.serializer(), w)
        val resp = call("POST", "/v1/client/presets", token = token, body = body)
        return json.decodeFromString(IdResponse.serializer(), resp).id
    }

    /** Обновить свой пресет. */
    suspend fun updatePreset(token: String, id: Long, w: PresetWrite) {
        val body = json.encodeToString(PresetWrite.serializer(), w)
        call("PUT", "/v1/client/presets/$id", token = token, body = body)
    }

    /** Удалить свой пресет. */
    suspend fun deletePreset(token: String, id: Long) {
        call("DELETE", "/v1/client/presets/$id", token = token, body = null)
    }

    /** Настройки аккаунта (профиль фильтрации DNS + адреса своего резолвера): GET /v1/client/settings. */
    suspend fun settings(token: String): AccountSettings {
        val resp = call("GET", "/v1/client/settings", token = token, body = null)
        return json.decodeFromString(AccountSettings.serializer(), resp)
    }

    /**
     * Смена профиля фильтрации: PUT /v1/client/settings. Ядро возвращает то, что РЕАЛЬНО легло в базу
     * (адреса нормализованы) — отдаём его ответ, чтобы экран показывал сохранённое, а не введённое.
     * Негодный ввод — 400 с человеческим текстом в MayakApiException.message: его и показываем.
     */
    suspend fun updateSettings(token: String, update: SettingsUpdate): AccountSettings {
        val body = json.encodeToString(SettingsUpdate.serializer(), update)
        val resp = call("PUT", "/v1/client/settings", token = token, body = body)
        return json.decodeFromString(AccountSettings.serializer(), resp)
    }

    /** Состояние доступа аккаунта (GET /v1/client/sync): активен ли, до какой даты, сколько устройств. */
    suspend fun accountStatus(token: String): AccountStatus {
        val resp = call("GET", "/v1/client/sync", token = token, body = null)
        return json.decodeFromString(AccountStatus.serializer(), resp)
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
                throw apiError(code, text, json)
            }
            return text
        } finally {
            conn.disconnect()
        }
    }
}
