// Хранение собственного опыта подключения (второй шаг «Авто»). Логика и правила — :core,
// ConnectHistory; здесь только диск и вызовы из лестницы.
//
// Почему отдельный файл настроек (`mayak_connect_stats`), а не общий `mayak_ui_prefs`: это данные
// замеров, а не настройки человека. Их можно потерять без последствий (порядок вернётся к серверному),
// и когда-нибудь их захочется чистить целиком — чужие настройки при этом трогать нельзя.
//
// 🔒 Что здесь НЕ хранится и храниться не должно: адреса, endpoint'ы, ключи, время суток подключений.
// Только «сколько миллисекунд поднимался туннель к направлению N и вышло ли вообще» — этого хватает
// для порядка списка и по этому нельзя восстановить, куда и когда человек ходил.
package org.amnezia.awg.mayak

import android.content.Context
import org.amnezia.awg.mayak.core.ConnectHistory
import org.amnezia.awg.mayak.core.ConnectStat

object MayakConnectStats {

    private const val PREFS = "mayak_connect_stats"

    private fun keySetup(id: Long) = "setup_$id"
    private fun keyAt(id: Long) = "at_$id"
    private fun keyFailed(id: Long) = "failed_$id"

    /** Опыт по направлению; null — записей нет. */
    fun stat(context: Context, directionId: Long): ConnectStat? {
        val p = prefs(context)
        val at = p.getLong(keyAt(directionId), 0L)
        if (at <= 0L) return null
        return ConnectStat(
            setupMs = p.getInt(keySetup(directionId), 0),
            lastAtMs = at,
            lastFailed = p.getBoolean(keyFailed(directionId), false),
        )
    }

    /**
     * Туннель поднялся: запомнить, сколько это заняло. Зовётся из лестницы РОВНО на подтверждённом
     * выходе в интернет (не на «интерфейс поднят») — иначе в статистику попадёт время до пути,
     * который на самом деле никуда не ведёт.
     */
    fun noteSuccess(context: Context, directionId: Long, setupMs: Long) {
        write(context, directionId, ConnectHistory.noteSuccess(stat(context, directionId), setupMs.toInt(), now()))
    }

    /** Ни одна ступень лестницы не дала выхода — направление уходит вниз списка на час. */
    fun noteFailure(context: Context, directionId: Long) {
        write(context, directionId, ConnectHistory.noteFailure(stat(context, directionId), now()))
    }

    private fun write(context: Context, directionId: Long, s: ConnectStat) {
        prefs(context).edit()
            .putInt(keySetup(directionId), s.setupMs)
            .putLong(keyAt(directionId), s.lastAtMs)
            .putBoolean(keyFailed(directionId), s.lastFailed)
            .apply()
    }

    private fun now() = System.currentTimeMillis()

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
