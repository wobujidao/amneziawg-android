// Экран «Mayak Networks» (бета MVP). Брендовый дизайн на XML-разметке (Material 3):
// вход — логотип-маяк + название + карточка с полями логин/пароль, кнопки «Войти», «Сканировать QR»,
// «Вставить рег-ссылку» (в ссылке зашиты адрес ядра + логин/пароль — юзеру ничего вводить не надо).
// Главный экран — список стран → подключение со сквозной пробой и авто-резервом (прямой → резерв).
// Тема следует системе (DayNight) или ручному выбору (MayakPrefs); язык — ru/be/kk/uz/en/de/fr.
package org.amnezia.awg.mayak

import android.Manifest
import android.animation.ObjectAnimator
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.provider.Settings
import android.view.HapticFeedbackConstants
import android.view.LayoutInflater
import android.view.View
import android.view.animation.AccelerateDecelerateInterpolator
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.widget.ImageViewCompat
import androidx.core.widget.doAfterTextChanged
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.google.android.material.button.MaterialButton
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import java.io.IOException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.amnezia.awg.BuildConfig
import org.amnezia.awg.R
import org.amnezia.awg.backend.GoBackend
import org.amnezia.awg.mayak.core.AccessDenial
import org.amnezia.awg.mayak.core.AppVersionInfo
import org.amnezia.awg.mayak.core.Direction
import org.amnezia.awg.mayak.core.Fallback
import org.amnezia.awg.mayak.core.FallbackDecision
import org.amnezia.awg.mayak.core.HostProvider
import org.amnezia.awg.mayak.core.LadderTelemetry
import org.amnezia.awg.mayak.core.Onboarding
import org.amnezia.awg.mayak.core.UpdateNudge
import org.amnezia.awg.mayak.core.MayakApiException
import org.amnezia.awg.mayak.core.MayakBackend
import org.amnezia.awg.mayak.core.MayakHosts
import org.amnezia.awg.mayak.core.NoReachableHostException
import org.amnezia.awg.mayak.core.OutdatedBuild
import org.amnezia.awg.mayak.core.accessDenial
import org.amnezia.awg.mayak.core.orderForAutoWithHistory
import org.amnezia.awg.mayak.core.outdatedBuild
import org.amnezia.awg.mayak.core.splitRecommended

class MayakActivity : AppCompatActivity() {

    private lateinit var store: KeystoreSecureStore
    private lateinit var session: MayakSession
    private lateinit var tunnel: GoTunnel
    private val probe = IpifyProbe()

    /**
     * Своя область видимости для проб выхода — НЕ дочерняя корутине коннекта.
     *
     * Нужна ровно для одного: чтобы ожидание пробы можно было бросить по таймауту, не дожидаясь
     * блокирующего резолва внутри неё (см. `awaitAtMost` в :core). Дочерняя задача такого не позволяет:
     * `withTimeoutOrNull` обязан дождаться завершения своих детей.
     */
    private val probeScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    /** Сработал ли уже сторож «трафик не идёт» в ТЕКУЩЕМ подключении (чтобы залить лог один раз). */
    private var noTrafficReported = false

    /** Пробовали ли уже вылечиться переподключением в ТЕКУЩЕМ подключении (ровно одна попытка). */
    private var selfHealTried = false
    // IPv6-проба: api6.ipify.org резолвится ТОЛЬКО в IPv6 → успешный 200 = реальный IPv6-egress через
    // туннель. Честный сигнал для значка «IPv6» (SPEC-0014): зажигаем по факту выхода, не по наличию ::/0.
    private val probe6 = IpifyProbe(url = "https://api6.ipify.org?format=json")

    private var backend: MayakBackend? = null
    private var pendingConnect: Direction? = null

    private lateinit var status: TextView
    private var dirsContainer: LinearLayout? = null

    // --- состояние главного экрана (Happ-стиль) ---
    private enum class ConnState { DISCONNECTED, CONNECTING, CONNECTED }
    private var connState = ConnState.DISCONNECTED
    private var isHomeShown = false // главный экран показан (для пересинхронизации состояния в onResume)
    private var connectJob: Job? = null // корутина текущего подключения — чтобы можно было ОТМЕНИТЬ тапом
    // Когда показали текущую надпись шага подключения (SystemClock). 0 = ещё ничего не показывали.
    private var statusShownAt = 0L
    // Когда под кнопкой появился текст ОШИБКИ. 0 = ошибки на экране нет. Нужен, чтобы вчерашняя
    // ошибка не встречала человека при следующем открытии приложения (аудит 2026-07-31, п. 17).
    private var errorShownAt = 0L
    // Тап по надписи после ПРОВАЛА подключения ведёт в диагностику (находка 2026-08-03: раньше
    // «Диагностика и помощь» была достижима, только если человек сам знал спуститься в самый низ
    // «Настроек» — на месте отказа ни слова, ни кнопки в её сторону не было). Живёт своей жизнью,
    // не через errorShownAt: «Подключение отменено» — тоже errorShownAt, но не отказ, помощь тут не нужна.
    private var errorHelpAvailable = false
    // Поколение подключения: растёт на КАЖДОМ подъёме и КАЖДОМ обрыве. Фоновые пробы запоминают своё
    // и молча выбрасывают результат, если он вернулся уже к другому подключению (диаг #64).
    private var connGeneration = 0
    private var ipv6ProbeJob: Job? = null
    // Кэш конфигов /connect по направлению живёт в MayakSession (процесс-скоупный, ПЕРЕЖИВАЕТ пересоздание
    // Activity) — предзагружается при выборе страны, берётся ОДНОРАЗОВО в момент коннекта (нет
    // переиспользования устаревшего lease; провал → след. коннект тянет свежий). Раньше это было поле
    // Activity → умирало при смене темы и /connect гонялся заново (баг: смена темы дёргала сеть).
    // preloadJob отменяет предыдущую предзагрузку при быстром переключении стран.
    private var preloadJob: Job? = null
    private var directions: List<Direction> = emptyList()
    private var selectedDir: Direction? = null
    private var connectedDir: Direction? = null // направление ЖИВОГО туннеля (для авто-переключения при смене страны)
    private val rowViews = mutableListOf<View>()
    private var timerJob: Job? = null
    private var sessionStartElapsed = 0L
    private var pingJob: Job? = null // периодический пинг сервера текущего подключения
    private var speedJob: Job? = null // периодический замер скорости передачи (если включён в настройках)

    // вьюхи круга/таймера (на главном экране)
    private var connectCircle: View? = null
    private var connectIcon: ImageView? = null
    private var connectGlow: View? = null
    private var timerView: TextView? = null
    private var ipView: TextView? = null
    private var ipv6Badge: TextView? = null
    private var fallbackBadge: TextView? = null // «Резерв» — подключены через запасной канал (SPEC-0039)
    private var pingView: TextView? = null
    private var speedView: TextView? = null
    private var pulseAnimator: ObjectAnimator? = null
    private var glowBreath: ObjectAnimator? = null
    private var rippleView: RippleView? = null
    private var networkBg: NetworkBackgroundView? = null

    // согласие на VPN → продолжаем отложенное подключение
    private val vpnPermission =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            val dir = pendingConnect
            pendingConnect = null
            if (result.resultCode == RESULT_OK && dir != null) {
                doConnect(dir)
            } else {
                renderState(ConnState.DISCONNECTED)
                setStatus(getString(R.string.mayak_err_no_vpn_perm))
            }
        }

    // POST_NOTIFICATIONS (API 33+) для уведомления «Подключено». Если выдали во время активного
    // коннекта — показываем уведомление сразу; отказ не критичен (просто не будет уведомления).
    private val notifPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted && tunnel.isUp()) MayakNotification.show(this, GoTunnel.connectedLabel, GoTunnel.connectedPingMs)
            // 🔔 Разрешение только что появилось — сразу сходить за ящиком. Первая сверка происходит
            // РАНЬШЕ этого диалога (человек вошёл, экран собрался, ящик проверен), и показать
            // сообщение тогда было нечем. Без этого толчка новичок увидел бы первое уведомление
            // в шторке только через час (столько ждёт следующая проверка на переднем плане) —
            // замерено на эмуляторе 12-08.
            if (granted) {
                lifecycleScope.launch {
                    val r = MayakMessages.sync(this@MayakActivity, MayakMessages.SyncTrigger.ALWAYS)
                    if (r.ok) updateMessagesBadge()
                }
                // Показывать стало можно — значит и будить нас есть смысл. До этого момента адрес
                // доставки не регистрировался вовсе (MayakPush.refresh): пуш, из которого нельзя
                // сделать уведомление, Google считает поводом понизить приоритет ВСЕМ нашим пушам.
                MayakPush.refresh(this)
            }
        }

    /**
     * Спросить разрешение на уведомления — НА ГЛАВНОМ ЭКРАНЕ, а не в момент подключения.
     *
     * Раньше вызывалось из connectTo(), то есть встык с системным согласием на VPN: на первом
     * подключении человек получал ДВА системных диалога подряд, второй поверх первого. Оба нужны и
     * оба системные, но перебивать ими один жест — это уже наша работа, а не Android'а. Теперь
     * спрашиваем один раз при показе главного (после входа), когда ничего не ждёт и отказ ничего не
     * ломает, — и на первом подключении остаётся ровно ОДИН перебив: согласие на VPN.
     *
     * Раз на процесс: пересоздание Activity (поворот экрана, смена темы) не должно переспрашивать.
     * Дальше ограничивает сам Android — после двух отказов диалог больше не показывается.
     */
    private fun maybeRequestNotifPermission() {
        if (notifAskedThisProcess) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            notifAskedThisProcess = true
            notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    // Разрешение «состояние телефона» УБРАНО целиком (решение владельца 2026-07-29 + аудит 07-31).
    // Оно и не работало: запрос телефона улетал поверх запроса уведомлений и терялся, а флаг «уже
    // спросили» ставился ДО показа диалога — то есть защёлкивался навсегда. В итоге в карточке Play
    // и в настройках приложение числилось с группой «Телефон», а диагностика не получала ничего.

    override fun onCreate(savedInstanceState: Bundle?) {
        MayakPrefs.applyTheme(this)
        super.onCreate(savedInstanceState)
        MayakSystemBars.apply(this) // контраст иконок статус-бара/навбара под тему (свет→тёмные иконки)
        store = KeystoreSecureStore(this)
        session = MayakSession(store, AwgKeyProvider(), AndroidHwidProvider(this, store))
        tunnel = GoTunnel(this)
        MayakUpdater.cleanup(this) // подчистить скачанный APK после обновления/отмены («убрать лишнее»)

        // Обновились ПОВЕРХ сборки другого контура — стереть чужие адреса и сессию до первого запроса.
        // Иначе прод-сборка молча продолжает работать с дев-ядром (находка владельца 07-08).
        val movedContour = MayakHostList.dropForeignContour(this, store, session)

        if (session.hasToken()) {
            backend = MayakBackend(hostProvider(), bypassTunnel = OutsideTunnel.opener(this@MayakActivity))
            showHome(); loadDirections()
            checkAppUpdate() // мягкий нудж, если вышла новая версия (Вариант А)
            refreshRuDirect() // OTA-подтяжка РФ-списка split-туннеля (в фоне, best-effort)
            refreshHosts()    // адреса ядра и кабинета из реестра доменов (в фоне, best-effort)
            maybeAutoEnableRuPreset() // авто-РФ-пресет при первом запуске (в фоне, best-effort)
        } else {
            showLogin()
            // Порог старых сборок проверяем и ЗДЕСЬ: сборку, которую мы отключили, человек чаще всего
            // и открывает на экране входа (вход в ней уже не работает). Мягкий нудж поверх формы не
            // показываем — сюда мы пришли только за порогом.
            checkAppUpdate(nudge = false)
            // Пришли сюда из-за отозванного входа (см. sessionExpired) — объясняем, а не молчим.
            if (intent?.getBooleanExtra(EXTRA_SESSION_EXPIRED, false) == true) {
                setStatus(getString(R.string.mayak_session_expired))
                Toast.makeText(this, R.string.mayak_session_expired, Toast.LENGTH_LONG).show()
            } else if (movedContour) {
                // Человек не выходил сам — объясняем, почему вдруг просим пароль.
                setStatus(getString(R.string.mayak_moved_contour))
                Toast.makeText(this, R.string.mayak_moved_contour, Toast.LENGTH_LONG).show()
            }
        }
    }

    /**
     * Освежить адреса ядра/кабинета из реестра доменов (ADR-0013, миграция 0089). Раз на процесс,
     * в фоне, best-effort. Раньше это делал ТОЛЬКО еженедельный телеметри-воркер — то есть свежий
     * резервный домен доезжал до устройства в худшем случае через неделю, а на телефонах с
     * прибитой фоновой активностью не доезжал вовсе. Запрос крошечный и идёт на тот же домен, что и
     * остальные вызовы, — отдельного следа для DPI не создаёт.
     */
    private fun refreshHosts() {
        if (hostsRefreshedThisProcess) return
        hostsRefreshedThisProcess = true
        val b = backend ?: return
        lifecycleScope.launch {
            // DoH-резолверы из ПРИНЯТОГО РАНЬШЕ delivery-документа (F-T8) — до сети, из кэша:
            // они нужны ровно тогда, когда сеть до ядра ещё не достучалась.
            runCatching { MayakDelivery.applyDoh(this@MayakActivity) }
            runCatching { MayakHostList.refresh(this@MayakActivity, b) }
        }
    }

    /** Синхрон пресетов split-туннеля с ядра (SPEC-0028): системные «РФ напрямую» + пользовательские.
     *  Раз на процесс, в фоне, best-effort (ошибка → молча остаётся кэш/зашитый ассет). НЕ во время
     *  коннекта (DPI палит домен рядом с хендшейком — см. MayakSession), а на старте/после логина.
     *  После синхрона обновляем селектор пресетов на главном (если показан). */
    private fun refreshRuDirect() {
        // Раз на процесс — И ещё раз, если сменился язык: имя системного набора приходит с сервера
        // и переводится там же, где названия стран (миграция 0134). Кэш на диске подписан языком,
        // поэтому «уже синхронизировали» проверяем по языку, а не флагом (дефект 0.5.2).
        val lang = MayakBackend.namesLanguageBucket()
        if (presetsSyncedLang == lang && MayakPresets.cachedLang(this) == lang) return
        val b = backend ?: return
        lifecycleScope.launch {
            runCatching { session.syncPresets(this@MayakActivity, b) }
                .onSuccess { presetsSyncedLang = lang } // только на успех: иначе следующий заход повторит
            MayakPresets.invalidate()
            runCatching { updatePresetSelector() }
        }
    }

    /**
     * Авто-включение РФ-пресета split-туннеля при первом запуске (прямой запрос владельца 2026-08-03):
     * человеку из РФ иначе банки и Госуслуги идут через туннель и не пускают его, а тумблер в
     * настройках он сам не найдёт. Включаем один раз и только когда это безопасно проверить:
     *  - ещё не пробовали (`MayakPrefs.ruAutoTried`) — иначе лезли бы в сеть на каждом запуске;
     *  - человек ещё не тронул тумблер пресета РУКАМИ (`MayakPrefs.presetUserDecided`) — иначе
     *    перебили бы его осознанный выбор своей догадкой;
     *  - не поднят НИКАКОЙ VPN — ни наш, ни чужой (`MayakNet.vpnActive`): под чужим VPN внешний IP
     *    чужой, страна определится неверно, а выключить чужой VPN Android не позволяет.
     *
     * Если VPN поднят — НИЧЕГО не делаем и `ruAutoTried` НЕ ставим: это перенос проверки на следующий
     * запуск, а не отказ от неё. Сеть — best-effort и в фоне, НЕ на пути коннекта (тот же принцип,
     * что и у refreshRuDirect: DPI палит домен рядом с хендшейком) — зовём отсюда же, на старте/
     * после входа. Ошибка/таймаут → тихо ничего, флаг тоже не ставим — попробуем ещё раз.
     */
    private fun maybeAutoEnableRuPreset() {
        if (ruAutoCheckedThisProcess) return
        if (MayakPrefs.ruAutoTried(this) || MayakPrefs.presetUserDecided(this)) return
        val b = backend ?: return
        if (MayakNet.vpnActive(this)) return // откладываем БЕЗ пометки — попробуем при следующем запуске
        ruAutoCheckedThisProcess = true
        lifecycleScope.launch {
            val check = b.egressCheck() ?: run {
                ruAutoCheckedThisProcess = false // сеть подвела — не считаем это «попыткой», повторим
                return@launch
            }
            MayakPrefs.setRuAutoTried(this@MayakActivity, true)
            if (check.country != "RU") return@launch
            if (MayakPrefs.activePresetId(this@MayakActivity) == 0L) {
                MayakPresets.cached(this@MayakActivity).firstOrNull { it.source == "system" }
                    ?.let { MayakPrefs.setActivePresetId(this@MayakActivity, it.id) }
            }
            MayakPrefs.setPresetEnabled(this@MayakActivity, true)
            runCatching { updatePresetSelector() }
            Toast.makeText(this@MayakActivity, R.string.mayak_ru_preset_auto_enabled, Toast.LENGTH_LONG).show()
        }
    }

    /** Самообновление (Вариант А): сверяем свою версию с /version.json на хосте; если вышла новее —
     *  мягкое окно со ссылкой на скачивание. Раз на запуск процесса (пересоздание Activity не дёргает);
     *  «Позже» для версии запоминаем (не долбим). Любая ошибка сети/файла — молча ничего.
     *
     *  Этим же запросом проверяется ПОРОГ старых сборок (`min_version_code`, см. :core/MinVersionGate).
     *  Один запрос на два решения намеренно: version.json — тот самый файл, где живёт порог, и второй
     *  поход за ним означал бы второй след для DPI и две расходящиеся картины «какая версия боевая».
     *
     *  @param nudge показывать ли МЯГКОЕ окно «вышла новая версия». На экране входа — нет: там мы
     *   пришли только за порогом, а предложение обновиться поверх формы входа человека не звало.
     */
    private fun checkAppUpdate(force: Boolean = false, nudge: Boolean = true) {
        // Авто-проверка (force=false) — раз на процесс, молча. По кнопке «Обновить» (force=true) проверяем
        // ВСЕГДА (минуя once-per-process и запомненное «Позже») и даём фидбек «последняя версия».
        if (!force) {
            if (updateCheckedThisProcess) return
            updateCheckedThisProcess = true
        }
        // Свой экземпляр, если поля ещё нет: порог обязан срабатывать и на экране входа — то есть БЕЗ
        // сессии и БЕЗ туннеля, раньше первого /connect. version.json отдаётся без токена.
        val b = backend ?: MayakBackend(hostProvider(), bypassTunnel = OutsideTunnel.opener(this))
        lifecycleScope.launch {
            val info = b.appVersion() ?: run {
                // Нет сети или файла — порога тоже нет: человек работает как раньше. Запереть его
                // из-за нашей недоступности было бы худшим из возможных исходов.
                if (force) Toast.makeText(this@MayakActivity, R.string.mayak_update_check_failed, Toast.LENGTH_SHORT).show()
                return@launch
            }
            // ПОРОГ СТАРЫХ СБОРОК — раньше мягкого нуджа и раньше запомненного «Позже»: эту сборку мы
            // отключили, и отложить такое человек не может (решение — в чистой функции :core).
            val verdict = outdatedBuild(
                versionCode = BuildConfig.VERSION_CODE,
                minVersionCode = info.minVersionCode,
                latestVersionCode = info.latestVersionCode,
                fromPlay = MayakUpdater.installedFromPlay(this@MayakActivity),
                apkUrl = info.apkUrl,
            )
            if (verdict != OutdatedBuild.NONE) {
                MayakOutdatedActivity.show(this@MayakActivity, verdict, info)
                return@launch
            }
            if (!nudge) return@launch
            if (info.latestVersionCode <= BuildConfig.VERSION_CODE || info.apkUrl.isBlank()) {
                if (force) Toast.makeText(this@MayakActivity, R.string.mayak_update_uptodate, Toast.LENGTH_SHORT).show()
                return@launch
            }
            // Ступенчатые напоминания (core.UpdateNudge): день 0 — обычное предложение, 14 и 21 —
            // повторные с более жёстким текстом, с 36-го дня — каждый холодный старт. Раньше одно
            // «Позже» выключало напоминание про эту версию навсегда, и между ним и жёстким порогом
            // min_version_code не было ничего.
            var step = UpdateNudge.STEP_DAYS.size - 1 // «проверить обновления» руками — самый прямой текст
            if (!force) {
                val (days, lastStep) = MayakPrefs.updateNudgeState(
                    this@MayakActivity, info.latestVersionCode, System.currentTimeMillis())
                step = UpdateNudge.stepToShow(days, lastStep)
                if (step == UpdateNudge.NONE) return@launch
            }
            // Установлено из Play — обновляет сам Play. Наш APK с сайта подписан ДРУГИМ ключом, и
            // установка такому человеку гарантированно падает на несовпадении подписи: он качает
            // файл, ждёт и получает отказ, который читается как поломка приложения. Поэтому здесь
            // не предлагаем скачивание вовсе: по кнопке «проверить» отправляем в Play, а сами по
            // себе молчим — Play уже следит за версией.
            if (MayakUpdater.installedFromPlay(this@MayakActivity)) {
                if (force) showPlayUpdateDialog()
                return@launch
            }
            showUpdateDialog(info, step)
        }
    }

    /** Обновление для установки из Play: ведём в Play вместо скачивания APK с сайта. */
    private fun showPlayUpdateDialog() {
        AlertDialog.Builder(this)
            .setTitle(R.string.mayak_update_title)
            .setMessage(R.string.mayak_update_via_play)
            .setPositiveButton(R.string.mayak_update_open_play) { _, _ -> MayakUpdater.openPlay(this) }
            .setNegativeButton(R.string.mayak_update_later, null)
            .show()
    }

    /**
     * Окно «вышло обновление». Ступень (core.UpdateNudge) меняет ТОЛЬКО тон текста, а не поведение:
     * кнопки те же, отказ всегда возможен. Настойчивость — в словах, а не в отнятии выхода.
     */
    private fun showUpdateDialog(info: AppVersionInfo, step: Int) {
        val name = info.latestVersionName.ifBlank { info.latestVersionCode.toString() }
        val lead = when (step) {
            0, 1 -> getString(R.string.mayak_update_msg, name)
            2 -> getString(R.string.mayak_update_msg_aging, name)
            else -> getString(R.string.mayak_update_msg_old, name)
        }
        val msg = buildString {
            append(lead)
            if (info.changelog.isNotBlank()) { append("\n\n"); append(info.changelog) }
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.mayak_update_title)
            .setMessage(msg)
            .setPositiveButton(R.string.mayak_update_now) { _, _ -> startInAppUpdate(info) }
            .setNegativeButton(R.string.mayak_update_later) { _, _ ->
                // Записываем ПОКАЗАННУЮ ступень, а не «отказался навсегда»: следующая ступень придёт
                // сама на своём сроке. Старый ключ update_dismissed_code больше не растим.
                MayakPrefs.setUpdateNudgeStep(this, step)
            }
            .setOnDismissListener { MayakPrefs.setUpdateNudgeStep(this, step) }
            .show()
    }

    /** Вариант Б: качаем APK ВНУТРИ приложения с прогрессом, проверяем подпись, запускаем установку.
     *  Сам путь живёт в MayakUpdater.runUpdate — его же зовёт экран отрезанной сборки, и второй копии
     *  проверки подписи у нас быть не должно. */
    private fun startInAppUpdate(info: AppVersionInfo) =
        MayakUpdater.runUpdate(this, info.apkUrl, backend?.knownBases ?: emptyList())

    override fun onStart() {
        super.onStart()
        // Блокировка приложения (запрос владельца 2026-07-06): при появлении на переднем плане (cold-start И
        // возврат из фона дольше GRACE) требуем биометрию/системный PIN, если включено в настройках. Экран
        // блокировки — отдельная MayakLockActivity ПОВЕРХ (контент главной скрыт под ней). Fail-open: выкл или
        // нечем проверить → shouldLock=false. VPN/туннель НЕ трогаем — это чисто UI-гейт.
        if (MayakLock.shouldLock(this)) {
            MayakLock.lockShowing = true
            startActivity(Intent(this, MayakLockActivity::class.java))
            @Suppress("DEPRECATION")
            overridePendingTransition(0, 0) // без анимации — чтобы контент главной не мелькнул
        }
    }

    override fun onResume() {
        super.onResume()
        networkBg?.startAnimation() // фон оживает, только пока экран виден
        // при возврате на «Подключение…» возобновляем волны и НЕ трогаем состояние (оно переходное)
        if (connState == ConnState.CONNECTING) { rippleView?.startWaves(); return }
        // Пересинхронизируемся с фактическим состоянием НАШЕГО туннеля: он мог измениться, пока
        // приложение было свёрнуто (сам отвалился, или юзер включил VPN другим приложением → наш
        // VpnService погашен). Так экран всегда честно отражает реальность на возврате.
        if (isHomeShown) {
            syncConnStateFromTunnel()
            // Пинг сервера — только на переднем плане (см. onPause). Возобновляем при возврате, если подключены.
            if (connState == ConnState.CONNECTED) startPing()
            // Вернулись с экрана «Сообщения» — там могли что-то прочитать: кружок обязан это отразить,
            // иначе он висит с прежним числом и читается как «прочтение не сработало».
            updateMessagesBadge()
            // 🔴 И ЗАГЛЯНУТЬ В ЯЩИК. Раньше проверка стояла только в сборке главного экрана, то есть
            // при СОЗДАНИИ Activity: человек, который открыл приложение из недавних (обычный случай —
            // Activity жива), на сервер не ходил вовсе. Ровно так 13-08 сообщение и не доехало.
            syncMessages()
        }
        // Пока экран открыт, надпись под кнопкой ходит за процесс-скоупным сторожем живости: сеть
        // может пропасть между тактами пинга, и человек не должен узнавать об этом позже шторки.
        MayakLiveness.onChange = { runOnUiThread { if (connState == ConnState.CONNECTED) setStatus(connectedStatusText()) } }
        clearStaleError()
    }

    override fun onPause() {
        networkBg?.stopAnimation() // экономим, когда экран не на переднем плане
        rippleView?.stopWaves()
        // ПИНГ сервера — ТОЛЬКО пока приложение открыто. В фоне/свёрнутым не долбим ноду каждые 5с (правка
        // владельца 2026-07-06): при масштабе тысячи фоновых пингов = лишняя нагрузка на VPS и канал.
        // Туннель/таймер/уведомление это не трогает — рвётся лишь UI-индикатор пинга, он и не виден в фоне.
        stopPing()
        MayakLiveness.onChange = null // экрана нет — обновлять надпись некому (и держать ссылку на Activity незачем)
        super.onPause()
    }

    override fun onStop() {
        // Отметить уход в фон — но НЕ когда сверху наш же экран блокировки (это не «фон»: иначе долгая
        // аутентификация >GRACE тут же пере-заперла бы после успешной разблокировки → бесконечный цикл).
        if (!MayakLock.lockShowing) MayakLock.noteBackground()
        super.onStop()
    }

    /**
     * Список адресов ядра. По умолчанию — публичный домен + IP-фолбэк (домен первым, при
     * недоступности :core сам переключится на IP). Рег-ссылка/QR могут сохранить свой адрес
     * (KEY_SERVER) — тогда используем его (а IP-фолбэк добавляем как страховку).
     */
    private fun hostProvider(): HostProvider =
        HostProvider(MayakHostList.effective(this, store.get(KEY_SERVER)))

    // --- экран входа: логотип + название + карточка логин/пароль + QR + рег-ссылка ---

    private fun showLogin() {
        isHomeShown = false
        setContentView(R.layout.activity_mayak_login)
        MayakSystemBars.padForBars(findViewById(R.id.mayak_login_content))
        dirsContainer = null
        status = findViewById(R.id.mayak_status)

        val emailField = findViewById<TextInputEditText>(R.id.mayak_login)
        val passField = findViewById<TextInputEditText>(R.id.mayak_password)
        val loginLayout = findViewById<TextInputLayout>(R.id.mayak_login_layout)
        val passwordLayout = findViewById<TextInputLayout>(R.id.mayak_password_layout)

        setupThemeButton()
        findViewById<MaterialButton>(R.id.mayak_language_button).setOnClickListener { MayakLanguages.showDialog(this) }

        // Ошибку показываем У ПОЛЯ, а не серой строкой под карточкой: с открытой клавиатурой низ экрана
        // не виден, и нажатие «Войти» выглядело как «ничего не произошло» (замечание владельца 07-26).
        // Пользователь начал править — ошибку убираем, иначе она висит и спорит с тем, что он вводит.
        emailField.doAfterTextChanged { loginLayout.error = null }
        passField.doAfterTextChanged { passwordLayout.error = null }

        findViewById<TextInputEditText>(R.id.mayak_totp)?.doAfterTextChanged {
            findViewById<TextInputLayout>(R.id.mayak_totp_layout)?.error = null
        }
        findViewById<MaterialButton>(R.id.mayak_sign_in).setOnClickListener {
            val email = emailField.text?.toString()?.trim().orEmpty()
            val pass = passField.text?.toString().orEmpty()
            loginLayout.error = null
            passwordLayout.error = null
            if (email.isBlank() || pass.isBlank()) {
                val target = if (email.isBlank()) loginLayout else passwordLayout
                target.error = getString(R.string.mayak_err_fill_login)
                shake(target)
                return@setOnClickListener
            }
            // Поле кода уже показано, но пустое — не шлём заведомо тот же запрос: ядро ответит
            // «нужен код», экран перерисуется в то же состояние, и кнопка будет выглядеть мёртвой.
            val totpRow = findViewById<TextInputLayout>(R.id.mayak_totp_layout)
            if (totpRow != null && totpRow.visibility == View.VISIBLE && visibleTotpCode().isEmpty()) {
                totpRow.error = getString(R.string.mayak_err_totp_empty)
                shake(totpRow)
                return@setOnClickListener
            }
            // Код 2FA отправляем, только если поле уже показано (его раскрывает ответ ядра totp_required).
            doSignIn(email, pass, totpCode = visibleTotpCode())
        }
        findViewById<MaterialButton>(R.id.mayak_forgot_password).setOnClickListener {
            showForgotPasswordDialog(emailField.text?.toString()?.trim().orEmpty())
        }
        // Регистрация — СВОИМ экраном (SPEC-0048). Раньше кнопка открывала браузер: человек заводил
        // аккаунт в вебе и возвращался вводить номер и пароль заново, теряя первые минуты знакомства.
        // Дорога в браузер осталась ВНУТРИ того экрана — на случай, если проверка «вы человек» не
        // проходит (тупика быть не должно).
        findViewById<MaterialButton>(R.id.mayak_register).setOnClickListener {
            MayakRegisterActivity.open(this)
        }
        // Пришли с экрана регистрации, где учётка создалась, а сессию сервер не выдал: номер уже
        // известен — подставляем, чтобы человек не переписывал его руками с прошлого экрана.
        intent?.getStringExtra(EXTRA_PREFILL_LOGIN)?.takeIf { it.isNotBlank() }?.let { login ->
            emailField.setText(login)
            passField.requestFocus()
        }
    }

    // Разбор регистрационной ссылки mayak://reg?… жил здесь и вызывался из QR-сканера и диалога
    // «Вставить ссылку». Оба входа убраны 2026-08-01 (аудит, п. 10): таких ссылок никто не выдаёт.
    // Разбор со всей защитой (opaque-URI не роняет приложение, server только валидный https) — в
    // истории git и частично в core/RegLink; возвращать его надо deep-link'ом, а не кнопкой.

    /** «Забыли пароль?» шаг 1: спросить email → POST /forgot (код на почту) → шаг 2 (ввод кода+нового пароля).
     *
     *  🔴 Сюда вводят НОМЕР АККАУНТА — потому что в соседнее поле входа его вводить можно, и у части
     *  людей (анонимная регистрация, подарочные учётки) почты нет вовсе. Раньше такой ввод уходил на
     *  сервер и возвращался бодрым «код отправлен» — человек шёл ждать письмо, которого не будет
     *  никогда. Теперь не-почту заворачиваем ЗДЕСЬ и объясняем, что делать вместо этого. */
    private fun showForgotPasswordDialog(prefillEmail: String) {
        val input = TextInputEditText(this).apply {
            hint = getString(R.string.mayak_email_hint)
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS
            // Номер в поле входа не подставляем: он тут не работает, а подставленное значение
            // читается как «этого достаточно, жми кнопку».
            if (prefillEmail.contains('@')) setText(prefillEmail)
        }
        val wrapper = TextInputLayout(this).apply { setPadding(dp(24), dp(8), dp(24), 0); addView(input) }
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.mayak_forgot_title))
            .setMessage(getString(R.string.mayak_forgot_msg))
            .setView(wrapper)
            .setPositiveButton(getString(R.string.mayak_forgot_send)) { _, _ ->
                val email = input.text?.toString()?.trim().orEmpty()
                if (email.isBlank()) { setStatus(getString(R.string.mayak_err_fill_login)); return@setPositiveButton }
                // Не почта (номер аккаунта, логин бота, опечатка) — код слать некуда. Говорим сразу
                // и отдельным окном: строку статуса под карточкой закрывает клавиатура, а это тупик,
                // который человек обязан прочитать целиком.
                if (!email.contains('@')) { showForgotNoEmail(); return@setPositiveButton }
                backend = MayakBackend(hostProvider(), bypassTunnel = OutsideTunnel.opener(this@MayakActivity))
                setStatus(getString(R.string.mayak_forgot_sending))
                lifecycleScope.launch {
                    try {
                        backend!!.forgotPassword(email)
                        setStatus(getString(R.string.mayak_forgot_sent))
                        showResetPasswordDialog(email)
                    } catch (e: MayakApiException) {
                        // Ядро с 12-08 честно отказывает по номеру (no_email_recovery). Сюда попасть
                        // можно только с адресом, который наш фильтр счёл почтой, а ядро — номером.
                        if (e.code == "no_email_recovery") showForgotNoEmail() else setStatus(humanError(e))
                    } catch (e: Exception) { setStatus(humanError(e)) }
                }
            }
            .setNegativeButton(getString(R.string.mayak_cancel), null)
            .show()
    }

    /**
     * Тупик «сбрасывать нечем»: в поле сброса не почта. Объясняем, что делать вместо ожидания
     * письма — войти номером и паролем и привязать почту в кабинете, либо написать в поддержку.
     * Кнопка ведёт в кабинет: одно нажатие вместо пересказа адреса.
     */
    private fun showForgotNoEmail() {
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.mayak_forgot_title))
            .setMessage(getString(R.string.mayak_forgot_no_email))
            .setPositiveButton(getString(R.string.mayak_open_cabinet)) { _, _ -> openUrl(MayakHostList.cabinetUrl(this)) }
            .setNegativeButton(getString(R.string.mayak_cancel), null)
            .show()
    }

    /** «Забыли пароль?» шаг 2: код из письма + новый пароль → POST /reset → назад ко входу. */
    private fun showResetPasswordDialog(email: String) {
        val codeInput = TextInputEditText(this).apply {
            hint = getString(R.string.mayak_reset_code_hint)
            inputType = android.text.InputType.TYPE_CLASS_TEXT
        }
        val passInput = TextInputEditText(this).apply {
            hint = getString(R.string.mayak_reset_newpass_hint)
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(8), dp(24), 0)
            addView(TextInputLayout(this@MayakActivity).apply { addView(codeInput) })
            addView(TextInputLayout(this@MayakActivity).apply { addView(passInput) })
        }
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.mayak_reset_title))
            .setMessage(getString(R.string.mayak_reset_msg))
            .setView(box)
            .setPositiveButton(getString(R.string.mayak_reset_do)) { _, _ ->
                val code = codeInput.text?.toString()?.trim().orEmpty()
                val pass = passInput.text?.toString().orEmpty()
                if (code.isBlank() || pass.isBlank()) { setStatus(getString(R.string.mayak_err_fill_login)); return@setPositiveButton }
                backend = MayakBackend(hostProvider(), bypassTunnel = OutsideTunnel.opener(this@MayakActivity))
                setStatus(getString(R.string.mayak_reset_doing))
                lifecycleScope.launch {
                    try {
                        backend!!.resetPassword(email, code, pass)
                        setStatus(getString(R.string.mayak_reset_done))
                        findViewById<TextInputEditText>(R.id.mayak_login)?.setText(email)
                    } catch (e: MayakApiException) {
                        // Ядро возвращает разные code для "код неверный/просрочен" (bad_code) и
                        // "пароль слишком простой" (weak_password) — раньше оба 400 схлопывались в
                        // одну строку "Неверный/просроченный код или слишком слабый пароль.", и
                        // человек с верным кодом, но слабым паролем, начинал перепроверять письмо
                        // вместо пароля. Ветвимся по e.code, как везде в этом файле (humanError).
                        setStatus(
                            when {
                                e.code == "bad_code" -> getString(R.string.mayak_reset_bad_code)
                                e.code == "weak_password" -> getString(R.string.mayak_reset_weak_password)
                                e.status == 400 -> getString(R.string.mayak_reset_bad)
                                else -> humanError(e)
                            }
                        )
                    } catch (e: Exception) { setStatus(humanError(e)) }
                }
            }
            .setNegativeButton(getString(R.string.mayak_cancel), null)
            .show()
    }

    /**
     * Кнопка-переключатель темы в шапке (есть и на входе, и на главном).
     * Иконка отражает текущий режим (солнце/луна/авто); тап — цикл
     * Системная → Светлая → Тёмная → … с пересозданием активити.
     */
    private fun setupThemeButton() {
        val btn = findViewById<MaterialButton>(R.id.mayak_theme_button) ?: return
        btn.setIconResource(MayakPrefs.iconFor(MayakPrefs.themeMode(this)))
        btn.setOnClickListener {
            val next = MayakPrefs.nextMode(MayakPrefs.themeMode(this))
            Toast.makeText(
                this,
                "${getString(R.string.mayak_theme)}: ${getString(MayakPrefs.labelFor(next))}",
                Toast.LENGTH_SHORT
            ).show()
            MayakPrefs.setThemeMode(this, next) // пересоздаст активити → иконка обновится при пересборке
            recreate()
        }
    }

    /**
     * Сессия закончилась: 401 на ручке, куда мы ходили С токеном. Значит вход отозван — обычно
     * потому, что человек сменил пароль в кабинете (сброс гасит все сессии в той же транзакции).
     *
     * Раньше это показывалось как «Ошибка ядра (401): требуется авторизация» — строка, из которой
     * человеку непонятно ни что случилось, ни что делать. Приложение при этом выглядело как вошедшее,
     * но не работало ничего, а кнопки «войти заново» на экране нет: выход спрятан в самом низу
     * настроек. То есть каждый, кто сбросил пароль на сайте, попадал в тупик (разбор 2026-07-27).
     *
     * Гасим туннель, чистим сессию и возвращаем на вход с человеческим объяснением.
     *
     * ПЕРЕЗАПУСКАЕМ точку входа, а не просто рисуем экран логина: часть состояния живёт в процессе
     * (список направлений, кэш конфигов, флаги «уже загружали»). Показ экрана логина поверх живого
     * процесса оставлял их от прошлого пользователя, и после повторного входа список стран не
     * перерисовывался — экран навсегда застревал на «Загрузка стран…». Тот же приём, что у обычного
     * выхода из настроек (поймано живым проходом 2026-07-27).
     */
    private fun sessionExpired() {
        if (sessionExpiredHandled) return // 401 может прилететь из нескольких запросов сразу
        sessionExpiredHandled = true
        lifecycleScope.launch {
            // Гасим ИДУЩЕЕ подключение раньше туннеля: иначе живой bringUpPath поднимет его обратно
            // уже после нашего down(), и туннель останется висеть над экраном входа — выключить его
            // оттуда нечем, кнопки нет (найдено ревью 2026-07-27).
            connectJob?.cancel()
            connectJob = null
            runCatching { tunnel.down() }
            session.logout()
            MayakPresets.clear(this@MayakActivity) // настройки прошлого аккаунта не наследуем
            val intent = Intent(this@MayakActivity, MayakActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                .putExtra(EXTRA_SESSION_EXPIRED, true)
            startActivity(intent)
            finish()
        }
    }

    /** Код 2FA из поля — только если поле показано. Скрытое поле кода считаем незаполненным. */
    private fun visibleTotpCode(): String {
        val row = findViewById<TextInputLayout>(R.id.mayak_totp_layout) ?: return ""
        if (row.visibility != View.VISIBLE) return ""
        return findViewById<TextInputEditText>(R.id.mayak_totp)?.text?.toString()?.trim().orEmpty()
    }

    /**
     * Вход по email. serverOverride (из рег-ссылки) сохраняем как приоритетный адрес ядра.
     *
     * Про 2FA. Ветвимся по МАШИННОМУ признаку из тела ответа, а не по HTTP-коду: 401 может значить и
     * «неверный пароль», и «пароль верен, нужен код». Пока их не различали, включивший 2FA в кабинете
     * видел в приложении «Неверный email или пароль» — ложь, которая уводила его сбрасывать
     * заведомо верный пароль (разбор 2026-07-27).
     */
    private fun doSignIn(
        email: String,
        password: String,
        serverOverride: String? = null,
        totpCode: String = "",
    ) {
        if (serverOverride != null) store.put(KEY_SERVER, serverOverride)
        // Сети нет — не заставляем человека ждать круг таймаутов (5 с на домен × 2 домена × 2 захода),
        // чтобы в конце сказать то, что видно сразу. Тот же приём, что в doConnect.
        if (!MayakNet.hasNetwork(this)) {
            showLoginError(getString(R.string.mayak_status_no_network), blamePassword = false)
            return
        }
        backend = MayakBackend(hostProvider(), bypassTunnel = OutsideTunnel.opener(this@MayakActivity))
        setStatus(getString(R.string.mayak_status_signing_in))
        lifecycleScope.launch {
            try {
                session.login(backend!!, email, password, totpCode)
                // Новый аккаунт в ЖИВОМ процессе: сбрасываем флаги «уже сделали за этот процесс»,
                // иначе вошедший вторым донашивает чужое — список РФ-приложений не перечитывается,
                // а первое подключение идёт без предзагрузки (запрос к api рядом с хендшейком, чего
                // специально избегаем). Перезапуск Activity эти флаги НЕ сбрасывает: они статические.
                sessionExpiredHandled = false // новый вход — следующий отзыв снова должен сработать
                presetsSyncedLang = null
                ruAutoCheckedThisProcess = false
                homeWarmedThisProcess = false
                hideTotpField()
                showHome(); loadDirections(forceRefresh = true)
                refreshRuDirect() // OTA-подтяжка РФ-списка split-туннеля после входа
                maybeAutoEnableRuPreset() // авто-РФ-пресет при первом запуске (в фоне, best-effort)
            } catch (e: MayakApiException) {
                when {
                    // Сначала машинный признак: под 403 живут ДВА разных случая (email не подтверждён
                    // и аккаунт заблокирован), и предлагать заблокированному «подтвердить почту» —
                    // отправлять его чинить не то.
                    e.code == "account_blocked" -> showAccountBlocked(email)
                    // Ветвимся по КОДУ, а не по статусу: под 403 у ядра живёт не только
                    // «подтвердите почту», и звать к подтверждению почты того, у кого её нет,
                    // — отправлять чинить не то. Незнакомый 403 уйдёт в общую ветку и покажет
                    // текст ядра как есть.
                    e.code == "email_not_verified" -> showEmailNotVerified()
                    e.code == "totp_required" -> askTotpCode()
                    e.code == "totp_invalid" -> showTotpError()
                    e.status == 401 -> showLoginError(getString(R.string.mayak_err_bad_creds))
                    else -> showLoginError(humanError(e))
                }
            } catch (e: Exception) { showLoginError(humanError(e), blamePassword = !isNetworkFailure(e)) }
        }
    }

    /** Пароль принят, не хватает кода: раскрываем поле и ставим в него фокус. Про пароль не ругаемся. */
    private fun askTotpCode() = runOnUiThread {
        val row = findViewById<TextInputLayout>(R.id.mayak_totp_layout) ?: return@runOnUiThread
        row.visibility = View.VISIBLE
        row.error = null
        findViewById<TextInputEditText>(R.id.mayak_totp)?.requestFocus()
        // Toast'ом не мельтешим (правка владельца про промежуточные попапы): объяснение стоит подписью
        // под самим полем, где его видно всегда, а строку статуса чистим от «Вхожу…».
        setStatus("")
    }

    /** Код отклонён: чистим поле (иначе человек жмёт «Войти» с тем же протухшим кодом) и говорим прямо. */
    private fun showTotpError() = runOnUiThread {
        val row = findViewById<TextInputLayout>(R.id.mayak_totp_layout) ?: return@runOnUiThread
        row.visibility = View.VISIBLE
        findViewById<TextInputEditText>(R.id.mayak_totp)?.setText("")
        row.error = getString(R.string.mayak_err_totp_invalid)
        shake(row)
        setStatus("")
    }

    /** После успешного входа поле кода не должно остаться на экране (например, при повторном выходе). */
    private fun hideTotpField() = runOnUiThread {
        findViewById<TextInputLayout>(R.id.mayak_totp_layout)?.let {
            it.error = null
            it.visibility = View.GONE
        }
        findViewById<TextInputEditText>(R.id.mayak_totp)?.setText("")
    }

    /** Ошибка входа: красная подпись под полем пароля + короткая встряска. Раньше текст уходил в серую
     *  строку под карточкой — на телефоне её закрывает клавиатура, и человек не понимал, что произошло.
     *  Строку статуса тоже обновляем: она остаётся для тех, кто смотрит на большой экран без клавиатуры. */
    private fun showLoginError(text: String, blamePassword: Boolean = true) = runOnUiThread {
        val passwordLayout = findViewById<TextInputLayout>(R.id.mayak_password_layout)
        // Не про пароль (нет сети, сервер не отвечает) — под полем НЕ пишем и красным его не красим:
        // красная подпись у «Пароля» читается как «пароль неверный», и человек идёт его сбрасывать
        // вместо того, чтобы включить интернет.
        if (!blamePassword) {
            passwordLayout?.error = null
            setStatus(text)
            // Встряхиваем саму строку: без движения серая подпись внизу экрана легко проходит мимо
            // взгляда, а до этого отказ всегда «дёргался» (полем пароля) и его нельзя было не заметить.
            if (::status.isInitialized) shake(status)
            return@runOnUiThread
        }
        if (passwordLayout != null) {
            passwordLayout.error = text
            shake(passwordLayout)
            setStatus("")
        } else {
            setStatus(text)
        }
    }

    /** Короткая встряска элемента: движение читается боковым зрением быстрее любого текста. */
    private fun shake(view: View) {
        ObjectAnimator.ofFloat(view, View.TRANSLATION_X, 0f, -18f, 14f, -8f, 5f, 0f).apply {
            duration = 320
            start()
        }
    }

    /** 403 email_not_verified: понятное сообщение + предложение открыть кабинет для подтверждения. */
    /**
     * Тарифа у аккаунта нет (402 `no_plan`). ОТДЕЛЬНО от «доступ закончился»: продлевать нечего, и
     * совет продлить отправляет человека искать кнопку, которой для него не существует. Слова и
     * кнопка — как в кабинете: «доступ ещё не открыт» → выбрать тариф.
     */
    private fun showNoPlan() = runOnUiThread {
        connState = ConnState.DISCONNECTED
        connectedDir = null
        renderState(ConnState.DISCONNECTED)
        setStatus(getString(R.string.mayak_status_no_plan))
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.mayak_status_no_plan))
            .setMessage(getString(R.string.mayak_no_plan_msg))
            .setPositiveButton(getString(R.string.mayak_choose_plan)) { _, _ -> openUrl(MayakHostList.cabinetUrl(this)) }
            .setNegativeButton(getString(R.string.mayak_cancel), null)
            .show()
    }

    /**
     * Конец срока доступа (402 `no_subscription` либо 402 без признака). Отдельно от общей ошибки:
     * это не сбой, чинить его в приложении нечем, и диаг-лог на него заливать незачем — человеку
     * нужен понятный текст и вход в кабинет, где виден статус аккаунта.
     */
    private fun showAccessExpired() = runOnUiThread {
        connState = ConnState.DISCONNECTED
        connectedDir = null
        renderState(ConnState.DISCONNECTED)
        setStatus(getString(R.string.mayak_status_access_expired))
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.mayak_status_access_expired))
            .setMessage(getString(R.string.mayak_access_expired_msg))
            .setPositiveButton(getString(R.string.mayak_open_cabinet)) { _, _ -> openUrl(MayakHostList.cabinetUrl(this)) }
            .setNegativeButton(getString(R.string.mayak_cancel), null)
            .show()
    }

    /**
     * Все места под устройства заняты (409 при регистрации устройства). Частый бытовой случай: сменил
     * телефон или переустановил приложение. Показываем, что делать (освободить место в кабинете), а не
     * код ответа.
     */
    private fun showDeviceLimit() = runOnUiThread {
        connState = ConnState.DISCONNECTED
        connectedDir = null
        renderState(ConnState.DISCONNECTED)
        setStatus(getString(R.string.mayak_status_device_limit))
        // Кнопка ведёт в СВОЙ список устройств, а не в кабинет: кабинет — это внешний браузер с
        // отдельным входом, и открывать его пришлось бы ровно тогда, когда подключения нет (а у части
        // людей и сайт не открывается). Кнопки «Открыть кабинет» тут больше нет намеренно: третья
        // кнопка в диалоге встаёт столбиком и разводит «Отмену» между двумя действиями, а освободить
        // место теперь можно здесь же. Ссылка на кабинет осталась в Настройках.
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.mayak_status_device_limit))
            .setMessage(getString(R.string.mayak_device_limit_msg))
            .setPositiveButton(getString(R.string.mayak_settings_devices)) { _, _ -> MayakDevices.show(this) }
            .setNegativeButton(getString(R.string.mayak_cancel), null)
            .show()
    }

    /**
     * Аккаунт заблокирован (403 `account_blocked`). Единственное осмысленное действие человека —
     * написать нам, и до 08-08 адрес поддержки жил ТОЛЬКО внутри текста этой ошибки: его надо было
     * разглядеть в серой строке под формой, запомнить и вручную набрать в почте. Даём кнопку.
     */
    private fun showAccountBlocked(email: String) = runOnUiThread {
        val text = getString(R.string.mayak_err_account_blocked, MayakSupport.email)
        // blamePassword=false: пароль тут ни при чём, а красная подпись под полем «Пароль» читается
        // как «пароль неверный» и отправляет человека его сбрасывать (та же ошибка, что была с 2FA).
        showLoginError(text, blamePassword = false)
        AlertDialog.Builder(this)
            .setMessage(text)
            .setPositiveButton(getString(R.string.mayak_support_write)) { _, _ ->
                MayakSupport.writeToSupport(this, email)
            }
            .setNegativeButton(getString(R.string.mayak_cancel), null)
            .show()
    }

    private fun showEmailNotVerified() = runOnUiThread {
        setStatus(getString(R.string.mayak_err_email_not_verified))
        AlertDialog.Builder(this)
            .setMessage(getString(R.string.mayak_err_email_not_verified))
            .setPositiveButton(getString(R.string.mayak_open_cabinet)) { _, _ -> openUrl(MayakHostList.cabinetUrl(this)) }
            .setNegativeButton(getString(R.string.mayak_cancel), null)
            .show()
    }

    /** Открыть URL во внешнем браузере (кабинет/политика/условия). */
    private fun openUrl(url: String) {
        runCatching {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        }.onFailure { setStatus(getString(R.string.mayak_err_bad_link)) }
    }

    // --- Пресеты split-туннеля (SPEC-0028): селектор на главном + редактор ---
    private var presetBar: View? = null
    private var presetNameBtn: com.google.android.material.button.MaterialButton? = null
    private var presetSwitch: com.google.android.material.materialswitch.MaterialSwitch? = null
    private var presetHint: TextView? = null // короткая подпись под полоской (находка 03-08-2026)
    private var editingPresetId: Long = 0L // id правимого пресета (0 = создаём новый/форк)
    // Программно синхронизируем тумблер с сохранённым состоянием (updatePresetSelector) — на время
    // этого присваивания слушатель ниже должен молчать, иначе синхронизация UI выглядит как то, что
    // человек САМ тронул тумблер (presetUserDecided), и авто-включение РФ-пресета (2026-08-03)
    // потеряло бы право когда-либо его включить.
    private var suppressPresetSwitchListener = false

    private val presetEditorLauncher =
        registerForActivityResult(androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult()) { res ->
            if (res.resultCode != RESULT_OK) return@registerForActivityResult
            val data = res.data ?: return@registerForActivityResult
            // Редактор вернул запрос на удаление своего пресета.
            if (data.getBooleanExtra(MayakPresetEditorActivity.EXTRA_DELETE, false)) {
                val delId = data.getLongExtra(MayakPresetEditorActivity.EXTRA_ID, 0L)
                if (delId > 0L) deletePresetById(delId)
                return@registerForActivityResult
            }
            val name = data.getStringExtra(MayakPresetEditorActivity.EXTRA_NAME)?.takeIf { it.isNotBlank() } ?: return@registerForActivityResult
            val mode = data.getStringExtra(MayakPresetEditorActivity.EXTRA_MODE) ?: MayakPresetEditorActivity.MODE_EXCLUDE
            val apps = data.getStringArrayListExtra(MayakPresetEditorActivity.EXTRA_APPS) ?: arrayListOf()
            savePreset(editingPresetId, name, mode, apps)
        }

    private fun setupPresetSelector() {
        presetBar = findViewById(R.id.mayak_preset_bar)
        presetNameBtn = findViewById(R.id.mayak_preset_name)
        presetSwitch = findViewById(R.id.mayak_preset_switch)
        presetHint = findViewById(R.id.mayak_preset_hint)
        presetNameBtn?.setOnClickListener { showPresetChooser() }
        presetNameBtn?.setOnLongClickListener { confirmDeleteActivePreset(); true }
        presetSwitch?.setOnCheckedChangeListener { _, checked ->
            if (suppressPresetSwitchListener) return@setOnCheckedChangeListener // это МЫ синхронизируем UI, не человек
            MayakPrefs.setPresetEnabled(this, checked)
            // Человек САМ тронул тумблер — авто-включение РФ-пресета больше не имеет права его трогать.
            MayakPrefs.setPresetUserDecided(this, true)
            // применится при следующем подключении; текущий туннель не рвём молча.
            if (::status.isInitialized) { /* без тоста-спама */ }
        }
        updatePresetSelector()
    }

    /** Обновить селектор пресета: видимость (настройка), имя активного, состояние тумблера. */
    private fun updatePresetSelector() {
        val bar = presetBar ?: return
        if (!MayakPrefs.showPresetsOnHome(this)) {
            bar.visibility = View.GONE
            presetHint?.visibility = View.GONE
            return
        }
        bar.visibility = View.VISIBLE
        presetHint?.visibility = View.VISIBLE
        val active = MayakPresets.activePreset(this)
        presetNameBtn?.text = active?.name ?: getString(R.string.app_name)
        suppressPresetSwitchListener = true
        presetSwitch?.isChecked = MayakPrefs.presetEnabled(this)
        suppressPresetSwitchListener = false
    }

    /** Диалог выбора пресета: «Выбрать» — сделать активным; «Изменить» — редактировать/форкнуть выбранный;
     *  первый пункт «＋ Новый пресет» — создать с нуля. (Раньше правка была скрыта в долгом тапе — запрос владельца.) */
    private fun showPresetChooser() {
        val presets = MayakPresets.cached(this)
        if (presets.isEmpty()) { openPresetEditor(null); return }
        // Пункт 0 — создание нового; далее сами пресеты. Системный подписываем «(базовый)» — его нельзя
        // удалить, а имя может совпадать со своим форком (владелец путался, где системный, где свой).
        // ⚠️ Подписи берём из ресурсов, а НЕ пишем строкой здесь: до 13-08 они были русскими и
        // оставались русскими при любом языке приложения — на английском телефоне это выглядело
        // как «половина приложения не переведена» (нашлось при съёмке английских скриншотов).
        val items = (listOf(getString(R.string.mayak_preset_chooser_new)) +
            presets.map {
                it.name + if (it.source == "system") getString(R.string.mayak_preset_chooser_system_suffix) else ""
            }).toTypedArray()
        val activeId = MayakPrefs.activePresetId(this)
        var sel = presets.indexOfFirst { it.id == activeId }.let { if (it < 0) 0 else it } + 1
        com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
            .setTitle(getString(R.string.mayak_settings_split))
            .setSingleChoiceItems(items, sel) { _, which -> sel = which }
            .setPositiveButton(R.string.mayak_preset_chooser_select) { _, _ ->
                if (sel == 0) { openPresetEditor(null); return@setPositiveButton }
                MayakPrefs.setActivePresetId(this, presets[sel - 1].id)
                updatePresetSelector()
            }
            .setNeutralButton(R.string.mayak_preset_chooser_edit) { _, _ ->
                openPresetEditor(if (sel == 0) null else presets[sel - 1])
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    /** Открыть редактор: preset=null → новый; свой → правка; системный → форк (копия с предвыбранными приложениями). */
    private fun openPresetEditor(preset: org.amnezia.awg.mayak.core.Preset?) {
        editingPresetId = if (preset != null && preset.owned) preset.id else 0L
        val name = preset?.name ?: getString(R.string.app_name)
        val mode = preset?.mode ?: MayakPresetEditorActivity.MODE_EXCLUDE
        // приложения раскрываем в конкретные установленные пакеты (системный rule-based → отмеченные РФ-приложения).
        val apps = if (preset != null) MayakPresets.resolveApps(this, preset).toList() else emptyList()
        // Всегда редактируемо: свой пресет правим на месте, СИСТЕМНЫЙ (РФ напрямую) — форкаем в редактируемую
        // копию с предзаполненными приложениями (сохранится под НОВЫМ именем, editingPresetId=0). Запрос владельца:
        // «надо взять текущий пресет и его поменять» — раньше системный открывался view-only и не редактировался.
        val editable = true
        presetEditorLauncher.launch(MayakPresetEditorActivity.intent(this, editingPresetId, name, mode, apps, editable))
    }

    /** Сохранить пресет на сервер (создать/обновить), пересинхронить, обновить селектор. */
    private fun savePreset(id: Long, name: String, mode: String, apps: List<String>) {
        val b = backend ?: return
        lifecycleScope.launch {
            val ok = runCatching {
                if (id > 0) {
                    session.updatePreset(b, id, org.amnezia.awg.mayak.core.PresetWrite(name, mode, apps))
                } else {
                    val newId = session.createPreset(b, org.amnezia.awg.mayak.core.PresetWrite(name, mode, apps))
                    MayakPrefs.setActivePresetId(this@MayakActivity, newId)
                }
                session.syncPresets(this@MayakActivity, b)
                MayakPresets.invalidate()
            }.isSuccess
            updatePresetSelector()
            Toast.makeText(this@MayakActivity,
                if (ok) R.string.mayak_settings_split_applied else R.string.mayak_update_check_failed,
                Toast.LENGTH_SHORT).show()
        }
    }

    /** Удалить активный пресет (только свой) — по долгому тапу на имени. */
    private fun confirmDeleteActivePreset() {
        val active = MayakPresets.activePreset(this) ?: return
        if (!active.owned) return // системный удалить нельзя
        com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
            .setMessage(getString(R.string.mayak_preset_delete_confirm, active.name))
            .setPositiveButton(android.R.string.ok) { _, _ -> deletePresetById(active.id) }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    /** Удалить пресет по id на сервере, пересинхронить, сбросить активный, обновить селектор.
     *  Вызывается из редактора (кнопка «Удалить») и из долгого тапа по имени. Подтверждение — у вызывающего. */
    private fun deletePresetById(id: Long) {
        val b = backend ?: return
        lifecycleScope.launch {
            val ok = runCatching {
                session.deletePreset(b, id)
                session.syncPresets(this@MayakActivity, b)
                MayakPresets.invalidate()
            }.isSuccess
            if (MayakPrefs.activePresetId(this@MayakActivity) == id) {
                MayakPrefs.setActivePresetId(this@MayakActivity, 0L)
            }
            updatePresetSelector()
            Toast.makeText(this@MayakActivity,
                if (ok) R.string.mayak_settings_split_applied else R.string.mayak_update_check_failed,
                Toast.LENGTH_SHORT).show()
        }
    }

    // --- главный экран (Happ-стиль): круг-подключение + список стран с флагами ---

    private fun showHome() {
        isHomeShown = true
        setContentView(R.layout.activity_mayak_home)
        // Низ НЕ отступаем: над жестовой полосой контент поднимает штамп версии (ниже), сложились бы
        // два отступа. Верх и бока — здесь.
        MayakSystemBars.padForBars(findViewById(R.id.mayak_home_content), bottom = false)
        status = findViewById(R.id.mayak_status)
        dirsContainer = findViewById(R.id.mayak_dirs_container)
        // Бледный штамп версии внизу экрана (просьба владельца 2026-08-03): на присланном скриншоте
        // сразу видно сборку, спрашивать отдельно больше не нужно.
        findViewById<android.widget.TextView?>(R.id.mayak_version_stamp)?.let { stamp ->
            stamp.text = getString(R.string.mayak_version_stamp, BuildConfig.VERSION_NAME, BuildConfig.VERSION_CODE)
            // Поднимаем над системной панелью по фактическому отступу: на жестовой навигации штамп
            // иначе перечёркивает «пилюля» (видно на эмуляторе), а на трёхкнопочной он уезжает под кнопки.
            androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(stamp) { v, insets ->
                val bottom = insets.getInsets(androidx.core.view.WindowInsetsCompat.Type.systemBars()).bottom
                (v.layoutParams as? android.view.ViewGroup.MarginLayoutParams)?.let { lp ->
                    lp.bottomMargin = bottom + (6 * resources.displayMetrics.density).toInt()
                    v.layoutParams = lp
                }
                insets
            }
        }
        // SPEC-0031: перетаскивание строк в режиме «свои» (long-press на строке стартует drag).
        dirsContainer?.setOnDragListener { _, event -> handleRowDrag(event) }
        // Кнопка «Обновить» — явно перетянуть список стран с сервера (новые направления без перелогина).
        findViewById<View?>(R.id.mayak_refresh_dirs)?.setOnClickListener {
            MayakHaptics.tap(it)
            loadDirections(forceRefresh = true)
            checkAppUpdate(force = true) // «Обновить» проверяет и список стран, И версию приложения
        }
        // SPEC-0031: переключатель режима сортировки. Был циклом Авто → Пинг → Свои; режим «Пинг»
        // снят 15-08 вместе с клиентским пингом напротив стран (директива владельца: «это не
        // работает и задумывалось совсем по-другому») — остался цикл Авто ↔ Свои.
        findViewById<android.widget.TextView?>(R.id.mayak_sort_mode)?.let { btn ->
            updateSortModeLabel(btn)
            btn.setOnClickListener {
                MayakHaptics.tap(it)
                // Сохранённое значение 1 (снятый режим «Пинг») читается как «Авто» → следующий «Свои».
                val next = if (MayakPrefs.sortMode(this) == SORT_CUSTOM) SORT_AUTO else SORT_CUSTOM
                MayakPrefs.setSortMode(this, next)
                updateSortModeLabel(btn)
                if (next == SORT_CUSTOM) Toast.makeText(this, R.string.mayak_sort_custom_hint, Toast.LENGTH_LONG).show()
                applyOrderAndRender()
            }
        }
        connectCircle = findViewById(R.id.mayak_connect_circle)
        connectIcon = findViewById(R.id.mayak_connect_icon)
        connectGlow = findViewById(R.id.mayak_connect_glow)
        timerView = findViewById(R.id.mayak_timer)
        ipView = findViewById(R.id.mayak_ip)
        ipv6Badge = findViewById(R.id.mayak_ipv6_badge)
        fallbackBadge = findViewById(R.id.mayak_fallback_badge)
        pingView = findViewById(R.id.mayak_ping)
        speedView = findViewById(R.id.mayak_speed)
        rippleView = findViewById(R.id.mayak_ripple)
        networkBg = findViewById(R.id.mayak_network_bg)
        // НОВЫЙ дизайн (dev-сборка): «живой» фон-карта — мерцающие города тёплым маячным светом. В прод/
        // релизе NEW_DESIGN=false → карта статична, поведение прежнее. См. DESIGN-VISION §2.
        if (org.amnezia.awg.BuildConfig.NEW_DESIGN) networkBg?.livingMode = true
        // волны стартуют от края круга — радиус берём из того же dimen, что задаёт круг в раскладке,
        // иначе при смене размера кнопки кольца снова начнут расходиться не от её края
        rippleView?.coreRadiusPx = resources.getDimension(R.dimen.mayak_connect_circle) / 2f

        setupThemeButton()
        // Язык убран с главной (правка владельца 2026-07-18) — переключение только в настройках.
        findViewById<MaterialButton>(R.id.mayak_settings_button).setOnClickListener {
            startActivity(Intent(this, MayakSettingsActivity::class.java))
            MayakTransitions.applyAxis(this) // плавный переход к настройкам
        }
        // Ящик сообщений (SPEC-0047): кнопка в шапке + кружок непрочитанного.
        findViewById<MaterialButton?>(R.id.mayak_messages_button)?.setOnClickListener {
            MayakHaptics.tap(it)
            MayakMessagesActivity.open(this)
        }
        updateMessagesBadge()
        syncMessages() // тихая проверка ящика при открытии приложения (сама себя ограничивает по частоте)
        // Адрес доставки пуша — ускоритель того же ящика. Идемпотентно: уже отправленный адрес
        // второй раз на ядро не уходит. Нет сервисов Google или сборка не боевая — одна строка в лог.
        MayakPush.refresh(this)

        setupPresetSelector() // селектор пресета split-туннеля над кнопкой VPN (SPEC-0028)

        // Пришли сюда из «Настроек» → «Split-туннель» (та же кнопка-пресет, но по имени, которое
        // человек реально ищет) — сразу открыть тот же диалог. removeExtra — иначе пересоздание
        // активити (смена темы) откроет диалог второй раз без нового перехода из Настроек.
        if (intent?.getBooleanExtra(EXTRA_OPEN_SPLIT_TUNNEL, false) == true) {
            intent.removeExtra(EXTRA_OPEN_SPLIT_TUNNEL)
            showPresetChooser()
        }

        // Тап с press-feedback: лёгкое сжатие 0.96 + haptic-tick, затем toggle.
        connectCircle?.setOnClickListener { v ->
            MayakHaptics.tap(v)
            pressSqueeze(v)
            toggleConnect()
        }

        // Тап по статусу/таймеру (когда подключены) → лист «Подробности подключения» с IP/пингом/
        // сервером (правка владельца: IP убрали с главного, показываем по запросу в окне).
        val openDetails = View.OnClickListener {
            when {
                connState == ConnState.CONNECTED -> {
                    MayakHaptics.tap(it)
                    showConnectionDetails()
                }
                // Провал подключения: тот же жест (тап по надписи под кнопкой), но ведёт в помощь,
                // а не в подробности — подключения-то и не случилось.
                errorHelpAvailable -> {
                    MayakHaptics.tap(it)
                    openErrorHelp()
                }
            }
        }
        status.setOnClickListener(openDetails)
        timerView?.setOnClickListener(openDetails)
        pingView?.setOnClickListener(openDetails)

        // Восстанавливаем состояние круга после пересоздания Activity (смена темы/языка, возврат
        // в приложение при живом туннеле). tunnel.isUp() честен — backend процесс-скоупный (GoTunnel),
        // состояние НЕ теряется. Таймер стартует с фактического момента НАШЕГО коннекта, а не «с возврата».
        connState = if (tunnel.isUp()) ConnState.CONNECTED else ConnState.DISCONNECTED
        if (connState == ConnState.CONNECTED) {
            startTimer()
            startPing() // пинг сервера (хост персистится в GoTunnel)
            startKeepalive() // продление аренды overlay-IP (SPEC-0015)
            // Значок IPv6 + выходные IP персистятся в GoTunnel (процесс-скоупно) → на реоупене восстанавливаем.
            val v6 = GoTunnel.egressIpv6
            setIpv6Badge(v6 != null)
            setRouteBadge(GoTunnel.connectedRoute) // пометка маршрута тоже персистится в GoTunnel
            if (GoTunnel.egressIpv4 != null) renderEgress() // IP не на главном (в деталях) — mayak_ip скрыт
            MayakNotification.show(this, GoTunnel.connectedLabel, GoTunnel.connectedPingMs) // персист-метка направления
        } else {
            MayakNotification.clear(this)
        }
        renderState(connState)
        loadAccessLine()
        fadeInContent() // тонкий fade-through при заходе на главный (login→home)
        // Разрешение на уведомления просим ЗДЕСЬ, а не при подключении: см. maybeRequestNotifPermission.
        maybeRequestNotifPermission()
    }

    /**
     * Срок доступа на главном экране (аудит 2026-07-31, п. 11).
     *
     * Раньше человек мог узнать о своих пробных 7 днях только внизу настроек — на пятом экране
     * прокрутки, то есть на практике не узнавал и упирался в «доступ закончился» без предупреждения.
     * Строка тихая (мелкая, приглушённая), появляется только когда ядро ответило, и по тапу ведёт в
     * кабинет — туда, где доступ продлевают. Ошибку глотаем: главный экран не должен зависеть от
     * того, ответило ли ядро про аккаунт.
     */
    private fun loadAccessLine() {
        val view = findViewById<TextView?>(R.id.mayak_access) ?: return
        if (!session.hasToken()) { view.visibility = View.GONE; return }
        val b = backend ?: return
        lifecycleScope.launch {
            val st = runCatching { session.accountStatus(b) }.getOrNull() ?: return@launch
            val access = MayakAccessLine.of(this@MayakActivity, st)
            view.text = access.text
            view.setTextColor(
                androidx.core.content.ContextCompat.getColor(
                    this@MayakActivity,
                    if (access.alarming) R.color.mayak_red else R.color.mayak_on_bg_muted,
                )
            )
            view.setOnClickListener { openUrl(MayakHostList.cabinetUrl(this@MayakActivity)) }
            view.visibility = View.VISIBLE
        }
    }

    /**
     * Кружок с числом непрочитанных сообщений в шапке. Рисуется по СОХРАНЁННОМУ значению, то есть
     * виден сразу и без сети: тянуть его из сети было бы «сначала пусто, потом дёрнулось».
     * Больше 99 не пишем — в кружок 16dp такое число всё равно не влезет.
     */
    private fun updateMessagesBadge() {
        val badge = findViewById<TextView?>(R.id.mayak_messages_badge) ?: return
        val n = MayakMessages.unread(this)
        if (n <= 0) {
            badge.visibility = View.GONE
            return
        }
        badge.text = if (n > 99) getString(R.string.mayak_messages_badge_many) else n.toString()
        badge.visibility = View.VISIBLE
    }

    /**
     * Проверка ящика (SPEC-0047). Зовётся при показе главного, при ВОЗВРАТЕ в приложение и после
     * удачного подъёма туннеля — это моменты, когда мы и так в сети, а человек смотрит на экран.
     * Отсюда и триггер OPEN: пол в 10 секунд (защита от пересоздания экрана), а не в час, как было
     * до 13-08.
     *
     * Молчит при любой беде: нет входа, нет сети, ручки на ядре ещё не завезли. Число появится
     * кружком в шапке, а НОВОЕ сообщение — баннером (см. showMessageBanner).
     */
    private fun syncMessages() {
        if (!session.hasToken()) return
        lifecycleScope.launch {
            val r = MayakMessages.sync(this@MayakActivity, MayakMessages.SyncTrigger.OPEN)
            if (!r.ok) return@launch
            updateMessagesBadge()
            r.fresh.maxByOrNull { it.id }?.let { showMessageBanner(it) }
        }
    }

    /**
     * Новое сообщение пришло, пока человек В ПРИЛОЖЕНИИ — сказать явно, а не зажечь цифру.
     *
     * Пункт 4 разбора 13-08: цифра на конверте — это про «пришло когда-то раньше», её замечают не
     * сразу и не связывают с тем, что происходит сейчас. Уведомление в шторке при открытом приложении
     * человек тоже не увидит — он смотрит не туда. Поэтому баннер поверх экрана, с заголовком и
     * кнопкой «Открыть».
     *
     * Уведомление при этом всё равно показывается — оно останется в шторке, если баннер пропустили;
     * открытие карточки его снимет (markRead гасит уведомление по id).
     */
    private fun showMessageBanner(m: org.amnezia.awg.mayak.core.UserMessage) {
        if (!lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED)) return
        val root = findViewById<View>(android.R.id.content) ?: return
        com.google.android.material.snackbar.Snackbar
            .make(root, MayakMessages.title(this, m), com.google.android.material.snackbar.Snackbar.LENGTH_LONG)
            .setAction(R.string.mayak_messages_banner_open) {
                startActivity(
                    Intent(this, MayakMessagesActivity::class.java)
                        .putExtra(MayakMessagesActivity.EXTRA_MESSAGE_ID, m.id)
                )
                MayakTransitions.applyAxis(this)
            }
            // Своя палитра, а не материаловская по умолчанию: та берёт «инверсную» поверхность и на
            // тёмной теме выкатывает БЕЛУЮ плашку поперёк экрана (видно на замере 13-08). Цвета
            // ресурсами — у них есть ночной вариант, значит на светлой теме останется светлым.
            .setBackgroundTint(ContextCompat.getColor(this, R.color.mayak_card))
            .setTextColor(ContextCompat.getColor(this, R.color.mayak_on_bg))
            .setActionTextColor(ContextCompat.getColor(this, R.color.mayak_accent))
            .show()
    }

    /** Лёгкий fade-through контента экрана (вместо мгновенной подмены setContentView). */
    private fun fadeInContent() {
        if (reducedMotion()) return
        val root = findViewById<View>(android.R.id.content)
        root?.let {
            it.alpha = 0f
            it.animate().alpha(1f).setDuration(280).start()
        }
    }

    /** Лёгкое сжатие круга при нажатии (даёт тактильную «кнопочность»). Уважает reduced-motion. */
    private fun pressSqueeze(v: View) {
        if (reducedMotion()) return
        v.animate().cancel()
        v.scaleX = 0.96f; v.scaleY = 0.96f
        v.animate().scaleX(1f).scaleY(1f).setDuration(160)
            .setInterpolator(AccelerateDecelerateInterpolator()).start()
    }

    /** Системный «убрать анимацию» (Settings.Global.ANIMATOR_DURATION_SCALE == 0). */
    private fun reducedMotion(): Boolean =
        Settings.Global.getFloat(contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f

    /**
     * Загрузка направлений. По умолчанию берёт кэш (in-memory переживает пересоздание Activity при
     * смене темы → сеть не дёргается). forceRefresh=true — принудительный рефетч (после логина или
     * фейловера). «Загрузку…» показываем только когда реально идём в сеть, без мигания при кэше.
     */
    // loadDirections — cache-then-refresh: мгновенно показываем кэш (если есть), затем ВСЕГДА тянем свежий
    // список с сервера. Новые направления появляются сами, БЕЗ перелогина (баг владельца 2026-06-28: кэш
    // залипал до выхода/входа). Перерисовываем только когда список реально изменился и мы отключены —
    // чтобы не дёргать UI при активном подключении.
    private fun loadDirections(forceRefresh: Boolean = false) {
        val b = backend ?: return
        if (!session.hasCachedDirections()) setStatus(getString(R.string.mayak_status_loading))
        lifecycleScope.launch {
            try {
                // 1) мгновенный показ кэша (быстрый UI), если список ещё пуст
                if (directions.isEmpty() && session.hasCachedDirections()) {
                    renderDirections(session.directions(b, false))
                }
                // 2) свежий список с сервера (обновляет кэш) — раз на процесс ИЛИ по явному рефрешу/логину.
                // Пересоздание Activity (смена темы) в живом процессе → флаг уже true → в сеть НЕ идём (смена
                // темы молчит полностью). Перезапуск процесса или кнопка «Обновить» подтянут новые направления.
                if (forceRefresh || directionsFetchedLang != MayakBackend.namesLanguageBucket()) {
                    val fresh = session.directions(b, true)
                    // Только на успех: ошибка сети оставит прежнее значение → следующий заход повторит.
                    directionsFetchedLang = MayakBackend.namesLanguageBucket()
                    // «Изменился» — это ВСЁ содержимое, а не только набор id (равенство data-класса
                    // против СЫРОГО серверного порядка). Сравнение по id молча съедало смену полей на
                    // тех же направлениях: кэш на диске пишется сериализатором ТЕКУЩЕЙ схемы, у
                    // обновившегося приложения в нём нет новых полей (ipv6, recommended…) — свежий
                    // ответ ядра их принёс, а перерисовка не случалась, и бейджи не зажигались до
                    // перезапуска процесса (поймано на эмуляторе 09-08). То же с живой сменой
                    // health/recommended на сервере при тёплом кэше.
                    val changed = fresh != serverDirections
                    if (directions.isEmpty() || (changed && connState == ConnState.DISCONNECTED)) {
                        renderDirections(fresh)
                    }
                }
            } catch (e: Exception) {
                // Отозванный вход виден и здесь (список стран запрашивается с токеном). Показать
                // «Ошибка ядра (401)» и оставить человека на экране, где всё мертво, — тупик:
                // уводим на вход так же, как в коннекте.
                if (e is MayakApiException && e.code == "unauthorized") sessionExpired()
                else if (directions.isEmpty()) {
                    // Показать нечего И загрузка провалилась — это НЕ «стран нет»: список у нас,
                    // скорее всего, в порядке, не удалась именно загрузка. Говорим ровно это, а
                    // рядом даём кнопку повтора (одной фразой на две причины отделаться нельзя).
                    setStatus(humanError(e))
                    showDirsLoadError(e)
                }
            }
        }
    }

    /** Пользовательский порядок (SPEC-0031, режим «свои»): сначала направления в сохранённом порядке (по id),
     *  затем новые (не в сохранённом списке) — в порядке сервера. Сохранённые id, которых больше нет, игнор. */
    private fun applyCustomOrder(dirsIn: List<Direction>): List<Direction> {
        val order = MayakPrefs.customOrder(this)
        if (order.isEmpty()) return dirsIn
        val byId = dirsIn.associateBy { it.id }
        val ordered = order.mapNotNull { byId[it] }
        val orderSet = order.toSet()
        val rest = dirsIn.filter { it.id !in orderSet }
        return ordered + rest
    }

    /** Ярлык кнопки режима сортировки (Авто/Свои) по текущему режиму. */
    private fun updateSortModeLabel(btn: android.widget.TextView) {
        btn.setText(
            when (MayakPrefs.sortMode(this)) {
                SORT_CUSTOM -> R.string.mayak_sort_custom
                else -> R.string.mayak_sort_auto // в т. ч. легаси-значение 1 (снятый режим «Пинг»)
            }
        )
    }

    private var serverDirections: List<Direction> = emptyList()

    /** Вход при НОВЫХ данных сервера: запоминаем СЫРОЙ порядок сервера и рисуем по текущему режиму. */
    private fun renderDirections(dirsIn: List<Direction>) {
        serverDirections = dirsIn
        applyOrderAndRender()
    }

    /** Применить выбранный режим к сырому серверному списку и перерисовать (авто-режим не теряет порядок сервера). */
    private fun applyOrderAndRender() {
        val dirsIn = serverDirections
        // SPEC-0031: порядок по выбранному режиму. 0 авто — свежие ТИХИЕ замеры близости есть →
        // быстрейший выход первым (LatencyProbe/MayakLatency; цифр на экране нет — их владелец убрал
        // 15-08), замеров нет → как отдал сервер; 2 свои — пользовательский порядок (перетаскивание).
        // Видимый режим «по пингу» (легаси-значение 1) снят 15-08 — сохранённая единица читается как «авто».
        val mode = MayakPrefs.sortMode(this)
        val dirs = when (mode) {
            SORT_CUSTOM -> applyCustomOrder(dirsIn)
            // SORT_AUTO: сперва СОБСТВЕННЫЙ опыт подключения (сколько реально поднимался туннель у
            // этого человека), и только там, где опыта нет, — тихий замер близости. Правда сильнее
            // приближения: легенда на :443 отвечает и у направления, чей UDP-путь у оператора режется.
            else -> orderForAutoWithHistory(
                dirsIn,
                rttOf = { id -> MayakLatency.freshRtt(this, id) },
                statOf = { id -> MayakConnectStats.stat(this, id) },
                nowMs = System.currentTimeMillis(),
            )
        }
        directions = dirs
        val container = dirsContainer ?: return
        container.removeAllViews()
        rowViews.clear()
        if (dirs.isEmpty()) {
            // Сервер ОТВЕТИЛ, но стран в ответе нет. Это отдельная беда со своим лечением (обновить
            // через минуту, заглянуть в кабинет) — не путать с «список не загрузился», см.
            // showDirsLoadError. Статус под кнопкой оставляем прежним: он короткий, карточка длинная.
            setStatus(getString(R.string.mayak_err_empty_dirs))
            showDirsState(
                getString(R.string.mayak_dirs_empty_title),
                getString(R.string.mayak_dirs_empty_text),
                R.string.mayak_refresh,
            )
            return
        }
        hideDirsState()
        // Разбор на плитку/список — В :core (splitRecommended, RecommendationTest): рекомендованное
        // направление живёт РОВНО в одном месте на экране (баг 09-08 «Почему тут 2 Нидерланды?» —
        // плитку рисовали НАД полным списком, не убирая из него то же направление).
        val split = splitRecommended(dirs)
        for (d in split.list) {
            val row = countryRow(d)
            container.addView(row)
            rowViews.add(row)
        }
        renderRecommendedTile(split.tile) // до selectDir ниже: строка в плитке тоже подсвечивается выбором
        val lastId = MayakPrefs.lastDirectionId(this@MayakActivity)
        val initial = dirs.firstOrNull { it.id == lastId } ?: dirs.first()
        // На живом туннеле после пересоздания Activity (смена темы) connectedDir сброшен (instance-поле) —
        // восстанавливаем его из выбора (это и есть подключённая страна), иначе пассивный selectDir принял
        // бы живую страну за «другую» и, будь он userInitiated, дёрнул бы switchTo.
        if (connState == ConnState.CONNECTED && connectedDir == null)
            connectedDir = GoTunnel.connectedDirectionId?.let { id -> dirs.firstOrNull { it.id == id } } ?: initial
        // МЕТКА НАПРАВЛЕНИЯ ПЕРЕЖИВАЕТ СМЕНУ ЯЗЫКА (жалоба владельца 14-08: в шторке
        // «🇳🇱 Netherlands · Защищено» — половина строки на одном языке, половина на другом).
        //
        // Название страны приходит С СЕРВЕРА на языке телефона, а метку мы пишем ОДИН раз, в
        // onConnected, и держим процесс-скоупно (GoTunnel.connectedLabel). Смена языка на живом
        // туннеле пересоздаёт Activity и перетягивает список на новом языке, но метку не трогает
        // никто: onConnected больше не случится до переподключения. В шторке, в «Подробностях» и на
        // плитке оставалось имя на СТАРОМ языке — рядом с уже переведённым «Защищено».
        //
        // Здесь список уже нового языка, а подключённое направление известно строкой выше — значит
        // это единственное место, где метку можно починить, ничего не спрашивая у сети.
        if (connState == ConnState.CONNECTED) connectedDir?.let { d ->
            val fresh = MayakNotification.labelFor(this, d)
            if (fresh != GoTunnel.connectedLabel) {
                GoTunnel.connectedLabel = fresh
                MayakPrefs.setLastConnLabel(this, fresh, d.id) // и на диск: шторка читает оттуда без Activity
                MayakNotification.show(this, fresh, GoTunnel.connectedPingMs)
            }
        }
        selectDir(initial, userInitiated = false) // пассивно: без сети, без переподключения (тема молчит)
        if (connState == ConnState.DISCONNECTED) {
            setStatus(getString(R.string.mayak_status_disconnected))
        }
        // Тихий замер близости выходов (TCP до легенды узла :443) — ТОЛЬКО при выключенном VPN
        // (любом: признак TRANSPORT_VPN у системы MayakLatency проверяет сам, перед каждой попыткой)
        // и только для протухших замеров (TTL сутки) — то есть фактически раз в сутки на холодном
        // старте, без будильников. connState-гейт сверху — не дублёр той проверки, а про CONNECTING:
        // системный признак VPN во время подъёма туннеля ещё не выставлен, а спорить с рукопожатием
        // за радио замеру незачем. Появились новые замеры → перерисовать «Авто» по ним; повторный
        // вход цикла не даёт — всё уже свежо, measureIfNeeded выходит сразу.
        if (connState == ConnState.DISCONNECTED) {
            MayakLatency.measureIfNeeded(this, dirsIn, lifecycleScope) { applyOrderAndRender() }
        }
        maybeShowOnboarding() // после отрисовки списка: экран уже настоящий, а не пустой каркас
    }

    /**
     * Карточка стран без единой строки — показать человеку, что произошло и что делать.
     *
     * До этого пустая карточка была буквально пустой: ни слова внутри, только мелкий статус под
     * кнопкой подключения. Причин у пустоты две, лечения у них разные, и одной фразой их накрывать
     * запрещено правилом проекта — поэтому заголовок/текст/подпись кнопки приходят аргументами, а
     * не зашиты здесь. Кнопка всегда делает одно: перетягивает список с сервера заново.
     */
    private fun showDirsState(title: CharSequence, text: CharSequence, actionLabel: Int) {
        val block = findViewById<View?>(R.id.mayak_dirs_state) ?: return
        findViewById<TextView?>(R.id.mayak_dirs_state_title)?.text = title
        findViewById<TextView?>(R.id.mayak_dirs_state_text)?.text = text
        findViewById<MaterialButton?>(R.id.mayak_dirs_state_action)?.apply {
            setText(actionLabel)
            setOnClickListener {
                MayakHaptics.tap(it)
                loadDirections(forceRefresh = true)
            }
        }
        block.visibility = View.VISIBLE
        // Сортировать нечего — переключатель режима в пустой карточке только сбивает с толку.
        findViewById<View?>(R.id.mayak_sort_mode)?.visibility = View.GONE
        // Плитка «⚡ Рекомендуем» от ПРОШЛОГО списка над карточкой «стран нет / не загрузилось»
        // советовала бы то, чего в списке больше нет. Вернёт её следующий успешный рендер.
        findViewById<View?>(R.id.mayak_recommended_tile)?.visibility = View.GONE
    }

    /** Строки появились — убрать объяснение и вернуть переключатель сортировки. */
    private fun hideDirsState() {
        findViewById<View?>(R.id.mayak_dirs_state)?.visibility = View.GONE
        findViewById<View?>(R.id.mayak_sort_mode)?.visibility = View.VISIBLE
    }

    /**
     * Список не загрузился (сервер молчит / нет сети / отказ ядра) — это НЕ «стран нет».
     * Три разные причины — три разных текста: телефон без интернета человек чинит сам за секунду,
     * молчащий сервер — ждёт, отказ ядра объясняем его же словами (humanError уже переводит коды).
     */
    private fun showDirsLoadError(e: Throwable) {
        val text = when {
            e is MayakApiException -> humanError(e)
            e is IOException && !MayakNet.hasNetwork(this) -> getString(R.string.mayak_dirs_error_offline)
            else -> getString(R.string.mayak_dirs_error_server)
        }
        showDirsState(getString(R.string.mayak_dirs_error_title), text, R.string.mayak_dirs_retry)
    }

    /** Строка-страна: флаг + название (+город) + бейдж IPv6 + точка «подключено»; тап = выбор (без подключения).
     *  allowReorder=false — строка живёт в плитке «⚡ Рекомендуем»: перетаскивать её некуда
     *  (порядок «свои» — про список), долгий тап там ничего не делает. */
    private fun countryRow(d: Direction, allowReorder: Boolean = true): View {
        val container = dirsContainer
        val row = LayoutInflater.from(this).inflate(R.layout.mayak_country_row, container, false)
        // Флаг: эмодзи (как в шторке уведомления — владелец 04-08: «они симпатичнее»), а векторный
        // остаётся запасным. Прошивка без эмодзи-флагов нарисовала бы пустой квадрат вместо страны,
        // поэтому спрашиваем систему, есть ли глиф, и только тогда прячем картинку.
        val flagImage = row.findViewById<ImageView>(R.id.mayak_row_flag)
        val flagEmojiView = row.findViewById<TextView>(R.id.mayak_row_flag_emoji)
        MayakFlags.apply(flagImage, flagEmojiView, d.flagCode())
        // Клиентский пинг напротив страны СНЯТ 15-08 (директива владельца: «это не работает и
        // задумывалось совсем по-другому»). Пинг живого туннеля («Пинг: N мс» под статусом) остался.
        // Название — жирным; город приписан СБОКУ, в ту же строку («Нидерланды · Амстердам»).
        // Был подзаголовком снизу (SPEC-0037), но строка выходила ~54dp и десяток направлений на
        // экран не помещался (правка владельца 04-08). Пусто (старые направления без city) →
        // город скрыт, видно только название.
        row.findViewById<TextView>(R.id.mayak_row_name).apply {
            text = d.name
            // Крупный системный шрифт (≥150 %): бейджи съедают ширину, и «Нидерланды»
            // резалось до «Нидерл…» (замер 15-08 на 200 %). Разрешаем имени вторую строку —
            // перенос по дефису (hyphenationFrequency в разметке), строка списка подрастает и
            // прокручивается. При обычном шрифте остаётся одна строка — компактность списка
            // (правка владельца 04-08) не трогается.
            if (resources.configuration.fontScale >= 1.5f) maxLines = 2
        }
        row.findViewById<TextView>(R.id.mayak_row_city).apply {
            val c = d.city.trim()
            if (c.isNotEmpty()) {
                text = getString(R.string.mayak_row_city_inline, c)
                visibility = View.VISIBLE
            } else visibility = View.GONE
        }
        // Бейдж «IPv6» (директива владельца 01-07) — ТОЛЬКО по явному ipv6:true от ядра: это
        // проверенный egress-признак выходной ноды (curl -6 на ядре, cprepo/clientdata.go), тот же,
        // по которому чип рисуют лендинг и кабинет. Не по AAAA и не по своим догадкам — иначе бейдж
        // врёт; false и «поля нет» одинаково значат «не рисуем» (см. DirectionIPv6Test в :core).
        row.findViewById<TextView>(R.id.mayak_row_ipv6).visibility =
            if (d.ipv6) View.VISIBLE else View.GONE
        // Точка «трафик идёт через эту страну» — только у активного направления и только при живом
        // туннеле. Подсветка строки говорит «выбрана», и она одинакова при выключенном VPN; отличить
        // «выбрана» от «работает» по списку было нельзя. Условие — то же, что у живого пинга выше.
        row.findViewById<View>(R.id.mayak_row_connected_dot).apply {
            val active = connState == ConnState.CONNECTED &&
                d.id == (GoTunnel.connectedDirectionId ?: connectedDir?.id)
            visibility = if (active) View.VISIBLE else View.GONE
            contentDescription = if (active) getString(R.string.mayak_row_connected_dot_desc) else null
        }
        row.tag = d.id
        row.setOnClickListener {
            MayakHaptics.tap(it)
            selectDir(d)
        }
        // SPEC-0031, режим «свои»: зажать и перетащить строку → изменить порядок (сохраняется).
        if (allowReorder && MayakPrefs.sortMode(this) == SORT_CUSTOM) {
            row.setOnLongClickListener { v ->
                MayakHaptics.longPress(v)
                val data = ClipData.newPlainText("dirId", d.id.toString())
                v.startDragAndDrop(data, View.DragShadowBuilder(v), v, 0)
                true
            }
        }
        return row
    }

    /**
     * Плитка «⚡ Рекомендуем» над списком (SPEC-0031 T3, хвост F-T5).
     *
     * Кого рекомендовать, решает СЕРВЕР (recommended:true в /v1/client/directions — живое
     * направление с наименьшей загрузкой); отбор и разбор со списком — в :core (splitRecommended,
     * RecommendationTest), сюда приходит уже готовый Direction? (null → плитки нет: рекомендация,
     * выдуманная клиентом, разошлась бы с сервером и кабинетом; либо направление ровно одно —
     * см. doc-комментарий splitRecommended).
     *
     * Внутри плитки — ОБЫЧНАЯ строка-страна (countryRow): флаг и выбор по тапу достаются даром.
     * Строка попадает в rowViews (тот же список, что и строки под ней) — благодаря этому подсветка
     * выбора И точка «подключено» (refreshConnectedDots) находят строку ГДЕ БЫ она ни отрисовалась,
     * в плитке или в списке. Направление отрисовывается РОВНО в одном месте (splitRecommended уже
     * вырезал его из списка) — двух строк под одно направление больше не бывает (баг 09-08: плитка
     * и список показывали одно направление дважды с разными данными, потому что это были ДВЕ разные
     * View). Перетаскивание в режиме «свои» плитке отключено.
     */
    private fun renderRecommendedTile(rec: Direction?) {
        val tile = findViewById<View?>(R.id.mayak_recommended_tile) ?: return
        val slot = findViewById<android.view.ViewGroup?>(R.id.mayak_recommended_row_slot) ?: return
        slot.removeAllViews()
        if (rec == null) {
            tile.visibility = View.GONE
            return
        }
        val row = countryRow(rec, allowReorder = false)
        slot.addView(row)
        rowViews.add(row)
        tile.visibility = View.VISIBLE
    }

    private var draggedDirId: Long = -1L

    /** Перетаскивание строки в режиме «свои»: на дропе вычисляем целевой индекс по Y и сохраняем порядок. */
    private fun handleRowDrag(event: android.view.DragEvent): Boolean {
        when (event.action) {
            android.view.DragEvent.ACTION_DRAG_STARTED -> {
                draggedDirId = ((event.localState as? View)?.tag as? Long) ?: -1L
                return true
            }
            android.view.DragEvent.ACTION_DROP -> {
                val container = dirsContainer ?: return false
                val y = event.y
                var target = container.childCount - 1
                for (i in 0 until container.childCount) {
                    val c = container.getChildAt(i)
                    if (y < c.y + c.height / 2f) { target = i; break }
                }
                reorderCustom(draggedDirId, target)
                return true
            }
            android.view.DragEvent.ACTION_DRAG_ENDED -> { draggedDirId = -1L; return true }
            else -> return true
        }
    }

    /** Переставить направление id на позицию targetIndex в пользовательском порядке и сохранить. */
    private fun reorderCustom(id: Long, targetIndex: Int) {
        if (id < 0L) return
        val cur = directions.map { it.id }.toMutableList()
        val from = cur.indexOf(id)
        if (from < 0) return
        cur.removeAt(from)
        cur.add(targetIndex.coerceIn(0, cur.size), id)
        MayakPrefs.setCustomOrder(this, cur)
        applyOrderAndRender()
    }

    /**
     * Выбрать страну: подсветить строку, запомнить выбор. Не подключает.
     * userInitiated=true — реальный тап: греем /connect-кэш и, на живом туннеле, авто-переключаемся на
     * выбранную страну. userInitiated=false — пассивное восстановление выбора при пересоздании Activity
     * (смена темы/языка): НИКАКОЙ сети и НИКАКОГО переподключения (баг владельца 2026-07-06 — смена темы
     * дёргала /connect, а на живом туннеле рвала его через switchTo, т.к. connectedDir сбрасывался).
     */
    private fun selectDir(d: Direction, userInitiated: Boolean = true) {
        selectedDir = d
        MayakPrefs.setLastDirectionId(this, d.id)
        networkBg?.setExitByName(d.name) // дуга-маршрут на карте указывает на выбранную страну
        // ПРЕДЗАГРУЗКА конфига /connect (DPI: тёплый кэш к моменту коннекта). Греем по реальному тапу ИЛИ
        // один раз за процесс при первом входе на главный — но НЕ на пересоздании Activity (смена темы
        // молчит: userInitiated=false и homeWarmedThisProcess уже true). M4: отменяем предыдущую предзагрузку.
        if (userInitiated || !homeWarmedThisProcess) {
            homeWarmedThisProcess = true
            preloadJob?.cancel()
            preloadJob = backend?.takeIf { !session.hasCachedConnect(d.id) }
                ?.let { b -> lifecycleScope.launch { runCatching { session.preloadConnect(b, d) } } }
        }
        for (row in rowViews) {
            val isSel = (row.tag as? Long) == d.id
            row.setBackgroundResource(if (isSel) R.drawable.mayak_row_selected else android.R.color.transparent)
            if (isSel && !reducedMotion()) {
                row.alpha = 0.6f
                row.animate().alpha(1f).setDuration(150).start()
            }
        }
        // Выбор ДРУГОЙ страны на живом туннеле → авто-переподключение на неё (раньше выбор не переключал,
        // и юзер оставался на прежней стране — баг из фидбека владельца 2026-07-02). ТОЛЬКО по реальному
        // тапу: пассивное восстановление при смене темы не должно рвать живой туннель.
        if (userInitiated && connState == ConnState.CONNECTED && connectedDir?.id != d.id) switchTo(d)
    }

    /** Переключение страны на живом туннеле: гасим текущий туннель и поднимаем к выбранной стране. */
    private fun switchTo(d: Direction) {
        connectJob?.cancel()
        renderState(ConnState.CONNECTING)
        setStatus(getString(R.string.mayak_status_connecting, d.name))
        lifecycleScope.launch {
            runCatching { tunnel.down() }
            connGeneration++; ipv6ProbeJob?.cancel()
            stopTimer(); stopPing(); stopKeepalive()
            connState = ConnState.DISCONNECTED
            connectedDir = null
            connectTo(d) // повторно поднимаем к новой стране (разрешение VPN уже есть → сразу doConnect)
        }
    }

    /** Тап по кругу: подключиться к выбранной стране или отключиться. */
    private fun toggleConnect() {
        when (connState) {
            ConnState.CONNECTED -> disconnect()
            ConnState.CONNECTING -> cancelConnect() // тап во время подключения = ОТМЕНА (а не «игнор»/повторный коннект)
            ConnState.DISCONNECTED -> {
                val d = selectedDir
                if (d == null) { setStatus(getString(R.string.mayak_select_country_first)); return }
                connectTo(d)
            }
        }
    }

    /** Отмена идущего подключения: гасим корутину коннекта + туннель, возвращаем экран в DISCONNECTED. */
    private fun cancelConnect() {
        connectJob?.cancel()
        connectJob = null
        pendingConnect = null
        lifecycleScope.launch { runCatching { tunnel.down() } }
        stopTimer()
        stopPing()
        stopKeepalive()
        MayakNotification.clear(this)
        renderState(ConnState.DISCONNECTED)
        setStatus(getString(R.string.mayak_status_cancelled))
        errorShownAt = SystemClock.elapsedRealtime() // «отменено» — тоже событие, а не состояние
    }

    private fun connectTo(d: Direction) {
        // Разрешение на уведомления здесь БОЛЬШЕ НЕ спрашиваем — его просит главный экран
        // (maybeRequestNotifPermission в showHome). Иначе первое подключение перебивалось двумя
        // системными диалогами подряд вместо одного.
        val prepare = GoBackend.VpnService.prepare(this)
        if (prepare != null) {
            pendingConnect = d
            renderState(ConnState.CONNECTING)
            vpnPermission.launch(prepare)
        } else doConnect(d)
    }

    private fun doConnect(d: Direction) {
        val b = backend ?: return
        // Прежде чем винить протокол — спросить у системы, есть ли вообще связь (аудит 2026-07-31).
        // При выключенной сети приложение поднимало VpnService, заворачивало в мёртвый туннель весь
        // трафик на ~15 секунд и рассказывало человеку «UDP не проходит» и «ни один путь не вышел в
        // интернет». Ни одно из этих утверждений не было правдой: у телефона просто не было сети.
        if (!MayakNet.hasNetwork(this)) { fail(getString(R.string.mayak_status_no_network)); return }
        renderState(ConnState.CONNECTING)
        setStatus(getString(R.string.mayak_status_connecting, d.name))
        // Метку направления кладём на диск СРАЗУ, до подъёма туннеля: подъём обнуляет процесс-скоупную
        // (см. MayakPrefs.lastConnLabel), и если коннект не дойдёт до onConnected, шторке будет из чего
        // взять страну. Пишем цель ЭТОГО подключения — тогда даже при смене страны она не отстаёт.
        MayakPrefs.setLastConnLabel(this, MayakNotification.labelFor(this, d), d.id)
        statusShownAt = SystemClock.elapsedRealtime() // отсюда считается читаемость следующей надписи
        errorShownAt = 0L // прошлая ошибка ушла с экрана — ей больше нечего протухать
        connGeneration++ // новое подключение → результаты фоновых проб от прошлого больше не наши
        ipv6ProbeJob?.cancel()
        connectJob = lifecycleScope.launch {
            // Исход лестницы для недельного бикона (LadderTelemetry): какие ступени ПРОБОВАЛИ и они
            // не вышли, какая дала подтверждённый выход, сколько заняло. Записываем ТОЛЬКО явный
            // исход (успех ступени / все ступени мимо): отмена человеком и ошибки ядра (нет конфига,
            // кончился доступ) — не исход лестницы, по ним ступени судить нельзя.
            val ladderStartedAt = SystemClock.elapsedRealtime()
            val failedRungs = mutableListOf<String>()
            fun noteLadderOutcome(successRung: String?) {
                val took = SystemClock.elapsedRealtime() - ladderStartedAt
                MayakPrefs.noteLadder(
                    this@MayakActivity,
                    LadderTelemetry.attemptOutcome(failedRungs, successRung, took),
                )
                // Тот же исход — в собственный опыт по ЭТОМУ направлению (второй шаг «Авто»,
                // ConnectHistory). Бикон отвечает на вопрос «как работают ступени во флоте», а
                // опыт — на вопрос «что быстрее поднимается у ЭТОГО человека»; это разные данные,
                // и складывать их в одно место нельзя: бикон уходит на сервер, опыт живёт на телефоне.
                if (successRung != null) {
                    MayakConnectStats.noteSuccess(this@MayakActivity, d.id, took)
                } else {
                    MayakConnectStats.noteFailure(this@MayakActivity, d.id)
                }
            }
            try {
                // Конфиг берём из ПРЕДЗАГРУЖЕННОГО кэша (наполняется при выборе страны), чтобы в момент
                // подключения НЕ дёргать api.mayakvpn.ru: РФ-DPI (сотовая) палит наш VPN-домен в TLS/DNS
                // рядом с хендшейком и режет туннель. См. memory mobile-dpi-api-domain-leak-2026-06-28.
                val paths = try {
                    session.takeCachedConnect(d.id) ?: session.connect(b, d) // M4: одноразово (нет переиспользования устаревшего)
                } catch (e: NoReachableHostException) {
                    // Ядро недоступно (SPOF-инцидент 2026-07-05) → поднимаем последний РАБОЧИЙ конфиг с диска.
                    // Туннель идёт устройство→ЭКЗИТ, ядро лишь ВЫДАЁТ конфиг → при живом экзите сохранённого хватит.
                    val lastGood = session.lastGoodPaths(d.id) ?: throw e // нет сохранённого → честно «Ядро недоступно»
                    setStatus(getString(R.string.mayak_status_offline_lastgood, d.name))
                    lastGood
                }
                val direct = paths.directConf
                val relay = paths.relayConf
                if (direct == null && relay == null) {
                    fail(getString(R.string.mayak_status_no_egress)); return@launch
                }
                // Запасной канал (SPEC-0039) принадлежит плечу, но пробуется ПОСЛЕДНИМ (см. ниже),
                // поэтому берём первый пригодный и держим рядом конфиг, к которому он относится.
                val fb = paths.directFallback?.takeIf { it.usable() } ?: paths.relayFallback?.takeIf { it.usable() }
                val fbConf = if (paths.directFallback?.usable() == true) direct else relay

                // ЛЕСТНИЦА ПОДКЛЮЧЕНИЯ (порядок задан владельцем 2026-07-28):
                //   1. AWG напрямую к выходу;
                //   2. AWG через российский вход (транзит РФ) — тот же выход, другой адрес входа;
                //   3. запасной канал поверх :443.
                // Раньше ступень 3 стояла ВНУТРИ каждого плеча, то есть мост пробовался РАНЬШЕ транзита.
                // Это и было причиной жалобы: на МТС мост поднимался, но скорость на глазах падала до
                // килобайт (внутри TCP едет UDP-туннель, потерю лечат оба слоя сразу), и человек
                // оставался на заведомо худшем пути, хотя рабочий UDP-путь был рядом. Мост — последний
                // вдох, а не транспорт: пока есть хоть один непроверенный UDP-путь, он ждёт.
                if (!(fb != null && fbConf != null && MayakPrefs.forceFallback(this@MayakActivity))) {
                    // Прямой путь приоритетен. Сервер добавляет пира в течение ~15с (sync-таймер),
                    // поэтому на ПОСЛЕДНЕЙ ступени пробу egress повторяем несколько раз, прежде чем сдаться.
                    if (direct != null) {
                        val ip = bringUpUdp(direct, hasNextRung = relay != null || fb != null,
                            route = GoTunnel.ROUTE_DIRECT, serverHost = MayakPing.hostOf(paths.directEndpoint),
                            peerSyncSlackMs = peerSyncSlack(paths))
                        if (ip != null) { session.rememberWorking(d.id, paths); noteLadderOutcome(GoTunnel.ROUTE_DIRECT); holdStatus(); onConnected(ip, d); return@launch }
                        failedRungs += GoTunnel.ROUTE_DIRECT
                    }
                    if (relay != null) {
                        if (direct != null) announce(getString(R.string.mayak_status_relay_switch))
                        val ip = bringUpUdp(relay, hasNextRung = fb != null,
                            route = GoTunnel.ROUTE_RELAY, serverHost = MayakPing.hostOf(paths.relayEndpoint),
                            peerSyncSlackMs = peerSyncSlack(paths))
                        if (ip != null) { session.rememberWorking(d.id, paths); noteLadderOutcome(GoTunnel.ROUTE_RELAY); holdStatus(); onConnected(ip, d); return@launch }
                        failedRungs += GoTunnel.ROUTE_RELAY
                    }
                }
                if (fb != null && fbConf != null) {
                    val ip = switchToFallback(fbConf, fb)
                    if (ip != null) { session.rememberWorking(d.id, paths); noteLadderOutcome(GoTunnel.ROUTE_FALLBACK); holdStatus(); onConnected(ip, d); return@launch }
                    failedRungs += GoTunnel.ROUTE_FALLBACK
                }
                // Не вышла ни одна ступень: ГАСИМ туннель (иначе VpnService остаётся активным и
                // чёрной-холит весь трафик, а UI показывает «отключено» — тихий no-internet).
                // В бикон — только если сеть у телефона БЫЛА: «нет сети» — не провал лестницы,
                // и записать его провалом значило бы завысить цифры блокировок на ровном месте.
                if (MayakNet.hasNetwork(this@MayakActivity)) noteLadderOutcome(null)
                runCatching { tunnel.down() }
                // Сеть могла пропасть уже ПОСЛЕ старта (человек вышел из зоны, выключил Wi-Fi). Тогда
                // «ни один путь не вышел в интернет» — правда формально и ложь по сути: пути ни при чём.
                fail(getString(
                    if (MayakNet.hasNetwork(this@MayakActivity)) R.string.mayak_status_no_egress
                    else R.string.mayak_status_no_network
                ))
            } catch (e: kotlinx.coroutines.CancellationException) {
                // пользователь отменил подключение (тап по кнопке) — гасим туннель, БЕЗ ошибки/инвалидации.
                runCatching { tunnel.down() }
                throw e
            } catch (e: Exception) {
                runCatching { tunnel.down() }
                // Коннект упал — топология/направление могли измениться: сбрасываем кэш направлений,
                // чтобы следующая загрузка пошла в ядро за свежим списком (фейловер).
                session.invalidateDirections()
                // 402 и 409 — не «ошибки ядра», а два штатных ответа про аккаунт: срок доступа
                // закончился и все места под устройства заняты. Человеку нужен понятный текст и куда
                // пойти, а не «Ошибка ядра (409): достигнут лимит устройств тарифа».
                when {
                    // ТОЛЬКО по машинному признаку. По голому 401 выходить нельзя: ядро отвечало
                    // им и на собственные сбои (например перезапуск базы) — тогда один блип разлогинил
                    // бы всех, кто в этот момент открыл приложение, и погасил бы им туннели. Ядро
                    // теперь отдаёт code=unauthorized только на реально отозванный вход, а на свои
                    // беды — 5xx (разбор 2026-07-27). Без признака ведём себя как при обычной ошибке.
                    e is MayakApiException && e.code == "unauthorized" -> sessionExpired()
                    // 402 значит ДВЕ разные вещи, и до 08-08 приложение их путало: «тарифа не
                    // выдавали» (no_plan) и «срок прошёл» (no_subscription) приходят под одним
                    // статусом, а показывали обоим «Доступ закончился — продлите». Тому, кто ни разу
                    // не платил, это советует продлить то, чего у него не было. Разбор кода — в
                    // :core.accessDenial (юнит-тест на JVM), незнакомый код → прежняя ветка.
                    e is MayakApiException && accessDenial(e.status, e.code) == AccessDenial.NO_PLAN ->
                        showNoPlan()
                    e is MayakApiException && accessDenial(e.status, e.code) == AccessDenial.EXPIRED ->
                        showAccessExpired()
                    // Конфликт ключа устройства сессия чинит сама (перевыпуск пары + повтор). Если он
                    // долетел СЮДА — повтор тоже не прошёл, и это точно не про лимит устройств:
                    // молча показать «лимит» значило бы отправить человека чистить чужие слоты.
                    e is MayakApiException && e.code == "pubkey_taken" ->
                        fail(getString(R.string.mayak_err_device_key_conflict))
                    e is MayakApiException && e.status == 409 -> showDeviceLimit()
                    else -> fail(humanError(e))
                }
            } finally {
                connectJob = null
            }
        }
    }

    /**
     * Сколько ещё нельзя сдаваться на UDP-плече из-за того, что пир может не доехать до ноды.
     *
     * Пир заводится на выходе не в момент выдачи конфига, а на следующем поллинге агента (15 с).
     * Пока его нет, сервер не отвечает на инициацию — рукопожатия не будет, и лестница честно
     * проходит все ступени мимо, заканчивая «нет выхода» на ровном месте (разбор 2026-07-29).
     *
     * Считаем от ВОЗРАСТА конфига, а не «ждём 15 с всегда»: тёплый предзагруженный конфиг (обычный
     * случай — грели, пока человек выбирал страну) даёт слак 0 и не стоит ни миллисекунды.
     */
    private fun peerSyncSlack(paths: Paths): Long {
        if (paths.issuedAtElapsed == 0L) return 0L // конфиг с диска: возраст неизвестен, пир заведомо старый
        return FallbackDecision.peerSyncSlackMs(SystemClock.elapsedRealtime() - paths.issuedAtElapsed)
    }

    /**
     * Поднимает UDP-плечо и доводит его до подтверждённого выхода. null — плечо не вышло в интернет.
     *
     * @param hasNextRung есть ли следующая ступень лестницы. Если есть — не досиживаем полный набор
     * проб (~34с), а сдаёмся по порогам [FallbackDecision] (6с без хендшейка / 10с ПОСЛЕ него): пока
     * человек смотрит на «Подключаюсь…», следующая ступень может уже работать. Если ступень
     * ПОСЛЕДНЯЯ — терпим до конца: сдаться некуда, а сервер добавляет пира ~15с (sync-таймер), и
     * ранний отказ здесь означал бы «не подключается» там, где надо было просто подождать.
     */
    private suspend fun bringUpUdp(
        conf: String,
        hasNextRung: Boolean,
        route: String,
        serverHost: String?,
        peerSyncSlackMs: Long = 0L,
    ): String? {
        tunnel.up(prepareConf(conf))
        // Метку пути и хост сервера ставим ПОСЛЕ подъёма, а не до. `tunnel.up()` внутри сначала делает
        // down() (иначе новый конфиг не применится — «Tunnel already up»), а down() сбрасывает всё
        // состояние подключения, включая маршрут и сервер. Пока их выставляли ДО, их тут же стирало:
        // владелец 2026-07-28 сидел на транзите через Россию, а «Путь» показывал «Напрямую», в
        // «Сервер» и «Пинг» стояли прочерки (скриншот + диаг-лог #71). Интерфейс уверенно говорил не то.
        GoTunnel.connectedRoute = route
        GoTunnel.connectedServerHost = serverHost
        announce(getString(R.string.mayak_status_probing))
        // Ожидание ОБЪЯСНЯЕМ, а не просто держим надпись.
        //
        // На ПОСЛЕДНЕЙ ступени бюджет пробы — 6 попыток × 5 с + 5 пауз × 2 с = до 40 секунд, и всё
        // это время под кнопкой стояла одна и та же строка «Проверяем, что интернет пошёл…». Причина
        // задержки при этом штатная и известная: пир заводится на выходе следующим поллингом агента
        // (до 15 с), то есть ждать НАДО. Но неподвижная надпись полминуты читается как зависание —
        // человек жмёт кнопку ещё раз (тап = ОТМЕНА) и уходит с мыслью «не работает».
        // Поэтому через PATIENCE_MS говорим ровно то, что происходит, и называем срок.
        val patience = lifecycleScope.launch {
            delay(PATIENCE_MS)
            setStatus(getString(R.string.mayak_status_peer_sync))
            statusShownAt = SystemClock.elapsedRealtime() // следующую надпись держим читаемой, как обычно
        }
        return try {
            if (hasNextRung) probeUntilThreshold(peerSyncSlackMs) else probeWithRetry()
        } finally {
            patience.cancel()
        }
    }

    /**
     * Показать шаг подключения так, чтобы его успели ПРОЧИТАТЬ.
     *
     * Зачем: шаги лестницы иногда сменяются быстрее, чем человек читает. У владельца 2026-07-28
     * «пробую через Россию» и «подключено» разделяли 200 мс — он увидел, что надпись была, но что
     * именно там написано, разобрать не смог. Надпись, которую нельзя прочесть, не информирует, а
     * тревожит: человек понимает только, что «что-то пошло не так».
     *
     * Поэтому перед сменой надписи досиживаем остаток [STATUS_HOLD_MS] от предыдущей. Цена — до
     * полутора секунд на переход, и только когда переход реально случился (обычный случай — прямой
     * путь с первого раза — не платит ничего).
     */
    private suspend fun announce(text: String) {
        holdStatus()
        setStatus(text)
        statusShownAt = SystemClock.elapsedRealtime()
    }

    /** Досидеть остаток времени показа текущей надписи (перед сменой на следующую или перед успехом). */
    private suspend fun holdStatus() {
        if (statusShownAt == 0L) return
        val left = STATUS_HOLD_MS - (SystemClock.elapsedRealtime() - statusShownAt)
        if (left > 0) delay(left)
    }

    /**
     * Ждём egress по уже поднятому туннелю, но не дольше порогов [FallbackDecision].
     * Возвращает выходной IP или null, если за отведённое время путь себя не подтвердил.
     */
    private suspend fun probeUntilThreshold(peerSyncSlackMs: Long = 0L): String? {
        val started = SystemClock.elapsedRealtime()
        if (peerSyncSlackMs > 0) {
            android.util.Log.i(PROBE_TAG, "конфиг свежий — не сдаюсь раньше ${peerSyncSlackMs}мс (пир едет на ноду)")
        }
        // Момент ПЕРВОГО увиденного хендшейка. От него, а не от подъёма туннеля, отсчитывается время
        // на подтверждение выхода: иначе медленное рукопожатие съедает бюджет пробы и мы бросаем
        // рабочий путь (живой случай 2026-07-28, диаг-лог #63 — см. FallbackDecision).
        var handshakeAt: Long? = null
        // Проба живёт СВОЕЙ жизнью, а мы опрашиваем состояние короткими тактами.
        //
        // Почему не «ждать пробу, потом посмотреть на рукопожатие» (так было в 0.3.79): ожидание
        // пробы съедало весь бюджет, и рукопожатие мы ЗАМЕЧАЛИ только после него. В диаг-логе
        // владельца #72 это видно прямо: «рукопожатие за 6001мс» и «6002мс» — ровно бюджет 6000,
        // хотя само рукопожатие прошло раньше. Из-за этого каждая ступень стоила 11 с вместо шести,
        // а на трёх ступенях набегало полминуты «Подключаюсь…» — то самое, что мы и чинили.
        var running: kotlinx.coroutines.Deferred<String?>? = null
        try {
            while (true) {
                // Хендшейк читаем из статистики движка (JNI) — не на главном потоке.
                val handshake = withContext(Dispatchers.IO) { tunnel.hasHandshake() }
                val elapsed = SystemClock.elapsedRealtime() - started
                if (handshake && handshakeAt == null) {
                    handshakeAt = elapsed
                    android.util.Log.i(PROBE_TAG, "рукопожатие за ${elapsed}мс — отсюда ${FallbackDecision.NO_EGRESS_MS}мс на проверку выхода")
                }
                if (FallbackDecision.shouldSwitch(elapsed, handshakeAt, peerSyncSlackMs)) {
                    android.util.Log.i(PROBE_TAG, "UDP не пошёл за ${elapsed}мс (рукопожатие=$handshakeAt) → следующая ступень")
                    return null
                }
                // Проба одна за раз; закончилась — забираем результат и при неудаче заводим следующую.
                // Блокирующий резолв внутри отменить нельзя (см. awaitAtMost), поэтому мы его и не
                // ждём: задача досидит своё сама, а мы продолжаем считать время.
                val done = running?.takeIf { it.isCompleted }
                if (done != null) {
                    val ip = runCatching { done.await() }.getOrNull()
                    if (ip != null) return ip // UDP работает — запасной канал не нужен
                    running = null
                }
                if (running == null) running = probeScope.async { probe.externalIp() }
                delay(PROBE_POLL_MS)
            }
        } finally {
            running?.cancel() // best-effort: держать осиротевшую пробу незачем
        }
    }

    /**
     * Ждать пробу НЕ ДОЛЬШЕ [ms] — по-настоящему, а не на словах.
     *
     * Почему нельзя просто `withTimeoutOrNull { probe.externalIp() }` (так было до 2026-07-29):
     * внутри пробы — блокирующий `HttpURLConnection`, и его `connectTimeout`/`readTimeout` НЕ
     * покрывают резолв имени. При мёртвом туннеле системный резолвер уходит В туннель и перебирает
     * серверы ~28 с. Отмена корутины кооперативная: `withContext(Dispatchers.IO)` не вернётся, пока
     * блокирующий вызов не отработает сам, поэтому таймаут молча ждал вместе с ним.
     *
     * Чем это кончалось у людей (диаг-лог владельца #71, Мегафон, 0.3.78): бюджет на проверку выхода
     * 5 с, а уход на следующую ступень случился через 29 034 мс — «UDP не пошёл за 29034мс». Человек
     * полминуты смотрел на «Подключаюсь…», хотя транзит рядом поднимался за секунду. Порог считался
     * ПРАВИЛЬНО и тест на него был зелёный ([FallbackDecision]) — он проверял намерение, а не эффект.
     *
     * Решение: пробу запускаем ОТДЕЛЬНОЙ задачей в своей области видимости, а ждём её `await()` —
     * это настоящая точка приостановки, её таймаут прерывает мгновенно. Осиротевший поток досидит
     * свой DNS сам и умрёт; нас он больше не держит, результат его нам не нужен.
     */
    /**
     * Переключение на запасной канал: гасим туннель, поднимаем локальный шим (AWG внутри обычного HTTPS
     * к нашему сайту) и поднимаем ТОТ ЖЕ конфиг с Endpoint'ом на шим.
     *
     * Туннель обязательно опускаем: `GoBackend.setState(UP)` при уже поднятом туннеле — no-op
     * («Tunnel already up»), новый конфиг просто не применился бы. Шим стартуем ПОСЛЕ down(): он и так
     * подключается лениво, на первой датаграмме от движка, то есть уже при живом VpnService — иначе
     * `protect()` вернул бы false и мы бы отказались подключаться (защита от петли).
     */
    private suspend fun switchToFallback(conf: String, fb: Fallback): String? {
        // Запасной канал имеет смысл, только если сеть у телефона ЕСТЬ. Без неё объявление «прямой
        // путь не идёт, включаю запасной» — вранье про наш транспорт вместо простого «нет связи».
        if (!MayakNet.hasNetwork(this)) return null
        announce(getString(R.string.mayak_status_fallback_switch))
        runCatching { tunnel.down() }
        // Имя моста разрешаем ЗДЕСЬ — туннель уже опущен, а под поднятым туннелем системный резолвер
        // пошёл бы через него, то есть через путь, который как раз и не работает. Защитить резолвер
        // мы не можем (он не наш сокет), поэтому дальше соединяемся по IP; имя остаётся для TLS.
        val bridgeIp = withContext(Dispatchers.IO) { resolveBridgeHost(fb.url, fb.ip) }
        val local = MayakFallbackTransport.start(fb, bridgeIp) ?: return null // не пригоден/не поднялся — молча остаёмся ни с чем
        val up = runCatching { tunnel.up(prepareConf(org.amnezia.awg.mayak.core.ConfRenderer.withEndpoint(conf, local))) }
        if (up.isFailure) { MayakFallbackTransport.stop(); return null }
        // Метка пути и сервер — ПОСЛЕ подъёма: down() выше (и внутри up()) сбрасывает состояние
        // подключения. Сервер для пинга у запасного канала — ХОСТ МОСТА, а не адрес ноды; без него в
        // подробностях оставались прочерки в «Сервер» и «Пинг» (жалоба владельца 2026-07-28).
        GoTunnel.connectedRoute = GoTunnel.ROUTE_FALLBACK
        GoTunnel.connectedServerHost = fb.ip.ifBlank {
            runCatching { java.net.URI(fb.url).host }.getOrNull().orEmpty()
        }.ifBlank { null }
        // Подтверждение по запасному каналу — КОРОТКОЕ (FALLBACK_PROBE_ATTEMPTS), а не полный набор.
        // Раньше здесь стоял полный (~34 с), и когда мост недостижим, человек смотрел на «Подключаюсь…»
        // почти минуту: сначала UDP-фаза, потом ещё полминуты проб по мёртвому резерву (разбор 2026-07-27).
        // Путь до моста короткий и наш: если за две пробы выход не подтвердился — он и не подтвердится,
        // а честный отказ через 10 с полезнее молчания через 60.
        val ip = probeWithRetry(attempts = FALLBACK_PROBE_ATTEMPTS)
        if (ip == null) { MayakFallbackTransport.stop(); return null }
        GoTunnel.connectedViaFallback = true
        return ip
    }

    /**
     * Адрес хоста моста, разрешённый ПОКА ТУННЕЛЬ ОПУЩЕН. Сначала DoH (оператор подменяет DNS нашего
     * домена — с этого начинались все мобильные блокировки), при неудаче обычный резолвер: он тоже
     * годится, пока VPN не поднят, и лучше, чем остаться совсем без адреса. null — не разрешили;
     * тогда шим попробует сам, и если туннель уже поднят — упрётся в тот самый замкнутый круг.
     */
    private fun resolveBridgeHost(url: String, given: String): String? {
        if (given.isNotBlank()) {
            android.util.Log.i(PROBE_TAG, "адрес моста из выдачи ядра: $given (резолв не нужен)")
            return given
        }
        val host = runCatching { java.net.URI(url).host }.getOrNull() ?: return null
        runCatching {
            org.amnezia.awg.mayak.core.DohResolver.resolveHost(host).takeIf { it != host }
        }.getOrNull()?.let {
            android.util.Log.i(PROBE_TAG, "адрес моста по DoH: $host → $it")
            return it
        }
        return runCatching { java.net.InetAddress.getByName(host).hostAddress }.getOrNull()?.also {
            android.util.Log.i(PROBE_TAG, "адрес моста обычным резолвером: $host → $it (DoH не ответил)")
        }
    }

    /** Тумблер «Не использовать IPv6» (SPEC-0014 T5): при выкл срезаем v6 из .conf перед подъёмом
     *  туннеля (без ::/0 → IPv6 идёт мимо туннеля, значок не зажигается). По умолч. IPv6 ВКЛ. */
    private fun maybeStripIpv6(conf: String): String =
        if (MayakPrefs.useIpv6(this)) conf else org.amnezia.awg.mayak.core.ConfRenderer.stripIpv6(conf)

    /** Готовит .conf к подъёму туннеля: сначала IPv6-тумблер (SPEC-0014), затем split-туннель
     *  (SPEC-0018 F1 — выбранные приложения мимо/только-в туннель). Оба — трансформы строки конфига
     *  из настроек пользователя; применяются к обоим плечам (direct/relay). Пустой список split — no-op. */
    private fun prepareConf(conf: String): String {
        // split-туннель по АКТИВНОМУ пресету (SPEC-0028): тумблер пресета ВКЛ → режим+приложения пресета,
        // иначе весь трафик в VPN. Пресеты синхронизируются с ядра (системные+свои).
        val (apps, excluded) = org.amnezia.awg.mayak.MayakPresets.effectiveSplit(this)
        return org.amnezia.awg.mayak.core.ConfRenderer.withSplitTunnel(maybeStripIpv6(conf), apps, excluded)
    }

    /** Фоновая IPv6-проба выхода после коннекта: значок «IPv6» зажигаем ТОЛЬКО при реальном egress
     *  (api6.ipify.org вернул адрес). Не блокирует коннект (v4 уже подтверждён). Честно (SPEC-0014). */
    /**
     * Фоновая проба IPv6-выхода. Живёт до ~12 с (6 попыток с паузами), поэтому ОБЯЗАНА быть привязана
     * к своему подключению.
     *
     * Чем это кончилось без привязки (диаг-лог владельца #64, 0.3.74): человек переподключался
     * несколько раз подряд. Проба от ПРЕДЫДУЩЕГО подключения продолжала работать в промежутке, когда
     * туннель уже опущен, — и в этот момент честно дотянулась до интернета по НАТИВНОМУ IPv6 оператора
     * (`2a03:d000:…`, Мегафон). Вернулась она уже после подъёма нового туннеля, увидела
     * `connState == CONNECTED` и записала бы этот адрес как НАШ выходной IPv6.
     *
     * Цена ошибки максимальная из возможных: на экране «Выходной IPv6» показался бы собственный адрес
     * человека, то есть ровно картина утечки — при том что утечки нет. А в обратную сторону это
     * прикрыло бы настоящую утечку, если бы она была. У нашего NL-выхода IPv6 нет вовсе
     * (VPSVille не маршрутизирует блок), поэтому ЛЮБОЙ IPv6-ответ через этот туннель — заведомо мимо него.
     *
     * Поэтому: старую пробу отменяем, а результат применяем, только если поколение подключения не
     * сменилось. Одной отмены мало — корутина может дойти до записи между отменой и проверкой.
     */
    private fun startIpv6Probe() {
        ipv6ProbeJob?.cancel()
        GoTunnel.egressIpv6 = null
        setIpv6Badge(false)
        if (!MayakPrefs.useIpv6(this)) {
            android.util.Log.i(PROBE_TAG, "IPv6-проба ПРОПУЩЕНА: тумблер «Использовать IPv6» ВЫКЛючен") // диаг
            return // пользователь выключил IPv6 — не пробуем и не зажигаем
        }
        val gen = connGeneration // поколение ЭТОГО подключения — результат чужого сюда не попадёт
        ipv6ProbeJob = lifecycleScope.launch {
            // РЕТРАИМ (как v4-пробу): v6-выход МОЖЕТ не пройти с первого раза даже когда IPv6 реально работает —
            // AAAA-резолв через туннель, прогрев conntrack NAT66 на экзите, лаг соты. Одиночная проба давала
            // ЛОЖНОЕ «нет IPv6» на всю сессию (баг 2026-07-07: диаг-лог #30 v6 есть, #31 нет; на ноде IPv6 жив).
            val v6 = probeWithRetry(probe6, IPV6_PROBE_ATTEMPTS) // api6-only host: успех = реальный IPv6-выход
            android.util.Log.i(PROBE_TAG, // диаг: итог v6-пробы (причины провала каждой попытки — в IpifyProbe)
                if (v6 != null) "IPv6-проба OK: $v6 (поколение $gen)"
                else "IPv6-проба НЕ прошла ($IPV6_PROBE_ATTEMPTS попыток, поколение $gen)")
            if (gen != connGeneration) {
                android.util.Log.i(PROBE_TAG, "результат IPv6-пробы отброшен: подключение сменилось ($gen → $connGeneration)")
                return@launch
            }
            if (v6 != null && connState == ConnState.CONNECTED) {
                GoTunnel.egressIpv6 = v6
                setIpv6Badge(true)
                renderEgress() // показать выходной IPv6 рядом с IPv4 (SPEC-0014)
                // Обновляем уведомление — теперь с честным значком IPv6.
                MayakNotification.show(this@MayakActivity, GoTunnel.connectedLabel, GoTunnel.connectedPingMs)
            }
        }
    }

    /** Показ выходных адресов: «IP: <v4>» и, если IPv6 реально работает, второй строкой «IPv6: <v6>». */
    private fun renderEgress() = runOnUiThread {
        val v4 = GoTunnel.egressIpv4 ?: return@runOnUiThread
        val v6 = GoTunnel.egressIpv6
        ipView?.text = if (v6 != null)
            getString(R.string.mayak_ip_label, v4) + "\n" + getString(R.string.mayak_ip6_label, v6)
        else getString(R.string.mayak_ip_label, v4)
    }

    /** Показать/скрыть значок «IPv6» на главном экране (с тонким fade при появлении). */
    private fun setIpv6Badge(on: Boolean) = runOnUiThread {
        ipv6Badge?.let {
            if (on && it.visibility != View.VISIBLE) { it.visibility = View.VISIBLE; fadeIn(it) }
            else if (!on) it.visibility = View.GONE
        }
    }

    /**
     * Значок маршрута на главном: показываем, если идём НЕ напрямую.
     *
     * Прямой путь — норма, его подписывать нечем. А вот транзит через Россию и запасной канал человек
     * обязан видеть: у них другие задержки и свои помехи. Раньше отличался только запасной канал, и
     * владелец 2026-07-28 оказался на транзите, не подозревая об этом, — узнал лишь из разбора лога.
     */
    private fun setRouteBadge(route: String) = runOnUiThread {
        val badge = fallbackBadge ?: return@runOnUiThread
        val text = when (route) {
            GoTunnel.ROUTE_RELAY -> getString(R.string.mayak_route_relay_badge)
            GoTunnel.ROUTE_FALLBACK -> getString(R.string.mayak_fallback_badge)
            else -> null
        }
        if (text == null) { badge.visibility = View.GONE; return@runOnUiThread }
        badge.text = text
        if (badge.visibility != View.VISIBLE) { badge.visibility = View.VISIBLE; fadeIn(badge) }
    }

    /** Человекочитаемое имя маршрута для подробностей подключения. */
    private fun routeLabel(route: String): String = getString(
        when (route) {
            GoTunnel.ROUTE_RELAY -> R.string.mayak_route_relay
            GoTunnel.ROUTE_FALLBACK -> R.string.mayak_route_fallback
            else -> R.string.mayak_route_direct
        }
    )

    /** Несколько попыток egress-пробы (пир появляется на сервере не сразу; v6-выход может «прогреться» позже).
     *  По умолчанию v4-проба (probe, PROBE_ATTEMPTS); v6-проба зовёт с probe6 и IPV6_PROBE_ATTEMPTS. */
    private suspend fun probeWithRetry(p: IpifyProbe = probe, attempts: Int = PROBE_ATTEMPTS): String? {
        repeat(attempts) { attempt ->
            val ip = org.amnezia.awg.mayak.core.awaitAtMost(probeScope, PROBE_ATTEMPT_MS) { p.externalIp() }
            if (ip != null) return ip
            if (attempt < attempts - 1) delay(PROBE_DELAY_MS)
        }
        return null
    }

    /**
     * @param d направление, К КОТОРОМУ реально поднялся туннель. Берём именно его, а НЕ глобальный
     * `selectedDir`: пока шёл коннект к A, пользователь мог ткнуть страну B (во время CONNECTING тап
     * лишь меняет выбор, переключения не делает) — и по завершении коннекта метка/`connectedDir`
     * говорили бы «B», хотя трафик идёт через A. Хуже: следующий тап по B попадал на гард
     * «уже подключены к B» → no-op, и добраться до B из списка становилось нельзя вообще.
     */
    private fun onConnected(ip: String, d: Direction?) = runOnUiThread {
        noTrafficReported = false
        selfHealTried = false
        // Сюда попадают ТОЛЬКО с подтверждённым выходом (проба вернула внешний IP) — это и есть
        // единственное основание сказать «Защищено». Ставим до renderState: он читает состояние живости.
        GoTunnel.liveness = GoTunnel.LIVE_OK
        // Ушли не прямым путём — значит у этого оператора прямой UDP не прошёл. Это ровно тот факт,
        // ради которого мы и разбираем логи («у кого что режут»), а узнавали мы его только когда
        // человек сам жаловался. Заливка тихая и под общим лимитом (не чаще раза в 6ч).
        if (GoTunnel.connectedRoute != GoTunnel.ROUTE_DIRECT) {
            maybeAutoSendDiag("ladder-" + GoTunnel.connectedRoute)
        }
        connState = ConnState.CONNECTED
        renderState(ConnState.CONNECTED)
        MayakPrefs.noteConnect(this) // best-effort счётчики для тихого телеметри-бикона (не-ПДн агрегаты)
        // Подключились через запасной канал → честная пометка на главном + счётчик в бикон (владельцу
        // важно знать, у скольких людей UDP уже не проходит: это сигнал о цензуре, а не украшение).
        setRouteBadge(GoTunnel.connectedRoute)
        if (GoTunnel.connectedViaFallback) MayakPrefs.noteFallbackConnect(this)
        GoTunnel.egressIpv4 = ip // персистим выходной IPv4 (показ переживает пересоздание Activity)
        // таймер/IP появляются с лёгким fade (не резким visibility).
        renderEgress() // IP-адреса теперь НЕ на главном (правка владельца: в детали) — mayak_ip остаётся скрытым
        timerView?.let { fadeIn(it) }
        successHaptic()
        startTimer()
        startPing() // пинг сервера текущего подключения
        startKeepalive() // продление аренды overlay-IP, пока туннель поднят (SPEC-0015)
        startIpv6Probe() // фоновая проба IPv6-выхода → честный значок «IPv6»
        // Постоянное уведомление «Подключено» (флаг+направление); метку персистим в GoTunnel (процесс-
        // скоупно) — на повторном открытии покажем то же направление.
        connectedDir = d // направление ЖИВОГО туннеля — из аргумента, не из мутабельного выбора в списке
        GoTunnel.connectedDirectionId = d?.id // надёжный процесс-скоупный источник активной страны (для показа её пинга без hairpin)
        // Точка «идёт трафик» — ПОСЛЕ того, как активное направление стало известно. renderState выше
        // отрисовал круг раньше этой строки, и тогда активной страны ещё не было: на эмуляторе точка
        // не зажглась вовсе, хотя туннель стоял. Отрисовка состояния и знание «куда» приходят в
        // РАЗНЫЕ моменты — поэтому дёргаем оба раза.
        refreshConnectedDots()
        GoTunnel.connectedLabel = MayakNotification.labelFor(this, d)
        // id — чтобы безголовый подъём (плитка/Always-On) мог взять ГОТОВОЕ имя, а не код (см. MayakPrefs).
        // Направление тут может быть неизвестно (d == null) — тогда −1: метка есть, переиспользовать её нельзя.
        MayakPrefs.setLastConnLabel(this, GoTunnel.connectedLabel, d?.id ?: -1L)
        MayakNotification.show(this, GoTunnel.connectedLabel, GoTunnel.connectedPingMs)
        Toast.makeText(this, getString(R.string.mayak_connected), Toast.LENGTH_SHORT).show()
        // Туннель поднялся — значит связь ЕСТЬ. Второй из трёх поводов заглянуть в ящик (SPEC-0047):
        // у части людей до ядра иначе не достучаться вовсе, и это единственный надёжный момент.
        syncMessages()
    }

    // ⛔ Разговора про «постоянное подключение» после первого коннекта здесь БОЛЬШЕ НЕТ (решение
    // владельца 10-08: «и без этого всё нормально работает, а просить пользователя, особенно
    // нового, что-то делать — не очень хорошая мысль»). Лист выпрыгивал сразу после первой
    // удачи и уводил человека в системные настройки Android. Сама возможность никуда не делась:
    // кнопка «Без защиты — без интернета» живёт в «Настройках» и открывает тот же системный
    // раздел — но её человек нажимает САМ, когда захочет, а не по нашей просьбе.

    /**
     * Знакомство при первом входе: три коротких карточки о том, что это и как пользоваться.
     *
     * Показывается ДО первого подключения и ровно один раз. Это ЕДИНСТВЕННЫЙ лист, который человек
     * видит сам собой: разговор про постоянное подключение, приезжавший после первого коннекта,
     * снят 10-08 — просить нового человека лезть в системные настройки мы не будем.
     */
    private fun maybeShowOnboarding() {
        if (!Onboarding.shouldShow(
                alreadyShown = MayakPrefs.onboardingShown(this),
                signedIn = session.hasToken(),
                connectCount = MayakPrefs.connectCount(this),
            )
        ) return
        MayakPrefs.setOnboardingShown(this) // помечаем СРАЗУ: показали — значит показали, второй раз не надо
        val view = layoutInflater.inflate(R.layout.sheet_mayak_onboarding, null)
        val sheet = com.google.android.material.bottomsheet.BottomSheetDialog(this)
        sheet.setContentView(view)
        if (reducedMotion()) sheet.window?.setWindowAnimations(0)
        view.findViewById<View>(R.id.mayak_onboarding_done).setOnClickListener {
            MayakHaptics.tap(it)
            sheet.dismiss()
        }
        sheet.show()
    }

    /** Отклик на подтверждённое подключение. Тумблер «Отклик вибрацией» смотрит MayakHaptics. */
    private fun successHaptic() {
        val v = connectCircle ?: return
        MayakHaptics.stateChanged(v, connected = true)
    }

    private fun fadeIn(v: View) {
        if (reducedMotion()) { v.alpha = 1f; return }
        v.alpha = 0f
        v.animate().alpha(1f).setDuration(220).start()
    }

    private fun fail(message: String) = runOnUiThread {
        connState = ConnState.DISCONNECTED
        connectedDir = null
        // Провал подключения — это ПОЛНАЯ остановка, а не только надпись на экране.
        //
        // Аудит 2026-07-31: после провала в шторке продолжало висеть «Защищено», хотя tun0 в системе
        // не было вовсе. Виноват был этот метод: он менял текст и уходил, а циклы ПРОШЛОГО подключения
        // (пинг, скорость, аренда) продолжали крутиться — и пинг-цикл каждые 5 с заново публиковал
        // уведомление. Отключение (disconnect) всё это гасило, провал — нет.
        stopTimer()
        stopPing()
        stopKeepalive()
        MayakNotification.clear(this)
        renderState(ConnState.DISCONNECTED)
        setStatus(message)
        errorShownAt = SystemClock.elapsedRealtime() // с этого момента надпись «протухает» (см. onResume)
        // Диагностика уходит сама (ниже), но человеку в моменте это не помогает — на экране только
        // фраза. «Отправить лог» — на самом дне «Настроек», найти его в моменте отказа неоткуда
        // (находка 2026-08-03). Тап по этой же надписи — путь туда, значок подсказывает, что можно тапнуть.
        errorHelpAvailable = true
        setStatusInfoIcon(true)
        Toast.makeText(this, message, Toast.LENGTH_LONG).show() // ошибку показываем попапом — её надо заметить
        maybeAutoSendDiag() // авто-заливка диаг-лога на ошибку подключения (тихо, rate-limited) — 0.3.48
    }

    /**
     * Авто-заливка диагностики при ОШИБКЕ подключения (0.3.48). Мотивация: регрессия коннекта раньше
     * всплывала только когда пользователь сам жал «Отправить лог» — теперь лог с source="auto" уходит
     * сам. Строго: (1) rate-limited (MayakPrefs/AutoDiagGate) — чтобы шквал ошибок не породил шквал
     * заливок, а провальная попытка не сжигала 6-часовой лимит впустую (0.3.99); (2) требует входа и
     * backend (как ручная кнопка) — иначе тихо пропускаем; (3) полностью тихо (без UI) и БЕЗ
     * ретраев; любой сбой глотаем (диагностика не должна ронять UI). Несданный лог с прошлого раза
     * при этом не теряем — DiagLogPending.flush пробует дослать его первым.
     */
    private fun maybeAutoSendDiag(reason: String = "connect-error") {
        if (!::session.isInitialized || !session.hasToken()) return
        val b = backend ?: return
        if (!MayakPrefs.autoDiagDue(this)) return // слишком часто — пропускаем
        MayakPrefs.noteAutoDiagAttempt(this) // короткий анти-шквальный зазор — ставим ДО сети, независимо от исхода
        val dirName = selectedDir?.name ?: ""
        lifecycleScope.launch {
            try {
                DiagLogPending.flush(this@MayakActivity, session, b) // сначала дошлём то, что осталось с прошлого раза
                val req = DiagCollector.collect(this@MayakActivity, direction = dirName, deviceId = session.deviceId(), source = "auto", reason = reason, tunnel = tunnel)
                session.sendDiagLog(b, req)
                MayakPrefs.noteAutoDiagSuccess(this@MayakActivity) // 6-часовой лимит тратится ТОЛЬКО на успехе
            } catch (_: Exception) { /* тихо: авто-диагностика best-effort, без ретраев/краша */ }
        }
    }

    /**
     * Молча переподключиться, когда туннель поднят, а трафика нет.
     *
     * Зачем: самый частый способ остаться без интернета — не «не подключилось», а «подключилось и
     * тихо умерло». Живой случай 2026-07-29: у устройства истекла аренда оверлей-адреса (телефон
     * спал, продления не уходили), жнец её освободил, и пир сняли с выхода — приложение показывало
     * «Защищено», а не открывалось ничего. Человеку предлагали переподключиться вручную; теперь
     * делаем это сами — новый /connect выдаёт свежий конфиг и заново заводит пира.
     *
     * Осторожность, оплаченная кровью (0.3.76/0.3.77): переподнимать туннель в ФОНЕ нельзя — Android
     * убивает процесс в паузе между DOWN и UP, и человек получает «VPN сам выключился». Поэтому
     * лечимся ТОЛЬКО при открытом экране (RESUMED) и ровно один раз на подключение: если не помогло,
     * второй заход тем более не поможет, а мигать туннелем по кругу — худшее, что можно сделать.
     */
    private fun maybeSelfHeal() {
        if (selfHealTried) return
        if (!lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED)) return
        // Лечить нечего, когда у телефона нет сети: переподъём гарантированно провалится, а туннель
        // мы при этом уроним — и трафик пойдёт в открытую сеть, как только связь вернётся. Ждём сеть.
        if (!MayakNet.hasNetwork(this)) return
        val d = connectedDir ?: return
        selfHealTried = true
        android.util.Log.i(PROBE_TAG, "трафика нет — переподключаюсь сам (направление ${d.code})")
        // Туннель ОБЯЗАТЕЛЬНО гасим ПЕРЕД перевыпуском конфига. Иначе замкнутый круг: за новым
        // конфигом приложение идёт к ядру по HTTPS, а весь трафик уходит в туннель — тот самый,
        // который не работает. Запрос отваливается по таймауту, срабатывает офлайн-фолбэк «последний
        // рабочий конфиг», и мы поднимаем ровно ту же мёртвую конфигурацию.
        // Проверено вживую 2026-07-29 на эмуляторе: с поднятым туннелем самолечение отработало и НЕ
        // помогло (в ядре не появилось ни одной новой выдачи конфига), с опущенным — лечит.
        // Трафик в это время наружу не утекает: он и так никуда не идёт, туннель мёртв.
        lifecycleScope.launch {
            runCatching { tunnel.down() }
            connectTo(d)
        }
    }

    private fun disconnect() {
        renderState(ConnState.CONNECTING)
        lifecycleScope.launch {
            runCatching { tunnel.down() }
            connGeneration++; ipv6ProbeJob?.cancel()
            stopTimer()
            stopPing()
            stopKeepalive()
            MayakNotification.clear(this@MayakActivity)
            connState = ConnState.DISCONNECTED
            connectedDir = null
            renderState(ConnState.DISCONNECTED)
            setStatus(getString(R.string.mayak_status_disconnected))
        }
    }

    /** Применяет визуальное состояние круга/иконки/статуса/таймера + анимацию (пульс/glow). */
    private fun renderState(state: ConnState) = runOnUiThread {
        // Отклик вибрацией на ОТКЛЮЧЕНИЕ — только на настоящем переходе «было подключено → стало нет».
        // Без этого условия телефон вибрировал бы на каждом холодном старте: renderState(DISCONNECTED)
        // зовётся и при первой отрисовке экрана, когда ничего не отключалось.
        val wasConnected = connState == ConnState.CONNECTED
        if (wasConnected && state == ConnState.DISCONNECTED) {
            connectCircle?.let { MayakHaptics.stateChanged(it, connected = false) }
        }
        connState = state // единый источник истины: connState всегда синхронен с отрисованным состоянием
        refreshConnectedDots() // точка «идёт трафик» в списке живёт по тому же состоянию, что и круг
        val circleBg = when (state) {
            ConnState.DISCONNECTED -> R.drawable.mayak_circle_disconnected
            ConnState.CONNECTING -> R.drawable.mayak_circle_connecting
            ConnState.CONNECTED -> R.drawable.mayak_circle_connected
        }
        connectCircle?.setBackgroundResource(circleBg)
        val iconTint = when (state) {
            ConnState.CONNECTED -> R.color.mayak_circle_icon_on
            else -> R.color.mayak_circle_icon_off
        }
        connectIcon?.let {
            ImageViewCompat.setImageTintList(it, ContextCompat.getColorStateList(this, iconTint))
        }
        // contentDescription круга меняется по состоянию (доступность, см. дизайн-ревью §3.6).
        connectCircle?.contentDescription = getString(
            when (state) {
                ConnState.DISCONNECTED -> R.string.mayak_a11y_connect
                ConnState.CONNECTING -> R.string.mayak_a11y_connecting
                ConnState.CONNECTED -> R.string.mayak_a11y_disconnect
            }
        )
        when (state) {
            ConnState.DISCONNECTED -> {
                stopPulse()
                stopGlowBreath()
                setGlow(0f)
                rippleView?.stopWaves()
                networkBg?.setConnected(false)
                timerView?.visibility = View.GONE
                ipView?.visibility = View.GONE
                pingView?.visibility = View.GONE
                ipv6Badge?.visibility = View.GONE
                fallbackBadge?.visibility = View.GONE
                if (::status.isInitialized) status.text = getString(R.string.mayak_status_disconnected)
                setStatusInfoIcon(false)
                errorHelpAvailable = false // «Не защищено» — обычное состояние, а не отказ, подсказка не нужна
            }
            ConnState.CONNECTING -> {
                startPulse()
                setGlow(0.35f)
                rippleView?.startWaves() // от кнопки расходятся волны (sonar/активация)
                if (::status.isInitialized) status.text = getString(R.string.mayak_connecting)
                setStatusInfoIcon(false)
            }
            ConnState.CONNECTED -> {
                stopPulse()
                rampGlow(1f)            // яркая вспышка-ореол
                startGlowBreath()       // затем ровное «дыхание» свечения — круг живой
                rippleView?.bloom()     // финальная вспышка-волна
                networkBg?.setConnected(true) // фон-сеть оживает ярче
                timerView?.visibility = View.VISIBLE
                // НЕ константа «Защищено»: поднятый туннель сам по себе ничего не доказывает. Сюда же
                // приходят при возврате в приложение с давно поднятым (и, может, уже мёртвым) туннелем.
                if (::status.isInitialized) status.text = connectedStatusText()
                setStatusInfoIcon(true) // «ⓘ» рядом с «Подключено» — подсказка: тапни для подробностей
            }
        }
    }

    /**
     * Текст под кнопкой в подключённом состоянии — ровно то, что мы можем ДОКАЗАТЬ.
     *
     * Слово «Защищено» разрешено единственным состоянием — LIVE_OK (подтверждённый трафик). Всё
     * остальное — честные промежуточные формулировки. Источник состояния общий с уведомлением
     * (GoTunnel.liveness), поэтому экран и шторка не могут разойтись во мнениях — а до аудита
     * 2026-07-31 они расходились: на экране ошибка, в шторке «Защищено».
     */
    private fun connectedStatusText(): String = getString(
        when (GoTunnel.liveness) {
            GoTunnel.LIVE_OK -> R.string.mayak_connected
            GoTunnel.LIVE_NO_TRAFFIC -> R.string.mayak_status_no_traffic
            GoTunnel.LIVE_NO_NETWORK -> R.string.mayak_status_no_network
            else -> R.string.mayak_status_checking
        }
    )

    /**
     * Значок «ⓘ» справа от статуса «Подключено» — визуальная подсказка, что по статусу можно тапнуть и
     * открыть «Подробности подключения» (правка владельца 2026-07-06: было непонятно, что статус кликабелен).
     * Рисуем как compound-drawable (сохраняет центрирование текста), масштабируем под 18dp и тинтуем.
     */
    private fun setStatusInfoIcon(show: Boolean) {
        if (!::status.isInitialized) return
        if (show) {
            val size = (18 * resources.displayMetrics.density).toInt()
            val icon = ContextCompat.getDrawable(this, R.drawable.ic_info)?.mutate()?.apply {
                setBounds(0, 0, size, size)
                setTint(ContextCompat.getColor(this@MayakActivity, R.color.mayak_on_bg))
            }
            status.setCompoundDrawablesRelative(null, null, icon, null)
            status.compoundDrawablePadding = (6 * resources.displayMetrics.density).toInt()
        } else {
            status.setCompoundDrawablesRelative(null, null, null, null)
        }
    }

    /** Ровное «дыхание» ореола в состоянии «Под защитой» (alpha 0.7↔1.0, ~2.4с). */
    private fun startGlowBreath() {
        stopGlowBreath()
        val glow = connectGlow ?: return
        if (reducedMotion()) { glow.alpha = 1f; return }
        glowBreath = ObjectAnimator.ofFloat(glow, View.ALPHA, 1f, 0.7f).apply {
            startDelay = 420 // после ramp-вспышки
            duration = 2400
            repeatMode = ObjectAnimator.REVERSE
            repeatCount = ObjectAnimator.INFINITE
            interpolator = AccelerateDecelerateInterpolator()
            start()
        }
    }

    private fun stopGlowBreath() {
        glowBreath?.cancel()
        glowBreath = null
    }

    /**
     * Пульс кольца на «Подключение…»: scale 1.0↔1.08 + alpha, цикл ~1.2с, ease-in-out.
     * Reduced-motion: пульса нет — оставляем статичный круг (статус всё равно меняется текстом).
     */
    private fun startPulse() {
        stopPulse()
        val circle = connectCircle ?: return
        if (reducedMotion()) { circle.scaleX = 1f; circle.scaleY = 1f; circle.alpha = 1f; return }
        pulseAnimator = ObjectAnimator.ofPropertyValuesHolder(
            circle,
            android.animation.PropertyValuesHolder.ofFloat(View.SCALE_X, 1f, 1.08f),
            android.animation.PropertyValuesHolder.ofFloat(View.SCALE_Y, 1f, 1.08f),
            android.animation.PropertyValuesHolder.ofFloat(View.ALPHA, 1f, 0.55f),
        ).apply {
            duration = 1200
            repeatMode = ObjectAnimator.REVERSE
            repeatCount = ObjectAnimator.INFINITE
            interpolator = AccelerateDecelerateInterpolator()
            start()
        }
    }

    private fun stopPulse() {
        pulseAnimator?.cancel()
        pulseAnimator = null
        connectCircle?.let { it.scaleX = 1f; it.scaleY = 1f; it.alpha = 1f }
    }

    /** Мгновенно задать прозрачность ореола (отменив возможную идущую анимацию). */
    private fun setGlow(alpha: Float) {
        connectGlow?.let { it.animate().cancel(); it.alpha = alpha }
    }

    /** Плавно «разгореть» ореол до target (вспышка при connected). Reduced-motion → мгновенно. */
    private fun rampGlow(target: Float) {
        val glow = connectGlow ?: return
        if (reducedMotion()) { glow.alpha = target; return }
        glow.animate().alpha(target).setDuration(400)
            .setInterpolator(AccelerateDecelerateInterpolator()).start()
    }

    // --- таймер сессии ---

    private fun startTimer() {
        // Источник истины по началу сессии — GoTunnel (момент НАШЕГО up(), процесс-скоупный). После
        // пересоздания Activity таймер продолжает считать реальный аптайм подключения, а не «с возврата».
        // Fallback на now() — только если по какой-то причине метка отсутствует (напр. туннель поднят вне up()).
        sessionStartElapsed = GoTunnel.connectedSinceElapsed ?: SystemClock.elapsedRealtime()
        timerJob?.cancel()
        timerJob = lifecycleScope.launch {
            while (isActive) {
                val sec = (SystemClock.elapsedRealtime() - sessionStartElapsed) / 1000
                timerView?.text = formatDuration(sec)
                delay(1000)
            }
        }
    }

    /**
     * Привести UI к фактическому состоянию НАШЕГО туннеля (вызов из onResume). Следим ТОЛЬКО за нашим
     * коннектом: tunnel.isUp() true лишь для туннеля, поднятого через наш backend — VPN другого
     * приложения (Happ и т.п.) сюда не попадёт (наш VpnService при этом погашен → isUp()=false).
     */
    private fun syncConnStateFromTunnel() {
        val target = if (tunnel.isUp()) ConnState.CONNECTED else ConnState.DISCONNECTED
        if (connState == target) {
            // Состояние совпало — но уведомление могло пережить и смерть процесса, и провал коннекта
            // (аудит 2026-07-31: «Защищено» в шторке при отсутствующем tun0). Раз туннеля нет — снимаем
            // его здесь; раньше метод в этой ветке уходил молча, и осиротевшее уведомление жило вечно.
            if (target == ConnState.DISCONNECTED) MayakNotification.clear(this)
            return // дальше — только анимации/рендер, дёргать их зря не нужно
        }
        if (target == ConnState.CONNECTED) {
            startTimer()
            startPing()
            startKeepalive()
            MayakNotification.show(this, GoTunnel.connectedLabel, GoTunnel.connectedPingMs)
        } else {
            stopTimer()
            stopPing()
            stopKeepalive()
            MayakNotification.clear(this)
        }
        renderState(target)
    }

    private fun stopTimer() {
        timerJob?.cancel()
        timerJob = null
        runOnUiThread { timerView?.text = formatDuration(0) }
    }

    /** Периодический пинг сервера ТЕКУЩЕГО подключения (GoTunnel.connectedServerHost) — показываем на
     *  главном экране, пока подключены. Хост персистим в GoTunnel → пинг продолжается и после пересоздания
     *  Activity. Нет хоста (напр. туннель поднят вне приложения) → пинг не показываем. */
    /** Цвет пинга (по порогам владельца): 0 (нет ответа) и >250 мс — красный; 1–150 — зелёный;
     *  151–250 — жёлтый. */
    private fun pingColor(ms: Int): Int = ContextCompat.getColor(
        this,
        when (ms) {
            in 1..150 -> R.color.mayak_ping_green
            in 151..250 -> R.color.mayak_ping_yellow
            else -> R.color.mayak_red
        },
    )

    /**
     * Точка «через эту страну идёт трафик» — по всем видимым строкам сразу.
     *
     * Идём по rowViews, а не по детям контейнера, по той же причине, что и живой пинг ниже: активная
     * строка может жить в плитке «⚡ Рекомендуем», а она контейнеру списка не ребёнок. Зовётся из
     * renderState — то есть из единственного места, где состояние подключения становится видимым;
     * вешать это на каждое присваивание connState (их восемь) значило бы однажды забыть одно.
     */
    private fun refreshConnectedDots() {
        val activeId = if (connState == ConnState.CONNECTED) (GoTunnel.connectedDirectionId ?: connectedDir?.id) else null
        for (row in rowViews) {
            val dot = row.findViewById<View>(R.id.mayak_row_connected_dot) ?: continue
            val on = activeId != null && (row.tag as? Long) == activeId
            dot.visibility = if (on) View.VISIBLE else View.GONE
            dot.contentDescription = if (on) getString(R.string.mayak_row_connected_dot_desc) else null
        }
    }

    private fun startPing() {
        val host = GoTunnel.connectedServerHost
        pingJob?.cancel()
        if (host == null) { pingView?.visibility = View.GONE; return }
        pingView?.visibility = View.VISIBLE
        pingJob = lifecycleScope.launch {
            var misses = 0
            while (isActive) {
                val ms = MayakPing.ping(host)
                GoTunnel.connectedPingMs = ms
                val shown = ms ?: 0 // для шторки: «нет ответа» она рисует своим способом
                pingView?.apply {
                    // Нет ответа — так и пишем («Пинг: —»), а НЕ «Пинг: 0 мс». Ноль миллисекунд —
                    // это утверждение, что сервер отвечает мгновенно, то есть ровно обратное правде;
                    // и именно эта строка стоит на экране в самом коварном случае — «Защищено, а
                    // интернета нет» (снято на эмуляторе 2026-08-07: «Защищено», «Пинг: 0 мс»,
                    // интернет мёртв). Строка mayak_ping_label_na для этого уже была, но нигде не
                    // использовалась. Цвет — тот же «плохой», что и раньше.
                    text = if (ms == null) getString(R.string.mayak_ping_label_na)
                    else getString(R.string.mayak_ping_label, ms)
                    setTextColor(pingColor(shown))
                }
                // Туннель поднят, а трафик через него не идёт — это самый неприятный из возможных
                // исходов: у человека НЕТ интернета вообще (всё уходит в туннель), а приложение
                // спокойно говорит «Защищено». Так бывает, например, если устройство удалили в
                // кабинете: пир снимается с ноды за секунды, туннель остаётся поднятым (проверено
                // вживую 2026-07-27 — «Защищено», «Пинг: 0 мс», интернета нет).
                // Молчать про это нельзя. Сразу рвать тоже плохо: короткий провал бывает на мобильной
                // сети, а обрыв туннеля отправил бы трафик в открытую сеть. Поэтому: несколько
                // подряд неудач → честный статус и предложение переподключиться, туннель не трогаем.
                //
                // Правка после аудита 2026-07-31: пинг-цикл больше не единственный судья. Отсутствие
                // сети он раньше видел так же медленно, как всё прочее (4 промаха = от 20 до 50 с
                // «Защищено» при выключенном Wi-Fi — замерено 42 с), поэтому связь спрашиваем у
                // системы сразу, а вердикт пишем в общее состояние живости, а не в надпись на экране.
                if (ms == null) misses++ else misses = 0
                if (connState == ConnState.CONNECTED) {
                    val live = when {
                        !MayakNet.hasNetwork(this@MayakActivity) -> GoTunnel.LIVE_NO_NETWORK
                        misses == 0 -> GoTunnel.LIVE_OK // сервер ответил — трафик через туннель идёт
                        misses >= PING_MISSES_TO_WARN -> GoTunnel.LIVE_NO_TRAFFIC
                        else -> GoTunnel.liveness // один-два промаха на мобильной сети — норма, не пугаем
                    }
                    MayakLiveness.apply(this@MayakActivity, live) // общее состояние: экран + шторка разом
                    setStatus(connectedStatusText())
                    if (live == GoTunnel.LIVE_NO_TRAFFIC) {
                        // Именно здесь у человека и случается «подключено, а ничего не открывается» —
                        // и именно этот случай авто-заливка раньше НЕ ловила: подключение-то прошло
                        // успешно (жалоба бета-тестера 2026-07-29). Шлём один раз на срабатывание,
                        // дальше сторож молчит до восстановления трафика; сверху ещё лимит в MayakPrefs.
                        if (!noTrafficReported) { noTrafficReported = true; maybeAutoSendDiag("no-traffic") }
                        maybeSelfHeal()
                    } else if (live == GoTunnel.LIVE_OK) {
                        noTrafficReported = false // трафик вернулся сам
                    }
                }
                // Когда включена скорость — уведомление ведёт SpeedNotifier (пинг+скорость, живёт при сворачивании).
                if (connState == ConnState.CONNECTED && !MayakPrefs.showSpeed(this@MayakActivity)) {
                    // ms, а не shown: при отсутствии ответа шторка ПРОПУСКАЕТ строку пинга (так делают
                    // и все остальные вызывающие), вместо того чтобы написать «Пинг: 0 мс».
                    MayakNotification.show(this@MayakActivity, GoTunnel.connectedLabel, ms)
                }
                delay(PING_INTERVAL_MS)
            }
        }
        startSpeed()
    }

    /** Показ скорости передачи (↓/↑) раз в секунду по дельте rx/tx. Цикл крутится ВСЕГДА, пока подключены,
     *  а видимость решает тумблер НА КАЖДОМ тике → включение «Показывать скорость» применяется СРАЗУ, без
     *  переподключения (правка владельца 2026-07-06). */
    private fun startSpeed() {
        stopSpeed()
        speedJob = lifecycleScope.launch {
            var lastRx = -1L
            var lastTx = -1L
            while (isActive) {
                if (MayakPrefs.showSpeed(this@MayakActivity)) {
                    val t = withContext(Dispatchers.IO) { tunnel.transfer() } // JNI getStatistics — не на UI-потоке
                    if (t != null) {
                        val (rx, tx) = t
                        if (lastRx >= 0) {
                            speedView?.visibility = View.VISIBLE
                            speedView?.text = getString(
                                R.string.mayak_speed_fmt,
                                formatSpeed((rx - lastRx).coerceAtLeast(0)),
                                formatSpeed((tx - lastTx).coerceAtLeast(0)),
                            )
                        }
                        lastRx = rx; lastTx = tx
                    }
                } else {
                    speedView?.visibility = View.GONE
                    lastRx = -1L; lastTx = -1L // сброс дельты — при повторном включении первая цифра корректна
                }
                delay(SPEED_INTERVAL_MS)
            }
        }
    }

    private fun stopSpeed() {
        speedJob?.cancel(); speedJob = null
        runOnUiThread { speedView?.visibility = View.GONE }
    }

    /** Скорость в Мбит/с — как во ВСЕХ спидтестах (правка владельца 2026-07-07: раньше показывали МБ/с/
     *  мегабайты, цифра казалась в ~8× меньше спидтеста; человека волнует скорость в битах). bytesPerSec×8.
     *  Малые — с двумя знаками (0.20 Мбит/с), покрупнее — с одним (3.4), от 10 — целые (95 Мбит/с). */
    private fun formatSpeed(bytesPerSec: Long): String {
        val mbit = bytesPerSec * 8.0 / 1_000_000.0
        return when {
            mbit >= 10 -> String.format("%.0f Мбит/с", mbit)
            mbit >= 1 -> String.format("%.1f Мбит/с", mbit)
            else -> String.format("%.2f Мбит/с", mbit)
        }
    }

    private fun stopPing() {
        pingJob?.cancel()
        pingJob = null
        runOnUiThread { pingView?.visibility = View.GONE }
        stopSpeed()
    }

    /** Продление аренды overlay-IP (SPEC-0015) — делегируем ПРОЦЕСС-СКОУПНОМУ LeaseKeepalive, чтобы оно
     *  переживало уничтожение Activity (туннель живёт в процессе, а не в Activity). Идемпотентно. */
    // Сторож живости (MayakLiveness) заводится и гасится ровно там же: он тоже процесс-скоупный и
    // обязан жить, пока жив туннель, — иначе в шторке навсегда застынет последнее слово (аудит 07-31).
    // Там же заводится и частая проверка ящика (MayakMessagesPoll): пока туннель поднят, процесс жив
    // и сеть заведомо есть — это единственное окно, где мы можем доставлять сообщение минутами, а не
    // часами, и без всякого Firebase (пункт 5а разбора 13-08).
    private fun startKeepalive() {
        LeaseKeepalive.start(this); SpeedNotifier.start(this); MayakLiveness.start(this)
        MayakMessagesPoll.start(this)
    }

    private fun stopKeepalive() {
        LeaseKeepalive.stop(); SpeedNotifier.stop(); MayakLiveness.stop()
        MayakMessagesPoll.stop()
    }

    private fun formatDuration(totalSec: Long): String {
        val h = totalSec / 3600
        val m = (totalSec % 3600) / 60
        val s = totalSec % 60
        return String.format("%02d:%02d:%02d", h, m, s)
    }

    // --- helpers ---

    /**
     * Текст отказа для ЧЕЛОВЕКА. Раньше любой отказ ядра показывался как «Ошибка ядра (503): …» —
     * номер статуса человеку не говорит ничего, а «503» читается как «сервис лёг», хотя причина
     * бывает совсем другая и лечится другим действием.
     *
     * Ядро с 03-08 различает причины машинным кодом (`no_line_for_direction`, `pool_exhausted`,
     * `direction_not_allowed`, `overloaded`, …), поэтому известные случаи переводим в понятную
     * фразу С ПОДСКАЗКОЙ, ЧТО ДЕЛАТЬ. Незнакомый код — прежний текст сервера, но уже без «Ошибка
     * ядра (NNN)»: номер уезжает в диагностику, а не на главный экран.
     */
    private fun humanError(e: Throwable): String = when (e) {
        is MayakApiException -> when (e.code) {
            "no_line_for_direction", "pool_exhausted" ->
                getString(R.string.mayak_err_direction_busy)
            "direction_not_allowed" -> getString(R.string.mayak_err_direction_not_in_plan)
            "overloaded" -> getString(R.string.mayak_err_overloaded)
            "billing_unavailable" -> getString(R.string.mayak_err_billing_unavailable)
            else -> e.message ?: getString(R.string.mayak_err_generic)
        }
        // Сетевой отказ — это ДВЕ разные беды с разными лечениями, и путать их нельзя:
        // «у телефона нет интернета» человек чинит сам за секунду, «наш сервер не отвечает» —
        // ждёт или пишет в поддержку. Спрашиваем систему, есть ли связь, и говорим ровно это.
        //
        // Было (проверено на эмуляторе 2026-08-07, Wi-Fi выключен, экран входа): красным под полем
        // «Пароль» — «Ядро недоступно: ни один домен ядра недоступен (2): туннель не поднят —
        // обходить нечего». Три беды в одной строке: слово «ядро» человеку ничего не значит,
        // «обходить нечего» — реплика нашей внутренней механики, а красное поле «Пароль» вдобавок
        // намекает, что человек ошибся паролем, хотя пароль тут ни при чём.
        is IOException -> getString(
            if (MayakNet.hasNetwork(this)) R.string.mayak_err_server_unreachable
            else R.string.mayak_status_no_network
        )
        // Незнакомое исключение показываем ОБЩЕЙ фразой, а подробности отправляем в лог: имя
        // Java-класса («SSLHandshakeException») на экране не помогает никому, кроме нас, а лог
        // приезжает к нам сам (авто-заливка диагностики).
        else -> {
            android.util.Log.w(PROBE_TAG, "необработанная ошибка на экране: ${e.javaClass.name}: ${e.message}")
            getString(R.string.mayak_err_generic)
        }
    }

    /** Отказ по сети (нет интернета / сервер не отвечает) — пароль тут ни при чём. */
    private fun isNetworkFailure(e: Throwable): Boolean = e is IOException && e !is MayakApiException

    private fun copyToClipboard(label: String, text: String) {
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return
        cm.setPrimaryClip(ClipData.newPlainText(label, text))
        Toast.makeText(this, getString(R.string.mayak_details_copied), Toast.LENGTH_SHORT).show()
    }

    /**
     * Лист «Подробности подключения» (тап по статусу/таймеру при активном туннеле). Собирает данные из
     * GoTunnel (процесс-скоупные: IP/пинг/сервер/момент коннекта переживают пересоздание Activity) —
     * поэтому окно корректно и после смены темы/переоткрытия. IP-адреса живут здесь (их убрали с главного).
     */
    private fun showConnectionDetails() {
        if (connState != ConnState.CONNECTED) return
        val view = layoutInflater.inflate(R.layout.sheet_mayak_connection_details, null)
        val sheet = com.google.android.material.bottomsheet.BottomSheetDialog(this)
        sheet.setContentView(view)

        // Направление: флаг + название (живого туннеля, иначе выбранного)
        val d = connectedDir ?: selectedDir
        val flag = view.findViewById<ImageView>(R.id.mayak_det_flag)
        val flagEmoji = view.findViewById<TextView>(R.id.mayak_det_flag_emoji)
        val dirText = view.findViewById<TextView>(R.id.mayak_det_direction)
        if (d != null) {
            // Тем же способом, что список стран (MayakFlags.apply): раньше здесь стоял ТОЛЬКО вектор,
            // и Польша показывала глобус, потому что flag_pl завести забыли. Одно решение на оба экрана.
            MayakFlags.apply(flag, flagEmoji, d.flagCode())
            dirText.text = d.displayLabel()
        } else {
            flag.visibility = View.GONE
            flagEmoji.visibility = View.GONE
            dirText.text = GoTunnel.connectedLabel ?: "—"
        }

        // Пинг (цвет по порогам, как на главном)
        val pingText = view.findViewById<TextView>(R.id.mayak_det_ping)
        val ms = GoTunnel.connectedPingMs
        if (ms != null) {
            // Без слова «Пинг» — оно уже написано подписью строки (аудит 31-07, п. 20).
            pingText.text = getString(R.string.mayak_ping_value, ms)
            pingText.setTextColor(pingColor(ms))
        } else pingText.text = "—"

        // Время сессии — от фактического момента НАШЕГО коннекта (переживает пересоздание Activity)
        val since = GoTunnel.connectedSinceElapsed ?: SystemClock.elapsedRealtime()
        val secs = (SystemClock.elapsedRealtime() - since) / 1000
        view.findViewById<TextView>(R.id.mayak_det_session).text = formatDuration(secs)

        // IPv4 (+ копирование)
        val v4 = GoTunnel.egressIpv4
        view.findViewById<TextView>(R.id.mayak_det_ipv4).text = v4 ?: "—"
        view.findViewById<View>(R.id.mayak_det_ipv4_copy).apply {
            if (v4 != null) setOnClickListener { copyToClipboard("IPv4", v4) } else visibility = View.GONE
        }

        // IPv6 — только когда реально работает через туннель (честно); иначе строку прячем
        val v6 = GoTunnel.egressIpv6
        if (v6 != null) {
            view.findViewById<TextView>(R.id.mayak_det_ipv6).text = v6
            view.findViewById<View>(R.id.mayak_det_ipv6_copy).setOnClickListener { copyToClipboard("IPv6", v6) }
        } else {
            view.findViewById<View>(R.id.mayak_det_ipv6_row).visibility = View.GONE
            view.findViewById<View>(R.id.mayak_det_ipv6_divider).visibility = View.GONE
        }

        // Сервер (хост текущего подключения)
        view.findViewById<TextView>(R.id.mayak_det_server).text = GoTunnel.connectedServerHost ?: "—"
        view.findViewById<TextView>(R.id.mayak_det_route).text = routeLabel(GoTunnel.connectedRoute)

        // Скорость передачи — показываем ЗДЕСЬ тоже, если включён тумблер (правка владельца 2026-07-06:
        // «показывать её везде»). Живое обновление раз в секунду, пока лист открыт; глушим на закрытии.
        val speedRow = view.findViewById<View>(R.id.mayak_det_speed_row)
        val speedDivider = view.findViewById<View>(R.id.mayak_det_speed_divider)
        if (MayakPrefs.showSpeed(this)) {
            val speedText = view.findViewById<TextView>(R.id.mayak_det_speed)
            var lastRx = -1L
            var lastTx = -1L
            val speedJob = lifecycleScope.launch {
                while (isActive) {
                    withContext(Dispatchers.IO) { tunnel.transfer() }?.let { (rx, tx) -> // JNI не на UI-потоке
                        if (lastRx >= 0) speedText.text = getString(
                            R.string.mayak_speed_fmt,
                            formatSpeed((rx - lastRx).coerceAtLeast(0)),
                            formatSpeed((tx - lastTx).coerceAtLeast(0)),
                        )
                        lastRx = rx; lastTx = tx
                    }
                    delay(SPEED_INTERVAL_MS)
                }
            }
            sheet.setOnDismissListener { speedJob.cancel() }
        } else {
            speedRow.visibility = View.GONE
            speedDivider.visibility = View.GONE
        }

        sheet.show()
    }

    private fun setStatus(text: String) = runOnUiThread {
        // Только текст статуса на экране, БЕЗ Toast (правка владельца: промежуточные попапы «проверяю…/
        // туннель поднят…» мельтешат и не читаются). Финальный «Подключено» и ОШИБКИ показываем Toast'ом отдельно.
        if (::status.isInitialized) status.text = text
    }

    /**
     * Убрать протухшую ошибку с экрана (аудит 2026-07-31, п. 17).
     *
     * Было: после провала надпись «Ни один путь не вышел в интернет» оставалась под кнопкой навсегда —
     * сеть вернулась, человек ушёл в настройки и вернулся, открыл приложение назавтра, а его встречает
     * вчерашняя авария. Он думает, что всё сломано, хотя сломано ничего.
     *
     * Ошибка — сообщение о СОБЫТИИ, а не состояние. Она честна ровно столько, сколько человек её
     * читает; дальше честное состояние — «Не защищено». Поэтому при возврате на экран (или его
     * открытии) ошибку старше [ERROR_STALE_MS] заменяем на состояние. Свежую не трогаем: иначе
     * человек, метнувшийся в настройки за split-туннелем, потерял бы причину отказа.
     */
    private fun clearStaleError() {
        if (errorShownAt == 0L || connState != ConnState.DISCONNECTED) return
        if (SystemClock.elapsedRealtime() - errorShownAt < ERROR_STALE_MS) return
        errorShownAt = 0L
        errorHelpAvailable = false
        setStatusInfoIcon(false)
        setStatus(getString(R.string.mayak_status_disconnected))
    }

    /**
     * Тап по надписи об отказе → «Диагностика и помощь» в настройках (находка 2026-08-03, см.
     * `errorHelpAvailable`). Отдельный экран для этого заводить незачем — секция уже есть, её
     * просто не было видно с места отказа; открываем настройки и сразу прокручиваем к ней.
     */
    private fun openErrorHelp() {
        startActivity(
            Intent(this, MayakSettingsActivity::class.java)
                .putExtra(MayakSettingsActivity.EXTRA_OPEN_DIAGNOSTICS, true)
        )
        MayakTransitions.applyAxis(this)
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    companion object {
        const val KEY_SERVER = "server_url" // доступен из настроек для сборки того же HostProvider (диаг-лог)

        /** «Настройки» → «Split-туннель»: та же непомеченная кнопка-пресет на главном, но по имени,
         *  которое видит человек (находка 2026-08-03, docs/research/2026-08-03-app-post-login.md). */
        const val EXTRA_OPEN_SPLIT_TUNNEL = "mayak_open_split_tunnel"

        // SPEC-0031: режимы сортировки списка стран.
        private const val SORT_AUTO = 0   // свежие тихие замеры есть → быстрейший первым, иначе как отдал сервер
        // Значение 1 исторически занято снятым режимом «по клиентскому пингу» (убран 15-08 вместе с
        // пингом напротив стран) — НЕ переиспользовать: у людей оно ещё лежит в prefs и читается как «авто».
        private const val SORT_CUSTOM = 2 // пользовательский порядок (перетаскивание)

        // Адреса ядра, ЗАШИТЫЕ в сборку. Дев (MayakHosts, :core) — генерируется из реестра доменов
        // (админка → «Домены») скриптом scripts/gen-app-hosts.sh. Прод (MayakProdHosts, :ui) — свой
        // buildType prodRelease, BuildConfig.MAYAK_PROD_TARGET (см. MayakHostList, 2026-08-06). Домен
        // идёт первым (LE-серт, системное доверие), прямой IP ядра — последним (свой CA, см.
        // network_security_config + res/raw/mayak_ca.pem — оба тоже per-buildType для прод).
        // Актуальный список приложение подхватывает живьём (MayakHostList.refresh); здесь — то, с чем
        // оно стартует «из коробки» и к чему всегда может вернуться.
        val DEFAULT_HOSTS: List<String> = if (BuildConfig.MAYAK_PROD_TARGET) MayakProdHosts.baked else MayakHosts.baked

        // Адрес кабинета больше не константа: он приходит из реестра доменов вместе с адресами ядра
        // (MayakHostList.cabinetUrl). Зашитый в сборку стартовый адрес — MayakHosts.bakedCabinet.

        // Сервер добавляет пира sync-таймером нод (теперь 5с, было 15с — перф-2026-07-07) → пробуем плотнее:
        // таймаут пробы 4с + пауза 2с ловят пир около t≈5с (было 8+4 → первый ретрай лишь t≈12с). 6 попыток.
        /** Минимальное время показа надписи шага подключения — чтобы её успели прочитать (см. announce). */
        private const val STATUS_HOLD_MS = 1_500L

        /** Через сколько текст ошибки под кнопкой считается протухшим (см. clearStaleError). */
        private const val ERROR_STALE_MS = 30_000L

        /** Сколько ждём молча, прежде чем ОБЪЯСНИТЬ задержку (см. bringUpUdp). 6 с — быстрый путь
         *  (обычный случай, ~5-9 с до «Защищено») успевает закончиться и лишней надписи не показывает. */
        private const val PATIENCE_MS = 6_000L
        private const val PROBE_ATTEMPTS = 6

        // Проб по ЗАПАСНОМУ каналу — меньше, чем по прямому пути. Прямому нужен запас на sync-таймер
        // сервера (пир появляется до ~15 с), а к моменту переключения на резерв это время уже прошло:
        // тут проверяется только сам мост, и он либо отвечает сразу, либо не отвечает вовсе.
        private const val FALLBACK_PROBE_ATTEMPTS = 2
        private const val PROBE_DELAY_MS = 2_000L

        // Такт опроса состояния во время ожидания выхода: рукопожатие и порог смотрим ЧАСТО, а не
        // раз в бюджет. Именно из-за редкого опроса в 0.3.79 рукопожатие «случалось» на 6001 мс
        // (диаг-лог владельца #72) и каждая ступень стоила 11 с вместо шести.
        private const val PROBE_POLL_MS = 250L

        // Потолок ОДНОЙ попытки пробы. Внутри пробы блокирующий резолв: при мёртвом туннеле он
        // сидит ~28 с, и «две попытки по 4 с» превращались в минуту молчания.
        private const val PROBE_ATTEMPT_MS = 5_000L
        // v6-проба фоновая (не блокирует коннект, v4 уже подтверждён) → меньше попыток, чтобы не долбить
        // api6.ipify.org минуту, если IPv6 честно не работает. 4×(таймаут 8с + пауза 4с) ≈ до ~44с.
        private const val IPV6_PROBE_ATTEMPTS = 4
        // Диагностика v6-пробы ОСТАВЛЕНА НАМЕРЕННО (решение владельца 2026-07-07): низкий объём, без ПДн,
        // полезно для дебага. Опц. позже — за скрытый тумблер «Диагностика». (см. docs/APP-BACKLOG.md)
        private const val PROBE_TAG = "AmneziaWG/mayak-probe"

        // Период пинга сервера текущего подключения (обновление показателя на главном экране).
        private const val PING_INTERVAL_MS = 5_000L

        /** Сколько подряд пропущенных пингов считаем «трафик не идёт» (4 × 5с = ~20с). Меньше —
         *  ловили бы обычные провалы мобильной сети и пугали зря. */
        private const val PING_MISSES_TO_WARN = 4
        private const val SPEED_INTERVAL_MS = 1_000L


        // Проверку обновления делаем раз на запуск процесса (пересоздание Activity — смена темы — не дёргает).
        @Volatile private var updateCheckedThisProcess = false
        // OTA-подтяжка списка split-туннеля: на каком языке пресеты уже синхронизированы в этом
        // процессе. null = ещё не синхронизировали. Язык, а не Boolean, — потому что имя системного
        // набора серверное и переводится (см. refreshRuDirect).
        @Volatile private var presetsSyncedLang: String? = null
        @Volatile private var hostsRefreshedThisProcess = false    // реестр доменов (ядро + кабинет) — раз на процесс
        @Volatile private var ruAutoCheckedThisProcess = false // авто-РФ-пресет (2026-08-03) — раз на процесс, не спамим egress-check
        @Volatile private var notifAskedThisProcess = false // запрос POST_NOTIFICATIONS — раз на процесс (поворот не переспрашивает)

        // Тёплый /connect-кэш (DPI: не дёргать api.mayakvpn.ru рядом с хендшейком) греем один раз за процесс
        // при первом входе на главный. Пересоздание Activity (смена темы) уже НЕ греет (баг владельца 2026-07-06).
        @Volatile private var homeWarmedThisProcess = false

        // Свежий список направлений тянем из сети РАЗ на запуск процесса (при первом успешном показе главного).
        // Пересоздание Activity (смена ТЕМЫ) НЕ рефетчит — показываем процесс-скоупный кэш, сеть молчит
        // (баг владельца 2026-07-06: смена темы после TTL всё равно дёргала GET /directions). Новые направления
        // подхватываются перезапуском приложения ИЛИ кнопкой «Обновить» (forceRefresh) — она для этого и есть.
        //
        // Здесь ЯЗЫК, а не голое «уже тянули»: имена стран приходят с сервера и зависят от языка телефона.
        // Пока это был флаг Boolean, смена языка на живом процессе оставляла список на прежнем языке —
        // 14-08 владелец переключил приложение на английский и увидел английский интерфейс с русскими
        // «Нидерланды/Польша/Россия» (дефект 0.5.2). Теперь помним, на каком языке список получен:
        // разошлось с текущим — идём в сеть, даже если в этом процессе уже ходили.
        // null = ещё не тянули; ставим только на УСПЕХ (ошибка сети → следующий вход повторит).
        @Volatile private var directionsFetchedLang: String? = null

        /** Точку входа перезапустили из-за отозванного входа — на экране логина объясним, почему. */
        private const val EXTRA_SESSION_EXPIRED = "mayak_session_expired"

        /** Подставить логин в форму входа: номер новой учётки, которой сервер не выдал сессию
         *  (SPEC-0048, ветка «аккаунт создан, а токен не выдан»). */
        const val EXTRA_PREFILL_LOGIN = "mayak_prefill_login"

        /** Один отзыв входа — один перезапуск: 401 может прилететь сразу из нескольких запросов. */
        @Volatile private var sessionExpiredHandled = false
    }
}
