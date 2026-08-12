// Постоянное уведомление «Подключено» (флаг + направление), пока туннель поднят НАМИ. Директива
// владельца 2026-07-02 (по образцу Happ): в шторке видно, что VPN включён и куда. Реализовано обычным
// ongoing-уведомлением из app-слоя (не foreground-service): показываем при НАШЕМ коннекте, убираем при
// отключении; при повторном открытии MayakActivity пересинхронизирует по факту туннеля. «Только наш
// коннект» — вызывается из тех же мест, что и смена состояния круга (см. фикс состояния 2026-07-02).
// AdBlock-суффикс добавим, когда в приложении появится DNS-переключатель (APP-BACKLOG §Логика).
package org.amnezia.awg.mayak

import android.Manifest
import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import org.amnezia.awg.R
import org.amnezia.awg.mayak.core.Direction

object MayakNotification {
    private const val CHANNEL_ID = "mayak_vpn_status"
    private const val NOTIF_ID = 0x4D41 // 'MA'

    // Тег «AmneziaWG/*» — только такие строки попадают в присланный диаг-лог (SPEC-0012). Сюда пишем
    // единственный случай, который сам себя не покажет: уведомление попросили без метки направления.
    private const val TAG = "AmneziaWG/mayak-notif"

    /** Метка направления для уведомления/персиста: флаг + подпись РОВНО как в списке приложения
     *  ("🇳🇱 Нидерланды (nl)"), либо дефолт. Текст берём из displayLabel() — один источник со списком. */
    fun labelFor(ctx: Context, dir: Direction?): String {
        if (dir == null) return ctx.getString(R.string.mayak_connected)
        return "${MayakFlags.emojiForCode(dir.flagCode())} ${dir.displayLabel()}"
    }

    private fun ensureChannel(ctx: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = ctx.getSystemService(NotificationManager::class.java) ?: return
        if (nm.getNotificationChannel(CHANNEL_ID) != null) return
        val ch = NotificationChannel(
            CHANNEL_ID,
            ctx.getString(R.string.mayak_notif_channel_name),
            NotificationManager.IMPORTANCE_LOW, // статус: без звука/вибро/всплытия
        ).apply {
            description = ctx.getString(R.string.mayak_notif_channel_desc)
            setShowBadge(false)
        }
        nm.createNotificationChannel(ch)
    }

    /** Разрешено ли вообще показывать уведомления (POST_NOTIFICATIONS с Android 13).
     *  Не private: ту же проверку делает ящик сообщений (MayakMessages), и второй её копии,
     *  которая когда-нибудь разъедется с этой, у нас быть не должно. */
    internal fun canPost(ctx: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED

    /**
     * Слово, которое человек читает в шторке. «Защищено» — ТОЛЬКО при подтверждённом трафике
     * (GoTunnel.liveness), в остальных случаях честный промежуточный статус.
     *
     * До аудита 2026-07-31 подзаголовок был константой «Защищено»: туннель поднят — значит защищено.
     * Человек в шторку смотрит чаще, чем в приложение, и именно там оставалось самое уверенное враньё.
     */
    private fun statusText(ctx: Context): String = ctx.getString(
        when (GoTunnel.liveness) {
            GoTunnel.LIVE_OK -> R.string.mayak_connected
            GoTunnel.LIVE_NO_TRAFFIC -> R.string.mayak_notif_no_traffic
            GoTunnel.LIVE_NO_NETWORK -> R.string.mayak_status_no_network
            else -> R.string.mayak_status_checking
        }
    )

    /** Показать/обновить уведомление о НАШЕМ подключении. label — из labelFor (или GoTunnel.connectedLabel);
     *  pingMs — пинг сервера для подзаголовка «Защищено · Пинг: 42 мс» (null → без пинга);
     *  speed — строка «↓… ↑…» в тексте уведомления (в статус-баре — всегда обычный значок Маяка).
     *  Значок IPv6 и пометка пути НЕ параметры, а чтение GoTunnel: уведомление обновляется из
     *  полудюжины мест, и параметр там забывали. Именно так значок IPv6 и пропадал — проба ставила
     *  его один раз, а следующий такт пинг-цикла (через пару секунд) затирал значением по умолчанию. */
    @SuppressLint("MissingPermission") // notify защищён canPost() (проверка POST_NOTIFICATIONS выше)
    fun show(ctx: Context, label: String?, pingMs: Int? = null, speed: String? = null) {
        // ⛔ ЕДИНСТВЕННЫЙ ЗАМОК: нет НАШЕГО поднятого туннеля — нет и уведомления, кто бы ни попросил.
        //
        // Аудит 2026-07-31 поймал «осиротевшее» уведомление «Защищено» при полностью провалившемся
        // подключении (tun0 в системе отсутствует). Прилетало оно из пинг-цикла прошлого подключения,
        // который никто не остановил. Чинить это по одному вызывающему бессмысленно — их полдюжины
        // (пинг, скорость, IPv6-проба, реоупен, автоподключение, выдача разрешения), и следующий
        // забудут снова. Поэтому проверка стоит ЗДЕСЬ, в единственной двери.
        if (GoTunnel.connectedSinceElapsed == null) { clear(ctx); return }
        if (!canPost(ctx)) return // нет POST_NOTIFICATIONS (API33+) — молча пропускаем (запросим в Activity)
        ensureChannel(ctx)
        val open = Intent(ctx, MayakActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pi = PendingIntent.getActivity(
            ctx, 0, open,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        // Кнопка «Отключить» (Happ-стиль action): гасит туннель без открытия приложения.
        val disconnectIntent = Intent(ctx, MayakNotificationReceiver::class.java)
            .setAction(MayakNotificationReceiver.ACTION_DISCONNECT)
        val disconnectPi = PendingIntent.getBroadcast(
            ctx, 1, disconnectIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        // Макет как в Happ: имя приложения «Маяк» рисует система в шапке, крупная строка — направление.
        val status = statusText(ctx)
        // ЗАПАСНАЯ МЕТКА НАПРАВЛЕНИЯ (жалоба владельца 2026-08-03: в шторке осталось голое «Защищено»
        // без страны, пинга и IPv6, хотя туннель жив и на главном экране всё на месте).
        //
        // Метка процесс-скоупная (GoTunnel.connectedLabel) и обнуляется на КАЖДОМ переподъёме туннеля:
        // GoBackend.setState(UP) внутри сначала делает DOWN, а тот приходит к нам как «внешний обрыв»
        // и сбрасывает состояние коннекта. Возвращает метку ТОЛЬКО успешный onConnected на открытом
        // экране — значит любой путь, где туннель остался поднятым, а onConnected не дошёл, оставлял
        // шторку без метки до самого отключения. Поэтому метку персистим и берём с диска, когда её нет.
        val shownLabel = label ?: MayakPrefs.lastConnLabel(ctx)?.also {
            android.util.Log.w(TAG, "уведомление без метки направления — беру сохранённую ($it)")
            GoTunnel.connectedLabel = it // вернуть и в состояние: из него читают «Подробности» и плитка
        }
        val builder = NotificationCompat.Builder(ctx, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_mayak) // всегда значок Маяка (скорость — в тексте/на экране)
            // Без метки направления заголовком идёт САМ СТАТУС. Раньше сюда подставлялось «Защищено» —
            // и именно так выглядело осиротевшее уведомление из аудита: одно слово, и то неправда.
            .setContentTitle(shownLabel ?: status)
            .setOngoing(true)          // нельзя смахнуть, пока подключены
            .setOnlyAlertOnce(true)    // обновление метки не «пикает» повторно
            .setShowWhen(false)
            .setContentIntent(pi)      // тап → открыть приложение
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .addAction(0, ctx.getString(R.string.mayak_notif_disconnect), disconnectPi)
        // Подзаголовок: честный статус + пинг сервера («Пинг: 0» = нет ответа) + IPv6, если измерены.
        // Пинг/скорость/IPv6 дописываем ТОЛЬКО к подтверждённому статусу: рядом со словом
        // «трафик не идёт» цифры прошлого замера лишь путают.
        //
        // Строка рисуется ВСЕГДА, а не только при известном направлении. Раньше весь этот блок стоял
        // под `if (label != null)`, и потеря одной метки уносила из шторки заодно пинг, IPv6, «Резерв»
        // и скорость — человек видел одно слово вместо всего, что мы про подключение знаем.
        val ok = GoTunnel.liveness == GoTunnel.LIVE_OK
        val parts = buildList {
            if (shownLabel != null) add(status) // заголовок занят направлением → статус идёт сюда
            if (ok) {
                if (pingMs != null) add(ctx.getString(R.string.mayak_ping_label, pingMs))
                // Честный значок IPv6 — по факту УДАВШЕЙСЯ пробы выхода (GoTunnel.egressIpv6), а не по
                // тому, вспомнил ли о нём вызывающий (см. комментарий к сигнатуре).
                if (GoTunnel.egressIpv6 != null) add(ctx.getString(R.string.mayak_ipv6_badge))
                // «Резерв» — идём через запасной канал (SPEC-0039). Флаг процесс-скоупный (GoTunnel), а не
                // параметр: уведомление обновляется из полудюжины мест, и все они его бы просто забыли.
                when (GoTunnel.connectedRoute) {
                    GoTunnel.ROUTE_RELAY -> add(ctx.getString(R.string.mayak_route_relay_badge))
                    GoTunnel.ROUTE_FALLBACK -> add(ctx.getString(R.string.mayak_fallback_badge))
                }
                if (speed != null) add(speed) // ↓/↑ скорость (видно в шторке при свёрнутом app)
            }
        }
        if (parts.isNotEmpty()) builder.setContentText(parts.joinToString(" · "))
        NotificationManagerCompat.from(ctx).notify(NOTIF_ID, builder.build())
    }

    /** Скорость в Мбит/с — как во ВСЕХ спидтестах (правка владельца 2026-07-07). bytesPerSec×8. Синхронно с главным. */
    fun formatSpeed(bytesPerSec: Long): String {
        val mbit = bytesPerSec * 8.0 / 1_000_000.0
        return when {
            mbit >= 10 -> String.format("%.0f Мбит/с", mbit)
            mbit >= 1 -> String.format("%.1f Мбит/с", mbit)
            else -> String.format("%.2f Мбит/с", mbit)
        }
    }

    fun clear(ctx: Context) {
        NotificationManagerCompat.from(ctx).cancel(NOTIF_ID)
    }
}
