// Список адресов ядра: что зашито в сборку, что узнали от сервера и в каком порядке пробовать.
//
// Зачем отдельный объект. Раньше список был константой MayakActivity.DEFAULT_HOSTS, и её копировали
// в четырёх местах (главный экран, настройки, keepalive, телеметрия). Пока домен один, это работало;
// но по директиве владельца 2026-07-26 у проекта появился РЕЕСТР доменов (админка → «Домены»,
// миграция 0089), и приложение обязано (1) собираться с актуальным списком и (2) подхватывать его
// живьём — иначе после блокировки основного домена установленные приложения останутся с мёртвым
// адресом до следующего релиза в маркете.
//
// Порядок сборки итогового списка:
//   1. сервер, заданный руками в настройках (или пришедший в рег-ссылке) — воля пользователя выше всего;
//   2. адреса, полученные от ядра (GET /v1/client/hosts) и сохранённые локально — самые свежие;
//   3. зашитые в сборку (baked, см. ниже) — работают, даже когда сеть не пускает никуда.
// Дубликаты убираем с сохранением порядка: HostProvider обходит список по кругу, и повтор означал бы
// лишний таймаут на том же мёртвом адресе.
//
// ДВА бэкенда (2026-08-06): дев mayakvpn.ru и прод mayaknetworks.com — своя корневая CA у каждого,
// дев-сборка к прод-ядру не подключится и наоборот (намеренно). Разводит их buildType (см.
// ui/build.gradle.kts): prodRelease собирается с BuildConfig.MAYAK_PROD_TARGET=true и своими
// res/raw/mayak_ca.pem + res/xml/network_security_config.xml (ui/src/prodRelease/res). ЭТО —
// единственная точка ветвления между MayakHosts (:core, дев) и MayakProdHosts (:ui, прод): не
// хардкодим второй список рядом с первым в одном файле — так его нельзя случайно взять не в той
// сборке (2026-08-05 поймали 4 бага ровно на вшитых списках/доменах).
package org.amnezia.awg.mayak

import android.content.Context
import android.util.Log
import org.amnezia.awg.BuildConfig
import org.amnezia.awg.mayak.core.MayakBackend
import org.amnezia.awg.mayak.core.MayakHosts

object MayakHostList {
    private const val TAG = "Mayak/Hosts"

    private val bakedHosts: List<String>
        get() = if (BuildConfig.MAYAK_PROD_TARGET) MayakProdHosts.baked else MayakHosts.baked

    private val bakedCabinet: String
        get() = if (BuildConfig.MAYAK_PROD_TARGET) MayakProdHosts.bakedCabinet else MayakHosts.bakedCabinet

    /** Итоговый список базовых URL для HostProvider. savedServer — сервер из настроек (может быть null). */
    fun effective(context: Context, savedServer: String?): List<String> {
        val out = LinkedHashSet<String>()
        savedServer?.trimEnd('/')?.takeIf { it.isNotBlank() && ownContour(it) }?.let { out.add(it) }
        out.addAll(MayakPrefs.learnedHosts(context).filter { ownContour(it) })
        // Ядра из ПОДПИСАННОГО delivery-документа (F-T8). ownContour к ним НАРОЧНО не применяется:
        // фильтр отсекает домены, которых вшитый список не знает, а весь смысл подписанного канала —
        // доставить НОВЫЙ домен, когда старые сгорели. Доверие здесь даёт подпись Ed25519
        // (офлайн-ключ владельца, проверка в :core Delivery), а не совпадение зоны.
        out.addAll(MayakDelivery.cores(context))
        out.addAll(bakedHosts)
        // Seed-IP (L4) — самый последний резерв, после вшитых: сырому IP нужен серт с SAN=IP под
        // вшитым CA, и пока сервер seed'ы не выдаёт (формат поддержан, список пуст).
        out.addAll(MayakDelivery.seedUrls(context))
        return out.toList()
    }

    /**
     * Адрес принадлежит контуру ЭТОЙ сборки?
     *
     * 🔴 Зачем (находка владельца 2026-08-07, живьём): выученные и сохранённые адреса стоят в списке
     * ВЫШЕ вшитых — так и задумано, иначе после блокировки домена установленное приложение осталось
     * бы с мёртвым адресом до следующего релиза. Но при обновлении ПОВЕРХ старой установки данные
     * переживают подмену сборки: прод-сборка, поставленная поверх дев-версии, честно пошла по
     * сохранённому ДЕВ-адресу, показала дев-страны и оставила человека залогиненным в деве. Снаружи
     * всё выглядит здоровым — заметить можно только по списку стран.
     *
     * Чистая установка этого не показывает (сохранённого нет) — поэтому проверка на эмуляторе была
     * зелёной. Сверяем с ВШИТЫМИ адресами сборки: они единственные, кто заведомо знает свой контур.
     */
    fun ownContour(url: String): Boolean = ownContour(url, bakedHosts + bakedCabinet)

    /**
     * Тот же ответ, но со списком вшитых адресов АРГУМЕНТОМ.
     *
     * Зачем отдельная форма (19-08): юнит-тесты AGP гоняются на buildType `debug`
     * (`:ui:testDebugUnitTest`), а в нём `MAYAK_PROD_TARGET=false` — то есть сторож, зовущий
     * `ownContour(url)`, проверял бы ТОЛЬКО дев-список и про боевой контур молчал. Со списком
     * аргументом один тест накрывает оба.
     */
    internal fun ownContour(url: String, baked: List<String>): Boolean {
        val host = hostOf(url) ?: return false
        for (b in baked) {
            val own = hostOf(b) ?: continue
            if (host == own) return true
            // Домен контура: любое имя внутри него своё (api./cabinet./mayaknetworks.com).
            // У адреса-IP «своих поддоменов» не бывает — там только точное совпадение выше.
            val zone = registrable(own) ?: continue
            if (host == zone || host.endsWith(".$zone")) return true
        }
        return false
    }

    /**
     * Ссылку, ПРИШЕДШУЮ ОТ СЕРВЕРА, наружу отдаём только если это `https` и наш контур.
     *
     * 🔴 Зачем (аудит 19-08, A3). Одноразовый вход в кабинет (`POST /v1/client/cabinet-link`)
     * открывался в браузере как есть, сырой строкой из ответа. Схему никто не смотрел: `http://`
     * увёл бы этот вход открытым текстом — `network_security_config` запрещает cleartext НАШЕМУ
     * процессу, но не браузеру, которому мы отдали интент. Проверка стоит здесь, а не у вызывающего,
     * чтобы её нельзя было забыть на новом месте.
     */
    fun ownHttpsUrl(url: String): Boolean = ownHttpsUrl(url, bakedHosts + bakedCabinet)

    internal fun ownHttpsUrl(url: String, baked: List<String>): Boolean =
        url.trim().startsWith("https://", ignoreCase = true) && ownContour(url, baked)

    private fun hostOf(url: String): String? {
        val trimmed = url.trim()
        val noScheme = trimmed.substringAfter("://", trimmed)
        // Отрезаем ВСЁ, что идёт после хоста. '#' и '\\' здесь не формальность (найдено при правке
        // A3 19-08): без них `https://evil.com#api.mayaknetworks.com` и `https://evil.com\.mayak…`
        // давали «хост», который заканчивается на нашу зону, — то есть чужая страница проходила
        // проверку за свою. Браузер в обоих случаях идёт на evil.com.
        val authority = noScheme.substringBefore('/').substringBefore('\\')
            .substringBefore('?').substringBefore('#')
        // До '@' стоит ЛОГИН, а не хост: `https://api.mayaknetworks.com@evil.com` ведёт на evil.com.
        val hostPort = authority.substringAfterLast('@')
        val host = if (hostPort.startsWith("[")) hostPort.substringAfter('[').substringBefore(']')
        else hostPort.substringBefore(':')
        return host.lowercase().takeIf { it.isNotBlank() }
    }

    /** Домен второго уровня; null — если это адрес-IP (у него «зоны» нет). */
    private fun registrable(host: String): String? {
        if (host.none { it.isLetter() }) return null // 213.226.71.181 и прочие v4-адреса
        val parts = host.split('.')
        if (parts.size < 2) return null
        return parts.takeLast(2).joinToString(".")
    }

    /**
     * Разовая уборка при переезде между контурами: если в памяти лежат адреса ЧУЖОГО контура —
     * стереть их вместе с сессией. Токен, device_id и кэш направлений принадлежат чужому ядру: на
     * своём они не работают, а оставленные молча создают вид, что человек вошёл.
     *
     * Возвращает true, если что-то чистили (вызывающий покажет экран входа).
     */
    fun dropForeignContour(context: Context, store: KeystoreSecureStore, session: MayakSession): Boolean {
        val savedServer = store.get(MayakActivity.KEY_SERVER)
        val foreignSaved = savedServer != null && savedServer.isNotBlank() && !ownContour(savedServer)
        val learned = MayakPrefs.learnedHosts(context)
        val foreignLearned = learned.any { !ownContour(it) }
        val cabinet = MayakPrefs.learnedCabinet(context)
        val foreignCabinet = cabinet.isNotBlank() && !ownContour(cabinet)
        val site = MayakPrefs.learnedSite(context)
        val foreignSite = site.isNotBlank() && !ownContour(site)
        if (!foreignSaved && !foreignLearned && !foreignCabinet && !foreignSite) return false

        Log.w(TAG, "адреса чужого контура в памяти — стираю вместе с сессией (переезд сборки)")
        if (foreignSaved) store.remove(MayakActivity.KEY_SERVER)
        if (foreignLearned) MayakPrefs.setLearnedHosts(context, learned.filter { ownContour(it) })
        if (foreignCabinet) MayakPrefs.setLearnedCabinet(context, "")
        if (foreignSite) MayakPrefs.setLearnedSite(context, "")
        MayakPresets.clear(context) // пресеты — настройки аккаунта ЧУЖОГО ядра
        session.logout()
        return true
    }

    /**
     * Адрес веб-кабинета (регистрация, политика, продление). Из реестра, если ядро его прислало;
     * иначе зашитый в сборку. Хардкодить нельзя по той же причине, что и адрес ядра: сменится домен
     * (или его заблокируют) — установленные приложения будут вести людей в никуда до релиза в маркете.
     */
    fun cabinetUrl(context: Context): String {
        val learned = MayakPrefs.learnedCabinet(context).trim().removeSuffix("/")
        // 🔴 ownContour — ровно как у siteUrl ниже (аудит 19-08, A3: у соседа фильтр стоял, здесь
        // его забыли). Выученный адрес кабинета уходит не только в браузер: от него строится
        // appCaptchaUrl, а это WebView шага регистрации — то есть чужая страница получила бы наш
        // JS-мост. Уборка dropForeignContour срабатывает только при следующем старте, а решение
        // нужно здесь и сейчас.
        val host = learned.takeIf { it.isNotEmpty() && ownContour(it) } ?: bakedCabinet
        // https ЖЁСТКО: startsWith("http") пропускал бы и `http://` — а по этому адресу человек
        // вводит пароль от кабинета.
        return if (host.startsWith("https://", ignoreCase = true)) host
        else "https://" + host.substringAfter("://", host)
    }

    /**
     * Адрес САЙТА (там живёт справочный центр). Источники в том же порядке, что и у кабинета:
     * присланный ядром (роль `site` реестра доменов) → выведенный из зашитого адреса кабинета.
     *
     * Зашитой константы с доменом сайта здесь НЕТ намеренно: она была бы ВТОРЫМ местом, где домен
     * записан руками, и отстала бы от реестра ровно так же, как отставали адреса ядра. Вместо этого
     * берём зону зашитого кабинета (`cabinet.<зона>` → `<зона>`) — сайт и кабинет живут в одной.
     * У адреса-IP зоны нет, и тогда справки у нас нет — это честнее, чем увести человека в никуда.
     */
    fun siteUrl(context: Context): String? {
        val learned = MayakPrefs.learnedSite(context).trim().removeSuffix("/")
        if (learned.isNotEmpty() && ownContour(learned)) {
            return if (learned.startsWith("http")) learned else "https://$learned"
        }
        val zone = hostOf(bakedCabinet)?.let { registrable(it) } ?: return null
        return "https://$zone"
    }

    /**
     * Справочный центр (`/help.html` на сайте) — то, куда ведёт «Помощь и поддержка». null, если
     * адреса сайта нет: кнопку в этом случае не показываем, а не показываем неработающую.
     */
    fun helpUrl(context: Context): String? = siteUrl(context)?.let { "$it/help.html" }

    /** Политика конфиденциальности и Условия — страницы того же кабинета. */
    fun privacyUrl(context: Context): String = cabinetUrl(context) + "/#/privacy"

    fun termsUrl(context: Context): String = cabinetUrl(context) + "/#/terms"

    /** Вход в кабинет: туда уводим за тем, чего в приложении нет (удаление аккаунта, продление). */
    fun cabinetLoginUrl(context: Context): String = cabinetUrl(context) + "/#/login"

    /**
     * РЕГИСТРАЦИЯ. Кнопка «Регистрация / Личный кабинет» на экране входа — единственный путь новичка,
     * и до 2026-07-31 она открывала форму ВХОДА: голый адрес кабинета роутер уводит на `#/login`, а
     * регистрация там — мелкая ссылка «Создать» под формой. Человек без аккаунта нажимал «Регистрация»
     * и упирался в тупик на первом же шаге воронки. Маршрут `#/register` в кабинете есть (cabinet/app.js,
     * VIEWS/PUBLIC) — ведём прямо на него.
     */
    fun cabinetRegisterUrl(context: Context): String = cabinetUrl(context) + "/#/register"

    /**
     * Страница-виджет «не робот» для WebView шага регистрации (SPEC-0048, T1).
     *
     * Лежит в КАБИНЕТЕ, а не на лендинге: скрипт Turnstile разрешён политикой безопасности только
     * там (и заголовком Caddy, и `<meta>` в index.html). Адрес берём из того же реестра доменов, что
     * и остальные ссылки, — иначе после смены домена приложение открывало бы виджет в никуда, а
     * регистрация в приложении умирала бы молча.
     *
     * Ключ сайта передаём параметром: он публичен по устройству Turnstile, а менять его надо БЕЗ
     * релиза приложения. Тему передаём явно — у нас она своя, системной здесь верить нельзя.
     *
     * ЯЗЫК — по той же причине, что и тему. WebView сообщает странице только язык БРАУЗЕРА, а язык
     * приложения человек мог выбрать руками (MayakLanguages), и это разные вещи. Пока язык не
     * передавался, выбравший English получал на шаге «подтвердите, что вы человек» русский экран и
     * русскую галочку Cloudflare — на ПЕРВОМ шаге создания аккаунта, где бросить проще всего.
     *
     * Берём фактически применённую локаль из конфигурации ресурсов — ровно тем же способом, что и
     * диалог выбора языка: AppCompatDelegate.getApplicationLocales() пуст, если локаль пришла от
     * системы, а не от нашего диалога. Приложение говорит на двух языках, поэтому всё, что не
     * русский, — «en»: страница с английским текстом честнее страницы с русским.
     */
    fun appCaptchaUrl(context: Context, sitekey: String, dark: Boolean): String =
        cabinetUrl(context) + "/app-captcha.html?sitekey=" +
            java.net.URLEncoder.encode(sitekey, "UTF-8") +
            "&theme=" + (if (dark) "dark" else "light") +
            "&lang=" + uiLang(context)

    /** Язык интерфейса приложения как «ru» или «en» — для страниц, которые мы открываем сами. */
    fun uiLang(context: Context): String =
        if (context.resources.configuration.locales.get(0)?.language == "ru") "ru" else "en"

    /** Спросить у ядра актуальный реестр и запомнить. Best-effort: ошибка/пустой ответ — молча оставляем
     *  прежний список (пустой список от сервера означал бы «адресов нет» и отрезал бы клиента от ядра). */
    suspend fun refresh(context: Context, backend: MayakBackend) {
        // Подписанный delivery-документ — отдельный, ДОВЕРЕННЫЙ источник (F-T8); его провал не должен
        // мешать реестру доменов, и наоборот. Оба зовутся из одних и тех же мест (старт + недельный
        // воркер), поэтому живут в одном refresh.
        runCatching { MayakDelivery.refresh(context, backend) }
        val list = backend.hosts() ?: return
        // Кабинет приходит тем же ответом; пустое поле (старое ядро) не затираем — останется зашитый.
        // ownContour ЗДЕСЬ, а не только на чтении (аудит 19-08, A3): чужой адрес не должен даже
        // ложиться в память — он переживает перезапуск и читается не одним местом. Новый домен
        // приезжает не отсюда, а ПОДПИСАННЫМ delivery-документом (MayakDelivery) — там доверие даёт
        // подпись, а не совпадение зоны.
        list.cabinet.trim().removeSuffix("/").takeIf { it.isNotEmpty() && ownContour(it) }?.let {
            if (it != MayakPrefs.learnedCabinet(context)) MayakPrefs.setLearnedCabinet(context, it)
        }
        // Сайт (роль `site` того же реестра) — адрес справочного центра. Ядро отдаёт его с самого
        // заведения реестра, а приложение поле игнорировало: справки в приложении не было вовсе.
        list.site.trim().removeSuffix("/").takeIf { it.isNotEmpty() && ownContour(it) }?.let {
            if (it != MayakPrefs.learnedSite(context)) MayakPrefs.setLearnedSite(context, it)
        }
        val urls = list.api.mapNotNull { h ->
            val host = h.trim().removeSuffix("/")
            if (host.isEmpty()) null else if (host.startsWith("http")) host else "https://$host"
        }
        if (urls.isEmpty()) {
            Log.i(TAG, "сервер вернул пустой список адресов — оставляю прежний")
            return
        }
        if (urls == MayakPrefs.learnedHosts(context)) return
        MayakPrefs.setLearnedHosts(context, urls)
        Log.i(TAG, "список адресов ядра обновлён: ${urls.size} шт")
    }
}
