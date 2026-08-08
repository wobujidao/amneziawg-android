// DTO клиентского API ядра «Маяк» (SPEC-0004). Имена JSON-полей повторяют Go-структуры
// internal/clientapi + internal/cprepo/clientcfg + internal/desiredstate (сверено 2026-06-25):
//   login   POST /v1/client/login     {login,password}            -> {token}
//   device  POST /v1/client/devices   {pubkey,label}              -> {device_id}
//   dirs    GET  /v1/client/directions                            -> [{id,code,name,p2p}]
//   connect POST /v1/client/connect   {device_id,direction_id}    -> {direction,direct?,relay?}
package org.amnezia.awg.mayak.core

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Вход. `totpCode` — код двухфакторной аутентификации ЛИБО резервный код; пустая строка = не слали
 * (ядро в этом случае при включённой 2FA ответит 401 `totp_required`, и экран входа спросит код).
 */
@Serializable
data class LoginRequest(
    val email: String,
    val password: String,
    @SerialName("totp_code") val totpCode: String = "",
)

@Serializable
data class LoginResponse(
    val token: String,
)

// Сброс пароля по email-коду (POST /v1/auth/password/forgot → код на почту; /reset — код+новый пароль).
@Serializable
data class ForgotPasswordRequest(
    val email: String,
)

@Serializable
data class ResetPasswordRequest(
    val email: String,
    val code: String,
    val password: String,
)

// Самоудаление аккаунта (POST /v1/client/account/delete). Ядро требует ОБА поля: пароль (токен живёт
// 30 дней и мог утечь вместе с телефоном, а уничтожение данных необратимо) и явное confirm.
@Serializable
data class DeleteAccountRequest(
    val password: String,
    val confirm: Boolean = true,
)

@Serializable
data class DeviceRequest(
    val pubkey: String,
    val label: String,
    // Стабильный HWID: ядро апсертит устройство по (user, hwid), чтобы переустановка не плодила
    // новые устройства и не упиралась в лимит. Пустой допустим (старое ядро игнорирует поле).
    val hwid: String = "",
)

@Serializable
data class DeviceResponse(
    @SerialName("device_id") val deviceId: Long,
)

/**
 * Устройство аккаунта (GET /v1/client/devices). Ровно то, что отдаёт cprepo.DeviceInfo: ключ
 * приходит УЖЕ маскированным, полного публичного ключа ядро наружу не даёт.
 *
 * Даты — RFC3339 от ядра; `lastSeen` пустая, если устройство ни разу не подключалось.
 */
@Serializable
data class DeviceItem(
    val id: Long = 0,
    val label: String = "",
    val pubkey: String = "",
    @SerialName("created_at") val createdAt: String = "",
    // Nullable ОСОЗНАННО: ядро сейчас поле опускает (omitempty на nil-указателе), но если оно
    // когда-нибудь начнёт приходить как явный null, разбор непустого типа упал бы и экран устройств
    // перестал бы открываться. Читателю всё равно — обе формы дают «ни разу не подключалось».
    @SerialName("last_seen") val lastSeen: String? = null,
) {
    /** Момент последнего подключения в мс эпохи; null — не подключалось или дата не разобралась. */
    fun lastSeenMs(): Long? = parseTime(lastSeen)

    /** Когда устройство добавлено, в мс эпохи; null — дата не разобралась. */
    fun createdAtMs(): Long? = parseTime(createdAt)

    private fun parseTime(s: String?): Long? {
        if (s.isNullOrBlank()) return null
        return runCatching { java.time.OffsetDateTime.parse(s).toInstant().toEpochMilli() }.getOrNull()
    }
}

@Serializable
data class Direction(
    val id: Long,
    val code: String,
    val name: String,
    val p2p: Boolean = false,
    // country_code — явный ISO-код страны для флага (SPEC-0033 §2.6). Приложение рисует флаг по нему,
    // а если пусто — по code. Позволяет назвать направление любым кодом и всё равно показать верный флаг.
    @SerialName("country_code") val countryCode: String = "",
    // city — город-подзаголовок карточки (SPEC-0037): «Амстердам», «Франкфурт». Пусто → подзаголовок не рисуем.
    val city: String = "",
    // pool_host — GeoDNS-имя направления (резолвится в живые IP линий). Пингуем его для замера RTT
    // «телефон→сервер» (SPEC-0031). Пусто → пингуем по серверному хинту-фолбэку.
    @SerialName("pool_host") val poolHost: String = "",
    // SPEC-0031: серверный хинт ранжирования (омитемпти на ядре → дефолты, если поля нет).
    // health: "ok" | "degraded" | "down" (пусто = неизвестно). loadHint: 0..100 (0 = нет свежих метрик).
    // recommended: сервер помечает одно живое направление с наим. загрузкой.
    val health: String = "",
    @SerialName("load_hint") val loadHint: Int = 0,
    val recommended: Boolean = false,
) {
    /** Уровень «сигнала» 0..3 для полосок из СЕРВЕРНОГО хинта (без клиентского пинга → без нагрузки на
     *  серверы при масштабе). down → 0; иначе базовый уровень по загрузке (Proton-пороги: ≤75%→3, ≤90%→2,
     *  >90%→1; нет метрик→3 оптимистично), затем degraded ограничивает максимум 2 полосками. Клиентский
     *  RTT (позже, разреженно) сможет уточнить. */
    fun signalLevel(): Int {
        if (health == "down") return 0
        val byLoad = when {
            loadHint in 1..75 -> 3
            loadHint in 76..90 -> 2
            loadHint > 90 -> 1
            else -> 3 // нет свежих метрик — оптимистично
        }
        return if (health == "degraded") minOf(byLoad, 2) else byLoad
    }
}

@Serializable
data class ConnectRequest(
    @SerialName("device_id") val deviceId: Long,
    @SerialName("direction_id") val directionId: Long,
    // Версия приложения (BuildConfig.VERSION_NAME). Ядро пишет её в аналитику/last_seen устройства.
    // Раньше поле не заполнялось → на проде приходило пустым (2026-07-23). Пусто допустимо
    // (старое ядро игнорирует поле; connect не использует DisallowUnknownFields).
    @SerialName("app_version") val appVersion: String = "",
)

// keepalive аренды overlay-IP (SPEC-0015): продлеваем аренду устройства, пока туннель поднят.
@Serializable
data class KeepaliveRequest(
    @SerialName("device_id") val deviceId: Long,
)

/** Ответ connect: имя направления + конфиги путей. Оба пути опциональны (omitempty на стороне ядра). */
@Serializable
data class ConnectResult(
    val direction: String,
    val direct: ClientConfig? = null,
    val relay: ClientConfig? = null,
)

/** Структурированный конфиг плеча (clientcfg.Config). Приватный ключ сюда НЕ приходит — он на устройстве. */
@Serializable
data class ClientConfig(
    val address: String,
    // IPv6-overlay-адрес клиента (SPEC-0014, dual-stack). Пусто → IPv6 у выдачи выкл. Добавляется в
    // строку Address рядом с IPv4; ядро отдаёт ТОЛЬКО для ipv6_ok-нод. Форк умеет IPv6 в конфиге сам.
    @SerialName("address_v6") val addressV6: String = "",
    val dns: String = "",
    val mtu: Int = 0,
    val obfuscation: Obfuscation? = null,
    @SerialName("server_pubkey") val serverPubkey: String,
    val endpoint: String, // IP:port — рабочий путь без DNS
    @SerialName("endpoint_fqdn") val endpointFqdn: String = "", // fqdn:port — резолвим через DoH, фоллбэк на endpoint
    @SerialName("allowed_ips") val allowedIps: String,
    @SerialName("persistent_keepalive") val persistentKeepalive: Int = 0,
    // Запасной транспорт (SPEC-0039): AWG внутри обычного HTTPS к нашему сайту, когда оператор душит
    // весь UDP. Поля может не быть вовсе — тогда запасного канала у этой линии нет, работаем как раньше.
    val fallback: Fallback? = null,
)

/**
 * Параметры запасного канала. `kind` пока всегда "wss"; поле оставлено на случай, если появится
 * второй вид транспорта — тогда старый клиент увидит незнакомое значение и просто не полезет туда.
 */
@Serializable
data class Fallback(
    val kind: String = "",
    val url: String = "",
    val token: String = "",
    // IP моста от ядра — чтобы не резолвить имя (системный резолвер при поднятом VPN ходит через
    // туннель, то есть через путь, который в этот момент и не работает). Пусто у старых ядер →
    // клиент резолвит сам, как раньше. Имя из url остаётся для SNI и проверки сертификата.
    val ip: String = "",
) {
    /** Годен ли к использованию ЭТИМ клиентом (умеем только wss и только с непустыми полями). */
    fun usable(): Boolean = kind == "wss" && url.startsWith("wss://") && token.isNotEmpty()
}

/** Профиль обфускации AmneziaWG 2.0 (desiredstate.Obfuscation). Поля 1:1 ложатся на парсер Interface форка. */
@Serializable
data class Obfuscation(
    val jc: Int = 0,
    val jmin: Int = 0,
    val jmax: Int = 0,
    val s1: Int = 0,
    val s2: Int = 0,
    val s3: Int = 0,
    val s4: Int = 0,
    val h1: String = "",
    val h2: String = "",
    val h3: String = "",
    val h4: String = "",
    val i1: String = "",
    val i2: String = "",
    val i3: String = "",
    val i4: String = "",
    val i5: String = "",
)

/**
 * Тело ошибки ядра: {"error":"...","code":"..."} (writeErr в clientapi).
 *
 * `code` — МАШИННЫЙ признак причины (`totp_required`, `email_not_verified`, …). Раньше его тут не
 * было, и клиент различал причины только по русскому тексту — то есть не различал: любой 401 шёл в
 * «Неверный email или пароль». Для человека с включённой 2FA это было прямой ложью (пароль верен),
 * см. разбор 2026-07-27. Текст показываем, решения принимаем по `code`.
 */
@Serializable
data class ApiError(
    val error: String = "",
    val code: String = "",
)

/**
 * Диагностический лог для отправки на сервер (POST /v1/client/diag-log): сам лог движка + контекст
 * устройства/сети, чтобы инженер понял причину «не работает на мобиле» (блок IP/сигнатура vs клиент).
 */
@Serializable
data class DiagLogRequest(
    @SerialName("app_version") val appVersion: String,
    val os: String,
    @SerialName("device_model") val deviceModel: String,
    @SerialName("network_type") val networkType: String, // wifi | cellular | other
    @SerialName("other_vpn") val otherVpn: Boolean,       // в момент сбора активен ДРУГОЙ VPN?
    val direction: String = "",
    @SerialName("device_id") val deviceId: Long = 0,
    // Источник заливки (0.3.48): "manual" — кнопка «Отправить лог» в настройках; "auto" — авто-заливка
    // при ошибке подключения (rate-limited). encodeDefaults=true → сериализуется всегда (ядру нужно поле).
    @SerialName("source") val source: String = "manual",
    val meta: Map<String, String> = emptyMap(),           // доп. поля (внешний IP, оператор и т.п.)
    val log: String,
)

@Serializable
data class DiagLogResponse(
    val status: String = "",
    val id: Long = 0,
)

/** Инфо о последней версии приложения (самообновление, Вариант А): статический version.json на хосте.
 *  Приложение сверяет свой versionCode; если ниже latest — мягкий нудж со ссылкой apkUrl. */
@Serializable
data class AppVersionInfo(
    @SerialName("latest_version_code") val latestVersionCode: Int = 0,
    @SerialName("latest_version_name") val latestVersionName: String = "",
    @SerialName("apk_url") val apkUrl: String = "",
    @SerialName("min_version_code") val minVersionCode: Int = 0, // ниже этого — жёсткий апдейт (на будущее)
    val changelog: String = "",
)

/**
 * Тихий еженедельный телеметри-бикон (POST /v1/client/telemetry → 204 No Content). Не-ПДн: версия
 * приложения/сборки, модель устройства, версия ОС, локаль, источник установки + агрегированные счётчики
 * использования. user_id и ip ядро проставляет САМО по Bearer-токену — их НЕ шлём.
 * ⚠️ Ядро парсит тело с DisallowUnknownFields → набор ключей должен совпадать ТОЧНО (ни лишних, ни
 * пропущенных). Все поля обязательны (без дефолтов) → сериализуются всегда, ровно 9 ключей.
 */
@Serializable
data class TelemetryRequest(
    @SerialName("app_version") val appVersion: String,
    @SerialName("version_code") val versionCode: Int,
    @SerialName("device_model") val deviceModel: String,
    @SerialName("os_version") val osVersion: String,
    val locale: String,
    @SerialName("install_source") val installSource: String,
    @SerialName("connect_count") val connectCount: Int,
    @SerialName("active_days") val activeDays: Int,
    // Из них ушло через запасной канал (SPEC-0039) — метрика «у скольких людей задавлен UDP».
    @SerialName("fallback_connects") val fallbackConnects: Int = 0,
    // Состояние тумблера «Открывать российские сервисы напрямую» (RU-пресет split-туннеля,
    // MayakPrefs.ruDirect). Без него сервер не видел настройку вовсе: при ВЫКЛ весь трафик, включая
    // банки и Госуслуги, идёт через туннель, и вход с загран-IP может не пройти. Шлём всегда явно
    // (true/false, не пропускаем ключ) — приложение своё состояние знает точно.
    @SerialName("ru_direct_enabled") val ruDirectEnabled: Boolean = false,
)

/** OTA-список РФ-приложений для split-туннеля «Открывать российские сервисы напрямую» (BlancVPN-parity
 *  2026-07-09). Клиент тянет /v1/client/ru-direct, кэширует локально; фолбэк — зашитый в APK ассет.
 *  version = хэш enabled-набора (клиент по нему решает, обновлять ли кэш). Правится в админке. */
@Serializable
data class RuDirectList(
    val version: String = "",
    val regex: List<String> = emptyList(),
    val exceptions: List<String> = emptyList(),
    val apps: List<String> = emptyList(),
)

/** Ответ GET /v1/egress-check (БЕЗ авторизации): внешний IP и страна по нему, определяемая ядром
 *  локально по реестрам RIPE. country — ISO2 ("RU" и т.п.) или пусто, если определить не удалось.
 *  Нужен авто-включению РФ-пресета split-туннеля (2026-08-03): человеку, вышедшему в интернет из РФ,
 *  банки и Госуслуги должны идти мимо туннеля сами, без похода в настройки (см. MayakActivity). */
@Serializable
data class EgressCheck(
    val ip: String = "",
    val country: String = "",
)

/** Адреса ядра из реестра доменов на сервере (GET /v1/client/hosts, миграция 0089). Приложение
 *  запоминает их и ходит по списку сверху вниз: к моменту, когда основной домен где-то заблокируют,
 *  резервный у него уже есть — без обновления из маркета. Без схемы («host» / «host:port»). */
@Serializable
data class HostList(
    val api: List<String> = emptyList(),
    val cabinet: String = "",
    val site: String = "",
)

/** Пресет split-туннеля (SPEC-0028). mode: all|exclude|include. source: system|user. owned — можно править.
 *  Системный «РФ напрямую» — rule-based (regex/exceptions/apps); пользовательские — только apps (галочки). */
@Serializable
data class Preset(
    val id: Long = 0,
    val name: String = "",
    val mode: String = "exclude",
    val source: String = "user",
    val owned: Boolean = false,
    val regex: List<String> = emptyList(),
    val exceptions: List<String> = emptyList(),
    val apps: List<String> = emptyList(),
)

@Serializable
data class PresetsResponse(val presets: List<Preset> = emptyList())

/** Тело создания/обновления пользовательского пресета. */
@Serializable
data class PresetWrite(
    val name: String,
    val mode: String,
    val apps: List<String>,
)

@Serializable
data class IdResponse(val id: Long = 0)

/** Настройки аккаунта (GET /v1/client/settings, миграции 0086/0088): профиль фильтрации DNS и адреса
 *  своего резолвера. Настройка живёт на аккаунте, а не на устройстве: применяется ко всем выдачам
 *  конфигов этого пользователя — и в приложении, и на роутере. */
@Serializable
data class AccountSettings(
    @SerialName("dns_mode") val dnsMode: String = DNS_DEFAULT,
    // Адреса своего резолвера ядро отдаёт ВСЕГДА, даже когда выбран другой профиль: человек вернётся
    // к «своему» — и вводить их заново не придётся.
    @SerialName("dns_custom") val dnsCustom: String = "",
) {
    companion object {
        const val DNS_DEFAULT = "default"
        const val DNS_ADBLOCK = "adblock"
        const val DNS_FAMILY = "family"
        const val DNS_CUSTOM = "custom"

        /** Профили в том порядке, в каком показываем их человеку. */
        val MODES = listOf(DNS_DEFAULT, DNS_ADBLOCK, DNS_FAMILY, DNS_CUSTOM)
    }
}

/** Тело PUT /v1/client/settings. dnsCustom = null («не трогай адреса») отличается от "" («сотри») —
 *  ровно так это различает ядро (settingsReq.DNSCustom — указатель). */
@Serializable
data class SettingsUpdate(
    @SerialName("dns_mode") val dnsMode: String,
    @SerialName("dns_custom") val dnsCustom: String? = null,
)

/**
 * Аккаунт человека (GET /v1/client/account, хендлер `handleAccount` в internal/clientapi). Ядро
 * отдаёт там всю карточку (почта, тариф, трафик), а приложению из неё нужен ровно ПУБЛИЧНЫЙ НОМЕР —
 * то, чем человек называет себя поддержке. Остальное приложение уже знает из /v1/client/sync, и
 * дублировать его тут значило бы держать две расходящиеся копии одного контракта.
 *
 * `account_number` — строка из девяти цифр БЕЗ дефисов; разметку добавляет [AccountNumber.format].
 * Строкой, а НЕ числом: ведущие нули значимы, в JSON-числе «007891234» стало бы «7891234».
 *
 * Пусто — ШТАТНО, и таких случаев три; приложение во всех молчит про номер, а не падает:
 *   • пустая строка — у учётки номера нет (в ядре поле объявлено обычной строкой, без omitempty,
 *     то есть приезжает всегда — именно этот случай и будет самым частым);
 *   • поля нет вовсе — ядро старее правки, добавившей номер в этот ответ;
 *   • явный null — на случай, если поле однажды станет указателем; nullable здесь именно за этим
 *     (не-nullable поле с дефолтом на `null` УПАЛО БЫ: coerceInputValues в defaultJson не включён).
 */
@Serializable
data class AccountInfo(
    @SerialName("account_number") val accountNumber: String? = null,
)

/** Состояние доступа аккаунта (GET /v1/client/sync). access: active | expired | none («ничего не
 *  выдано»). validUntil — RFC3339 от ядра; пусто = срок не задан (бессрочный доступ админом). */
@Serializable
data class AccountStatus(
    val access: String = "",
    @SerialName("valid_until") val validUntil: String = "",
    @SerialName("devices_used") val devicesUsed: Int = 0,
    @SerialName("device_limit") val deviceLimit: Int = 0,
) {
    /**
     * Сколько ЦЕЛЫХ суток осталось до конца доступа, если ядро прислало срок. null — срока нет или
     * он не разобрался (тогда UI покажет только словесный статус, а не «осталось N дней»).
     * Округляем ВВЕРХ: пока не наступила дата окончания, у человека «остался день», а не «0 дней».
     */
    fun daysLeft(nowMs: Long = System.currentTimeMillis()): Int? {
        val untilMs = validUntilMs() ?: return null
        val left = untilMs - nowMs
        if (left <= 0) return 0
        return ((left + DAY_MS - 1) / DAY_MS).toInt()
    }

    /** Момент окончания доступа в мс эпохи; null — поля нет или оно не разбирается. */
    fun validUntilMs(): Long? {
        if (validUntil.isBlank()) return null
        return runCatching { java.time.OffsetDateTime.parse(validUntil).toInstant().toEpochMilli() }
            .getOrNull()
    }

    /** Доступ действует прямо сейчас (ядро уже посчитало это за нас — сверяем только по его ответу). */
    fun active(): Boolean = access == "active"

    companion object {
        private const val DAY_MS = 24L * 60 * 60 * 1000
    }
}
