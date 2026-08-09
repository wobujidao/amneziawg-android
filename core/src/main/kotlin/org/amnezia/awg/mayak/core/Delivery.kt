// Delivery — клиентское потребление ПОДПИСАННОГО документа доставки (F-T8, SPEC-0009 T5).
//
// Зачем: заблокируют домен — приложение живёт на вшитом списке адресов до следующего релиза в
// маркете. Сервер отдаёт на GET /v1/client/delivery конверт `configsign.Envelope` (ядро,
// internal/configsign): payload с адресами ядра/DoH/seed-IP, подписанный Ed25519-ключом, который
// хранится ОФЛАЙН у владельца. Доверие даёт ПОДПИСЬ, а не транспорт: любой канал (резервный домен,
// DoH, дроп) лишь переносит документ, подменить его незаметно нельзя, а version+expiry ловят
// откат/заморозку (TUF-семантика).
//
// ⚠️ Самая опасная грабля — КАНОНИЧНЫЕ подписанные байты (проверено по configsign.go ядра):
// подпись покрывает НЕ переданный конверт, а отдельно пересобранный JSON
// `{"v":<version>,"e":<expires_at>,"k":<key_epoch>,"p":"<base64std(payload)>"}` — порядок полей
// v,e,k,p фиксирован, без пробелов, `p` — Base64 StdEncoding С ПАДДИНГОМ (НЕ url-safe, НЕ
// no-padding). Воспроизводим байт-в-байт (см. signedBytes) — сверено тестом с байтами, которые
// произвёл НАСТОЯЩИЙ Go-код ядра (DeliveryTest, вектор из configsign+delivery).
//
// Крипта: BouncyCastle lightweight API (Ed25519Signer + Ed25519PublicKeyParameters, bcprov-jdk18on).
// НЕ Tink (ждёт свой key-контейнер и префикс подписи) и НЕ java.security Ed25519 (только с API 33,
// у нас minSdk 24). НЕ JCA-Provider (в Android вшита урезанная копия BC — конфликт имён), а
// низкоуровневые классы напрямую. Ресёрч: docs/research/2026-07-11-android-ed25519-verify.md (ядро).
//
// Порядок проверок СТРОГО как у серверного configsign.Verify: подпись ПЕРВОЙ (полям непроверенного
// конверта верить нельзя) → срок → анти-откат → разбор payload. Любой отказ = документ НЕ применяется,
// приложение живёт на прежнем списке (fail-closed, но без потери связи).
package org.amnezia.awg.mayak.core

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.bouncycastle.crypto.params.Ed25519PublicKeyParameters
import org.bouncycastle.crypto.signers.Ed25519Signer
import java.net.URI
import java.util.Base64

/** Полезная нагрузка документа доставки — зеркало internal/delivery.Doc ядра (формат additive:
 *  неизвестные поля игнорируются, свои deny-семантики не несут — инвариант delivery.Parse). */
data class DeliveryDoc(
    /** Базовые URL ядра по приоритету: [0] основной (L0), дальше резервы (L1). Всегда непуст. */
    val cores: List<String>,
    /** DoH-резолверы (L2) + их bootstrap-IP (резолвить сам резолвер БЕЗ системного DNS). */
    val doh: List<DeliveryDohResolver>,
    /** Seed-IP ядра (L4, последний резерв, когда доменов не осталось). На сервере пока пусто. */
    val seeds: List<String>,
    /** Подсказка, как часто перепроверять документ (секунды); 0 — клиент решает сам. */
    val ttlHintSec: Int,
)

data class DeliveryDohResolver(val url: String, val bootstrapIps: List<String>)

/** Причины отказа. Различимы для лога/диагностики, но исход один: документ НЕ применяется. */
enum class DeliveryReject { MALFORMED, UNKNOWN_EPOCH, BAD_SIGNATURE, EXPIRED, ROLLED_BACK, BAD_PAYLOAD }

sealed class DeliveryOutcome {
    data class Ok(
        val doc: DeliveryDoc,
        val version: Int,
        val keyEpoch: Int,
        val expiresAt: Long,
    ) : DeliveryOutcome()

    data class Rejected(val reason: DeliveryReject) : DeliveryOutcome()
}

object Delivery {
    /** Порт client-api ядра — тот же, что у вшитого IP-фолбэка (https://<ip>:8443). */
    private const val CLIENT_API_PORT = 8443

    // Лимиты — как у серверного delivery.Validate (что сервер не подпишет, то и не принимаем).
    private const val MAX_LIST = 16
    private const val MAX_URL_LEN = 300

    private val json = Json { ignoreUnknownKeys = true }

    /** Внешний конверт (configsign.Envelope). Payload/Sig в Go — []byte → base64-строки в JSON.
     *  Парсим ТОЛЕРАНТНО: внешний JSON не подписан, каноничность нужна только signedBytes. */
    @Serializable
    private data class EnvelopeDto(
        val version: Int,
        @SerialName("expires_at") val expiresAt: Long,
        @SerialName("key_epoch") val keyEpoch: Int,
        val payload: String,
        val sig: String,
    )

    @Serializable
    private data class DohDto(
        val url: String = "",
        @SerialName("bootstrap_ips") val bootstrapIps: List<String> = emptyList(),
    )

    @Serializable
    private data class DocDto(
        val cores: List<String> = emptyList(),
        val doh: List<DohDto> = emptyList(),
        val seeds: List<String> = emptyList(),
        @SerialName("ttl_hint") val ttlHint: Int = 0,
    )

    /**
     * КАНОНИЧНЫЕ подписанные байты — байт-в-байт как configsign.signedBytes ядра:
     * Go `json.Marshal(struct{V,E,K,P})` с фикс. порядком полей и `[]byte` как base64std.
     * Собираем строку РУКАМИ, а не сериализатором: сериализатор имеет право переставить поля
     * или вставить пробел — и подпись перестанет сходиться при верном ключе.
     * Go HTML-эскейпит `<>&`, но в числах и base64-алфавите (A-Za-z0-9+/=) их не бывает.
     */
    fun signedBytes(version: Int, expiresAt: Long, keyEpoch: Int, payload: ByteArray): ByteArray {
        // java.util.Base64: СТАНДАРТНЫЙ алфавит, С паддингом, без переносов (= Base64.NO_WRAP
        // Android). Доступен с minSdk 24 через core-library desugaring (как в WsDatagramClient).
        val p = Base64.getEncoder().encodeToString(payload)
        return """{"v":$version,"e":$expiresAt,"k":$keyEpoch,"p":"$p"}""".toByteArray(Charsets.UTF_8)
    }

    /**
     * Полная проверка конверта. [anchorsByEpoch] — вшитые trust-anchor'ы (эпоха → 32 байта pubkey);
     * выбор СТРОГО по key_epoch конверта, неизвестная эпоха = отказ (fail-closed, «попробовать все
     * ключи» запрещено). [minVersion] — последняя ПРИНЯТАЯ версия (анти-откат, хранится персистентно);
     * документ с version < minVersion отвергается, равная проходит (повторное скачивание того же —
     * норма). [nowUnix] обязан быть настоящим временем: 0/минус — отказ, как у ядра (нулевое время
     * молча отключало бы проверку протухания).
     */
    fun verify(
        envelopeJson: String,
        anchorsByEpoch: Map<Int, ByteArray>,
        nowUnix: Long,
        minVersion: Int,
    ): DeliveryOutcome {
        val env = try {
            json.decodeFromString(EnvelopeDto.serializer(), envelopeJson)
        } catch (e: Exception) {
            return DeliveryOutcome.Rejected(DeliveryReject.MALFORMED)
        }
        val payload = b64(env.payload) ?: return DeliveryOutcome.Rejected(DeliveryReject.MALFORMED)
        val sig = b64(env.sig) ?: return DeliveryOutcome.Rejected(DeliveryReject.MALFORMED)
        if (sig.size != 64) return DeliveryOutcome.Rejected(DeliveryReject.MALFORMED)

        val anchor = anchorsByEpoch[env.keyEpoch]?.takeIf { it.size == 32 }
            ?: return DeliveryOutcome.Rejected(DeliveryReject.UNKNOWN_EPOCH)

        // 1) ПОДПИСЬ ПЕРВОЙ — до неё ни одному полю конверта верить нельзя.
        if (!verifyEd25519(anchor, signedBytes(env.version, env.expiresAt, env.keyEpoch, payload), sig)) {
            return DeliveryOutcome.Rejected(DeliveryReject.BAD_SIGNATURE)
        }
        // 2) Срок (анти-freeze/replay). Граница как у ядра: now >= expires — протух.
        if (nowUnix <= 0) return DeliveryOutcome.Rejected(DeliveryReject.MALFORMED)
        if (nowUnix >= env.expiresAt) return DeliveryOutcome.Rejected(DeliveryReject.EXPIRED)
        // 3) Анти-откат.
        if (env.version < minVersion) return DeliveryOutcome.Rejected(DeliveryReject.ROLLED_BACK)
        // 4) Полезная нагрузка — только после того, как конверту можно верить.
        val doc = parseDoc(payload) ?: return DeliveryOutcome.Rejected(DeliveryReject.BAD_PAYLOAD)
        return DeliveryOutcome.Ok(doc, env.version, env.keyEpoch, env.expiresAt)
    }

    /** Чистый Ed25519 (RFC 8032, как Go crypto/ed25519): Ed25519Signer, НЕ ph/ctx-варианты. */
    internal fun verifyEd25519(pubKeyRaw: ByteArray, message: ByteArray, sig: ByteArray): Boolean {
        if (pubKeyRaw.size != 32 || sig.size != 64) return false
        return try {
            val verifier = Ed25519Signer()
            verifier.init(false, Ed25519PublicKeyParameters(pubKeyRaw, 0))
            verifier.update(message, 0, message.size)
            verifier.verifySignature(sig)
        } catch (e: Exception) {
            false // кривая точка/битый ключ — это «подпись не сошлась», а не краш приложения
        }
    }

    /** Seed-IP → базовый URL client-api (как вшитый IP-фолбэк). IPv6 — в квадратных скобках. */
    fun seedUrl(ip: String): String =
        if (ip.contains(':')) "https://[$ip]:$CLIENT_API_PORT" else "https://$ip:$CLIENT_API_PORT"

    /**
     * DoH-эндпоинты ПО IP из документа: bootstrap-IP + путь из URL резолвера — ровно тот вид,
     * в котором DohResolver ходит без системного DNS. Путь не выдумываем: берём как в документе.
     */
    fun dohEndpoints(doc: DeliveryDoc): List<String> {
        val out = LinkedHashSet<String>()
        for (r in doc.doh) {
            val path = try {
                URI(r.url).rawPath.orEmpty()
            } catch (e: Exception) {
                continue // не разобрали URL — эндпоинт пропускаем, не ломаясь
            }
            for (ip in r.bootstrapIps) {
                out.add(if (ip.contains(':')) "https://[$ip]$path" else "https://$ip$path")
            }
        }
        return out.toList()
    }

    // ---- разбор и валидация payload (зеркало delivery.Validate ядра) ----

    private fun parseDoc(payload: ByteArray): DeliveryDoc? {
        val dto = try {
            json.decodeFromString(DocDto.serializer(), payload.toString(Charsets.UTF_8))
        } catch (e: Exception) {
            return null
        }
        if (dto.cores.isEmpty()) return null // пустой список ядер сервер не подписывает
        if (dto.cores.size > MAX_LIST || dto.doh.size > MAX_LIST || dto.seeds.size > MAX_LIST) return null
        if (dto.ttlHint < 0) return null
        if (dto.cores.size != dto.cores.toSet().size) return null
        if (!dto.cores.all { isHttpsBase(it) }) return null
        val dohUrls = dto.doh.map { it.url }
        if (dohUrls.size != dohUrls.toSet().size) return null
        for (r in dto.doh) {
            if (!isHttpsEndpoint(r.url)) return null
            if (r.bootstrapIps.isEmpty()) return null // резолвер нечем достать минуя DNS
            if (!r.bootstrapIps.all { isIpLiteral(it) }) return null
        }
        if (dto.seeds.size != dto.seeds.toSet().size) return null
        if (!dto.seeds.all { isIpLiteral(it) }) return null
        return DeliveryDoc(
            cores = dto.cores,
            doh = dto.doh.map { DeliveryDohResolver(it.url, it.bootstrapIps) },
            seeds = dto.seeds,
            ttlHintSec = dto.ttlHint,
        )
    }

    /** https-БАЗА ядра: без пути/query/fragment (клиент сам приклеивает /v1/...). */
    private fun isHttpsBase(s: String): Boolean {
        val u = parseHttps(s) ?: return false
        val path = u.rawPath.orEmpty()
        return path.isEmpty() || path == "/"
    }

    /** https-эндпоинт: путь РАЗРЕШЁН (DoH вида /dns-query), query/fragment — нет. */
    private fun isHttpsEndpoint(s: String): Boolean = parseHttps(s) != null

    private fun parseHttps(s: String): URI? {
        if (s.isEmpty() || s.length > MAX_URL_LEN) return null
        val u = try {
            URI(s)
        } catch (e: Exception) {
            return null
        }
        if (u.isOpaque || !u.isAbsolute) return null
        if (u.scheme != "https") return null
        if (u.rawUserInfo != null) return null // authority-confusion `https://trusted@evil`
        val host = u.host ?: return null
        if (host.isEmpty()) return null
        if (host.any { it.code > 127 }) return null // homograph — punycode задавать явно
        if (u.port != -1 && u.port !in 1..65535) return null
        if (u.rawQuery != null || u.rawFragment != null) return null
        return u
    }

    /** Строгий IP-литерал (v4/v6). Гейт по символам ГАРАНТИРУЕТ, что InetAddress не пойдёт в DNS. */
    private fun isIpLiteral(s: String): Boolean {
        if (s.isEmpty() || s.length > 45) return false
        val v4 = s.count { it == '.' } == 3 && s.all { it.isDigit() || it == '.' }
        val v6 = s.contains(':') &&
            s.all { it.isDigit() || it in 'a'..'f' || it in 'A'..'F' || it == ':' || it == '.' }
        if (!v4 && !v6) return false
        return try {
            java.net.InetAddress.getByName(s) // для литерала DNS не дёргается (гейт выше)
            true
        } catch (e: Exception) {
            false
        }
    }

    private fun b64(s: String): ByteArray? = try {
        Base64.getDecoder().decode(s) // строгий std-декодер: url-safe/мусор — отказ
    } catch (e: IllegalArgumentException) {
        null
    }
}
