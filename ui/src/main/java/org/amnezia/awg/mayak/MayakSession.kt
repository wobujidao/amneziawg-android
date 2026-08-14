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
import org.amnezia.awg.BuildConfig
import org.amnezia.awg.mayak.core.ConfRenderer
import org.amnezia.awg.mayak.core.Direction
import org.amnezia.awg.mayak.core.DohResolver
import org.amnezia.awg.mayak.core.HostProvider
import org.amnezia.awg.mayak.core.HwidProvider
import org.amnezia.awg.mayak.core.KeyProvider
import org.amnezia.awg.mayak.core.MayakApiException
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
    // Запасной канал плеча (SPEC-0039): чем поднимать AWG, когда оператор душит UDP. null — линия
    // запасного канала не выдала (поле `fallback` в /connect отсутствует), работаем как раньше.
    // Поля с дефолтами → сохранённые на диск last-good конфиги СТАРОГО формата читаются как прежде.
    val directFallback: org.amnezia.awg.mayak.core.Fallback? = null,
    val relayFallback: org.amnezia.awg.mayak.core.Fallback? = null,
    // Монотонная метка выдачи (SystemClock.elapsedRealtime). Нужна лестнице: пир появляется на ноде
    // только на следующем поллинге агента, и по возрасту конфига видно, доехал он уже или ещё нет
    // (FallbackDecision.peerSyncSlackMs). 0 = возраст неизвестен → ждать нечего.
    //
    // @Transient — на диск НЕ пишем осознанно: elapsedRealtime обнуляется при перезагрузке, и
    // сохранённое значение после ребута означало бы «конфиг из будущего». Конфиг с диска и так
    // заведомо не свежий, поэтому 0 для него — правильный ответ, а не потеря данных.
    @kotlinx.serialization.Transient
    val issuedAtElapsed: Long = 0L,
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

        // На каком языке получены имена в K_DIRS_CACHE («ru»/«en»). Человек переключил язык →
        // подпись разошлась → кэш считается чужим и список тянется заново (дефект 0.5.2: интерфейс
        // стал английским, а страны остались русскими).
        private const val K_DIRS_LANG = "dirs_cache_lang"

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

        // Языковая подпись того, что лежит в memDirections (см. K_DIRS_LANG).
        @Volatile private var memDirectionsLang: String? = null

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

    /**
     * ЧТО ВВЕЛИ в поле входа: почта — или НОМЕР АККАУНТА (вход по нему работает с 11-08).
     *
     * 🔴 Это НЕ «почта аккаунта» и показывать это как почту нельзя: у вошедшего номером получалось
     * «Почта: 848681728». Настоящую почту отдаёт ядро — [accountCard]. Здесь остаётся ровно то, для
     * чего значение годится без сети: понять, под кем вошли, и подставить логин обратно в форму.
     */
    fun loginName(): String? = store.get(K_EMAIL)

    fun logout() {
        store.remove(K_TOKEN)
        store.remove(K_EMAIL)
        // Номер аккаунта — про КОНКРЕТНУЮ учётку: переживи он выход, следующий вошедший увидел бы
        // чужой номер и продиктовал его поддержке как свой.
        MayakAccountNumber.forget(store)
        store.remove(K_DEVICE)
        store.remove(K_LAST_GOOD) // сохранённый конфиг прошлого пользователя не должен пережить выход
        invalidateDirections() // чужой кэш не должен пережить выход
        // ключи устройства оставляем — это идентичность устройства; токен/девайс перезаведём при логине
    }

    /**
     * Принять сессию, ВЫДАННУЮ ПРИ РЕГИСТРАЦИИ (SPEC-0048): токен приезжает тем же ответом, что и
     * номер аккаунта, второй раз логиниться тем же паролем незачем.
     *
     * Кладём то же самое, что кладёт [login], плюс номер: он у такой учётки ЕДИНСТВЕННЫЙ якорь (почты
     * нет), и человек назовёт его поддержке. В `K_EMAIL` уходит номер — это поле значит «чем вошли»,
     * а не «почта аккаунта» (см. [loginName]), и именно номер подставится обратно в форму входа.
     *
     * ⚠️ Зовётся ТОЛЬКО когда сервер отдал непустой токен. В ветке «учётка создана, а сессия не
     * выдана» номер в хранилище класть нельзя: сессии нет, а сохранённый номер стал бы чужим для
     * следующего вошедшего (освежается он лишь при пустом кэше — MayakAccountNumber.refresh).
     */
    fun adoptRegistration(token: String, accountNumber: String) {
        require(token.isNotBlank()) { "нечего принимать: токен пустой" }
        store.put(K_TOKEN, token)
        store.put(K_EMAIL, accountNumber.trim())
        org.amnezia.awg.mayak.core.AccountNumber.remember(store, accountNumber)
        invalidateDirections() // новая учётка — чужой кэш направлений неактуален
    }

    /** Логин по email: получаем токен и кладём в защищённое хранилище. totpCode — код 2FA, если запрошен. */
    suspend fun login(backend: MayakBackend, email: String, password: String, totpCode: String = "") {
        val resp = backend.login(email, password, totpCode)
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
        // Язык в подписи ≠ текущему → кэш «свежим» не считаем ни при каком TTL: имена в нём чужие.
        if (memDirectionsLang != MayakBackend.namesLanguageBucket()) return false
        return at != 0L && memDirections != null && (SystemClock.elapsedRealtime() - at) < ttlMs
    }

    /** Сбросить кэш направлений (смена логина/выход/неуспешный коннект — топология могла измениться). */
    fun invalidateDirections() {
        memDirections = null
        memDirectionsAt = 0L
        memDirectionsLang = null
        connectCache.clear() // предзагруженные конфиги прошлой топологии/пользователя тоже неактуальны
        store.remove(K_DIRS_CACHE)
        store.remove(K_DIRS_LANG)
    }

    /**
     * Кэш направлений: in-memory → зашифрованное хранилище. null — кэша нет, он битый ИЛИ он на
     * чужом языке.
     *
     * Про язык. Названия стран приходят С СЕРВЕРА и зависят от языка телефона (`Accept-Language`).
     * Человек переключил язык — прежний кэш стал чужим: показывать «Нидерланды» при английском
     * интерфейсе нельзя. Поэтому кэш подписан «корзиной» языка, и при несовпадении мы отвечаем
     * «кэша нет» — вызывающий сходит в сеть и получит имена на нужном языке.
     */
    private fun cachedDirections(): List<Direction>? {
        val lang = MayakBackend.namesLanguageBucket()
        if (memDirections != null && memDirectionsLang == lang) return memDirections
        if (memDirections != null && memDirectionsLang != lang) {
            // Язык сменился на живом процессе: и память, и диск теперь про другой набор имён.
            memDirections = null
            memDirectionsAt = 0L
        }
        if (store.get(K_DIRS_LANG) != lang) return null
        val raw = store.get(K_DIRS_CACHE) ?: return null
        return runCatching { json.decodeFromString(dirsSerializer, raw) }
            .getOrNull()?.takeIf { it.isNotEmpty() }
            ?.also { memDirections = it; memDirectionsLang = lang }
    }

    /** Положить направления в кэш (in-memory + зашифрованное хранилище через SecureStore). */
    private fun cacheDirections(dirs: List<Direction>) {
        memDirections = dirs
        memDirectionsAt = SystemClock.elapsedRealtime() // отметка «свежо из сети» → смена темы не рефетчит
        // Подпись языком: на каком языке эти имена получены. Сменится язык телефона — кэш станет
        // чужим (см. cachedDirections) и список перезапросится сам.
        memDirectionsLang = MayakBackend.namesLanguageBucket()
        runCatching { store.put(K_DIRS_LANG, memDirectionsLang!!) }
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
        ensureKeys() // пара ключей должна существовать до регистрации устройства (она шлёт pubkey)
        val deviceId = ensureDevice(backend, token)
        // Приватный ключ читаем ПОСЛЕ регистрации: она может перевыпустить пару (ключ оказался занят
        // другим аккаунтом). Прочитанный раньше — старый, и .conf собрался бы на нём при том, что ядро
        // знает уже НОВЫЙ публичный: туннель поднимется, а трафик никуда не пойдёт. Поймано живым
        // проходом на эмуляторе 2026-07-27 («Туннель поднят, проверяю выход…» → «Не защищено»).
        val priv = ensureKeys()
        // app_version — версия приложения «Маяк» (BuildConfig.VERSION_NAME). Заполняем на /connect, иначе
        // на ядре поле приходит пустым (2026-07-23). Не путать с версией движка AmneziaWG.
        val res = backend.connect(token, deviceId, direction.id, BuildConfig.VERSION_NAME)
        // DoH-резолв endpoint делаем ОДИН раз на путь (и для .conf, и для пинг-хоста).
        val directCfg = res.direct?.let { dohEndpoint(it) }
        val relayCfg = res.relay?.let { dohEndpoint(it) }
        Paths(
            directionName = res.direction,
            directConf = directCfg?.let { ConfRenderer.render(it, priv) },
            relayConf = relayCfg?.let { ConfRenderer.render(it, priv) },
            directEndpoint = directCfg?.endpoint,
            relayEndpoint = relayCfg?.endpoint,
            directFallback = directCfg?.fallback,
            relayFallback = relayCfg?.fallback,
            issuedAtElapsed = SystemClock.elapsedRealtime(),
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

    /**
     * Самоудаление аккаунта (требование Google Play: удаление доступно ИЗ приложения).
     * Ядро уничтожает профиль, устройства и выдачи; локально после этого держать нечего — стираем
     * токен и сохранённые конфиги тем же logout(), что и при обычном выходе.
     * Ошибка ядра (в т.ч. неверный пароль) пробрасывается наверх — локальные данные не трогаем.
     */
    suspend fun deleteAccount(backend: MayakBackend, password: String) {
        backend.deleteAccount(requireToken(), password)
        logout()
    }

    /** Тихий еженедельный телеметри-бикон (MayakTelemetryWorker). Требует токен — воркер сам no-op'ит
     *  через hasToken() до вызова, если пользователь не вошёл. */
    suspend fun sendTelemetry(backend: MayakBackend, req: org.amnezia.awg.mayak.core.TelemetryRequest) =
        backend.telemetry(requireToken(), req)

    /** Пресеты split-туннеля (SPEC-0028): синхрон с ядра (кэш в MayakPresets) + CRUD своих. Токен —
     *  внутри session (не светим наружу). */
    suspend fun syncPresets(context: android.content.Context, backend: MayakBackend) {
        MayakPresets.sync(context, backend, requireToken())
    }

    suspend fun createPreset(backend: MayakBackend, w: org.amnezia.awg.mayak.core.PresetWrite): Long =
        backend.createPreset(requireToken(), w)

    suspend fun updatePreset(backend: MayakBackend, id: Long, w: org.amnezia.awg.mayak.core.PresetWrite) =
        backend.updatePreset(requireToken(), id, w)

    suspend fun deletePreset(backend: MayakBackend, id: Long) =
        backend.deletePreset(requireToken(), id)

    /** Настройки аккаунта (профиль фильтрации DNS + адреса своего резолвера). Требует входа. */
    suspend fun settings(backend: MayakBackend): org.amnezia.awg.mayak.core.AccountSettings =
        backend.settings(requireToken())

    /**
     * Сменить профиль фильтрации. custom = null — «адреса не трогать» (ядро сохранит прежние).
     * После смены сбрасываем предзагруженные конфиги: DNS проставляется ядром В МОМЕНТ выдачи
     * конфига, поэтому тёплый кэш /connect несёт СТАРЫЙ резолвер, и без сброса новый профиль
     * включился бы только через час-другой — человек решил бы, что настройка не работает.
     */
    suspend fun updateSettings(
        backend: MayakBackend,
        mode: String,
        custom: String?,
    ): org.amnezia.awg.mayak.core.AccountSettings {
        val saved = backend.updateSettings(
            requireToken(),
            org.amnezia.awg.mayak.core.SettingsUpdate(mode, custom),
        )
        // Сохранённый на диск last-good конфиг НЕ трогаем: это спасательный круг на случай, когда
        // ядро недоступно, и лучше подняться со старым резолвером, чем не подняться вовсе.
        connectCache.clear()
        return saved
    }

    /** Состояние доступа аккаунта (активен/истёк, до какой даты, сколько устройств). Требует входа. */
    suspend fun accountStatus(backend: MayakBackend): org.amnezia.awg.mayak.core.AccountStatus {
        val token = requireToken()
        val st = backend.accountStatus(token)
        // Попутно добираем номер аккаунта, если его ещё нет. Почему здесь: люди, вошедшие ДО того,
        // как номер появился, больше никогда не логинятся, а номер нужен АВТО-диагностике при
        // отказе подключения — то есть на пути, который не проходит через экран Настроек. Сверка
        // доступа — единственный запрос с токеном, который случается у всех и сам собой.
        // Стоимость: одна проверка хранилища на сверку и РОВНО ОДИН лишний запрос на установку.
        runCatching { MayakAccountNumber.refresh(store, token, backend) }
        return st
    }

    /**
     * Публичный номер аккаунта: из хранилища, а если его там ещё нет — с ядра (один раз на установку).
     *
     * Экран Настроек зовёт это явно, чтобы показать номер сразу при открытии; всем остальным номер
     * достаётся сам — попутно с первой же сверкой доступа (см. [accountStatus]).
     */
    suspend fun accountNumber(backend: MayakBackend): String? =
        MayakAccountNumber.refresh(store, requireToken(), backend)

    /**
     * Карточка учётки с ядра (GET /v1/client/account): номер И настоящая почта одним запросом.
     *
     * Зачем отдельно от [accountNumber]: тот отдаёт сохранённый номер и в сеть без нужды не ходит
     * (номер не меняется никогда), а почта — меняется: её можно привязать в кабинете уже после
     * входа. Экран Настроек зовёт это при открытии, попутно освежая номер в хранилище.
     */
    suspend fun accountCard(backend: MayakBackend): org.amnezia.awg.mayak.core.AccountInfo {
        val info = backend.account(requireToken())
        org.amnezia.awg.mayak.core.AccountNumber.remember(store, info.accountNumber)
        return info
    }

    /** Устройства аккаунта — для экрана «Мои устройства» (MayakDevices). Требует входа. */
    suspend fun listDevices(backend: MayakBackend): List<org.amnezia.awg.mayak.core.DeviceItem> =
        backend.listDevices(requireToken())

    /**
     * Отключить устройство аккаунта. Если отключили ТЕКУЩЕЕ — забываем локальный device_id: иначе
     * приложение продолжало бы слать удалённый id на /connect и получало бы отказ, который человеку
     * нечем объяснить. Пустой id заставит следующий коннект зарегистрировать устройство заново
     * (место как раз освободилось), то есть отключение своего устройства не запирает приложение.
     */
    suspend fun revokeDevice(backend: MayakBackend, id: Long) {
        backend.revokeDevice(requireToken(), id)
        if (id == deviceId()) store.remove(K_DEVICE)
    }

    // ===== Обращения в поддержку (форма вместо mailto, 08-08) =====
    //
    // Тонкие обёртки: вся логика «что это значит» живёт в :core (supportFailure) и на экране. Здесь
    // только токен — но именно поэтому они здесь: экран не должен доставать его из хранилища сам.

    /** Создать обращение. Тема — код из SupportTopics; контекст аккаунта соберёт ядро по сессии. */
    suspend fun createSupportTicket(
        backend: MayakBackend,
        topic: String,
        message: String,
    ): org.amnezia.awg.mayak.core.SupportSent =
        backend.createSupportTicket(requireToken(), topic, message)

    /** Свои обращения (свежие сверху) + сколько из них с непрочитанным ответом. */
    suspend fun supportTickets(backend: MayakBackend): org.amnezia.awg.mayak.core.SupportTicketList =
        backend.supportTickets(requireToken())

    /** Своё обращение с перепиской. Этот же запрос гасит на ядре пометку «есть новый ответ». */
    suspend fun supportThread(backend: MayakBackend, id: Long): org.amnezia.awg.mayak.core.SupportThread =
        backend.supportThread(requireToken(), id)

    /** Дописать в своё обращение. */
    suspend fun replySupport(backend: MayakBackend, id: Long, message: String) =
        backend.replySupport(requireToken(), id, message)

    // ===== Ящик сообщений (SPEC-0047) =====
    //
    // Тонкие обёртки: решения («что показать», «звенеть ли») живут в MayakMessages и на экране.
    // Здесь только токен — чтобы ни воркеру, ни экрану не приходилось доставать его из хранилища.

    /** Свои сообщения. sinceId = 0 — всё за 90 дней (экран); иначе только новее указанного (фон). */
    suspend fun messages(backend: MayakBackend, sinceId: Long = 0): org.amnezia.awg.mayak.core.MessagesResponse =
        backend.messages(requireToken(), sinceId)

    /** Пометить своё сообщение прочитанным. Чужое/несуществующее → 404 (ядро их не различает). */
    suspend fun markMessageRead(backend: MayakBackend, id: Long) =
        backend.markMessageRead(requireToken(), id)

    /** Выключатели уведомлений (категории + тихие часы). */
    suspend fun notificationPrefs(backend: MayakBackend): org.amnezia.awg.mayak.core.NotificationPrefs =
        backend.notificationPrefs(requireToken())

    /** Сменить выключатели. Время согласия на новости проставляет СЕРВЕР, не мы. */
    suspend fun updateNotificationPrefs(
        backend: MayakBackend,
        prefs: org.amnezia.awg.mayak.core.NotificationPrefs,
    ) = backend.updateNotificationPrefs(requireToken(), prefs)

    /**
     * Адрес доставки пуша (ускоритель ящика). Версию сборки подставляем здесь, а не у вызывающего:
     * у нас уже был случай, когда версия в двух местах разъезжалась молча.
     */
    suspend fun registerPush(backend: MayakBackend, pushToken: String) =
        backend.registerPush(requireToken(), pushToken, BuildConfig.VERSION_NAME)

    /** Снять адрес доставки. Нет токена — снимать нечем: ядро узнаёт устройство по сессии. */
    suspend fun unregisterPush(backend: MayakBackend, pushToken: String) =
        backend.unregisterPush(requireToken(), pushToken)

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
        } catch (e: MayakApiException) {
            // Ключ этого телефона принадлежит ДРУГОМУ аккаунту: на аппарате сменился пользователь
            // (ключ устройства при выходе намеренно сохраняется — это идентичность аппарата).
            // Для нового аккаунта эта идентичность недоступна, и единственное осмысленное действие —
            // завести новую пару ключей, как при чистой установке. Раньше ядро отдавало здесь голый
            // 409, приложение считало его лимитом устройств и отправляло человека с НУЛЁМ устройств
            // освобождать слоты (разбор 2026-07-27).
            if (e.code == "pubkey_taken") {
                val fresh = rotateDeviceKeys()
                val resp = backend.registerDevice(token, fresh, label = deviceName(), hwid = hwids.hwid())
                store.put(K_DEVICE, resp.deviceId.toString())
                resp.deviceId
            } else {
                cached ?: throw e
            }
        } catch (e: Exception) {
            cached ?: throw e
        }
    }

    /**
     * Перевыпуск ключей устройства. Возвращает новый публичный ключ.
     *
     * Сохранённый конфиг обязателен к сбросу: он выписан на СТАРЫЙ приватный ключ и после смены пары
     * поднимет туннель, который никуда не пропускает (молчаливое «подключено, но интернета нет»).
     */
    private fun rotateDeviceKeys(): String {
        val km = keys.generate()
        store.put(K_PRIV, km.privateKeyBase64)
        store.put(K_PUB, km.publicKeyBase64)
        store.remove(K_DEVICE)
        store.remove(K_LAST_GOOD)
        return km.publicKeyBase64
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
