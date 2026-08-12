// Ящик сообщений (SPEC-0047): забор с ядра, счётчик непрочитанного, системные уведомления и
// локализованный текст повода.
//
// 🔴 Зачем это вообще. До сих пор канала до человека НЕ БЫЛО: почта есть не у всех (учётки без почты
// живут с 11-08), Telegram — только у бот-магазина, кабинет требует, чтобы человек сам зашёл. Три
// вещи, которые мы УЖЕ вычисляем на сервере — «доступ заканчивается», чек об оплате, приглашение в
// общий доступ, — до половины людей не доходили никак. Ящик закрывает именно это.
//
// Сегодня приложение в фоне на сервер не ходит вовсе (кроме телеметрии раз в 7 дней) — значит вся
// доставка держится на четырёх поводах: открытие приложения (MayakActivity.onResume), удачный подъём
// туннеля, частая проверка, пока туннель поднят (MayakMessagesPoll), и фоновая проверка раз в 6 часов
// (MayakMessagesWorker).
//
// 🔴 ЧЕМУ НАУЧИЛ ЖИВОЙ ТЕЛЕФОН 13-08 (разбор — docs/research/2026-08-13-uvedomleniya-ne-doshli-razbor.md).
// Первая версия ящика на живом телефоне не доставила НИ ОДНОГО уведомления, хотя на эмуляторе
// проходила целиком. Сложились два решения, каждое по отдельности разумное:
//   1) проверка на переднем плане была зажата анти-дребезгом в ЧАС (сервер просит 6 ч, вилка
//      [5 мин, 1 ч] давала час) — человек открыл приложение через 4 минуты после сообщения, и
//      приложение молча не пошло на сервер вовсе;
//   2) экран «Сообщения» забирал ящик САМ и намеренно не показывал уведомлений (человек и так
//      смотрит в список).
// Вместе: единственный путь, которым сообщение попадало в телефон, совпал с тем, где показ запрещён.
// Уведомление было структурно недостижимо — при любых настройках телефона.
//
// Отсюда устройство этого файла: «ЗАБРАТЬ» и «ПОКАЗАТЬ» разведены явно (SyncTrigger), а частота
// захода — свойство ПОВОДА, а не одна константа на всех. Открыл приложение — идём (пол в секундах,
// не в часах).
//
// ⚠️ СЛОВА. Уведомление читают через плечо, а приложение умеет прятаться под «Погоду»/«Заметки»
// (MayakDisguise). Поэтому ни в одном показываемом тексте нет слов «VPN», «туннель», «обход» — это
// сторожит MayakMessagesWordsTest, а не только внимательность автора.
package org.amnezia.awg.mayak

import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import java.util.Calendar
import org.amnezia.awg.R
import org.amnezia.awg.mayak.core.HostProvider
import org.amnezia.awg.mayak.core.MayakBackend
import org.amnezia.awg.mayak.core.MessageKinds
import org.amnezia.awg.mayak.core.MessagesResponse
import org.amnezia.awg.mayak.core.NotificationPrefs
import org.amnezia.awg.mayak.core.UserMessage
import org.amnezia.awg.mayak.core.quietHourNow

object MayakMessages {

    /** Канал сообщений — ОТДЕЛЬНЫЙ от статусного «Подключено» (тот немой и постоянный). */
    const val CHANNEL_ID = "mayak_messages"

    /**
     * Второй канал — для тихих часов. Он существует не «на всякий случай», а потому что с Android 8
     * звук решает КАНАЛ, а не уведомление: у канала с важностью DEFAULT сигнал по умолчанию свой, и
     * настройка «тихие часы» без отдельного канала была бы кнопкой без эффекта.
     *
     * 🔴 Суффикс `_v2` и важность DEFAULT — правка 13-08. Первая версия канала была IMPORTANCE_LOW,
     * и это оказалось перестраховкой с ценой: с Android 11 система прячет значки «тихих» уведомлений
     * из статус-бара, а у человека сверху может быть ещё и «Не беспокоить» (на Samsung он умеет
     * убирать уведомления из шторки целиком) — получалось ДВОЙНОЕ глушение, при котором ночное
     * сообщение не видно вообще нигде. Нам нужно ровно «без звука», а не «поменьше заметно»: канал
     * DEFAULT со снятым звуком и вибрацией даёт значок в статус-баре и обычную строку в шторке.
     *
     * Важность существующего канала приложение изменить НЕ может (после создания её правит только
     * человек) — поэтому новый id, а прежний канал удаляется в ensureChannels().
     */
    const val CHANNEL_QUIET_ID = "mayak_messages_quiet_v2"

    /** Прежний тихий канал (IMPORTANCE_LOW). Остаётся только затем, чтобы его удалить. */
    private const val CHANNEL_QUIET_ID_OLD = "mayak_messages_quiet"

    private const val PREFS = "mayak_messages"

    /** id последнего сообщения, о котором мы УЖЕ уведомляли: иначе одно и то же зазвенит на каждой проверке. */
    private const val K_LAST_NOTIFIED = "last_notified_id"

    /** Сколько непрочитанного по мнению сервера — для бейджа, чтобы он был виден до всякой сети. */
    private const val K_UNREAD = "unread"

    /** Когда последний раз ходили в ящик (настенные часы) — анти-дребезг тихих проверок. */
    private const val K_LAST_SYNC = "last_sync_ms"

    /**
     * Тег диаг-лога. В присланный человеком диаг-лог попадают только строки с тегом «AmneziaWG…» или
     * «Mayak…» (фильтр DiagCollector), поэтому всё про доставку сообщений пишем именно под этим.
     */
    private const val TAG = "AmneziaWG/mayak-messages"

    /** База номеров уведомлений. 'MS' — рядом со статусным 0x4D41 ('MA'), но заведомо мимо него. */
    private const val NOTIF_BASE = 0x4D530000

    /** Сколько уведомлений максимум показываем за одну проверку. Остальное человек увидит на экране
     *  и по бейджу: пачка из десяти уведомлений — это спам, а спам в Play разбирают по жалобе. */
    private const val MAX_NOTIFY_PER_SYNC = 3

    /**
     * Поводы заглянуть в ящик. У каждого СВОЯ частота и своё право показывать уведомление — именно
     * это разведение и есть починка 13-08 (см. шапку файла).
     *
     * @param minGapMs минимальный зазор с прошлого захода. Это защита от дребезга (пересоздание
     *   Activity при смене темы/повороте — это не «человек открыл приложение» второй раз), а НЕ
     *   экономия запросов: сам запрос крошечный, а человек в этот момент смотрит на экран.
     * @param notify показывать ли уведомление о найденном. Экрану «Сообщения» это не нужно —
     *   человек уже читает список; всем остальным нужно, иначе сообщение не покидает сервер.
     */
    enum class SyncTrigger(internal val minGapMs: Long, internal val notify: Boolean) {
        /** Человек открыл приложение (или вернулся в него из фона). Пол — 10 секунд. */
        OPEN(10_000L, true),

        /** Туннель поднят: частая тихая проверка процесс-скоупным MayakMessagesPoll. */
        TUNNEL(60_000L, true),

        /** Расписание уже держит кто-то другой (WorkManager раз в 6 ч, толчок после выданного
         *  разрешения на уведомления) — второй потолок поверх него означал бы пропущенные такты. */
        ALWAYS(0L, true),
    }

    /**
     * Итог проверки. `fresh` — то, что в ЭТОМ заходе оказалось новым: нужно не для бейджа, а чтобы
     * открытое приложение показало сообщение явно (баннером), а не молча зажгло цифру.
     */
    data class SyncResult(val ok: Boolean, val fresh: List<UserMessage>) {
        companion object {
            val NONE = SyncResult(false, emptyList())
        }
    }

    // ===== Счётчик непрочитанного (бейдж) =====

    /** Непрочитанных по последним данным сервера. Работает без сети — значение с прошлой проверки. */
    fun unread(context: Context): Int = prefs(context).getInt(K_UNREAD, 0)

    private fun setUnread(context: Context, n: Int) {
        prefs(context).edit().putInt(K_UNREAD, n.coerceAtLeast(0)).apply()
    }

    /** Уменьшить счётчик на единицу, когда человек открыл карточку (не дожидаясь ответа сервера). */
    fun noteRead(context: Context) = setUnread(context, unread(context) - 1)

    /**
     * Забыть всё про ящик. Зовётся при выходе из аккаунта: бейдж и «о чём уже уведомляли» — про
     * КОНКРЕТНУЮ учётку, и следующий вошедший на этом телефоне не должен видеть чужой счётчик.
     */
    fun clear(context: Context) {
        prefs(context).edit().clear().apply()
        runCatching { NotificationManagerCompat.from(context).cancelAll() }
    }

    // ===== Проверка ящика =====

    /**
     * Тихая проверка: забрать новое, показать уведомления, обновить счётчик.
     *
     * Молчит при ЛЮБОЙ беде — нет входа, нет сети, ручки на ядре ещё не завезли (404). Это не
     * снисходительность к ошибкам, а требование: серверная половина выкатывается отдельно, и
     * приложение обязано вести себя ровно как раньше, пока её нет.
     */
    suspend fun sync(context: Context, trigger: SyncTrigger): SyncResult {
        val app = context.applicationContext
        val last = prefs(app).getLong(K_LAST_SYNC, 0L)
        if (!due(last, System.currentTimeMillis(), trigger.minGapMs)) {
            Log.i(TAG, "sync skip: trigger=$trigger gap=${trigger.minGapMs}ms since=${System.currentTimeMillis() - last}ms")
            return SyncResult.NONE
        }
        // Один заход за раз. Пол по времени этого не даёт: onCreate и onResume идут ВСТЫК (замерено на
        // эмуляторе 13-08 — два запроса в одну миллисекунду), и оба успевают спросить «пора?» раньше,
        // чем первый успел записать «сходил».
        if (inFlight) {
            Log.i(TAG, "sync skip: trigger=$trigger — заход уже идёт")
            return SyncResult.NONE
        }
        inFlight = true
        try {
            return syncOnce(app, trigger)
        } finally {
            inFlight = false
        }
    }

    @Volatile private var inFlight = false

    private suspend fun syncOnce(app: Context, trigger: SyncTrigger): SyncResult {
        // since_id — чтобы не тянуть заново всё, о чём уже уведомляли. Ноль (первый заход) отдаёт
        // весь ящик за 90 дней: он же наполнит бейдж, но звенеть на всю пачку мы не станем (ниже).
        val sinceId = prefs(app).getLong(K_LAST_NOTIFIED, 0L)
        val resp = runCatching { pull(app, sinceId = sinceId, notify = trigger.notify) }.getOrNull()
            ?: return SyncResult.NONE
        return SyncResult(true, resp.messages.filter { !it.read }.sortedBy { it.id })
    }

    /**
     * Забрать ящик ЦЕЛИКОМ для экрана «Сообщения» (since_id = 0 — экран для того и открыт, чтобы
     * перечитать старое).
     *
     * Отличий от тихой проверки два, и оба намеренные: ошибки НЕ глотаем (экран обязан сказать
     * причину словами — `failureText`), уведомлений не показываем (человек уже смотрит в список).
     *
     * 🔴 Но ЗАБРАТЬ — забираем, как и все: тот же `pull`, те же локальные отметки, тот же счётчик.
     * До 13-08 экран ходил на сервер сам, в обход этого файла, и был единственным путём доставки —
     * ровно тем, на котором показ запрещён (шапка файла).
     */
    suspend fun loadForScreen(context: Context): List<UserMessage> =
        pull(context.applicationContext, sinceId = 0, notify = false).messages

    /**
     * Один заход в ящик: запрос, обновление локальных отметок, решение про показ. Ошибки НЕ глотает —
     * это делают вызывающие, каждый по-своему.
     */
    private suspend fun pull(app: Context, sinceId: Long, notify: Boolean): MessagesResponse {
        val store = KeystoreSecureStore(app)
        val session = MayakSession(store, AwgKeyProvider(), AndroidHwidProvider(app, store))
        check(session.hasToken()) { "нет входа" }
        val backend = MayakBackend(
            HostProvider(MayakHostList.effective(app, store.get(MayakActivity.KEY_SERVER))),
            bypassTunnel = OutsideTunnel.opener(app),
        )
        val resp = session.messages(backend, sinceId = sinceId)
        prefs(app).edit()
            .putInt(K_UNREAD, resp.unread.coerceAtLeast(0))
            .putLong(K_LAST_SYNC, System.currentTimeMillis())
            .apply()
        Log.i(
            TAG,
            "pull ok: since=$sinceId got=${resp.messages.size} unread=${resp.unread} " +
                "serverAsks=${resp.nextCheckAfterSec}s notify=$notify",
        )
        if (notify) {
            notifyAbout(app, resp.messages, firstSync = sinceId == 0L)
        } else {
            // Показывать не наше дело, но отметку «человек это видел» двигаем: иначе фоновая проверка
            // потом зазвенит о том, что он только что прочитал на экране.
            noteSeenUpTo(app, resp.messages.maxOfOrNull { it.id } ?: 0L, resp.unread)
        }
        return resp
    }

    /**
     * Пометить сообщение прочитанным. Счётчик уменьшаем СРАЗУ, не дожидаясь сервера: человек уже
     * прочитал, и бейдж, который живёт своей жизнью до следующей сверки, читается как поломка.
     * Отказ сервера глотаем — при следующей проверке он пришлёт свою правду и она победит.
     */
    suspend fun markRead(context: Context, id: Long) {
        val app = context.applicationContext
        noteRead(app)
        runCatching { NotificationManagerCompat.from(app).cancel(notifId(id)) }
        val store = KeystoreSecureStore(app)
        val session = MayakSession(store, AwgKeyProvider(), AndroidHwidProvider(app, store))
        if (!session.hasToken()) return
        val backend = MayakBackend(
            HostProvider(MayakHostList.effective(app, store.get(MayakActivity.KEY_SERVER))),
            bypassTunnel = OutsideTunnel.opener(app),
        )
        runCatching { session.markMessageRead(backend, id) }
    }

    /** Запомнить, что до этого id всё уже показано (ящик забирали там, где уведомление не нужно). */
    private fun noteSeenUpTo(context: Context, maxId: Long, unread: Int) {
        val p = prefs(context)
        val edit = p.edit().putInt(K_UNREAD, unread.coerceAtLeast(0))
        if (maxId > p.getLong(K_LAST_NOTIFIED, 0L)) edit.putLong(K_LAST_NOTIFIED, maxId)
        edit.apply()
    }

    /**
     * Пора ли идти: прошло ли `minGapMs` с прошлого захода. Чистая функция без Android — её и
     * проверяет сторож (MayakMessagesTriggerTest), потому что ровно здесь 13-08 стоял час.
     */
    internal fun due(lastSyncMs: Long, nowMs: Long, minGapMs: Long): Boolean {
        if (lastSyncMs <= 0L) return true // ни разу не ходили
        val since = nowMs - lastSyncMs
        // Часы могли уехать назад (смена времени/пояса) — тогда since отрицательный: считаем, что пора.
        return since < 0 || since >= minGapMs
    }

    // ===== Уведомления =====

    private fun notifyAbout(context: Context, messages: List<UserMessage>, firstSync: Boolean) {
        val fresh = messages.filter { !it.read }.sortedBy { it.id }
        val maxId = messages.maxOfOrNull { it.id } ?: 0L
        val canPost = MayakNotification.canPost(context)
        // 🔴 ОТМЕТКУ «ОБ ЭТОМ УЖЕ СКАЗАЛИ» ДВИГАЕМ, ТОЛЬКО ЕСЛИ СКАЗАТЬ РЕАЛЬНО МОГЛИ.
        //
        // Найдено живым прогоном на эмуляторе 12-08. Раньше отметка ставилась ДО проверки прав — и
        // ровно в первый раз это ломало всё: разрешение на уведомления (Android 13+) человек выдаёт
        // в диалоге сразу после входа, а первая сверка ящика успевает пройти РАНЬШЕ. Сообщение
        // помечалось как «о нём уведомили», хотя уведомить было нечем, и в шторку оно не попадало
        // уже никогда — человек видел только цифру на конверте. Именно первое сообщение новичка,
        // то есть худший возможный случай.
        //
        // Когда уведомлять нечего (`fresh` пуст), отметку двигаем всё равно: иначе она замерла бы на
        // старом id и следующая пачка сочлась бы «первой» (лимит в одно уведомление).
        if (maxId > 0 && (fresh.isEmpty() || canPost)) {
            prefs(context).edit().putLong(K_LAST_NOTIFIED, maxId).apply()
        }
        val quiet = quietNow(context)
        // 🩺 ДИАГНОСТИЧЕСКАЯ ЗАПИСЬ О ПОПЫТКЕ ПОКАЗА (правка 13-08). Разбор «почему человек не увидел
        // уведомление» до этого шёл рассуждениями: в логе не было ни строчки о том, пытались ли мы
        // вообще. Теперь есть — и она попадает в присланный диаг-лог (тег ловит DiagCollector).
        Log.i(
            TAG,
            "notify: fresh=${fresh.size} canPost=$canPost quiet=$quiet firstSync=$firstSync " +
                "channel=${if (quiet) CHANNEL_QUIET_ID else CHANNEL_ID} ids=${fresh.map { it.id }}",
        )
        if (fresh.isEmpty() || !canPost) return
        // Первый заход после установки/входа: в ящике может лежать всё за 90 дней, и разом звенеть на
        // всю пачку нельзя. Показываем ОДНО, самое свежее — остальное человек увидит по бейджу.
        val limit = if (firstSync) 1 else MAX_NOTIFY_PER_SYNC
        for (m in fresh.takeLast(limit)) notifyOne(context, m, quiet)
    }

    /**
     * Тихие часы — решение ПРИЛОЖЕНИЯ по времени телефона (SPEC-0047 §4): сервер часового пояса
     * человека не знает и гадать не должен. Настройка живёт на сервере (quiet_hours), её последнее
     * известное значение мы держим у себя — в 23:00 сходить за ней некуда, а решать надо сейчас.
     */
    private fun quietNow(context: Context): Boolean {
        if (!quietHoursEnabled(context)) return false
        return quietHourNow(Calendar.getInstance().get(Calendar.HOUR_OF_DAY))
    }

    @SuppressLint("MissingPermission") // notify под MayakNotification.canPost() (проверка POST_NOTIFICATIONS)
    private fun notifyOne(context: Context, m: UserMessage, quiet: Boolean) {
        val channel = if (quiet) CHANNEL_QUIET_ID else CHANNEL_ID
        ensureChannels(context)
        val open = Intent(context, MayakMessagesActivity::class.java)
            .setAction(Intent.ACTION_VIEW) // разные extra на разных PendingIntent живут только с разным action/данными
            .putExtra(MayakMessagesActivity.EXTRA_MESSAGE_ID, m.id)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        val pi = PendingIntent.getActivity(
            context, notifId(m.id), open,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val builder = NotificationCompat.Builder(context, channel)
            .setSmallIcon(R.drawable.ic_stat_mayak)
            // 🔒 В УВЕДОМЛЕНИИ ТОЛЬКО ЗАГОЛОВОК. Тела здесь нет и не должно быть.
            //
            // Замерено на эмуляторе 12-08: одного `VISIBILITY_PRIVATE` для «на экране блокировки не
            // видно текста» НЕ ХВАТАЕТ. Android прячет содержимое, только если ОБА условия сразу:
            // уведомление просит приватности И человек сам включил «скрывать личное на заблокированном
            // экране» (по умолчанию оно ВЫКЛЮЧЕНО). С настройкой по умолчанию «Осталось дней: 3» и
            // «Зачислено: 299 ₽» читались на локскрине целиком — то есть настройка защищала ровно
            // тех, кто и так о ней позаботился, а всех остальных — нет.
            //
            // Заставить систему скрыть текст у всех нельзя (локскрин принадлежит человеку, а не нам),
            // поэтому единственная надёжная защита — не класть текст в уведомление вовсе. Заголовок
            // нейтрален по построению (сторож MayakMessagesWordsTest), полный текст живёт на экране
            // «Сообщения». Ровно это и обещает SPEC-0047 §5: заголовок в шторке, текст — в приложении.
            .setContentTitle(title(context, m))
            .setAutoCancel(true)
            .setContentIntent(pi)
            // До Android 8 звук решает само уведомление: тихие часы = БЕЗ ЗВУКА, а не «пониже
            // приоритетом». PRIORITY_LOW убирал уведомление из статус-бара — то есть глушил не звук,
            // а видимость (правка 13-08, та же причина, что у нового id тихого канала).
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setSilent(quiet)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            // Оставляем и его: тем, кто «скрывать личное» включил, система спрячет и заголовок.
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
        NotificationManagerCompat.from(context).notify(notifId(m.id), builder.build())
    }

    private fun ensureChannels(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = context.getSystemService(NotificationManager::class.java) ?: return
        // Прежний тихий канал (IMPORTANCE_LOW) убираем: важность у созданного канала уже не поменять,
        // а оставленный он висел бы в системных настройках вторым «Сообщения без звука» — человек
        // читает это как две разные настройки, из которых одна ничего не делает.
        if (nm.getNotificationChannel(CHANNEL_QUIET_ID_OLD) != null) {
            runCatching { nm.deleteNotificationChannel(CHANNEL_QUIET_ID_OLD) }
        }
        if (nm.getNotificationChannel(CHANNEL_ID) == null) {
            nm.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    context.getString(R.string.mayak_messages_channel_name),
                    NotificationManager.IMPORTANCE_DEFAULT,
                ).apply {
                    // lockscreenVisibility здесь НЕ ставим: система такое присвоение от приложения
                    // молча игнорирует (поле правит только она сама — в дампе остаётся
                    // VISIBILITY_NO_OVERRIDE, проверено 12-08). Приватность задаёт само уведомление.
                    description = context.getString(R.string.mayak_messages_channel_desc)
                }
            )
        }
        if (nm.getNotificationChannel(CHANNEL_QUIET_ID) == null) {
            nm.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_QUIET_ID,
                    context.getString(R.string.mayak_messages_channel_quiet_name),
                    // DEFAULT со снятым звуком, а НЕ LOW: нам нужно «молча», но видно (значок в
                    // статус-баре, обычная строка в шторке). LOW система с Android 11 прячет из
                    // статус-бара, и вместе с «Не беспокоить» человека сообщение исчезало совсем.
                    NotificationManager.IMPORTANCE_DEFAULT,
                ).apply {
                    description = context.getString(R.string.mayak_messages_channel_quiet_desc)
                    setSound(null, null)
                    enableVibration(false)
                }
            )
        }
    }

    private fun notifId(id: Long): Int = NOTIF_BASE + (id % 1000).toInt()

    // ===== Локальная копия выключателей (нужна там, где сети нет) =====

    private const val K_QUIET_HOURS = "quiet_hours"

    fun quietHoursEnabled(context: Context): Boolean = prefs(context).getBoolean(K_QUIET_HOURS, true)

    /** Запомнить выключатели, пришедшие/уехавшие на сервер. Локальная копия нужна ровно для одного
     *  решения — звенеть ли ночью; за ним в 23:00 в сеть не сходишь. */
    fun rememberPrefs(context: Context, prefsValue: NotificationPrefs) {
        prefs(context).edit().putBoolean(K_QUIET_HOURS, prefsValue.quietHours).apply()
    }

    // ===== Почему не загрузилось =====

    /**
     * Отказ СЛОВАМИ для экрана «Сообщения». Классификацию берём общую (`supportFailure` в :core, она
     * под тестами), а формулировки — СВОИ.
     *
     * 🔴 Почему не переиспользовать тексты поддержки целиком: они написаны про форму обращения и
     * говорят «Текст сохранён — нажмите Повторить», а 404 у них значит «обращение не найдено». На
     * пустом экране ящика это выглядело именно так — «Не удалось загрузить сообщения. Обращение не
     * найдено» (поймано на эмуляторе 12-08). Тот же класс, что «текст пережил фичу, которую
     * описывал»: слова из соседнего экрана становятся ложью, как только их переносят.
     *
     * 404 здесь значит другое: ядро про ящик ещё не знает (серверная половина выкатывается отдельно).
     */
    fun failureText(context: Context, e: Throwable): String = context.getString(
        when (org.amnezia.awg.mayak.core.supportFailure(e)) {
            org.amnezia.awg.mayak.core.SupportFailure.NO_CONNECTION -> R.string.mayak_messages_err_offline
            org.amnezia.awg.mayak.core.SupportFailure.NEED_LOGIN -> R.string.mayak_messages_err_login
            org.amnezia.awg.mayak.core.SupportFailure.NOT_FOUND -> R.string.mayak_messages_err_unavailable
            org.amnezia.awg.mayak.core.SupportFailure.RATE_LIMITED -> R.string.mayak_messages_err_rate
            else -> R.string.mayak_messages_err_retry
        }
    )

    /** Есть ли смысл в кнопке «Повторить»: там, где повтор даст тот же отказ, её быть не должно. */
    fun canRetry(e: Throwable): Boolean = when (org.amnezia.awg.mayak.core.supportFailure(e)) {
        org.amnezia.awg.mayak.core.SupportFailure.NEED_LOGIN,
        org.amnezia.awg.mayak.core.SupportFailure.NOT_FOUND,
        org.amnezia.awg.mayak.core.SupportFailure.RATE_LIMITED -> false

        else -> true
    }

    // ===== Текст повода на языке интерфейса =====

    /**
     * Заголовок. Для известного повода — НАШ перевод, для незнакомого и для `custom` — серверный
     * текст как есть. Так новый повод на сервере не превращается в пустую строку у человека со
     * старой сборкой, а перевод не приходится дублировать в каждой рассылке.
     */
    fun title(context: Context, m: UserMessage): String =
        localTitle(context, m.kind) ?: m.title.ifBlank { context.getString(R.string.mayak_messages_title) }

    /** Текст. Тот же принцип; если нашему шаблону не хватает параметра — берём серверный текст. */
    fun body(context: Context, m: UserMessage): String =
        localBody(context, m) ?: m.body.ifBlank { localTitle(context, m.kind).orEmpty() }

    private fun localTitle(context: Context, kind: String): String? {
        val res = when (kind) {
            MessageKinds.SUBSCRIPTION_EXPIRING -> R.string.mayak_msg_subscription_expiring
            MessageKinds.SUBSCRIPTION_EXPIRED -> R.string.mayak_msg_subscription_expired
            MessageKinds.ACCESS_REVOKED -> R.string.mayak_msg_access_revoked
            MessageKinds.PAYMENT_RECEIPT -> R.string.mayak_msg_payment_receipt
            MessageKinds.PAYMENT_REFUND -> R.string.mayak_msg_payment_refund
            MessageKinds.BALANCE_TOPUP -> R.string.mayak_msg_balance_topup
            MessageKinds.BALANCE_CHARGE -> R.string.mayak_msg_balance_charge
            MessageKinds.AUTORENEW_OK -> R.string.mayak_msg_autorenew_ok
            MessageKinds.AUTORENEW_FAILED -> R.string.mayak_msg_autorenew_failed
            MessageKinds.PLAN_CHANGED -> R.string.mayak_msg_plan_changed
            MessageKinds.GROUP_INVITED -> R.string.mayak_msg_group_invited
            MessageKinds.GROUP_INVITE_ACCEPTED -> R.string.mayak_msg_group_invite_accepted
            MessageKinds.GROUP_SLOT_REVOKED -> R.string.mayak_msg_group_slot_revoked
            MessageKinds.GROUP_ROOT_EXPIRING -> R.string.mayak_msg_group_root_expiring
            MessageKinds.NEW_DEVICE_LOGIN -> R.string.mayak_msg_new_device_login
            MessageKinds.PASSWORD_CHANGED -> R.string.mayak_msg_password_changed
            MessageKinds.SUPPORT_REPLY -> R.string.mayak_msg_support_reply
            MessageKinds.INACTIVE_WARNING -> R.string.mayak_msg_inactive_warning
            MessageKinds.NEVER_CONNECTED -> R.string.mayak_msg_never_connected
            MessageKinds.MAINTENANCE -> R.string.mayak_msg_maintenance
            else -> return null // `custom` и всё незнакомое — текстом сервера
        }
        return context.getString(res)
    }

    /**
     * Текст известного повода. Где нужен параметр (сколько дней, какая сумма) — берём его из
     * `params`; параметра нет → возвращаем null, и наверху подставится серверный текст. Выдуманное
     * «осталось дней: —» хуже, чем фраза, которую сервер уже собрал правильно.
     */
    private fun localBody(context: Context, m: UserMessage): String? = when (m.kind) {
        MessageKinds.SUBSCRIPTION_EXPIRING ->
            m.param("days")?.let { context.getString(R.string.mayak_msg_subscription_expiring_body, it) }

        // Ключ именно `grace_days`, а не `days`: сервер кладёт сюда ЛЬГОТНЫЕ дни (сколько осталось
        // продлить без перерыва), и это другое число, чем «дней до конца» у соседнего повода.
        // Имена сверены с internal/usermsg — разойдутся, и человек молча получит серверный текст
        // вместо локализованного (SPEC-0047 §2.4).
        MessageKinds.SUBSCRIPTION_EXPIRED ->
            m.param("grace_days")?.let { context.getString(R.string.mayak_msg_subscription_expired_body, it) }
                ?: context.getString(R.string.mayak_msg_subscription_expired_body_plain)

        MessageKinds.ACCESS_REVOKED -> context.getString(R.string.mayak_msg_access_revoked_body)

        MessageKinds.PAYMENT_RECEIPT ->
            m.param("amount")?.let { context.getString(R.string.mayak_msg_payment_receipt_body, it) }
                ?: context.getString(R.string.mayak_msg_payment_receipt_body_plain)

        MessageKinds.PAYMENT_REFUND ->
            m.param("amount")?.let { context.getString(R.string.mayak_msg_payment_refund_body, it) }
                ?: context.getString(R.string.mayak_msg_payment_refund_body_plain)

        MessageKinds.BALANCE_TOPUP ->
            m.param("amount")?.let { context.getString(R.string.mayak_msg_balance_topup_body, it) }
                ?: context.getString(R.string.mayak_msg_balance_topup_body_plain)

        MessageKinds.BALANCE_CHARGE ->
            m.param("amount")?.let { context.getString(R.string.mayak_msg_balance_charge_body, it) }
                ?: context.getString(R.string.mayak_msg_balance_charge_body_plain)

        MessageKinds.AUTORENEW_OK -> context.getString(R.string.mayak_msg_autorenew_ok_body)
        MessageKinds.AUTORENEW_FAILED -> context.getString(R.string.mayak_msg_autorenew_failed_body)

        MessageKinds.PLAN_CHANGED ->
            m.param("plan")?.let { context.getString(R.string.mayak_msg_plan_changed_body, it) }

        MessageKinds.GROUP_INVITED -> context.getString(R.string.mayak_msg_group_invited_body)
        MessageKinds.GROUP_INVITE_ACCEPTED -> context.getString(R.string.mayak_msg_group_invite_accepted_body)
        MessageKinds.GROUP_SLOT_REVOKED -> context.getString(R.string.mayak_msg_group_slot_revoked_body)
        MessageKinds.GROUP_ROOT_EXPIRING -> context.getString(R.string.mayak_msg_group_root_expiring_body)

        MessageKinds.NEW_DEVICE_LOGIN ->
            m.param("device")?.let { context.getString(R.string.mayak_msg_new_device_login_body, it) }
                ?: context.getString(R.string.mayak_msg_new_device_login_body_plain)

        MessageKinds.PASSWORD_CHANGED -> context.getString(R.string.mayak_msg_password_changed_body)
        MessageKinds.SUPPORT_REPLY -> context.getString(R.string.mayak_msg_support_reply_body)

        MessageKinds.INACTIVE_WARNING ->
            m.param("days")?.let { context.getString(R.string.mayak_msg_inactive_warning_body, it) }
                ?: context.getString(R.string.mayak_msg_inactive_warning_body_plain)

        MessageKinds.NEVER_CONNECTED -> context.getString(R.string.mayak_msg_never_connected_body)

        // Плановые работы: даты знает только сервер — своего текста тут быть не может.
        else -> null
    }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
