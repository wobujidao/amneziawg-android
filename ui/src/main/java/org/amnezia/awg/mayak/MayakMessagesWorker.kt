// Фоновая проверка ящика сообщений (SPEC-0047): раз в 6 часов заходим на ядро за новым и, если оно
// есть, показываем уведомление. Устроено ровно как еженедельный телеметри-бикон — тем же
// WorkManager, с той же идемпотентной постановкой и с тем же правилом «любой сбой глотаем».
//
// 🔴 Почему это ВАЖНАЯ часть, а не украшение. До SPEC-0047 приложение в фоне на сервер не ходило
// ВООБЩЕ, кроме этого самого бикона раз в 7 дней. То есть «подписка заканчивается» доходило до
// человека только если он сам открыл приложение — а открывает он его как раз реже всего тогда,
// когда всё работает. Эти шесть часов и есть недостающий кусок доставки.
//
// Не вошёл (нет токена), нет сети, ручки на ядре ещё нет — тихо ничего: сбой доставки не должен
// превращаться ни в ошибку на экране, ни в retry-шторм. Всегда Result.success() → следующая попытка
// придёт штатным расписанием.
package org.amnezia.awg.mayak

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import java.util.concurrent.TimeUnit

class MayakMessagesWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        // ALWAYS: расписание здесь и так держит WorkManager — второй потолок поверх него означал бы
        // пропущенные такты.
        runCatching { MayakMessages.sync(applicationContext, MayakMessages.SyncTrigger.ALWAYS) }
        return Result.success()
    }

    companion object {
        private const val UNIQUE_WORK = "mayak-messages-6h"

        /** Период проверки. Совпадает с `next_check_after_sec`, который ядро отдаёт по умолчанию
         *  (21600 с): два разных числа в двух местах разъехались бы молча. */
        const val PERIOD_HOURS = 6L

        /** Поставить периодическую проверку. Идемпотентно (KEEP не пересоздаёт уже стоящую работу),
         *  поэтому безопасно звать на каждом старте приложения. Только при наличии сети. */
        fun enqueue(context: Context) {
            val request = PeriodicWorkRequestBuilder<MayakMessagesWorker>(PERIOD_HOURS, TimeUnit.HOURS)
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .build(),
                )
                .build()
            WorkManager.getInstance(context.applicationContext)
                .enqueueUniquePeriodicWork(UNIQUE_WORK, ExistingPeriodicWorkPolicy.KEEP, request)
        }
    }
}
