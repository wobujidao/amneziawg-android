// Приём пуша от транспорта. Единственное, что делает этот файл, — БУДИТ ПРОВЕРКУ ЯЩИКА.
//
// 🔴 Уведомление здесь НЕ РИСУЕТСЯ И НЕ МОЖЕТ БЫТЬ НАРИСОВАНО: в пуше нет текста (PushData в :core),
// есть только «загляни в ящик» и id для лога. Текст живёт на ядре, приложение забирает его обычным
// запросом и показывает через ЕДИНСТВЕННУЮ дверь показа — MayakMessages. Так тихие часы, лимит на
// пачку, отметка «об этом уже сказали» и проверка прав остаются в одном месте, а не в двух, которые
// когда-нибудь разъедутся.
//
// ⚠️ Пуш приходит data-only и только так. Блок `notification` система показала бы САМА, серверным
// текстом, в обход приложения — и на фоне мы бы об этом даже не узнали (onMessageReceived не
// зовётся вовсе). Держать это может только серверная половина; клиентская сторона такого пуша не
// увидит и предотвратить не сможет — поэтому требование записано в контракте.
package org.amnezia.awg.mayak

import android.util.Log
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.amnezia.awg.mayak.core.PushData

class MayakPushService : FirebaseMessagingService() {

    /**
     * Транспорт выдал новый адрес доставки. Зовётся и на первой регистрации, и при ротации; на
     * втором мы обязаны сходить на ядро сами — старый адрес с этого момента мёртв, и человек об этом
     * никак не узнает.
     *
     * ⚠️ В SDK 25.x метод помечен deprecated, и подсказка предлагает `onRegistered(installationId)`.
     * ПЕРЕХОДИТЬ НА НЕГО НЕЛЬЗЯ БЕЗ СЕРВЕРНОЙ ПОЛОВИНЫ: он отдаёт ДРУГОЙ идентификатор (Firebase
     * Installation ID, а не адрес доставки FCM), требует отдельной строки в манифесте
     * (`firebase_messaging_installation_id_enabled`) и другого способа отправки на стороне ядра.
     * Тихая замена одного на другое = зарегистрированный на ядре мусор вместо адреса, без единой
     * ошибки в логе. Наш контракт — FCM-токен, значит этот метод, пока он жив.
     */
    override fun onNewToken(token: String) {
        MayakPush.onNewToken(this, token)
    }

    /**
     * Пришёл толчок. Забираем ящик СИНХРОННО, но с потолком по времени.
     *
     * Почему синхронно: пока метод не вернулся, система держит наш процесс и wake-lock — это самый
     * короткий путь от пуша до уведомления, и Firebase рекомендует именно его для быстрых операций.
     * Почему с потолком: наш запрос к ядру в худшем случае перебирает домены и заходит второй раз
     * мимо туннеля (2 адреса × 2 маршрута × до 20 с) — это заметно больше окна, которое даёт система.
     * Не успели за отведённое — доводим работу штатным WorkManager, у него на это есть и wake-lock,
     * и повторные попытки, и ожидание сети.
     */
    override fun onMessageReceived(message: RemoteMessage) {
        val kind = message.data[PushData.KIND]
        val id = message.data[PushData.ID]
        Log.i(TAG, "получен пуш: kind=$kind id=$id поля=${message.data.keys}")
        if (kind != PushData.KIND_MAILBOX) {
            // Незнакомый повод — молча мимо. Сервер вправе завести новый вид толчка раньше, чем
            // приложение научится его понимать; это не ошибка и не повод шуметь.
            Log.i(TAG, "пуш с незнакомым поводом ($kind) — пропускаю")
            return
        }
        val result = runBlocking {
            withTimeoutOrNull(SYNC_BUDGET_MS) {
                // ALWAYS: анти-дребезг здесь не нужен, толчок и так приходит по делу, а свой потолок
                // означал бы «пуш пришёл, а мы решили не ходить».
                MayakMessages.sync(applicationContext, MayakMessages.SyncTrigger.ALWAYS)
            }
        }
        when {
            result == null -> {
                Log.i(TAG, "пуш id=$id: не успели за ${SYNC_BUDGET_MS / 1000} с — догоняем фоновой работой")
                MayakMessagesWorker.enqueueOnce(applicationContext)
            }
            result.ok -> Log.i(TAG, "пуш id=$id: сходил в ящик, новых ${result.fresh.size}")
            else -> {
                // Не вошёл, нет сети, ручки на ядре ещё нет — MayakMessages это уже записал причиной.
                Log.i(TAG, "пуш id=$id: в ящик сходить не удалось — догоняем фоновой работой")
                MayakMessagesWorker.enqueueOnce(applicationContext)
            }
        }
    }

    private companion object {
        const val TAG = "AmneziaWG/mayak-push"

        /**
         * Сколько ждём ящик, не отпуская пуш. Жёсткий предел самого SDK — 20 секунд («This should
         * complete within 20 seconds. Taking longer may … affect pending messages», исходник
         * FirebaseMessagingService), а руководство Firebase просит укладываться в «несколько секунд»
         * и уводить в WorkManager всё, что дольше десяти. Восемь — с запасом внутри обеих границ.
         */
        const val SYNC_BUDGET_MS = 8_000L
    }
}
