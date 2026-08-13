// DTO клиентского API ядра «Маяк» (SPEC-0004). Имена JSON-полей повторяют Go-структуры
// internal/clientapi + internal/cprepo/clientcfg + internal/desiredstate (сверено 2026-06-25):
//   login   POST /v1/client/login     {login,password}            -> {token}
//   device  POST /v1/client/devices   {pubkey,label}              -> {device_id}
//   dirs    GET  /v1/client/directions                            -> [{id,code,name,p2p}]
//   connect POST /v1/client/connect   {device_id,direction_id}    -> {direction,direct?,relay?}
package org.amnezia.awg.mayak.core

import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

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

/**
 * Нужна ли проверка «не робот» и с каким ключом сайта (GET /v1/public/captcha, БЕЗ авторизации).
 *
 * Ключ НЕ вшит в приложение осознанно: выключатель капчи в панели обязан срабатывать мгновенно, а
 * ключ — меняться без релиза. `enabled=false` → шага капчи нет вовсе, приложение шлёт пустой токен.
 * Ключ публичен по устройству Turnstile (он виден в разметке любой страницы с виджетом); секрет
 * живёт только на сервере.
 */
@Serializable
data class CaptchaInfo(
    val enabled: Boolean = false,
    val sitekey: String = "",
)

/**
 * Регистрация БЕЗ почты (POST /v1/auth/register-anon, SPEC-0046). Имена полей — 1:1 с
 * `registerAnonReq` в `internal/clientapi/authhandlers.go`; сверяет их таблица-тест
 * `RegisterAnonContractTest` (разъехавшиеся имена не падают, а молча теряют смысл: сервер прочтёт
 * согласие как «не дано»).
 *
 * `captchaToken` пустой — законное состояние: капча выключена в панели. Поле уходит всегда
 * (encodeDefaults), старое ядро его игнорирует.
 */
@Serializable
data class RegisterAnonRequest(
    val password: String,
    val consent: Boolean,
    @SerialName("captcha_token") val captchaToken: String = "",
)

/**
 * Ответ 201 на регистрацию без почты.
 *
 * 🔴 `token` МОЖЕТ БЫТЬ ПУСТЫМ, и это НЕ ошибка: у сервера есть ветка «учётка создана, а сессию
 * выдать не смогли» — тогда приходят только `account_number` и `message`. Аккаунт уже существует,
 * поэтому экран обязан показать номер и увести на вход, а не изобразить неудачу и предложить
 * повторить (повтор завёл бы второй аккаунт).
 *
 * `trial_days` — настройка сервера (`registration.anon_trial_days`). Приложение НЕ пишет «7 дней»
 * словами: показывает то, что пришло, и молчит про пробный срок, если это 0.
 */
@Serializable
data class RegisterAnonResponse(
    @SerialName("account_number") val accountNumber: String = "",
    val token: String = "",
    @SerialName("trial_days") val trialDays: Int = 0,
    val message: String = "",
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
    // ipv6 — бейдж «IPv6» у строки направления (директива владельца 01-07). true ТОЛЬКО когда у
    // направления есть активная линия, чей выходной узел прошёл ПРОВЕРКУ egress по IPv6 (curl -6 на
    // ядре, nodes.ipv6_ok; cprepo/clientdata.go). Это НЕ «у направления есть AAAA» и не догадка
    // клиента — рисовать бейдж по чему-либо, кроме явного true отсюда, запрещено: бейдж начнёт врать.
    // omitempty на ядре: старое ядро поле не шлёт → false → бейджа нет (честный дефолт).
    val ipv6: Boolean = false,
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

/**
 * Профиль обфускации AmneziaWG 3.0 (desiredstate.Obfuscation). Поля 1:1 ложатся на парсер Interface форка.
 *
 * Раньше в этой строке значилось «2.0» — от линии, на которой писался класс. Сам НАБОР полей с тех пор
 * не менялся: Jc/Jmin/Jmax, S1–S4, H1–H4, I1–I5 перешли из 2.0 как есть, и из нового в 3.0 здесь ровно
 * одно поле — `header_protection_key` в конце. Имена полей и JSON-ключи — контракт с ядром: правится
 * только по обе стороны сразу, иначе профиль молча приедет пустым.
 */
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
    // Ключ защиты заголовка (AWG 3.0, ядро отдаёт при заданном у линии ключе — миграция 0120).
    // НЕОБЯЗАТЕЛЬНОЕ: у боевых линий ключа нет и не будет ещё долго — отсутствие поля обязано
    // работать ровно как раньше (пусто = защита выключена, в .conf директива не пишется).
    // Формат — 64 hex-символа в нижнем регистре; проверяет ConfRenderer (fail-closed), а не разбор
    // JSON: уронить разбор ВСЕГО ответа connect из-за одного кривого поля значило бы потерять и
    // релейное плечо, у которого с ключом всё в порядке.
    // ⚠️ При заданном ключе движок требует S1–S4 ≥ 12 — сервер сам поднимает их до 12, так что
    // рядом с ключом приедут «непривычные» S (наш боевой эталон S4=0 — это профиль БЕЗ ключа).
    @SerialName("header_protection_key") val headerProtectionKey: String = "",
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
 *  Приложение сверяет свой versionCode; если ниже latest — мягкий нудж со ссылкой apkUrl, а если ниже
 *  [minVersionCode] — неотменяемый экран «сборка больше не работает» (см. MinVersionGate). */
@Serializable
data class AppVersionInfo(
    @SerialName("latest_version_code") val latestVersionCode: Int = 0,
    @SerialName("latest_version_name") val latestVersionName: String = "",
    @SerialName("apk_url") val apkUrl: String = "",
    /**
     * Порог старых сборок СЫРЬЁМ. Тип [JsonElement], а не `Int`, намеренно: разбор строгий
     * (`isLenient=false`), и строка или мусор в этом поле уронил бы разбор ВСЕГО файла — приложение
     * осталось бы и без порога, И без самообновления. Читать через [minVersionCode].
     */
    @SerialName("min_version_code") val minVersionCodeRaw: JsonElement? = null,
    val changelog: String = "",
) {
    /** Порог старых сборок: поля нет или мусор → 0, то есть «гейта нет» (fail-open). */
    val minVersionCode: Int get() = minVersionCodeOf(minVersionCodeRaw)
}

/**
 * Тихий еженедельный телеметри-бикон (POST /v1/client/telemetry → 204 No Content). Не-ПДн: версия
 * приложения/сборки, модель устройства, версия ОС, локаль, источник установки + агрегированные счётчики
 * использования. user_id и ip ядро проставляет САМО по Bearer-токену — их НЕ шлём.
 * ⚠️ Ядро парсит тело с DisallowUnknownFields → набор ключей должен совпадать ТОЧНО (ни лишних, ни
 * пропущенных). Обязательные поля (без EncodeDefault) сериализуются всегда — это старый контракт.
 *
 * Поля ladder_* / ladder_ms_avg — НОВЫЙ слой (исход лестницы подключения, 2026-08-09): помечены
 * `@EncodeDefault(NEVER)` и nullable, то есть null → ключ НЕ сериализуется вовсе. Так один класс
 * умеет оба контракта: полный бикон для нового ядра и, через [withoutLadder], урезанный для старого
 * (которое на незнакомый ключ отвечает 400). Ретрай на 400 делает MayakTelemetryWorker.
 */
@Serializable
@OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)
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
    // ── Исход лестницы подключения (кумулятивно с установки, как connect_count; только техника,
    // без адресов и содержимого — Политика обещает не собирать содержимое). Значения считает
    // LadderTelemetry + MayakPrefs, сюда кладёт TelemetryRequest.withLadder(). Смысл: до этого мы
    // узнавали «у скольких людей ломается прямой путь и спасает ли транзит» из жалоб, а не из данных.
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    @SerialName("ladder_direct_ok") val ladderDirectOk: Int? = null,
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    @SerialName("ladder_relay_ok") val ladderRelayOk: Int? = null,
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    @SerialName("ladder_fallback_ok") val ladderFallbackOk: Int? = null,
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    @SerialName("ladder_direct_fail") val ladderDirectFail: Int? = null,
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    @SerialName("ladder_relay_fail") val ladderRelayFail: Int? = null,
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    @SerialName("ladder_fallback_fail") val ladderFallbackFail: Int? = null,
    // Попытки, где НЕ вышла ни одна ступень («нет выхода в интернет» при живой сети).
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    @SerialName("ladder_none") val ladderNone: Int? = null,
    // Среднее время до ПОДТВЕРЖДЁННОГО выхода (мс) по успешным попыткам.
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    @SerialName("ladder_ms_avg") val ladderMsAvg: Int? = null,
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
    /**
     * НАСТОЯЩАЯ почта учётки — то, что о ней знает ядро, а не то, что человек ввёл в поле входа.
     * Разница видна ровно у тех, ради кого всё затевалось: вошедший по НОМЕРУ до 12-08 видел в
     * Настройках «Почта: 848681728», потому что показывалась введённая строка.
     *
     * Пусто (и это штатно, а не сбой) — почты у учётки нет вовсе: анонимная регистрация, подарочная
     * учётка, учётка из бота. Показ обязан отличать «нет почты» от «ещё не спросили ядро».
     */
    val email: String? = null,
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

// ===== Обращения в поддержку (internal/clientapi/support.go, support_tickets.go) =====
//
// POST /v1/client/support            {topic,message}  -> {status,reply_to}
// GET  /v1/client/support                             -> {tickets:[…],new_answers:N}
// GET  /v1/client/support/{id}                        -> {ticket:{…},messages:[…]}
// POST /v1/client/support/{id}/messages {message}     -> {status,id}
//
// ⚠️ Ядро читает тело с DisallowUnknownFields: ЛИШНЕЕ поле в запросе = 400 на весь запрос. Поэтому в
// запросах ровно те поля, что объявлены в Go-структурах, и ни одного «на будущее».
//
// Контекст обращения (тариф, срок, устройства, версия приложения) собирает СЕРВЕР из базы по сессии —
// клиент его не присылает и подделать не может. Дублировать его в тексте письма из приложения тоже не
// надо: получилось бы два контекста в одном обращении, из которых один врёт.

@Serializable
data class SupportRequest(
    val topic: String,
    val message: String,
)

/** Ответ на создание обращения. `reply_to` — адрес, на который придёт ответ (пусто = у аккаунта нет
 *  почты, тогда экран говорит «ответим в приложении», а не «ответим на почту (какую?)»). */
@Serializable
data class SupportSent(
    val status: String = "",
    @SerialName("reply_to") val replyTo: String = "",
)

/** Обращение в списке/нитке. `statusText` и `authorName` собирает ядро — вторая копия перевода в
 *  клиенте разъехалась бы с белым списком статусов ровно так же, как разъезжались темы. */
@Serializable
data class SupportTicket(
    val id: Long = 0,
    val topic: String = "",
    val subject: String = "",
    val status: String = "",
    @SerialName("status_text") val statusText: String = "",
    @SerialName("created_at") val createdAt: String = "",
    @SerialName("last_message_at") val lastMessageAt: String = "",
    val messages: Int = 0,
    @SerialName("new_answer") val newAnswer: Boolean = false,
) {
    /** Момент последнего сообщения в мс эпохи; null — дата не разобралась (тогда время не показываем). */
    fun lastMessageMs(): Long? = parseSupportTime(lastMessageAt)

    /** Когда обращение создано, в мс эпохи; null — дата не разобралась. */
    fun createdMs(): Long? = parseSupportTime(createdAt)
}

@Serializable
data class SupportTicketList(
    val tickets: List<SupportTicket> = emptyList(),
    @SerialName("new_answers") val newAnswers: Int = 0,
)

/** Сообщение нитки. author: `user` | `support`. Логина ответившего админа тут нет и быть не должно. */
@Serializable
data class SupportMessage(
    val id: Long = 0,
    val author: String = "",
    @SerialName("author_name") val authorName: String = "",
    val body: String = "",
    @SerialName("created_at") val createdAt: String = "",
) {
    fun createdMs(): Long? = parseSupportTime(createdAt)

    /** Написано ЧЕЛОВЕКОМ (а не поддержкой) — нитка красит эти сообщения иначе. */
    fun mine(): Boolean = author == AUTHOR_USER

    companion object {
        const val AUTHOR_USER = "user"
        const val AUTHOR_SUPPORT = "support"
    }
}

@Serializable
data class SupportThread(
    val ticket: SupportTicket = SupportTicket(),
    val messages: List<SupportMessage> = emptyList(),
)

@Serializable
data class SupportReplyRequest(
    val message: String,
)

/** RFC3339 от ядра → мс эпохи. Не разобралось → null: показать «неизвестно» честнее, чем 1970 год. */
private fun parseSupportTime(s: String?): Long? {
    if (s.isNullOrBlank()) return null
    return runCatching { java.time.OffsetDateTime.parse(s).toInstant().toEpochMilli() }.getOrNull()
}

// ===== Ящик сообщений (SPEC-0047, internal/clientapi + internal/usermsg) =====
//
// GET  /v1/client/messages?since_id=N -> {messages:[…], unread:N, next_check_after_sec:N}
// POST /v1/client/messages/{id}/read  -> {status:"ok"}
// GET  /v1/client/notification-prefs  -> {service,news,quiet_hours}
// PUT  /v1/client/notification-prefs  <- {service,news,quiet_hours}
//
// Разбор ответов ЛОЯЛЬНЫЙ (ignoreUnknownKeys у defaultJson): серверная половина пишется параллельно
// с этой, и лишнее поле в ответе не должно ронять весь ящик. В ЗАПРОС же (PUT) уходит ровно три
// ключа — ядро читает тело строго (DisallowUnknownFields), лишний ключ там = 400 на весь запрос.

/**
 * Одно сообщение человеку. `title`/`body` собраны СЕРВЕРОМ и годятся всегда; приложение по паре
 * ([kind], [params]) рисует локализованный текст для известных поводов, а для незнакомого повода и
 * для `custom` показывает серверный текст как есть (SPEC-0047 §2.4).
 *
 * `params` — [JsonObject], а не Map<String,String>: в базе это `jsonb`, и число `{"days":3}` вместо
 * строки `{"days":"3"}` уронило бы разбор ВСЕГО ящика на строгой карте. Читать через [param].
 */
@Serializable
data class UserMessage(
    val id: Long = 0,
    val category: String = "",
    val kind: String = "",
    val title: String = "",
    val body: String = "",
    val params: JsonObject = JsonObject(emptyMap()),
    val action: String = MessageActions.NONE,
    @SerialName("action_param") val actionParam: String = "",
    val critical: Boolean = false,
    @SerialName("created_at") val createdAt: String = "",
    val read: Boolean = false,
) {
    /** Значение параметра строкой; нет ключа/это объект или массив → null (подставлять нечего). */
    fun param(key: String): String? = paramString(params, key)

    /** Когда сообщение создано, в мс эпохи; null — дата не разобралась (тогда время не показываем). */
    fun createdMs(): Long? = parseSupportTime(createdAt)
}

/** Ответ ящика. `nextCheckAfterSec` — через сколько сервер просит зайти снова (0 = не сказал). */
@Serializable
data class MessagesResponse(
    val messages: List<UserMessage> = emptyList(),
    val unread: Int = 0,
    @SerialName("next_check_after_sec") val nextCheckAfterSec: Int = 0,
)

/**
 * Выключатели уведомлений. Строки в базе может не быть — это НЕ ошибка, а «всё по умолчанию»
 * (SPEC-0047 §2.3), поэтому дефолты здесь обязаны совпадать с дефолтами таблицы.
 * Категория `account` выключателя не имеет вовсе — это работа сервиса, а не рассылка.
 */
@Serializable
data class NotificationPrefs(
    val service: Boolean = true,
    val news: Boolean = false,
    @SerialName("quiet_hours") val quietHours: Boolean = true,
)
