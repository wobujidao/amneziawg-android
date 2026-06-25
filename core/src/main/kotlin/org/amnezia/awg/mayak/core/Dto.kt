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
    val dns: String = "",
    val mtu: Int = 0,
    val obfuscation: Obfuscation? = null,
    @SerialName("server_pubkey") val serverPubkey: String,
    val endpoint: String,
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
