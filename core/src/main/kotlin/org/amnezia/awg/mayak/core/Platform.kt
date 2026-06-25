// Граница core ↔ платформа. Переносимая логика (:core) зовёт эти интерфейсы; их реализации —
// платформо-зависимые (Android: Keystore, tunnel-crypto Curve25519, GoBackend) и живут в :ui/:tunnel.
// Так core остаётся без Android-зависимостей и переиспользуется на iOS/Windows/Linux под нативным UI
// (решение владельца 2026-06-25: нативный UI + переносимый не-UI-core, путь Mullvad/Proton).
package org.amnezia.awg.mayak.core

/** Пара ключей AmneziaWG в base64. Приватный ключ НИКОГДА не покидает устройство (ADR-0004). */
data class KeyMaterial(
    val privateKeyBase64: String,
    val publicKeyBase64: String,
)

/** Генерация ключей на устройстве. Android-реализация — поверх org.amnezia.awg.crypto.KeyPair. */
interface KeyProvider {
    fun generate(): KeyMaterial
}

/**
 * Защищённое хранилище секретов устройства (токен сессии, приватный ключ).
 * Android-реализация — Android Keystore / EncryptedSharedPreferences.
 */
interface SecureStore {
    fun get(key: String): String?
    fun put(key: String, value: String)
    fun remove(key: String)
}

/** Поднятие/спуск туннеля по готовому wg-quick .conf. Android-реализация — поверх GoBackend. */
interface Tunnel {
    suspend fun up(confText: String)
    suspend fun down()
}

/**
 * Сквозная проба egress: реально ли вышли в интернет через туннель (а не только хендшейк).
 * При блоке по AS хендшейк живёт, трафика нет — ловим это здесь (ADR-0012).
 * Возвращает внешний IP (для сверки со страной экзита) либо null, если egress не прошёл.
 */
interface EgressProbe {
    suspend fun externalIp(): String?
}
