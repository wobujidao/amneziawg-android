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
import java.util.Locale

/**
 * Ошибка вызова API: HTTP-код + сообщение (из тела {"error":...}, если ядро его прислало).
 *
 * `code` — машинный признак причины из того же тела (`totp_required`, `email_not_verified`, …) или
 * пустая строка, если ядро его не прислало. Ветвиться нужно ПО НЕМУ: по одному лишь HTTP-коду
 * «нужен код 2FA» неотличим от «неверный пароль», и экран входа показывал человеку ложь про пароль.
 */
class MayakApiException(
    val status: Int,
    message: String,
    val code: String = "",
    /**
     * Сколько СЕКУНД ядро просит подождать (заголовок Retry-After); 0 — не прислало.
     *
     * Нужен там, где отказ значит «подождите»: у обращений в поддержку лимит 5/ч на аккаунт, и
     * сказать человеку «слишком много обращений» без «можно снова через час» — значит оставить его
     * гадать, что делать дальше (и жать кнопку по кругу).
     */
    val retryAfterSec: Int = 0,
) : IOException(message)

/**
 * Разбор тела ошибки ядра в исключение. Вынесено из doRequest отдельной функцией, чтобы решение
 * «что клиент понял из ответа» можно было проверить тестом без сети и TLS (doRequest ходит только
 * по https, поднять его в юнит-тесте нечем).
 */
internal fun apiError(status: Int, body: String, json: Json, retryAfterSec: Int = 0): MayakApiException {
    val parsed = runCatching { json.decodeFromString(ApiError.serializer(), body) }.getOrNull()
    val msg = parsed?.error?.takeIf { it.isNotBlank() } ?: "HTTP $status"
    return MayakApiException(status, msg, parsed?.code.orEmpty(), retryAfterSec)
}

/**
 * Retry-After в секундах. RFC 7231 разрешает и HTTP-дату — её мы НЕ разбираем осознанно: наше ядро
 * всегда пишет секунды (`strconv.Itoa`), а угадывать по чужому формату дату, часовой пояс и расхождение
 * часов телефона с сервером — три способа наврать человеку про время. Не число → 0 («не сказали»).
 */
internal fun parseRetryAfter(raw: String?): Int =
    raw?.trim()?.toIntOrNull()?.takeIf { it > 0 } ?: 0

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

    /** Все известные базовые адреса (не только текущий) — в том же порядке, что пробуются. */
    val all: List<String> get() = hosts
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
    // Как открывать соединение. Параметром, а не жёстко внутри, чтобы :core остался чистым Kotlin:
    // увести сокет мимо туннеля умеет только :ui (там есть ConnectivityManager и сам VpnService).
    private val direct: (URL) -> HttpURLConnection = { it.openConnection() as HttpURLConnection },
    // Второй заход — мимо туннеля. null = возможности нет (тесты, не-Android).
    // ⚠️ Контракт: фабрика ОБЯЗАНА падать МГНОВЕННО (IOException), когда идти мимо туннеля незачем
    // или некуда — иначе на устройстве без интернета человек прождёт круг по доменам ДВАЖДЫ.
    private val bypassTunnel: ((URL) -> HttpURLConnection)? = null,
) {
    /**
     * ВСЕ известные базовые адреса ядра (домены из реестра + зашитые + IP-фолбэк). Нужны
     * самообновлению: ссылку на APK из version.json принимаем только с нашего домена, а «наш домен»
     * определяется по этому списку.
     *
     * Почему список, а не текущий адрес: когда домен заблокирован, приложение работает по IP-фолбэку,
     * и «домен второго уровня» у текущего адреса — это `128.138`. Сверка с ним отвергла бы законную
     * ссылку `https://mayakvpn.ru/dl/app`, то есть самообновление молча перестало бы работать ровно
     * у тех, кому оно нужнее всего (поймано на ревью 03-08 до выката).
     */
    val knownBases: List<String> get() = hosts.all

    companion object {
        val defaultJson = Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
        }

        /**
         * BCP-47 языка телефона, ровно как он уходит в `Accept-Language` («ru-RU», «en-US»).
         * null — языка нет или он «und»: заголовок в этом случае НЕ отправляется.
         */
        fun languageTag(): String? =
            runCatching { Locale.getDefault().toLanguageTag() }
                .getOrNull()?.takeIf { it.isNotBlank() && it != "und" }

        /**
         * «Корзина» языка НАЗВАНИЙ, приходящих с сервера: `ru` или `en`.
         *
         * Зачем корзина, а не сам тег. Сервер знает два набора имён: русский — тем, кто просит
         * русский, английский — всем остальным (`internal/clientapi/lang.go`). Значит для КЭША важно
         * не «сменился ли тег», а «сменился ли набор имён»: переезд de-DE → fr-FR не меняет ничего,
         * а ru-RU → en-US меняет всё. Заголовка нет → сервер отдаёт русские, поэтому null это «ru».
         *
         * Кэш направлений и пресетов подписан этим значением: смена языка телефона делает старый
         * кэш чужим и заставляет перезапросить. Без этого 14-08 у владельца интерфейс переключился
         * на английский, а список стран остался русским — список тянется раз на процесс, и никто
         * его не перезапрашивал (дефект жил ровно один выпуск, 0.5.2).
         */
        fun namesLanguageBucket(): String =
            if (languageTag()?.substringBefore('-')?.lowercase() == "ru") "ru" else "en"
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

    /**
     * Нужна ли проверка «не робот» и с каким ключом сайта: GET /v1/public/captcha, БЕЗ токена
     * (спрашивает экран регистрации, а у него учётки по определению нет).
     *
     * Ошибку НЕ глотаем (в отличие от [appVersion] и [ruDirect]): промолчать здесь значит решить за
     * человека, что капча выключена, и пойти регистрироваться заведомо в отказ `captcha_required`.
     * Экран покажет причину словами и предложит вторую дорогу — браузер.
     */
    suspend fun publicCaptcha(): CaptchaInfo {
        val resp = call("GET", "/v1/public/captcha", token = null, body = null)
        return json.decodeFromString(CaptchaInfo.serializer(), resp)
    }

    /**
     * Регистрация БЕЗ почты: POST /v1/auth/register-anon → 201 {account_number, token?, trial_days}.
     *
     * Отказы приходят MayakApiException с полем `code` (`consent_required`, `weak_password`,
     * `captcha_required`, `captcha_failed`, `captcha_unavailable`, `overloaded`) — ветвиться надо по
     * НЕМУ: под 400 у ядра живут три разные беды, под 503 — две.
     *
     * ⚠️ Повторять этот запрос «на всякий случай» НЕЛЬЗЯ: каждый успешный вызов создаёт живую учётку.
     */
    suspend fun registerAnon(password: String, consent: Boolean, captchaToken: String): RegisterAnonResponse {
        val body = json.encodeToString(
            RegisterAnonRequest.serializer(),
            RegisterAnonRequest(password = password, consent = consent, captchaToken = captchaToken),
        )
        val resp = call("POST", "/v1/auth/register-anon", token = null, body = body)
        return json.decodeFromString(RegisterAnonResponse.serializer(), resp)
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

    /**
     * Устройства аккаунта (GET /v1/client/devices). Нужны САМОМУ приложению, а не только кабинету:
     * при «занято максимум устройств» человек раньше упирался в тупик — освободить место предлагалось
     * в кабинете, то есть во внешнем браузере, с отдельным входом и ровно в тот момент, когда VPN не
     * поднялся (разбор приложения 07-08). Список читается тем же токеном, что и всё остальное.
     */
    suspend fun listDevices(token: String): List<DeviceItem> {
        val resp = call("GET", "/v1/client/devices", token = token, body = null)
        return json.decodeFromString(kotlinx.serialization.builtins.ListSerializer(DeviceItem.serializer()), resp)
    }

    /** Отключить устройство аккаунта (DELETE /v1/client/devices/{id}). 404 device_not_found — чужое/уже удалено. */
    suspend fun revokeDevice(token: String, id: Long) {
        call("DELETE", "/v1/client/devices/$id", token = token, body = null)
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

    /** ПОДПИСАННЫЙ delivery-документ (F-T8, SPEC-0009): GET /v1/client/delivery, БЕЗ токена, тело —
     *  configsign.Envelope как есть (проверка подписи — Delivery.verify, НЕ здесь). 404 — ШТАТНО:
     *  документ на ядре не заведён (владелец отложил каналы); любая ошибка → null, клиент живёт на
     *  прежнем списке и человеку ничего не показывает. */
    suspend fun deliveryEnvelope(): String? =
        runCatching { call("GET", "/v1/client/delivery", token = null, body = null) }.getOrNull()

    /** Адреса ядра из реестра доменов: публичный /v1/client/hosts, БЕЗ токена (список нужен и до входа,
     *  и когда основной домен уже не отвечает). Любая ошибка → null: у клиента остаётся прежний список. */
    suspend fun hosts(): HostList? =
        runCatching {
            val resp = call("GET", "/v1/client/hosts", token = null, body = null)
            json.decodeFromString(HostList.serializer(), resp)
        }.getOrNull()

    /** Страна по текущему внешнему IP: публичный /v1/egress-check, БЕЗ токена (нужен и до входа).
     *  Ядро определяет страну локально по реестрам RIPE — для РФ-адресов отдаёт "RU". Зовущий ОБЯЗАН
     *  убедиться, что в момент вызова не поднят VPN (ни свой, ни чужой) — иначе IP чужой и страна
     *  определится неверно (см. MayakActivity.maybeAutoEnableRuPreset). Любая ошибка/таймаут → null:
     *  вызывающий код не ставит «проверили» и пробует снова при следующем случае. */
    suspend fun egressCheck(): EgressCheck? =
        runCatching {
            val resp = call("GET", "/v1/egress-check", token = null, body = null)
            json.decodeFromString(EgressCheck.serializer(), resp)
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

    /**
     * Карточка аккаунта (GET /v1/client/account). Нужна ровно за одним полем — публичным НОМЕРОМ
     * аккаунта, которым человек называет себя поддержке; см. [AccountInfo].
     *
     * Отдельным запросом, а не полем в /v1/client/sync: sync ходит по расписанию (раз в час и на
     * resume), а номер у учётки не меняется никогда — тянуть его в каждой сверке незачем.
     */
    suspend fun account(token: String): AccountInfo {
        val resp = call("GET", "/v1/client/account", token = token, body = null)
        return json.decodeFromString(AccountInfo.serializer(), resp)
    }

    /** Состояние доступа аккаунта (GET /v1/client/sync): активен ли, до какой даты, сколько устройств. */
    suspend fun accountStatus(token: String): AccountStatus {
        val resp = call("GET", "/v1/client/sync", token = token, body = null)
        return json.decodeFromString(AccountStatus.serializer(), resp)
    }

    /**
     * Одноразовая ссылка «открыть кабинет уже вошедшим»: POST /v1/client/cabinet-link.
     *
     * Ссылка живёт две минуты и сгорает при первом открытии — её надо ОТКРЫВАТЬ СРАЗУ, не хранить
     * и не класть в кэш. Ядро подставляет в неё и язык, который приложение назвало заголовком
     * Accept-Language: кабинет иначе спрашивает браузер, и человек, выбравший в приложении русский
     * на английском телефоне, получал английский кабинет.
     *
     * Любая ошибка здесь — НЕ повод показать её человеку: путь к оплате не смеет упираться в нашу
     * служебную беду, звонящий обязан открыть обычный адрес кабинета (см. MayakCabinet).
     */
    suspend fun cabinetLink(token: String): CabinetLink {
        val resp = call("POST", "/v1/client/cabinet-link", token = token, body = "{}")
        return json.decodeFromString(CabinetLink.serializer(), resp)
    }

    /**
     * Обращение в поддержку: POST /v1/client/support {topic,message}.
     *
     * Тема — КОД из [SupportTopics] (ядро держит белый список и произвольную строку не примет);
     * контекст аккаунта (тариф, срок, устройства, версия приложения) собирает сервер сам.
     *
     * Отказы приходят MayakApiException с полем `code` — разбирать их надо через [supportFailure],
     * а не по HTTP-коду: под 400 у ядра три разные беды, под 503 — две.
     */
    suspend fun createSupportTicket(token: String, topic: String, message: String): SupportSent {
        val body = json.encodeToString(SupportRequest.serializer(), SupportRequest(topic, message))
        val resp = call("POST", "/v1/client/support", token = token, body = body)
        return json.decodeFromString(SupportSent.serializer(), resp)
    }

    /** Свои обращения: GET /v1/client/support. Пустой список — норма, а не ошибка (ядро отдаёт 200). */
    suspend fun supportTickets(token: String): SupportTicketList {
        val resp = call("GET", "/v1/client/support", token = token, body = null)
        return json.decodeFromString(SupportTicketList.serializer(), resp)
    }

    /**
     * Своё обращение с перепиской: GET /v1/client/support/{id}. Этот же запрос гасит на ядре пометку
     * «есть новый ответ» (открыл = прочитал), поэтому звать его «на всякий случай» в фоне не надо.
     *
     * 404 `not_found` значит и «нет такого», и «оно чужое» — ядро НАРОЧНО не различает их в ответе.
     */
    suspend fun supportThread(token: String, id: Long): SupportThread {
        val resp = call("GET", "/v1/client/support/$id", token = token, body = null)
        return json.decodeFromString(SupportThread.serializer(), resp)
    }

    /** Дописать в своё обращение: POST /v1/client/support/{id}/messages {message}. Нижнего порога
     *  длины у ядра здесь нет — «да»/«помогло» внутри разговора полноценный ответ. */
    suspend fun replySupport(token: String, id: Long, message: String) {
        val body = json.encodeToString(SupportReplyRequest.serializer(), SupportReplyRequest(message))
        call("POST", "/v1/client/support/$id/messages", token = token, body = body)
    }

    // ===== Ящик сообщений (SPEC-0047) =====

    /**
     * Свои сообщения: GET /v1/client/messages?since_id=N. Пустой список — норма (ядро отдаёт 200).
     *
     * `sinceId` = 0 → всё за последние 90 дней (не более 100), как просит экран «Сообщения»; фоновая
     * проверка передаёт id последнего, о котором уже уведомляла, чтобы не показать одно и то же дважды.
     *
     * ⚠️ Побочный эффект НА ЯДРЕ: выданные сообщения помечаются доставленными. Значит звать это
     * «на всякий случай» из мест, которые человеку ничего не показывают, нельзя — статистика
     * кампании начнёт считать доставленным то, чего человек не видел.
     */
    suspend fun messages(token: String, sinceId: Long = 0): MessagesResponse {
        val path = if (sinceId > 0) "/v1/client/messages?since_id=$sinceId" else "/v1/client/messages"
        val resp = call("GET", path, token = token, body = null)
        return json.decodeFromString(MessagesResponse.serializer(), resp)
    }

    /** Пометить своё сообщение прочитанным: POST /v1/client/messages/{id}/read.
     *  404 `message_not_found` — нет такого ИЛИ оно чужое: ядро НАРОЧНО не различает эти случаи. */
    suspend fun markMessageRead(token: String, id: Long) {
        call("POST", "/v1/client/messages/$id/read", token = token, body = null)
    }

    /** Выключатели уведомлений: GET /v1/client/notification-prefs. Строки в базе может не быть —
     *  ядро в этом случае отдаёт значения по умолчанию, а не ошибку. */
    suspend fun notificationPrefs(token: String): NotificationPrefs {
        val resp = call("GET", "/v1/client/notification-prefs", token = token, body = null)
        return json.decodeFromString(NotificationPrefs.serializer(), resp)
    }

    /**
     * Сменить выключатели: PUT /v1/client/notification-prefs. Тело — РОВНО три ключа (ядро читает
     * строго). Включение `news` ядро записывает как согласие с временем и источником — отдельного
     * поля для этого в запросе нет и быть не должно: время согласия проставляет сервер.
     */
    suspend fun updateNotificationPrefs(token: String, prefs: NotificationPrefs) {
        val body = json.encodeToString(NotificationPrefs.serializer(), prefs)
        call("PUT", "/v1/client/notification-prefs", token = token, body = body)
    }

    // ===== «Пригласи друга» (SPEC-0049) =====

    /**
     * Своя карточка приглашений: GET /v1/client/referral.
     *
     * Программу включают и выключают из панели, поэтому спрашивать её состояние надо КАЖДЫЙ раз,
     * когда экран открывается, а не запоминать в настройках: выключенная программа обязана исчезать
     * у людей без выката приложения.
     */
    suspend fun referral(token: String): ReferralInfo {
        val resp = call("GET", "/v1/client/referral", token = token, body = null)
        return json.decodeFromString(ReferralInfo.serializer(), resp)
    }

    /**
     * Применить чужой код: POST /v1/client/referral/apply {code}.
     *
     * Код чистится и здесь, и на ядре (регистр, пробелы, дефисы): его диктуют голосом и
     * перепечатывают с чужого экрана. Отказы приходят [MayakApiException] с полем `code` —
     * разбирать через [referralFailure], а не по HTTP-статусу: под 409 у ядра четыре разные причины.
     */
    suspend fun applyReferral(token: String, code: String): ReferralApplied {
        val body = json.encodeToString(ReferralApplyRequest.serializer(), ReferralApplyRequest(normalizeReferralCode(code)))
        val resp = call("POST", "/v1/client/referral/apply", token = token, body = body)
        return json.decodeFromString(ReferralApplied.serializer(), resp)
    }

    // ===== Push: адрес доставки у транспорта (ускоритель ящика) =====

    /**
     * Сказать ядру, куда будить это устройство: POST /v1/client/push/register {token,platform,app_version}.
     *
     * `pushToken` — адрес у транспорта (FCM), `token` — наш токен сессии (уходит заголовком). Ответ
     * ({"ok":true}) не разбираем: у ручки нет данных, которые нам нужны, а лишний разбор — лишний
     * способ упасть на поле, которое сервер завтра добавит.
     */
    suspend fun registerPush(token: String, pushToken: String, appVersion: String) {
        val body = json.encodeToString(
            PushRegisterRequest.serializer(),
            PushRegisterRequest(token = pushToken, appVersion = appVersion),
        )
        call("POST", "/v1/client/push/register", token = token, body = body)
    }

    /** Снять адрес доставки: POST /v1/client/push/unregister {token}. Зовётся при выходе из аккаунта
     *  и когда будить стало нечем (человек запретил уведомления). */
    suspend fun unregisterPush(token: String, pushToken: String) {
        val body = json.encodeToString(
            PushUnregisterRequest.serializer(),
            PushUnregisterRequest(token = pushToken),
        )
        call("POST", "/v1/client/push/unregister", token = token, body = body)
    }

    /**
     * Один HTTP-вызов с фейловером по доменам. На сетевой ошибке (домен недоступен/заблокирован)
     * крутим HostProvider и повторяем; на HTTP-ответе (в т.ч. 4xx/5xx) — НЕ переключаемся, а
     * возвращаем тело / кидаем MayakApiException (это ответ ядра, а не недоступность канала).
     *
     * Обойдя все домены, пробуем ТОТ ЖЕ круг МИМО ТУННЕЛЯ (`bypassTunnel`), если такая возможность
     * передана. Зачем: запросы к ядру уходят обычными сокетами, а значит при поднятом туннеле идут
     * ВНУТРИ него (доказано 07-08: диаг-логи приходили на ядро с выходных IP наших же нод). Туннель
     * «поднят, но мёртв» — самый частый отказ у людей, и в этом состоянии умирал весь управляющий
     * канал: вход, продление аренды, самообновление и — обиднее всего — отправка диагностики, то
     * есть человек не мог пожаловаться именно потому, что у него сломалось. Владелец поймал это
     * живьём на сотовой: «все домены недоступны» при сломанной немецкой линии.
     */
    private suspend fun call(method: String, path: String, token: String?, body: String?): String =
        withContext(Dispatchers.IO) {
            var lastError: IOException? = null
            // Сначала обычным путём (внутри туннеля, если он поднят), затем — мимо него.
            val routes = listOfNotNull(direct, bypassTunnel)
            for (open in routes) {
                repeat(hosts.size) {
                    val base = hosts.current()
                    try {
                        return@withContext doRequest("$base$path", method, token, body, open)
                    } catch (e: MayakApiException) {
                        throw e // ответ ядра — фейловер не нужен
                    } catch (e: IOException) {
                        lastError = e
                        hosts.rotate()
                    }
                }
            }
            throw NoReachableHostException(
                "ни один домен ядра недоступен (${hosts.size}): ${lastError?.message ?: "сетевая ошибка"}"
            )
        }

    /**
     * BCP-47 языка телефона для заголовка Accept-Language, напр. «ru-RU» или «en-US».
     *
     * Отдаём тег как есть, без q-весов: сервер разбирает и первичный субтег, и вес, а одинокий тег —
     * это ровно «мой язык такой». «und» (язык неизвестен) и пустое НЕ шлём: заголовок с мусором хуже
     * его отсутствия — сервер обязан в этом случае вести себя как со старой сборкой.
     */
    private fun acceptLanguage(): String? = languageTag()

    private fun doRequest(
        url: String,
        method: String,
        token: String?,
        body: String?,
        open: (URL) -> HttpURLConnection = direct,
    ): String {
        // только https: иначе Bearer-токен и данные ушли бы plaintext (напр. если резерв-домен задан http).
        require(url.startsWith("https://")) { "небезопасная схема (нужен https): $url" }
        val conn = open(URL(url))
        try {
            conn.requestMethod = method
            conn.connectTimeout = connectTimeoutMs
            conn.readTimeout = readTimeoutMs
            conn.setRequestProperty("Accept", "application/json")
            // Язык телефона. Ядро отдаёт по нему НАЗВАНИЯ СТРАН на нужном языке (миграция 0133):
            // они приходят с сервера, и без этого заголовка человек с английским телефоном видел на
            // главном экране «Нидерланды» и «Польша» — при том что весь остальной интерфейс уже
            // переведён. HttpURLConnection сам Accept-Language НЕ добавляет, поэтому ставим руками.
            // Ядро без заголовка ведёт себя как раньше (русские имена), так что старые сборки эта
            // правка не трогает — и наоборот: новая сборка со старым ядром просто не получит перевод.
            acceptLanguage()?.let { conn.setRequestProperty("Accept-Language", it) }
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
                // Retry-After снимаем ИМЕННО здесь: тело отказа его не содержит, а после disconnect()
                // заголовков уже нет. Без него «слишком много обращений» превращается в отказ без
                // подсказки, когда пробовать снова.
                throw apiError(code, text, json, parseRetryAfter(conn.getHeaderField("Retry-After")))
            }
            return text
        } finally {
            conn.disconnect()
        }
    }
}
