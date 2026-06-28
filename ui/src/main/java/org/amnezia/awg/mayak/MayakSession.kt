// Оркестрация клиентской сессии «Маяк» поверх :core. Держит токен/ключи/device_id в SecureStore,
// дергает MayakBackend и рендерит готовые .conf (прямой + резерв). Приватный ключ генерится на
// устройстве и НИКОГДА не уходит в ядро (ADR-0004) — в connect/devices летит только pubkey.
package org.amnezia.awg.mayak

import kotlinx.serialization.builtins.ListSerializer
import org.amnezia.awg.mayak.core.ConfRenderer
import org.amnezia.awg.mayak.core.Direction
import org.amnezia.awg.mayak.core.HostProvider
import org.amnezia.awg.mayak.core.HwidProvider
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
    private val hwids: HwidProvider,
) {
    companion object {
        private const val K_TOKEN = "token"
        private const val K_PRIV = "priv_key"
        private const val K_PUB = "pub_key"
        private const val K_DEVICE = "device_id"
        private const val K_DIRS_CACHE = "dirs_cache"

        // Процесс-скоупный кэш направлений: живёт, пока жив процесс, и ПЕРЕЖИВАЕТ пересоздание
        // Activity (смена темы/языка) — поэтому смена темы больше не дёргает сеть. MayakSession
        // создаётся заново на каждом onCreate, так что in-memory-слой держим в companion (static).
        @Volatile private var memDirections: List<Direction>? = null

        private val dirsSerializer = ListSerializer(Direction.serializer())
    }

    // Сериализатор кэша направлений: переиспользуем Json из :core (он же в MayakBackend).
    private val json = MayakBackend.defaultJson

    fun hasToken(): Boolean = store.get(K_TOKEN) != null

    fun logout() {
        store.remove(K_TOKEN)
        store.remove(K_DEVICE)
        invalidateDirections() // чужой кэш не должен пережить выход
        // ключи устройства оставляем — это идентичность устройства; токен/девайс перезаведём при логине
    }

    /** Логин по email: получаем токен и кладём в защищённое хранилище. */
    suspend fun login(backend: MayakBackend, email: String, password: String) {
        val resp = backend.login(email, password)
        store.put(K_TOKEN, resp.token)
        invalidateDirections() // смена логина → кэш направлений прошлого пользователя неактуален
    }

    /**
     * Направления с кэшем. По умолчанию отдаём кэш (in-memory → зашифрованное хранилище), чтобы
     * пересоздание Activity (смена темы) НЕ ходило в сеть. forceRefresh=true (явный рефреш/
     * фейловер) принудительно идёт в ядро и обновляет кэш. Кэш одноразовый: на пустоту/порчу — рефетч.
     */
    suspend fun directions(backend: MayakBackend, forceRefresh: Boolean = false): List<Direction> {
        if (!forceRefresh) {
            cachedDirections()?.let { return it }
        }
        val token = requireToken()
        val dirs = backend.directions(token)
        cacheDirections(dirs)
        return dirs
    }

    /** Есть ли готовый кэш направлений (UI решает, показывать ли «загрузка…» или отдать мгновенно). */
    fun hasCachedDirections(): Boolean = cachedDirections() != null

    /** Сбросить кэш направлений (смена логина/выход/неуспешный коннект — топология могла измениться). */
    fun invalidateDirections() {
        memDirections = null
        store.remove(K_DIRS_CACHE)
    }

    /** Кэш направлений: in-memory → зашифрованное хранилище. null — кэша нет или он битый. */
    private fun cachedDirections(): List<Direction>? {
        memDirections?.let { return it }
        val raw = store.get(K_DIRS_CACHE) ?: return null
        return runCatching { json.decodeFromString(dirsSerializer, raw) }
            .getOrNull()?.takeIf { it.isNotEmpty() }?.also { memDirections = it }
    }

    /** Положить направления в кэш (in-memory + зашифрованное хранилище через SecureStore). */
    private fun cacheDirections(dirs: List<Direction>) {
        memDirections = dirs
        // SecureStore (KeystoreSecureStore) уже шифрует at-rest → кэш зашифрован переиспользованием.
        // TODO(tech-debt): KeystoreSecureStore на депрекейтнутом androidx.security.crypto — мигрировать
        //   на Android Keystore напрямую / datastore-tink (отдельная задача, см. docs/research 2026-06-27).
        runCatching { store.put(K_DIRS_CACHE, json.encodeToString(dirsSerializer, dirs)) }
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
            directConf = res.direct?.let { ConfRenderer.render(dohEndpoint(it), priv) },
            relayConf = res.relay?.let { ConfRenderer.render(dohEndpoint(it), priv) },
        )
    }

    // Если выдача дала FQDN endpoint — резолвим его через DoH (шифрованно, мимо подмены DNS оператором) и
    // подставляем полученный IP. При недоступности DoH остаётся IP-endpoint из /connect → связь не ломается.
    private fun dohEndpoint(cfg: org.amnezia.awg.mayak.core.ClientConfig): org.amnezia.awg.mayak.core.ClientConfig {
        if (cfg.endpointFqdn.isBlank()) return cfg
        val resolved = DohResolver.resolveEndpoint(cfg.endpointFqdn)
        return if (resolved != cfg.endpointFqdn) cfg.copy(endpoint = resolved) else cfg
    }

    /** Отправка диагностического лога на сервер (кнопка «Отправить лог»). Требует входа (токен). */
    suspend fun sendDiagLog(backend: MayakBackend, req: org.amnezia.awg.mayak.core.DiagLogRequest) =
        backend.sendDiagLog(requireToken(), req)

    /** id устройства из хранилища (0 — ещё не зарегистрировано); для контекста диаг-лога. */
    fun deviceId(): Long = store.get(K_DEVICE)?.toLongOrNull() ?: 0L

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
        // hwid стабилен на устройстве после переустановки → ядро апсертит устройство по (user, hwid)
        // и переиспользует слот (не плодит новые устройства, не упирается в лимит).
        val resp = backend.registerDevice(token, pub, label = "android", hwid = hwids.hwid())
        store.put(K_DEVICE, resp.deviceId.toString())
        return resp.deviceId
    }
}
