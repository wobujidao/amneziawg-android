// Оркестрация клиентской сессии «Маяк» поверх :core. Держит токен/ключи/device_id в SecureStore,
// дергает MayakBackend и рендерит готовые .conf (прямой + резерв). Приватный ключ генерится на
// устройстве и НИКОГДА не уходит в ядро (ADR-0004) — в connect/devices летит только pubkey.
package org.amnezia.awg.mayak

import org.amnezia.awg.mayak.core.ConfRenderer
import org.amnezia.awg.mayak.core.Direction
import org.amnezia.awg.mayak.core.HostProvider
import org.amnezia.awg.mayak.core.KeyProvider
import org.amnezia.awg.mayak.core.MayakBackend
import org.amnezia.awg.mayak.core.SecureStore

/** Готовые конфиги на выбранное направление: прямой (обязателен) + резервный (если ядро дало). */
data class Paths(
    val directionName: String,
    val directConf: String?,
    val relayConf: String?,
)

class MayakSession(
    private val store: SecureStore,
    private val keys: KeyProvider,
) {
    companion object {
        private const val K_TOKEN = "token"
        private const val K_PRIV = "priv_key"
        private const val K_PUB = "pub_key"
        private const val K_DEVICE = "device_id"
    }

    fun hasToken(): Boolean = store.get(K_TOKEN) != null

    fun logout() {
        store.remove(K_TOKEN)
        store.remove(K_DEVICE)
        // ключи устройства оставляем — это идентичность устройства; токен/девайс перезаведём при логине
    }

    /** Логин: получаем токен и кладём в защищённое хранилище. */
    suspend fun login(backend: MayakBackend, login: String, password: String) {
        val resp = backend.login(login, password)
        store.put(K_TOKEN, resp.token)
    }

    suspend fun directions(backend: MayakBackend): List<Direction> {
        val token = requireToken()
        return backend.directions(token)
    }

    /**
     * Подключение к направлению: гарантируем ключи + регистрацию устройства, берём конфиги у ядра и
     * рендерим .conf с локальной подстановкой приватного ключа.
     */
    suspend fun connect(backend: MayakBackend, direction: Direction): Paths {
        val token = requireToken()
        val priv = ensureKeys()
        val deviceId = ensureDevice(backend, token)
        val res = backend.connect(token, deviceId, direction.id)
        return Paths(
            directionName = res.direction,
            directConf = res.direct?.let { ConfRenderer.render(it, priv) },
            relayConf = res.relay?.let { ConfRenderer.render(it, priv) },
        )
    }

    private fun requireToken(): String =
        store.get(K_TOKEN) ?: throw IllegalStateException("нет токена — нужен вход")

    /** Приватный ключ (base64). Генерим один раз на устройстве и переиспользуем. */
    private fun ensureKeys(): String {
        store.get(K_PRIV)?.let { return it }
        val km = keys.generate()
        store.put(K_PRIV, km.privateKeyBase64)
        store.put(K_PUB, km.publicKeyBase64)
        return km.privateKeyBase64
    }

    private suspend fun ensureDevice(backend: MayakBackend, token: String): Long {
        store.get(K_DEVICE)?.toLongOrNull()?.let { return it }
        val pub = store.get(K_PUB) ?: throw IllegalStateException("нет публичного ключа")
        val resp = backend.registerDevice(token, pub, label = "android")
        store.put(K_DEVICE, resp.deviceId.toString())
        return resp.deviceId
    }
}
