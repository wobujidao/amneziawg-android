// Push-уведомления: УСКОРИТЕЛЬ ящика сообщений (SPEC-0047), а не его замена.
//
// 🔴 Что здесь важно понять с первой строки: пуш НЕ НЕСЁТ ТЕКСТА. Он говорит только «загляни в
// ящик» — приложение идёт на ядро обычным запросом и показывает уведомление само, из ящика. Пуш
// проходит через Google, а уведомление читают через плечо (приложение умеет прятаться под «Погоду»/
// «Заметки», MayakDisguise), и замер 12-08 показал: спрятать текст на локскрине силами Android
// нельзя — `VISIBILITY_PRIVATE` работает только вместе с системной настройкой человека, а она
// выключена по умолчанию. Значит текст не отдаём НИКОМУ, включая транспорт. Контракт — PushData в :core.
//
// 🔴 Зачем это вообще, если ящик уже работает. Ящик держится на четырёх поводах (открытие приложения,
// подъём туннеля, частая проверка при поднятом туннеле, WorkManager раз в 6 часов). Быстрее
// WorkManager не умеет: минимальный период — 15 минут, и даже он не обещание, а «когда система
// сочтёт возможным». Значит человек, который приложение НЕ открывает и туннель НЕ держит, узнаёт про
// «доступ закончился» в среднем через три часа. Пуш закрывает именно эту дыру — и только её:
// решение владельца 13-08 дословно «Firebase делаем, но ПОСЛЕ обкатки ящика», push не заменяет pull.
//
// 🔴 Чего здесь СПЕЦИАЛЬНО нет: своего показа уведомлений. Всё, что делает пуш, — зовёт
// MayakMessages.sync(ALWAYS). Один путь показа = один набор правил (тихие часы, лимит на пачку,
// отметка «об этом уже сказали», проверка прав). Второй путь показа неминуемо разъехался бы с первым.
package org.amnezia.awg.mayak

import android.content.Context
import android.util.Log
import androidx.core.app.NotificationManagerCompat
import com.google.android.gms.common.ConnectionResult
import com.google.android.gms.common.GoogleApiAvailability
import com.google.android.gms.tasks.Tasks
import com.google.firebase.FirebaseApp
import com.google.firebase.messaging.FirebaseMessaging
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.amnezia.awg.BuildConfig
import org.amnezia.awg.mayak.core.HostProvider
import org.amnezia.awg.mayak.core.MayakBackend
import org.amnezia.awg.mayak.core.PushAction
import org.amnezia.awg.mayak.core.pushAction

object MayakPush {

    /** Тег диаг-лога: в присланный человеком лог попадают только строки «AmneziaWG…»/«Mayak…». */
    private const val TAG = "AmneziaWG/mayak-push"

    private const val PREFS = "mayak_push"

    /**
     * Адрес доставки, который МЫ УЖЕ отправили ядру. Хранится по двум причинам: чтобы не дёргать
     * сервер на каждом открытии экрана (адрес меняется раз в месяцы) и чтобы при выходе из аккаунта
     * было ЧТО снимать — на выходе транспорт может быть недоступен, а сказать ядру надо.
     */
    private const val K_SENT_TOKEN = "sent_token"

    /** Версия сборки, с которой адрес отправлен: обновились — ядро должно узнать новую. */
    private const val K_SENT_VERSION = "sent_version"

    /**
     * Сколько ждём адрес у транспорта. Задача уходит в сеть (Google), и на плохой сотовой это
     * секунды; ждём в фоне, никого не задерживая, но не бесконечно.
     */
    private const val TOKEN_WAIT_SEC = 20L

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * Умеет ли этот телефон в пуш ВООБЩЕ. Две разные причины «нет», и обе штатные:
     *
     *  1) НЕТ КОНФИГУРАЦИИ — сборка не боевая. Значения Firebase лежат строковыми ресурсами только в
     *     варианте prodRelease (генерирует scripts/firebase-res.sh, файл в .gitignore), плагин
     *     google-services мы не подключаем осознанно (причины — в ui/build.gradle.kts). Без ресурсов
     *     FirebaseApp не инициализируется, и это НЕ падение: FirebaseInitProvider просто пишет в лог
     *     «FirebaseApp initialization unsuccessful».
     *  2) НЕТ СЕРВИСОВ GOOGLE — телефон без GMS (Huawei, кастомная прошивка, /e/OS). Таких у нас
     *     часть людей, и для них ящик обязан работать ровно как раньше, на pull.
     *
     * Ни в одном из случаев ничего не должно ни падать, ни всплывать на экране — одна строка в лог.
     */
    fun available(context: Context): Boolean {
        val app = context.applicationContext
        if (FirebaseApp.getApps(app).isEmpty()) {
            Log.i(TAG, "push недоступен: нет конфигурации (сборка не боевая) — ящик работает опросом")
            return false
        }
        // runCatching, потому что сама проверка живёт в чужой библиотеке и на диких прошивках умеет
        // бросать (подделки GMS, урезанные сборки Android). Не смогли спросить — считаем, что нет.
        val code = runCatching {
            GoogleApiAvailability.getInstance().isGooglePlayServicesAvailable(app)
        }.getOrElse { e ->
            Log.i(TAG, "push недоступен: сервисы Google не отвечают (${e.javaClass.simpleName})")
            return false
        }
        if (code != ConnectionResult.SUCCESS) {
            Log.i(TAG, "push недоступен: сервисов Google нет на телефоне (код $code) — ящик работает опросом")
            return false
        }
        return true
    }

    /**
     * Привести подписку в соответствие с действительностью: зарегистрировать адрес доставки, если
     * будить нас есть смысл, и СНЯТЬ его, если смысла больше нет. Идемпотентно — уже отправленный
     * адрес второй раз на сервер не уходит.
     *
     * Зовётся с главного экрана (то есть и сразу после входа) и после выдачи разрешения на
     * уведомления. Ничего не ждёт и ничего не возвращает: это фоновая гигиена, а не шаг сценария.
     *
     * 🔑 «Есть ли смысл» = ВКЛЮЧЕНЫ ЛИ УВЕДОМЛЕНИЯ у приложения, и это одновременно наш выключатель
     * (см. отчёт: отдельный тумблер «быстрые уведомления» не заводим). Причина не только в том, что
     * показать всё равно нечем: Google понижает приоритет пушам приложения, чьи high-priority
     * сообщения не заканчиваются видимым уведомлением («may be deprioritized to normal priority»,
     * решение принимается по 7 дням поведения). То есть регистрировать адрес там, где показать
     * нельзя, — это портить доставку ОСТАЛЬНЫМ.
     */
    fun refresh(context: Context) {
        val app = context.applicationContext
        scope.launch {
            runCatching { refreshNow(app) }.onFailure {
                // Сюда попадает только неожиданное: сеть и отсутствие входа обработаны ниже по коду.
                Log.w(TAG, "refresh: не получилось (${it.javaClass.simpleName}: ${it.message})")
            }
        }
    }

    /**
     * Транспорт выдал НОВЫЙ адрес доставки (переустановка, очистка данных, ротация у Google).
     * Старый с этого момента мёртв, поэтому шлём новый сразу, не дожидаясь открытия приложения.
     */
    fun onNewToken(context: Context, token: String) {
        val app = context.applicationContext
        Log.i(TAG, "новый адрес доставки от транспорта (длина ${token.length})")
        scope.launch {
            runCatching { send(app, token) }.onFailure {
                // Не отправили — не беда: адрес не помечен отправленным, и следующее открытие
                // приложения (refresh) сходит снова.
                Log.i(TAG, "новый адрес не ушёл на ядро (${it.javaClass.simpleName}) — повторим при открытии")
            }
        }
    }

    /**
     * Выход из аккаунта или удаление аккаунта: снять адрес доставки. Ядро иначе продолжит будить
     * телефон по чужой уже учётке — а следующий вошедший на этом телефоне человек не должен получать
     * толчки, адресованные предыдущему.
     *
     * ⚠️ ЗВАТЬ ДО session.logout(): ядро узнаёт устройство по токену сессии, и после выхода снимать
     * уже нечем. Локальную отметку стираем В ЛЮБОМ случае — даже если сеть не дала сказать «сними»:
     * иначе следующий вошедший увидит «адрес уже отправлен» и не отправит свой.
     */
    fun onLogout(context: Context) {
        val app = context.applicationContext
        val sent = prefs(app).getString(K_SENT_TOKEN, null)
        forget(app)
        if (sent == null) return
        scope.launch {
            // Сам scope уже на Dispatchers.IO — сеть тут законна.
            val ok = runCatching { unregister(app, sent) }.isSuccess
            Log.i(TAG, "выход из аккаунта: снятие адреса доставки ok=$ok")
        }
    }

    // ===== Внутреннее =====

    private suspend fun refreshNow(app: Context) {
        // 🔑 Почему areNotificationsEnabled(), а не MayakNotification.canPost(): canPost смотрит
        // только на разрешение POST_NOTIFICATIONS (Android 13+), а человек мог выключить уведомления
        // приложения в системных настройках на ЛЮБОЙ версии. Для «есть ли смысл нас будить» нужна
        // честная картина, и это ровно один вызов, а не вторая копия проверки.
        val enabled = NotificationManagerCompat.from(app).areNotificationsEnabled()
        val sent = prefs(app).getString(K_SENT_TOKEN, null)
        val sentVersion = prefs(app).getString(K_SENT_VERSION, null)
        // Транспорт спрашиваем ТОЛЬКО когда уведомления разрешены: available() пишет в лог, а строка
        // «сервисов Google нет» при выключенных уведомлениях уводила бы разбор в ложную сторону.
        val available = enabled && available(app)
        val loggedIn = hasLogin(app)
        when (pushAction(enabled, available, loggedIn, sent != null, sentVersion == BuildConfig.VERSION_NAME)) {
            PushAction.NOTHING -> {
                Log.i(
                    TAG,
                    "ничего не делаем: уведомления=$enabled транспорт=$available вход=$loggedIn " +
                        "отправлен=${sent != null} версия=$sentVersion",
                )
                return
            }

            PushAction.UNREGISTER -> {
                // Отметку стираем ВСЕГДА, даже если сказать ядру не удалось: иначе следующий раз мы
                // сочтём адрес отправленным и не отправим его, когда уведомления снова разрешат.
                Log.i(TAG, "уведомления у приложения выключены — снимаем адрес доставки")
                forget(app)
                sent?.let { runCatching { unregister(app, it) } }
                return
            }

            PushAction.REGISTER -> {
                // Адрес у транспорта спрашиваем ПОСЛЕДНИМ: это единственный шаг, который лезет в сеть
                // ради самой проверки, и делать его до всех отказов было бы расточительством.
                val token = token() ?: return
                if (token == sent && sentVersion == BuildConfig.VERSION_NAME) {
                    Log.i(TAG, "адрес доставки уже зарегистрирован (версия $sentVersion) — на ядро не идём")
                    return
                }
                send(app, token)
            }
        }
    }

    /**
     * Спросить адрес доставки у транспорта. Блокирующее ожидание Tasks.await допустимо: сюда мы
     * приходим только на Dispatchers.IO (на главном потоке оно бы бросило исключение само), и это
     * дешевле, чем тащить ради одного вызова kotlinx-coroutines-play-services.
     *
     * Ошибка тут — норма жизни, а не поломка: телефон без сети, сервисы Google в процессе обновления,
     * SERVICE_NOT_AVAILABLE. Молчим и пробуем в следующий раз.
     *
     * ⚠️ `getToken()` в SDK 25.x помечен deprecated в пользу `register()`. Это НЕ переименование:
     * `register()` — часть другой модели, где сервер отправляет по Firebase Installation ID, а не по
     * адресу FCM, и включается она строкой в манифесте (после которой `getToken()` начинает бросать
     * IllegalStateException). Наш контракт с ядром — FCM-токен, так что здесь именно этот вызов;
     * менять его можно только ВМЕСТЕ с серверной половиной.
     */
    @Suppress("DEPRECATION")
    private fun token(): String? = runCatching {
        Tasks.await(FirebaseMessaging.getInstance().token, TOKEN_WAIT_SEC, TimeUnit.SECONDS)
    }.getOrElse {
        Log.i(TAG, "адрес доставки не получен (${it.javaClass.simpleName}) — попробуем в следующий раз")
        null
    }

    /** Отправить адрес ядру и запомнить, ЧТО отправили. Отметку ставим ТОЛЬКО после успеха. */
    private suspend fun send(app: Context, pushToken: String) {
        val store = KeystoreSecureStore(app)
        val session = MayakSession(store, AwgKeyProvider(), AndroidHwidProvider(app, store))
        if (!session.hasToken()) {
            Log.i(TAG, "нет входа — адрес доставки не отправляю")
            return
        }
        session.registerPush(backend(app, store), pushToken)
        prefs(app).edit()
            .putString(K_SENT_TOKEN, pushToken)
            .putString(K_SENT_VERSION, BuildConfig.VERSION_NAME)
            .apply()
        // 🔒 Сам адрес в лог НЕ пишем: это адрес доставки до конкретного телефона, и в присланном
        // диаг-логе ему делать нечего. Длины и признака «отправлен» хватает для разбора.
        Log.i(TAG, "адрес доставки зарегистрирован на ядре (длина ${pushToken.length}, версия ${BuildConfig.VERSION_NAME})")
    }

    private suspend fun unregister(app: Context, pushToken: String) {
        val store = KeystoreSecureStore(app)
        val session = MayakSession(store, AwgKeyProvider(), AndroidHwidProvider(app, store))
        if (!session.hasToken()) return
        session.unregisterPush(backend(app, store), pushToken)
    }

    /** Ядро тем же путём, что и весь клиентский API: свой список доменов + заход МИМО туннеля. */
    private fun backend(app: Context, store: KeystoreSecureStore) = MayakBackend(
        HostProvider(MayakHostList.effective(app, store.get(MayakActivity.KEY_SERVER))),
        bypassTunnel = OutsideTunnel.opener(app),
    )

    private fun hasLogin(app: Context): Boolean {
        val store = KeystoreSecureStore(app)
        return MayakSession(store, AwgKeyProvider(), AndroidHwidProvider(app, store)).hasToken()
    }

    /** Забыть, что мы что-то отправляли (выход из аккаунта, снятие адреса). */
    private fun forget(app: Context) {
        prefs(app).edit().remove(K_SENT_TOKEN).remove(K_SENT_VERSION).apply()
    }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
