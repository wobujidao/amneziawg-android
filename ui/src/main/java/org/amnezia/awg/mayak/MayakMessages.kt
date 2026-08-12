// Ящик сообщений (SPEC-0047): забор с ядра, счётчик непрочитанного, системные уведомления и
// локализованный текст повода.
//
// 🔴 Зачем это вообще. До сих пор канала до человека НЕ БЫЛО: почта есть не у всех (учётки без почты
// живут с 11-08), Telegram — только у бот-магазина, кабинет требует, чтобы человек сам зашёл. Три
// вещи, которые мы УЖЕ вычисляем на сервере — «доступ заканчивается», чек об оплате, приглашение в
// общий доступ, — до половины людей не доходили никак. Ящик закрывает именно это.
//
// Сегодня приложение в фоне на сервер не ходит вовсе (кроме телеметрии раз в 7 дней) — значит вся
// доставка держится на трёх поводах: открытие приложения, удачный подъём туннеля и фоновая проверка
// раз в 6 часов (MayakMessagesWorker).
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
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import java.util.Calendar
import org.amnezia.awg.R
import org.amnezia.awg.mayak.core.HostProvider
import org.amnezia.awg.mayak.core.MayakBackend
import org.amnezia.awg.mayak.core.MessageKinds
import org.amnezia.awg.mayak.core.NotificationPrefs
import org.amnezia.awg.mayak.core.UserMessage
import org.amnezia.awg.mayak.core.quietHourNow

object MayakMessages {

    /** Канал сообщений — ОТДЕЛЬНЫЙ от статусного «Подключено» (тот немой и постоянный). */
    const val CHANNEL_ID = "mayak_messages"

    /**
     * Второй канал — для тихих часов. Он существует не «на всякий случай», а потому что с Android 8
     * звук решает КАНАЛ, а не уведомление: `setSilent(true)` на канале с важностью DEFAULT ничего не
     * заглушит, и настройка «тихие часы» была бы кнопкой без эффекта. Единственный надёжный способ
     * показать без звука — положить уведомление в канал с важностью LOW.
     */
    const val CHANNEL_QUIET_ID = "mayak_messages_quiet"

    private const val PREFS = "mayak_messages"

    /** id последнего сообщения, о котором мы УЖЕ уведомляли: иначе одно и то же зазвенит на каждой проверке. */
    private const val K_LAST_NOTIFIED = "last_notified_id"

    /** Сколько непрочитанного по мнению сервера — для бейджа, чтобы он был виден до всякой сети. */
    private const val K_UNREAD = "unread"

    /** Когда последний раз ходили в ящик (настенные часы) — анти-дребезг тихих проверок. */
    private const val K_LAST_SYNC = "last_sync_ms"

    /** Сколько сервер просит ждать до следующего захода (next_check_after_sec). */
    private const val K_NEXT_AFTER_SEC = "next_check_after_sec"

    /** База номеров уведомлений. 'MS' — рядом со статусным 0x4D41 ('MA'), но заведомо мимо него. */
    private const val NOTIF_BASE = 0x4D530000

    /** Сколько уведомлений максимум показываем за одну проверку. Остальное человек увидит на экране
     *  и по бейджу: пачка из десяти уведомлений — это спам, а спам в Play разбирают по жалобе. */
    private const val MAX_NOTIFY_PER_SYNC = 3

    /** Потолок тихой проверки при живом экране: сервер обычно просит 6 часов, но это про ФОН.
     *  Сам экран «Сообщения» перечитывает ящик всегда и без ограничений — там человек и ждёт свежего. */
    private const val FOREGROUND_MAX_INTERVAL_MS = 60L * 60 * 1000
    private const val FOREGROUND_MIN_INTERVAL_MS = 5L * 60 * 1000

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
     *
     * @param force не смотреть на анти-дребезг (экран «Сообщения», кнопка «Обновить»).
     * @return true, если сервер ответил и данные обновились.
     */
    suspend fun sync(context: Context, force: Boolean = false): Boolean {
        val app = context.applicationContext
        if (!force && !dueForSync(app)) return false
        val store = KeystoreSecureStore(app)
        val session = MayakSession(store, AwgKeyProvider(), AndroidHwidProvider(app, store))
        if (!session.hasToken()) return false
        val backend = MayakBackend(
            HostProvider(MayakHostList.effective(app, store.get(MayakActivity.KEY_SERVER))),
            bypassTunnel = OutsideTunnel.opener(app),
        )
        val lastNotified = prefs(app).getLong(K_LAST_NOTIFIED, 0L)
        // since_id — чтобы не тянуть заново всё, о чём уже уведомляли. Ноль (первый заход) отдаёт
        // весь ящик за 90 дней: он же наполнит бейдж, но звенеть на всю пачку мы не станем (ниже).
        val resp = runCatching { session.messages(backend, sinceId = lastNotified) }.getOrNull() ?: return false
        prefs(app).edit()
            .putInt(K_UNREAD, resp.unread.coerceAtLeast(0))
            .putLong(K_LAST_SYNC, System.currentTimeMillis())
            .putInt(K_NEXT_AFTER_SEC, resp.nextCheckAfterSec)
            .apply()
        notifyAbout(app, resp.messages, firstSync = lastNotified == 0L)
        return true
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

    /** Запомнить, что до этого id всё уже показано (экран «Сообщения» прочитал ящик сам). */
    fun noteSeenUpTo(context: Context, maxId: Long, unread: Int) {
        val p = prefs(context)
        val edit = p.edit().putInt(K_UNREAD, unread.coerceAtLeast(0))
        if (maxId > p.getLong(K_LAST_NOTIFIED, 0L)) edit.putLong(K_LAST_NOTIFIED, maxId)
        edit.apply()
    }

    /** Пора ли в тихую проверку: не чаще, чем просит сервер, и в любом случае не чаще раза в 5 минут. */
    private fun dueForSync(context: Context): Boolean {
        val p = prefs(context)
        val last = p.getLong(K_LAST_SYNC, 0L)
        if (last <= 0L) return true
        val asked = p.getInt(K_NEXT_AFTER_SEC, 0).toLong() * 1000
        val gap = asked.coerceIn(FOREGROUND_MIN_INTERVAL_MS, FOREGROUND_MAX_INTERVAL_MS)
        val since = System.currentTimeMillis() - last
        // Часы могли уехать назад (смена времени/пояса) — тогда since отрицательный: считаем, что пора.
        return since < 0 || since >= gap
    }

    // ===== Уведомления =====

    private fun notifyAbout(context: Context, messages: List<UserMessage>, firstSync: Boolean) {
        val fresh = messages.filter { !it.read }.sortedBy { it.id }
        val maxId = messages.maxOfOrNull { it.id } ?: 0L
        if (maxId > 0) prefs(context).edit().putLong(K_LAST_NOTIFIED, maxId).apply()
        if (fresh.isEmpty()) return
        if (!MayakNotification.canPost(context)) return
        // Первый заход после установки/входа: в ящике может лежать всё за 90 дней, и разом звенеть на
        // всю пачку нельзя. Показываем ОДНО, самое свежее — остальное человек увидит по бейджу.
        val limit = if (firstSync) 1 else MAX_NOTIFY_PER_SYNC
        val quiet = quietNow(context)
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
            .setPriority(if (quiet) NotificationCompat.PRIORITY_LOW else NotificationCompat.PRIORITY_DEFAULT)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            // Оставляем и его: тем, кто «скрывать личное» включил, система спрячет и заголовок.
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
        NotificationManagerCompat.from(context).notify(notifId(m.id), builder.build())
    }

    private fun ensureChannels(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = context.getSystemService(NotificationManager::class.java) ?: return
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
                    NotificationManager.IMPORTANCE_LOW, // важность LOW = без звука. Иначе тихие часы не тихие
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
