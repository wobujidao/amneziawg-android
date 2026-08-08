// Публичный НОМЕР АККАУНТА в приложении: где он лежит, как его освежить и как отдать человеку.
//
// Зачем это отдельным местом. Номер («472-918-563») — главный якорь разговора с поддержкой: им
// оперирует поддержка, по нему приглашают в группу. Нужен он сразу в трёх разных точках приложения
// (экран «Настройки», письмо в поддержку, диагностический лог), и точки эти живут в файлах, которые
// про сессию ничего не знают и знать не должны. Поэтому — один держатель на всё приложение.
//
// Хранение: тот же зашифрованный SecureStore, где лежит email аккаунта. Номер НЕ секрет (это имя, а
// не пароль), но у него ровно тот же жизненный цикл, что у email: он про КОНКРЕТНУЮ учётку и обязан
// исчезнуть при выходе — иначе следующий вошедший увидит чужой номер и продиктует его поддержке.
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

    /** Ключ в SecureStore. Публичный: его снимает MayakSession.logout вместе с остальным про учётку. */
    const val KEY = "account_number"

    /** Сырой номер из хранилища (девять цифр без дефисов) или null, если ядро его ещё не давало. */
    fun cached(store: SecureStore): String? = store.get(KEY)?.takeIf { AccountNumber.isShowable(it) }

    /**
     * Готовая к показу и к диктовке строка «472-918-563» — или null, если показывать нечего.
     *
     * null здесь ШТАТЕН, а не аварийный: человек не входил; ядро старее правки, добавившей номер в
     * ответ; учётка старее миграции 0110. Во всех случаях экран просто молчит про номер.
     */
    fun display(store: SecureStore): String? = cached(store)?.let { AccountNumber.format(it) }

    /** То же, но из места, у которого на руках только Context (письмо поддержке, сбор диагностики). */
    fun display(context: Context): String? =
        runCatching { display(KeystoreSecureStore(context)) }.getOrNull()

    /**
     * Освежает номер с ядра (GET /v1/client/account) и запоминает.
     *
     * Тянуть ОДИН РАЗ на установку и хватит: у учётки номер не меняется никогда. Поэтому по
     * умолчанию (`force = false`) уже сохранённый номер не перезапрашивается — незачем ходить в сеть
     * за неизменяемым значением на каждом открытии экрана.
     *
     * Любой отказ (сети нет, ядро старое и отвечает 404, поля в ответе нет) проглатываем и
     * возвращаем то, что было: отсутствие номера не должно ломать ни экран, ни то, ради чего вызов
     * случился по пути (сверку доступа, например).
     */
    suspend fun refresh(
        store: SecureStore,
        token: String,
        backend: MayakBackend,
        force: Boolean = false,
    ): String? {
        if (!force && cached(store) != null) return display(store)
        val fresh = runCatching { backend.account(token).accountNumber }.getOrNull()
        // Записываем только то, что похоже на номер: пустое/null от старого ядра не должно стирать
        // уже сохранённый номер (иначе письмо в поддержку теряло бы его при первом же отказе).
        if (AccountNumber.isShowable(fresh)) store.put(KEY, fresh!!.trim())
        return display(store)
    }

    /** Забыть номер: вызывается из MayakSession.logout вместе с токеном и email. */
    fun forget(store: SecureStore) = store.remove(KEY)

    /**
     * Номер в буфер обмена + внятное подтверждение. Кладём В КАНОНИЧЕСКОМ ВИДЕ С ДЕФИСАМИ — именно
     * так его ждут наши формы и так его прочтёт человек, который вставит его в письмо; серверная
     * сторона дефисы разбирает сама (acctnum.Normalize).
     */
    fun copy(context: Context, shown: String) {
        runCatching {
            val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText(context.getString(R.string.mayak_account_number), shown))
        }
        Toast.makeText(context, R.string.mayak_account_number_copied, Toast.LENGTH_SHORT).show()
    }
}
