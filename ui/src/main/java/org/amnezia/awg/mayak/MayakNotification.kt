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

    /** Метка направления для уведомления/персиста: "🇳🇱 Нидерланды" (флаг + имя), либо дефолт. */
    fun labelFor(ctx: Context, dir: Direction?): String {
        if (dir == null) return ctx.getString(R.string.mayak_connected)
        return "${MayakFlags.emojiForCode(dir.code)} ${dir.name}"
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

    private fun canPost(ctx: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED

    /** Показать/обновить уведомление о НАШЕМ подключении. label — из labelFor (или GoTunnel.connectedLabel). */
    @SuppressLint("MissingPermission") // notify защищён canPost() (проверка POST_NOTIFICATIONS выше)
    fun show(ctx: Context, label: String?) {
        if (!canPost(ctx)) return // нет POST_NOTIFICATIONS (API33+) — молча пропускаем (запросим в Activity)
        ensureChannel(ctx)
        val open = Intent(ctx, MayakActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pi = PendingIntent.getActivity(
            ctx, 0, open,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notif = NotificationCompat.Builder(ctx, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_tile)
            .setContentTitle(ctx.getString(R.string.mayak_notif_connected_title))
            .setContentText(label ?: ctx.getString(R.string.mayak_connected))
            .setOngoing(true)          // нельзя смахнуть, пока подключены
            .setOnlyAlertOnce(true)    // обновление метки не «пикает» повторно
            .setShowWhen(false)
            .setContentIntent(pi)      // тап → открыть приложение
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .build()
        NotificationManagerCompat.from(ctx).notify(NOTIF_ID, notif)
    }

    fun clear(ctx: Context) {
        NotificationManagerCompat.from(ctx).cancel(NOTIF_ID)
    }
}
