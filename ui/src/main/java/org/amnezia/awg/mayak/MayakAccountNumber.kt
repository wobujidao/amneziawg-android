// Публичный НОМЕР АККАУНТА в приложении: откуда он приезжает и как попадает человеку в руки.
//
// Зачем это отдельным местом. Номер («472-918-563») — главный якорь разговора с поддержкой: им
// оперирует поддержка, по нему приглашают в группу. Нужен он сразу в трёх разных точках приложения
// (экран «Настройки», письмо в поддержку, диагностический лог), и точки эти живут в файлах, которые
// про сессию ничего не знают и знать не должны. Поэтому — один держатель на всё приложение.
//
// Разделение с :core намеренное: правила («что показывать», «что запоминать», «чего не стирать»)
// живут в org.amnezia.awg.mayak.core.AccountNumber, потому что там их накрывают тесты. Здесь —
// только то, что без Android не работает: сеть, зашифрованное хранилище, буфер обмена и тост.
//
// Наружу сам собой номер не уходит: в телеметри-биконе его нет и быть не должно (там не-ПДн). В
// письмо и диагностический лог он попадает потому, что человек их отправляет САМ и осознанно.
package org.amnezia.awg.mayak

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import org.amnezia.awg.R
import org.amnezia.awg.mayak.core.AccountNumber
import org.amnezia.awg.mayak.core.MayakBackend
import org.amnezia.awg.mayak.core.SecureStore

object MayakAccountNumber {

    /**
     * Уже спрашивали в ЭТОМ процессе. Нужен ровно для случая «ядро номер не отдаёт»: без флага
     * попытка повторялась бы на КАЖДОЙ сверке доступа (раз в час и на каждом resume) и стоила бы
     * лишнего запроса всем, у кого учётка без номера. Успешный ответ и так закрывается кэшем.
     *
     * Сбрасывается в [forget], то есть при выходе: иначе следующий вошедший на этом телефоне
     * остался бы без номера до перезапуска приложения.
     */
    @Volatile
    private var askedThisProcess = false

    /**
     * Готовая к показу строка из хранилища, для места, у которого на руках только Context (письмо
     * поддержке, сбор диагностики). null — показывать нечего, и это ШТАТНО: человек не входил; ядро
     * старее правки, добавившей номер в ответ; учётка старее миграции 0110.
     */
    fun display(context: Context): String? =
        runCatching { AccountNumber.display(KeystoreSecureStore(context)) }.getOrNull()

    /**
     * Освежает номер с ядра (GET /v1/client/account) и запоминает.
     *
     * Тянуть ОДИН РАЗ на установку и хватит: у учётки номер не меняется никогда. Поэтому по
     * умолчанию (`force = false`) уже сохранённый номер не перезапрашивается — незачем ходить в сеть
     * за неизменяемым значением на каждом открытии экрана.
     *
     * Любой отказ (сети нет, ядро отвечает 404, поля в ответе нет) проглатываем и возвращаем то, что
     * было: отсутствие номера не должно ломать ни экран, ни то, ради чего вызов случился по пути.
     */
    suspend fun refresh(
        store: SecureStore,
        token: String,
        backend: MayakBackend,
        force: Boolean = false,
    ): String? {
        if (!force && (AccountNumber.cached(store) != null || askedThisProcess)) {
            return AccountNumber.display(store)
        }
        askedThisProcess = true
        val fresh = runCatching { backend.account(token).accountNumber }.getOrNull()
        return AccountNumber.remember(store, fresh)
    }

    /** Забыть номер (выход из аккаунта) и разрешить спросить его заново для следующей учётки. */
    fun forget(store: SecureStore) {
        askedThisProcess = false
        AccountNumber.forget(store)
    }

    /**
     * Номер в буфер обмена + внятное подтверждение. Кладём В ВИДЕ С ДЕФИСАМИ — именно так его
     * прочтёт человек, который вставит его в письмо или в форму; серверная сторона дефисы разбирает
     * сама (acctnum.Normalize).
     */
    fun copy(context: Context, shown: String) {
        runCatching {
            val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText(context.getString(R.string.mayak_account_number), shown))
        }
        Toast.makeText(context, R.string.mayak_account_number_copied, Toast.LENGTH_SHORT).show()
    }
}
