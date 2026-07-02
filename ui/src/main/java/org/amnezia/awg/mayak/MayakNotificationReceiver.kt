// Обработчик кнопки «Отключить» в уведомлении «Подключено» (Happ-стиль action, директива владельца
// 2026-07-02). Гасит НАШ туннель без открытия приложения и убирает уведомление. Backend процесс-
// скоупный (GoTunnel) → down() опускает реально поднятый туннель; если процесс уже мёртв — no-op + чистим.
package org.amnezia.awg.mayak

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import kotlinx.coroutines.runBlocking

class MayakNotificationReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_DISCONNECT) return
        val appCtx = context.applicationContext
        val pending = goAsync() // держим ресивер живым, пока гасим туннель в фоне
        Thread {
            try {
                runBlocking { GoTunnel(appCtx).down() }
            } catch (_: Throwable) {
                // туннель мог быть уже опущен / процесс поднялся заново — не критично
            } finally {
                MayakNotification.clear(appCtx)
                pending.finish()
            }
        }.start()
    }

    companion object {
        const val ACTION_DISCONNECT = "org.amnezia.awg.mayak.action.DISCONNECT"
    }
}
