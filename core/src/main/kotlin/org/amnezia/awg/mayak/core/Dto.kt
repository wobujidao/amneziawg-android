// DTO клиентского API ядра «Маяк» (SPEC-0004). Имена JSON-полей повторяют Go-структуры
// internal/clientapi + internal/cprepo/clientcfg + internal/desiredstate (сверено 2026-06-25):
//   login   POST /v1/client/login     {login,password}            -> {token}
//   device  POST /v1/client/devices   {pubkey,label}              -> {device_id}
//   dirs    GET  /v1/client/directions                            -> [{id,code,name,p2p}]
//   connect POST /v1/client/connect   {device_id,direction_id}    -> {direction,direct?,relay?}
package org.amnezia.awg.mayak.core

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class LoginRequest(
    val email: String,
    val password: String,
)

@Serializable
data class LoginResponse(
    val token: String,
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

@Serializable
data class Direction(
    val id: Long,
    val code: String,
    val name: String,
    val p2p: Boolean = false,
)

@Serializable
data class ConnectRequest(
    @SerialName("device_id") val deviceId: Long,
    @SerialName("direction_id") val directionId: Long,
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
)

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

/** Тело ошибки ядра: {"error":"..."} (writeErr в clientapi). */
@Serializable
data class ApiError(
    val error: String = "",
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
