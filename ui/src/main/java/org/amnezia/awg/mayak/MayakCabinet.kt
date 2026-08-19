// Открыть кабинет ИЗ ПРИЛОЖЕНИЯ — без второго входа.
//
// Зачем. Проход живой сборкой 16-08: у нового человека кончился пробный доступ, он нажал
// «Подключиться», приложение честно сказало «Пробный период закончился» и предложило кнопку
// «Выбрать тариф» — а по кнопке открылась ФОРМА ВХОДА кабинета. То есть ровно в тот момент, когда
// человек готов заплатить, мы просили его вспомнить девятизначный номер и пароль. У учёток БЕЗ
// почты (у нас это основной вид) забытый пароль означает конец пути: восстанавливать нечем — так и
// написано в самом кабинете.
//
// Приложение при этом АВТОРИЗОВАНО. Значит спрашивать второй раз нечего: берём у ядра одноразовую
// ссылку (POST /v1/client/cabinet-link, живёт две минуты, сгорает при первом открытии) и открываем
// её. Кабинет меняет её на свою сессию тем же маршрутом `#/magic`, которым входят из бота.
//
// 🔴 ГЛАВНОЕ ПРАВИЛО ЭТОГО ФАЙЛА: любая беда на пути к ссылке — НЕ повод не открыть кабинет.
// Нет сети, ядро ответило 500, токен протух — открываем обычный адрес, как открывали раньше. Это
// дорога к оплате; она не смеет упираться в нашу служебную ошибку. Ошибку человек увидит там же,
// куда шёл, и в понятном виде (форма входа), а не как «не удалось открыть кабинет».
package org.amnezia.awg.mayak

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.util.Log
import android.widget.Toast
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import org.amnezia.awg.R
import org.amnezia.awg.mayak.core.HostProvider
import org.amnezia.awg.mayak.core.MayakBackend

object MayakCabinet {

    private const val TAG = "Mayak/Cabinet"

    /**
     * Сколько ждём ссылку, прежде чем открыть кабинет обычным адресом.
     *
     * Три секунды — не «запас на плохую сеть», а ПОТОЛОК ТИШИНЫ после нажатия. Человек нажал кнопку;
     * если за это время ссылки нет, лучше показать ему форму входа, чем крутить ожидание неизвестной
     * длины. Ядро отвечает на эту ручку одним запросом в базу — на живой сети это десятки миллисекунд.
     */
    private const val LINK_TIMEOUT_MS = 3_000L

    /**
     * Открыть кабинет: по возможности УЖЕ ВОШЕДШИМ, иначе — обычным адресом.
     *
     * [path] — куда именно внутри кабинета (например "#/messages"); пусто = на главную кабинета.
     * Одноразовая ссылка ведёт на служебный маршрут `#/magic`, поэтому путь применим только к
     * запасной дороге — после обмена кабинет сам уходит на свою главную.
     */
    fun <T> open(activity: T, path: String = "") where T : Activity, T : LifecycleOwner {
        val plain = MayakHostList.cabinetUrl(activity) + path
        val store = KeystoreSecureStore(activity)
        val session = MayakSession(store, AwgKeyProvider(), AndroidHwidProvider(activity, store))
        // Не вошли — и передавать нечего: кабинет спросит вход, это честное состояние.
        if (!session.hasToken()) {
            openUrl(activity, plain)
            return
        }
        val backend = MayakBackend(
            HostProvider(MayakHostList.effective(activity, store.get(MayakActivity.KEY_SERVER))),
            bypassTunnel = OutsideTunnel.opener(activity),
        )
        activity.lifecycleScope.launch {
            val link = withTimeoutOrNull(LINK_TIMEOUT_MS) {
                runCatching { session.cabinetLink(backend) }.getOrNull()
            }
            // Ссылка ПРИШЛА ОТ СЕРВЕРА, и это одноразовый вход в кабинет. До 19-08 (аудит, A3) её
            // отдавали в браузер сырой строкой: скомпрометированное ядро увело бы этот вход на чужой
            // домен, а `http://` — ещё и открытым текстом (cleartext запрещён нашему процессу, но не
            // браузеру, которому мы отдали интент). Не прошла проверку — открываем обычный адрес:
            // главное правило этого файла, дорога к оплате не упирается в нашу служебную беду.
            val fromServer = link?.takeIf { it.isNotBlank() }
            val safe = fromServer?.takeIf { MayakHostList.ownHttpsUrl(it) }
            if (fromServer != null && safe == null) {
                Log.w(TAG, "ядро прислало ссылку не своего контура или без https — открываю кабинет обычным адресом")
            }
            openUrl(activity, safe ?: plain)
        }
    }

    private fun openUrl(activity: Activity, url: String) {
        // Последний рубеж на ЕДИНСТВЕННОМ выходе наружу: сюда приходит и адрес из cabinetUrl (он уже
        // отфильтрован), и всё, что допишут потом. Проверка здесь, а не только у вызывающего, —
        // чтобы новый вызов не мог её обойти по невнимательности.
        if (!MayakHostList.ownHttpsUrl(url)) {
            Log.w(TAG, "ссылка не нашего контура или не https — наружу не отдаю")
            Toast.makeText(activity, R.string.mayak_err_bad_link, Toast.LENGTH_LONG).show()
            return
        }
        runCatching {
            activity.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        }.onFailure {
            // Ровно тот же текст, что у остальных ссылок приложения: браузера в системе может не
            // быть вовсе (редкие прошивки), и это не про кабинет.
            Toast.makeText(activity, R.string.mayak_err_bad_link, Toast.LENGTH_LONG).show()
        }
    }
}
