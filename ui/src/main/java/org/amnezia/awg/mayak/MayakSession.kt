// Оркестрация клиентской сессии «Маяк» поверх :core. Держит токен/ключи/device_id в SecureStore,
// дергает MayakBackend и рендерит готовые .conf (прямой + резерв). Приватный ключ генерится на
// устройстве и НИКОГДА не уходит в ядро (ADR-0004) — в connect/devices летит только pubkey.
package org.amnezia.awg.mayak

import android.os.Build
import android.os.SystemClock
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import org.amnezia.awg.mayak.core.ConfRenderer
import org.amnezia.awg.mayak.core.Direction
import org.amnezia.awg.mayak.core.HostProvider
import org.amnezia.awg.mayak.core.HwidProvider
import org.amnezia.awg.mayak.core.KeyProvider
import org.amnezia.awg.mayak.core.MayakBackend
import org.amnezia.awg.mayak.core.SecureStore

/** Готовые конфиги на выбранное направление: прямой (обязателен) + резервный (если ядро дало).
 *  *Endpoint — «IP:port» сервера соответствующего пути (после DoH-резолва) для пинга текущего подключения.
 *  @Serializable — чтобы сохранять последний РАБОЧИЙ конфиг на диск (offline-фоллбэк при недоступном ядре). */
@Serializable
data class Paths(
    val directionName: String,
    val directConf: String?,
    val relayConf: String?,
    val directEndpoint: String? = null,
    val relayEndpoint: String? = null,
)

/** Сохранённая на диск запись offline-фоллбэка: конфиг направления + настенная метка сохранения (мс).
 *  Список таких (а НЕ Map<Long,…>) сериализуем через ListSerializer — проверенный паттерн (как dirs_cache),
 *  без reified serializer<>()/Long-ключей карты (чтоб гарантированно не падать на старте). */
@Serializable
private data class PersistedEntry(val directionId: Long, val paths: Paths, val atWallMs: Long)

class MayakSession(
    private val store: SecureStore,
    private val keys: KeyProvider,
    private val hwids: HwidProvider,
) {
    companion object {
        private const val K_TOKEN = "token"
        private const val K_EMAIL = "email" // email аккаунта (показываем в Настройках, чтоб видеть, кто залогинен)
        private const val K_PRIV = "priv_key"
        private const val K_PUB = "pub_key"
        private const val K_DEVICE = "device_id"
        private const val K_DIRS_CACHE = "dirs_cache"

        // Последний УСПЕШНО подключившийся конфиг на диск (offline-фоллбэк): если ядро недоступно
        // (NoReachableHostException — инцидент SPOF ядра 2026-07-05), поднимаем сохранённый конфиг
        // ВМЕСТО «Ядро недоступно». Работает, т.к. туннель идёт устройство→ЭКЗИТ, а ядро — лишь выдаёт
        // конфиг; при живом экзите сохранённого достаточно. Overlay-IP на устройство стабилен (SPEC-0015)
        // → старый конфиг почти всегда валиден. Шифруется at-rest тем же SecureStore (в .conf есть priv-ключ,
        // но он и так лежит в K_PRIV того же хранилища → нового секрета на диск не добавляем).
        private const val K_LAST_GOOD = "last_good_v1"

        // Потолок возраста сохранённого конфига: 7 дней. Старше → не используем (аренда/топология
        // могли устареть безнадёжно). Фоллбэк только когда ядро реально недоступно — свежий /connect
        // всегда приоритетен, диск лишь резерв. Метка — НАСТЕННЫЕ часы (переживает ребут/смерть процесса).
        private const val LAST_GOOD_TTL_MS = 7L * 24 * 60 * 60 * 1000L

        // Процесс-скоупный кэш направлений: живёт, пока жив процесс, и ПЕРЕЖИВАЕТ пересоздание
        // Activity (смена темы/языка) — поэтому смена темы больше не дёргает сеть. MayakSession
        // создаётся заново на каждом onCreate, так что in-memory-слой держим в companion (static).
        @Volatile private var memDirections: List<Direction>? = null

        // Монотонная метка последнего СЕТЕВОГО фетча направлений (SystemClock.elapsedRealtime, мс).
        // 0 = кэш не из сети (свежий cold-start из зашифрованного хранилища) → считаем устаревшим, чтобы
        // первый onCreate дотянул свежий список. directionsFresh(ttl) по ней решает, нужен ли рефетч:
        // смена темы происходит в пределах TTL → сеть молчит; переоткрытие спустя TTL → рефетч (новые
        // направления появляются сами, без перелогина — примиряет оба бага владельца 06-27/06-28).
        @Volatile private var memDirectionsAt: Long = 0L

        // Процесс-скоупный кэш предзагруженных /connect-конфигов (переживает пересоздание Activity →
        // смена темы не дёргает /connect повторно). Одноразовый (take удаляет). Содержит приватный ключ
        // в .conf → только в памяти, чистим вместе с направлениями (логин/выход/фейловер).
        // Храним метку времени: конфиг несёт overlay-IP-«аренду» (SPEC-0015). Аренда живёт на сервере ~3ч;
        // чтобы не подключиться по УСТАРЕВШЕЙ (возможно уже переосвобождённой/переданной) аренде, кэш старше
        // CONNECT_CACHE_TTL_MS считаем протухшим → take вернёт null → потянем свежий /connect.
        private data class CachedPaths(val paths: Paths, val atElapsed: Long)
        private val connectCache = ConcurrentHashMap<Long, CachedPaths>()

        // 2ч < серверного TTL аренды (3ч) → предзагруженный конфиг не «переживёт» свою аренду.
        private const val CONNECT_CACHE_TTL_MS = 2 * 60 * 60 * 1000L

        private val dirsSerializer = ListSerializer(Direction.serializer())

        // Сохранённые конфиги на диск: список записей, одним ключом в SecureStore (чистый logout: снять
        // один K_LAST_GOOD). `by lazy` → инициализация НЕ на старте приложения (первый доступ — уже внутри
        // runCatching в readLastGood), поэтому даже теоретический сбой сериализатора не роняет запуск.
        private val lastGoodSerializer by lazy { ListSerializer(PersistedEntry.serializer()) }
    }

    // Сериализатор кэша направлений: переиспользуем Json из :core (он же в MayakBackend).
    private val json = MayakBackend.defaultJson

    fun hasToken(): Boolean = store.get(K_TOKEN) != null

    /** Email аккаунта, под которым выполнен вход (для показа в Настройках). null — если не входили. */
    fun email(): String? = store.get(K_EMAIL)

    fun logout() {
        store.remove(K_TOKEN)
        store.remove(K_EMAIL)
        store.remove(K_DEVICE)
        store.remove(K_LAST_GOOD) // сохранённый конфиг прошлого пользователя не должен пережить выход
        invalidateDirections() // чужой кэш не должен пережить выход
        // ключи устройства оставляем — это идентичность устройства; токен/девайс перезаведём при логине
    }

    /** Логин по email: получаем токен и кладём в защищённое хранилище. */
    suspend fun login(backend: MayakBackend, email: String, password: String) {
        val resp = backend.login(email, password)
        store.put(K_TOKEN, resp.token)
        store.put(K_EMAIL, email.trim()) // показываем в Настройках, под кем вошли
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

    /**
     * Свеж ли кэш направлений: получен из сети (memDirectionsAt != 0) и моложе ttlMs. true → рефетч не
     * нужен (напр. пересоздание Activity при смене темы в пределах TTL). false → кэш устарел/из хранилища
     * → UI дотянет свежий список. Не путать с hasCachedDirections (есть ли ЧТО показать вообще).
     */
    fun directionsFresh(ttlMs: Long): Boolean {
        val at = memDirectionsAt
        return at != 0L && memDirections != null && (SystemClock.elapsedRealtime() - at) < ttlMs
    }

    /** Сбросить кэш направлений (смена логина/выход/неуспешный коннект — топология могла измениться). */
    fun invalidateDirections() {
        memDirections = null
        memDirectionsAt = 0L
        connectCache.clear() // предзагруженные конфиги прошлой топологии/пользователя тоже неактуальны
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
        memDirectionsAt = SystemClock.elapsedRealtime() // отметка «свежо из сети» → смена темы не рефетчит
        // SecureStore (KeystoreSecureStore) уже шифрует at-rest → кэш зашифрован переиспользованием.
        // TODO(tech-debt): KeystoreSecureStore на депрекейтнутом androidx.security.crypto — мигрировать
        //   на Android Keystore напрямую / datastore-tink (отдельная задача, см. docs/research 2026-06-27).
        runCatching { store.put(K_DIRS_CACHE, json.encodeToString(dirsSerializer, dirs)) }
    }

    /**
     * Подключение к направлению: гарантируем ключи + регистрацию устройства, берём конфиги у ядра и
     * рендерим .conf с локальной подстановкой приватного ключа.
     */
    // На Dispatchers.IO целиком: тут сеть (backend.connect) И блокирующий DoH-резолв (dohEndpoint).
    // Иначе DoH на главном потоке кидает NetworkOnMainThreadException (фича была мертва, фоллбэк на IP).
    suspend fun connect(backend: MayakBackend, direction: Direction): Paths = withContext(Dispatchers.IO) {
        val token = requireToken()
        val priv = ensureKeys()
        val deviceId = ensureDevice(backend, token)
        val res = backend.connect(token, deviceId, direction.id)
        // DoH-резолв endpoint делаем ОДИН раз на путь (и для .conf, и для пинг-хоста).
        val directCfg = res.direct?.let { dohEndpoint(it) }
        val relayCfg = res.relay?.let { dohEndpoint(it) }
        Paths(
            directionName = res.direction,
            directConf = directCfg?.let { ConfRenderer.render(it, priv) },
            relayConf = relayCfg?.let { ConfRenderer.render(it, priv) },
            directEndpoint = directCfg?.endpoint,
            relayEndpoint = relayCfg?.endpoint,
        )
    }

    /**
     * Предзагрузить конфиг направления в процесс-скоупный кэш (тёплый кэш к моменту коннекта: в момент
     * подключения НЕ дёргаем api.mayakvpn.ru — РФ-DPI палит наш домен рядом с хендшейком). Переживает
     * пересоздание Activity, поэтому смена темы не гоняет /connect заново. Одноразовый (см. takeCachedConnect).
     */
    suspend fun preloadConnect(backend: MayakBackend, direction: Direction) {
        connectCache[direction.id] = CachedPaths(connect(backend, direction), android.os.SystemClock.elapsedRealtime())
    }

    /** Тёплый СВЕЖИЙ предзагруженный конфиг направления? (UI решает, надо ли гонять preloadConnect). Протухший
     *  (старше CONNECT_CACHE_TTL_MS) считаем отсутствующим — чтобы UI предзагрузил свежий. */
    fun hasCachedConnect(directionId: Long): Boolean {
        val c = connectCache[directionId] ?: return false
        return android.os.SystemClock.elapsedRealtime() - c.atElapsed <= CONNECT_CACHE_TTL_MS
    }

    /** Взять предзагруженный конфиг ОДНОРАЗОВО (удаляет из кэша — нет переиспользования устаревшего lease).
     *  Протухший (старше TTL аренды) НЕ отдаём → коннект дотянет свежий /connect (аренда могла освободиться). */
    fun takeCachedConnect(directionId: Long): Paths? {
        val c = connectCache.remove(directionId) ?: return null
        if (android.os.SystemClock.elapsedRealtime() - c.atElapsed > CONNECT_CACHE_TTL_MS) return null
        return c.paths
    }

    /**
     * Запомнить конфиг, который РЕАЛЬНО подключился (вызывать после успешной пробы egress). Сохраняется
     * на диск (зашифрованно) как offline-фоллбэк: при недоступном ядре поднимем именно его. Обновление
     * метки при повторном успехе продлевает жизнь конфига. Протухшие (> TTL) отсеиваем при записи.
     */
    fun rememberWorking(directionId: Long, paths: Paths) {
        val now = System.currentTimeMillis()
        // прочие направления сохраняем как есть, это направление перезаписываем, протухшие отсеиваем
        val kept = readLastGood().filter { it.directionId != directionId && now - it.atWallMs < LAST_GOOD_TTL_MS }
        val updated = kept + PersistedEntry(directionId, paths, now)
        runCatching { store.put(K_LAST_GOOD, json.encodeToString(lastGoodSerializer, updated)) }
    }

    /**
     * Последний РАБОЧИЙ конфиг направления с диска (offline-фоллбэк). null — нет сохранённого или он
     * старше TTL. Использовать ТОЛЬКО когда ядро недоступно (свежий /connect приоритетен всегда).
     */
    fun lastGoodPaths(directionId: Long): Paths? {
        val e = readLastGood().firstOrNull { it.directionId == directionId } ?: return null
        if (System.currentTimeMillis() - e.atWallMs > LAST_GOOD_TTL_MS) return null
        return e.paths
    }

    private fun readLastGood(): List<PersistedEntry> {
        val raw = store.get(K_LAST_GOOD) ?: return emptyList()
        return runCatching { json.decodeFromString(lastGoodSerializer, raw) }.getOrDefault(emptyList())
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

    /** keepalive аренды overlay-IP (SPEC-0015): продлеваем аренду, пока туннель поднят. Best-effort —
     *  нет токена/устройства или ошибка сети → тихо пропускаем (это не критичная операция). */
    suspend fun keepalive(backend: MayakBackend) {
        val token = store.get(K_TOKEN) ?: return
        val dev = deviceId()
        if (dev == 0L) return
        backend.keepalive(token, dev)
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
        val cached = store.get(K_DEVICE)?.toLongOrNull()
        val pub = store.get(K_PUB) ?: throw IllegalStateException("нет публичного ключа")
        // Регистрируем при КАЖДОМ старте сессии: ядро апсертит устройство по (user, hwid) — слот
        // переиспользуется (не плодит устройства, не упирается в лимит), зато имя (модель телефона)
        // и last_seen обновляются БЕЗ переустановки. Если ядро недоступно — берём закэшированный id,
        // чтобы офлайн-подключение (last-good conf) не сломалось.
        return try {
            val resp = backend.registerDevice(token, pub, label = deviceName(), hwid = hwids.hwid())
            store.put(K_DEVICE, resp.deviceId.toString())
            resp.deviceId
        } catch (e: Exception) {
            cached ?: throw e
        }
    }

    /** Человекочитаемое имя устройства для кабинета: «Производитель Модель · Android N»
     *  (напр. «Samsung SM-G991B · Android 14»). Модель у нас и так собирается для диаг-логов
     *  (DiagCollector). Без ПДн сверх модели и версии ОС. Раньше слали захардкоженное "android". */
    private fun deviceName(): String {
        val manu = (Build.MANUFACTURER ?: "").trim()
        val model = (Build.MODEL ?: "").trim()
        val base = when {
            model.isEmpty() && manu.isEmpty() -> "Android"
            manu.isEmpty() || model.startsWith(manu, ignoreCase = true) -> model.ifEmpty { manu }
            else -> "$manu $model"
        }.replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.getDefault()) else it.toString() }
        val ver = (Build.VERSION.RELEASE ?: "").trim()
        return if (ver.isEmpty()) base else "$base · Android $ver"
    }
}
