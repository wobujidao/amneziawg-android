// Регистрация ПРЯМО В ПРИЛОЖЕНИИ (SPEC-0048).
//
// 🔴 Что было до этого экрана. Кнопка «Регистрация» на входе открывала БРАУЗЕР: там человек заводил
// аккаунт, там придумывал пароль, там получал номер — и возвращался в приложение вводить всё заново.
// Сменилось приложение, сменился шрифт, сменились правила; половина людей уходит на этом шаге.
// Образец назван владельцем дважды — Mullvad: открыл приложение, нажал «создать», получил номер,
// ты внутри. Ни почты, ни писем, ни браузера.
//
// Три шага в одной Activity (переключаются видимостью, см. разметку):
//   1. пароль (можно придумать за человека) + согласие → POST /v1/auth/register-anon;
//   2. проверка «не робот» — единственное место, где мы вынуждены открыть WebView с JS (у Turnstile
//      нет мобильного SDK), поэтому там же собраны все запреты;
//   3. номер аккаунта. Он отдаётся ОДИН раз и восстановить его нечем: почты у такой учётки нет.
//
// ⚠️ FLAG_SECURE здесь НАМЕРЕННО НЕТ, хотя на экране и пароль, и номер. Запрет скриншотов лишил бы
// человека самого простого способа сохранить то, что мы просим сохранить, — а «сфотографируй экран»
// в этот момент делают все. Пароль по умолчанию скрыт точками, номер секретом не является (это имя,
// а не пароль, см. AccountNumber).
package org.amnezia.awg.mayak

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.webkit.JavascriptInterface
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.TextView
import androidx.activity.addCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.core.widget.doAfterTextChanged
import androidx.lifecycle.lifecycleScope
import com.google.android.material.button.MaterialButton
import com.google.android.material.checkbox.MaterialCheckBox
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import java.io.IOException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.amnezia.awg.R
import org.amnezia.awg.mayak.core.HostProvider
import org.amnezia.awg.mayak.core.MayakApiException
import org.amnezia.awg.mayak.core.MayakBackend
import org.amnezia.awg.mayak.core.PasswordPolicy
import org.amnezia.awg.mayak.core.RegisterForm

class MayakRegisterActivity : AppCompatActivity() {

    private enum class Step { FORM, CAPTCHA, DONE }

    private val store by lazy { KeystoreSecureStore(this) }
    private val session by lazy { MayakSession(store, AwgKeyProvider(), AndroidHwidProvider(this, store)) }
    private val backend by lazy {
        MayakBackend(
            HostProvider(MayakHostList.effective(this, store.get(MayakActivity.KEY_SERVER))),
            bypassTunnel = OutsideTunnel.opener(this),
        )
    }

    private var step = Step.FORM

    /** Идёт запрос к ядру — вторая кнопка завела бы ВТОРОЙ аккаунт (см. RegisterForm.canSubmit). */
    private var inFlight = false

    /** Номер выданной учётки: показываем его на шаге 3 и после поворота экрана. */
    private var number = ""
    private var trialDays = 0

    /** Токен сессии выдан вместе с номером? false — учётка есть, а входить надо руками. */
    private var signedIn = false

    /** Токен капчи уже приняли (или уже провалились) — второй колбэк со страницы игнорируем. */
    private var captchaHandled = false

    /** Сторож шага капчи: страница может загрузиться и молча не решиться. */
    private var captchaWatchdog: Job? = null

    private lateinit var passwordLayout: TextInputLayout
    private lateinit var passwordField: TextInputEditText
    private lateinit var consent: MaterialCheckBox
    private lateinit var submit: MaterialButton
    private lateinit var error: TextView
    private lateinit var browser: MaterialButton
    private lateinit var web: WebView

    override fun onCreate(savedInstanceState: Bundle?) {
        MayakPrefs.applyTheme(this)
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_mayak_register)
        MayakSystemBars.apply(this)
        applyInsets()

        passwordLayout = findViewById(R.id.mayak_reg_password_layout)
        passwordField = findViewById(R.id.mayak_reg_password)
        consent = findViewById(R.id.mayak_reg_consent)
        submit = findViewById(R.id.mayak_reg_submit)
        error = findViewById(R.id.mayak_reg_error)
        browser = findViewById(R.id.mayak_reg_browser)
        web = findViewById(R.id.mayak_reg_web)

        savedInstanceState?.let {
            step = Step.values().getOrElse(it.getInt(K_STEP)) { Step.FORM }
            number = it.getString(K_NUMBER).orEmpty()
            trialDays = it.getInt(K_TRIAL)
            signedIn = it.getBoolean(K_SIGNED_IN)
        }

        findViewById<MaterialButton>(R.id.mayak_reg_back).setOnClickListener { goBack() }
        findViewById<MaterialButton>(R.id.mayak_reg_captcha_back).setOnClickListener { goBack() }
        // Системная «Назад» — через диспетчер: onBackPressed() на Android 13+ не зовётся вовсе
        // (память android-back-handler-dead-on-13-plus), и экран стал бы ловушкой.
        onBackPressedDispatcher.addCallback(this) { goBack() }

        consent.setOnCheckedChangeListener { _, _ -> syncSubmit() }
        passwordField.doAfterTextChanged { passwordLayout.error = null }

        findViewById<MaterialButton>(R.id.mayak_reg_generate).setOnClickListener {
            val fresh = PasswordPolicy.generate()
            passwordField.setText(fresh)
            // Показываем открытым текстом: пароль, который человек не увидел, он не сохранит.
            passwordLayout.isEndIconVisible = true
            passwordField.transformationMethod = null
            passwordLayout.error = null
        }
        findViewById<MaterialButton>(R.id.mayak_reg_copy_password).setOnClickListener { copyPassword() }
        findViewById<MaterialButton>(R.id.mayak_reg_policy).setOnClickListener {
            openUrl(MayakHostList.privacyUrl(this))
        }
        submit.setOnClickListener { onSubmit() }
        findViewById<MaterialButton>(R.id.mayak_reg_copy_number).setOnClickListener {
            if (number.isNotBlank()) MayakAccountNumber.copy(this, number)
        }
        findViewById<MaterialButton>(R.id.mayak_reg_continue).setOnClickListener { leaveToApp() }
        browser.setOnClickListener { openUrl(MayakHostList.cabinetRegisterUrl(this)) }

        render()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        // Номер показывается ОДИН раз — поворот экрана и уход в фон не имеют права его потерять.
        // Пароль здесь НЕ сохраняем: его хранит само поле ввода, и лишней копии в Bundle не надо.
        outState.putInt(K_STEP, step.ordinal)
        outState.putString(K_NUMBER, number)
        outState.putInt(K_TRIAL, trialDays)
        outState.putBoolean(K_SIGNED_IN, signedIn)
    }

    override fun onDestroy() {
        captchaWatchdog?.cancel()
        // WebView живёт дольше Activity, если его не убить: он держит свой процесс-рендерер.
        runCatching {
            web.stopLoading()
            web.removeJavascriptInterface(JS_BRIDGE)
            web.destroy()
        }
        super.onDestroy()
    }

    private fun applyInsets() {
        for (id in listOf(R.id.mayak_reg_content, R.id.mayak_reg_step_captcha)) {
            val v = findViewById<View>(id)
            val baseTop = v.paddingTop
            val baseBottom = v.paddingBottom
            ViewCompat.setOnApplyWindowInsetsListener(v) { view, insets ->
                val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
                view.updatePadding(top = baseTop + bars.top, bottom = baseBottom + bars.bottom)
                insets
            }
        }
    }

    // ===== Экран =====

    private fun render() {
        findViewById<View>(R.id.mayak_reg_scroll).visibility =
            if (step == Step.CAPTCHA) View.GONE else View.VISIBLE
        findViewById<View>(R.id.mayak_reg_step_captcha).visibility =
            if (step == Step.CAPTCHA) View.VISIBLE else View.GONE
        findViewById<View>(R.id.mayak_reg_step_form).visibility =
            if (step == Step.FORM) View.VISIBLE else View.GONE
        findViewById<View>(R.id.mayak_reg_step_done).visibility =
            if (step == Step.DONE) View.VISIBLE else View.GONE

        if (step == Step.DONE) {
            findViewById<TextView>(R.id.mayak_reg_number).text = number
            findViewById<TextView>(R.id.mayak_reg_trial).let { view ->
                // Молчим, если сервер вернул 0: своих «7 дней» приложение не придумывает.
                view.visibility = if (trialDays > 0) View.VISIBLE else View.GONE
                if (trialDays > 0) view.text = getString(R.string.mayak_reg_trial, trialDays)
            }
            // Учётка есть, а сессии нет — говорим прямо и ведём на вход с подставленным номером.
            findViewById<TextView>(R.id.mayak_reg_done_warning).text = getString(
                if (signedIn) R.string.mayak_reg_done_warning else R.string.mayak_reg_no_session
            )
            // Дороги в браузер с этого шага нет: аккаунт уже создан, второй заводить незачем.
            browser.visibility = View.GONE
        }
        syncSubmit()
    }

    private fun syncSubmit() {
        submit.isEnabled = RegisterForm.canSubmit(consent.isChecked, inFlight)
        submit.setText(if (inFlight) R.string.mayak_reg_working else R.string.mayak_reg_title)
    }

    private fun showError(text: String, offerBrowser: Boolean) {
        error.text = text
        error.visibility = View.VISIBLE
        // Вторая дорога появляется РОВНО там, где своя не сработала, — тупика на экране быть не должно.
        browser.visibility = if (offerBrowser && step != Step.DONE) View.VISIBLE else View.GONE
    }

    private fun clearError() {
        error.visibility = View.GONE
        browser.visibility = View.GONE
    }

    private fun goBack() {
        when (step) {
            // Аккаунт уже создан: «назад» с этого шага = «продолжить», иначе человек вернулся бы к
            // форме и завёл второй.
            Step.DONE -> leaveToApp()
            Step.CAPTCHA -> {
                captchaWatchdog?.cancel()
                runCatching { web.stopLoading(); web.loadUrl("about:blank") }
                step = Step.FORM
                inFlight = false
                render()
            }
            Step.FORM -> { finish(); MayakTransitions.applyAxisReverse(this) }
        }
    }

    /** Уйти в приложение: с сессией — на главный экран, без неё — на вход с подставленным номером. */
    private fun leaveToApp() {
        val intent = Intent(this, MayakActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        if (!signedIn && number.isNotBlank()) {
            intent.putExtra(MayakActivity.EXTRA_PREFILL_LOGIN, number)
        }
        startActivity(intent)
        finish()
    }

    private fun copyPassword() {
        val pw = passwordField.text?.toString().orEmpty()
        if (pw.isEmpty()) return
        // ⚠️ Компромисс, названный в спеке: буфер обмена видят другие приложения, а на Android 13+
        // система ещё и показывает превью содержимого. Альтернатива — заставить человека переписать
        // 24 символа руками — хуже: он придумает «1234qwer» и потеряет доступ к аккаунту навсегда.
        val cm = getSystemService(android.content.Context.CLIPBOARD_SERVICE) as? android.content.ClipboardManager
        cm?.setPrimaryClip(
            android.content.ClipData.newPlainText(getString(R.string.mayak_reg_password_hint), pw)
        )
        android.widget.Toast.makeText(this, R.string.mayak_reg_password_copied, android.widget.Toast.LENGTH_SHORT).show()
    }

    /** Тёмная ли тема ПРЯМО СЕЙЧАС — виджет капчи должен приехать в цвет экрана, а не наоборот. */
    private fun isDarkNow(): Boolean =
        (resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK) ==
            android.content.res.Configuration.UI_MODE_NIGHT_YES

    private fun openUrl(url: String) {
        runCatching { startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse(url))) }
            .onFailure { showError(getString(R.string.mayak_err_bad_link), offerBrowser = false) }
    }

    // ===== Шаг 1 → шаг 2 =====

    private fun onSubmit() {
        clearError()
        passwordLayout.error = null
        val pw = passwordField.text?.toString().orEmpty()
        // Локальная проверка ТОЙ ЖЕ политикой, что на сервере: `weak_password` человек не должен
        // получать НИКОГДА — это отказ после ожидания и с потраченным токеном капчи.
        if (!PasswordPolicy.isStrong(pw)) {
            passwordLayout.error = getString(R.string.mayak_reg_err_weak)
            passwordField.requestFocus()
            return
        }
        if (!consent.isChecked) { // до кнопки дойти нельзя, но правило пусть будет и здесь
            showError(getString(R.string.mayak_reg_err_consent), offerBrowser = false)
            return
        }
        if (!MayakNet.hasNetwork(this)) {
            showError(getString(R.string.mayak_status_no_network), offerBrowser = false)
            return
        }
        inFlight = true
        syncSubmit()
        lifecycleScope.launch {
            val captcha = runCatching { backend.publicCaptcha() }.getOrElse { e ->
                inFlight = false
                syncSubmit()
                // Не узнали, нужна ли проверка, — идти дальше нельзя: сервер откажет
                // `captcha_required`, и человек не поймёт, за что.
                showError(humanError(e), offerBrowser = true)
                return@launch
            }
            if (!captcha.enabled) {
                // Капча выключена в панели → шага нет вовсе, токен уходит пустым.
                sendRegistration(pw, "")
                return@launch
            }
            if (captcha.sitekey.isBlank()) {
                inFlight = false
                syncSubmit()
                // Проверка включена, а ключа нет — это НАША беда, и человеку тут ловить нечего.
                showError(getString(R.string.mayak_reg_err_captcha_unavailable), offerBrowser = true)
                return@launch
            }
            startCaptcha(captcha.sitekey)
        }
    }

    // ===== Шаг 2: проверка «не робот» в WebView =====

    /**
     * Единственное место в приложении, где включён JavaScript, — и поэтому здесь же собраны все
     * запреты. Они не украшение: страница загружается по сети, а WebView с JS и доступом к файлам —
     * это чужой код рядом с нашим хранилищем.
     *
     *  • грузим ТОЛЬКО свой домен (главный фрейм), всё прочее отвергаем в shouldOverrideUrlLoading;
     *  • файлы и content:// — запрещены (иначе страница смогла бы читать локальные файлы);
     *  • хранилище DOM не включаем — виджету оно не нужно, а нам не нужен его след на диске;
     *  • JS-интерфейс отдаёт РОВНО два метода и ничего не возвращает наружу.
     *
     * Кадр виджета (challenges.cloudflare.com) грузиться обязан — иначе проверки не будет вовсе;
     * его и только его пускаем в ПОДчинённых фреймах. Всё остальное ограничивает сама страница
     * своей политикой безопасности (default-src 'none', см. cabinet/app-captcha.html).
     */
    private fun startCaptcha(sitekey: String) {
        captchaHandled = false
        step = Step.CAPTCHA
        render()
        findViewById<TextView>(R.id.mayak_reg_captcha_state).setText(R.string.mayak_reg_captcha_wait)

        web.settings.javaScriptEnabled = true
        web.settings.allowFileAccess = false
        web.settings.allowContentAccess = false
        web.settings.domStorageEnabled = false
        web.settings.mediaPlaybackRequiresUserGesture = true
        web.addJavascriptInterface(CaptchaBridge(), JS_BRIDGE)
        web.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val url = request.url?.toString().orEmpty()
                // Запасной путь страницы, если JS-интерфейс не подключился: mayak-captcha://token?…
                if (request.url?.scheme.equals(SCHEME, ignoreCase = true)) {
                    val value = request.url?.getQueryParameter("value").orEmpty()
                    val err = request.url?.getQueryParameter("error").orEmpty()
                    if (value.isNotEmpty()) onCaptchaToken(value) else onCaptchaFailed(err.ifEmpty { "scheme" })
                    return true
                }
                if (MayakHostList.ownContour(url)) return false // свой домен — грузим
                if (!request.isForMainFrame && url.startsWith(WIDGET_ORIGIN)) return false // кадр виджета
                android.util.Log.w(TAG, "переход мимо своего домена отвергнут: ${logsafeHost(url)}")
                return true
            }

            override fun onReceivedError(view: WebView, request: WebResourceRequest, err: WebResourceError) {
                if (!request.isForMainFrame) return // упавший ресурс виджета доложит сама страница
                onCaptchaFailed("page_not_loaded")
            }
        }
        web.loadUrl(MayakHostList.appCaptchaUrl(this, sitekey, dark = isDarkNow()))

        // Сторож: страница могла открыться и молча не решиться (в WebView со строгими настройками
        // так бывает). Своя страница сдаётся через 25 с — ждём чуть дольше, чтобы её собственное
        // объяснение («no_widget») дошло первым и причина в логе была точнее.
        captchaWatchdog?.cancel()
        captchaWatchdog = lifecycleScope.launch {
            delay(CAPTCHA_TIMEOUT_MS)
            onCaptchaFailed("timeout")
        }
    }

    /** Мост со страницей. Методы зовутся с ЧУЖОГО потока (JS-движок) — в UI уходим через runOnUiThread. */
    private inner class CaptchaBridge {
        @JavascriptInterface
        fun onToken(token: String) {
            runOnUiThread { onCaptchaToken(token) }
        }

        @JavascriptInterface
        fun onError(code: String) {
            runOnUiThread { onCaptchaFailed(code) }
        }
    }

    private fun onCaptchaToken(token: String) {
        if (captchaHandled || isFinishing) return
        captchaHandled = true
        captchaWatchdog?.cancel()
        if (token.isBlank()) { onCaptchaFailed("empty_token"); return }
        android.util.Log.i(TAG, "проверка «не робот» пройдена, токен получен (${token.length} симв.)")
        findViewById<TextView>(R.id.mayak_reg_captcha_state).setText(R.string.mayak_reg_working)
        val pw = passwordField.text?.toString().orEmpty()
        lifecycleScope.launch { sendRegistration(pw, token) }
    }

    private fun onCaptchaFailed(code: String) {
        if (captchaHandled || isFinishing) return
        captchaHandled = true
        captchaWatchdog?.cancel()
        android.util.Log.w(TAG, "проверка «не робот» не удалась: $code")
        step = Step.FORM
        inFlight = false
        render()
        runCatching { web.stopLoading(); web.loadUrl("about:blank") }
        // Любой провал этого шага — не тупик: рядом появляется кнопка «в браузере».
        showError(
            getString(
                if (code == "page_not_loaded" || code == "timeout" || code == "script_not_loaded") {
                    R.string.mayak_reg_err_captcha_unavailable
                } else {
                    R.string.mayak_reg_err_captcha
                }
            ),
            offerBrowser = true,
        )
    }

    // ===== Шаг 2 → шаг 3 =====

    private suspend fun sendRegistration(password: String, captchaToken: String) {
        try {
            val res = backend.registerAnon(password, consent = true, captchaToken = captchaToken)
            number = org.amnezia.awg.mayak.core.AccountNumber.format(res.accountNumber)
            trialDays = res.trialDays
            signedIn = res.token.isNotBlank()
            if (signedIn) {
                // Автоматический вход: токен уже выдан этим же ответом, логиниться заново нечем и незачем.
                session.adoptRegistration(res.token, res.accountNumber)
            } else {
                // Учётка создана, сессию сервер выдать не смог. Это НЕ ошибка: аккаунт существует,
                // и повторять запрос нельзя — покажем номер и уведём на вход.
                android.util.Log.w(TAG, "учётка создана, но сессия не выдана — ведём на вход")
            }
            inFlight = false
            step = Step.DONE
            clearError()
            render()
        } catch (e: MayakApiException) {
            inFlight = false
            step = Step.FORM
            render()
            when (e.code) {
                // Сервер отверг пароль, хотя наша проверка его пропустила: значит политики
                // разошлись. Показываем У ПОЛЯ и громко пишем в лог — это баг на нашей стороне.
                "weak_password" -> {
                    android.util.Log.e(TAG, "сервер отверг пароль, прошедший локальную политику — политики разошлись")
                    passwordLayout.error = getString(R.string.mayak_reg_err_weak)
                }
                "consent_required" -> showError(getString(R.string.mayak_reg_err_consent), offerBrowser = false)
                "captcha_required", "captcha_failed" ->
                    showError(getString(R.string.mayak_reg_err_captcha), offerBrowser = true)
                "captcha_unavailable" ->
                    showError(getString(R.string.mayak_reg_err_captcha_unavailable), offerBrowser = true)
                "overloaded" -> showError(getString(R.string.mayak_err_overloaded), offerBrowser = false)
                else -> showError(e.message ?: getString(R.string.mayak_err_generic), offerBrowser = true)
            }
        } catch (e: Exception) {
            inFlight = false
            step = Step.FORM
            render()
            showError(humanError(e), offerBrowser = true)
        }
    }

    /** Те же слова, что на экране входа: «нет интернета» и «сервер не отвечает» — разные беды. */
    private fun humanError(e: Throwable): String = when (e) {
        is MayakApiException -> e.message ?: getString(R.string.mayak_err_generic)
        is IOException -> getString(
            if (MayakNet.hasNetwork(this)) R.string.mayak_err_server_unreachable
            else R.string.mayak_status_no_network
        )
        else -> {
            android.util.Log.w(TAG, "необработанная ошибка регистрации: ${e.javaClass.name}: ${e.message}")
            getString(R.string.mayak_err_generic)
        }
    }

    /** Хост из адреса — для лога. Полный URL в лог не пишем: в нём ключ сайта и параметры. */
    private fun logsafeHost(url: String): String =
        runCatching { android.net.Uri.parse(url).host.orEmpty() }.getOrDefault("?")

    companion object {
        private const val TAG = "Mayak/Register"

        /** Имя JS-интерфейса — ровно то, которое зовёт страница (cabinet/app-captcha.html). */
        private const val JS_BRIDGE = "MayakCaptcha"

        /** Запасной путь страницы, если интерфейс не подключился. */
        private const val SCHEME = "mayak-captcha"

        /** Единственный чужой источник, которому позволено грузиться в кадре, — сам виджет. */
        private const val WIDGET_ORIGIN = "https://challenges.cloudflare.com"

        /** Потолок ожидания токена. Страница сдаётся сама через 25 с — оставляем ей время сказать. */
        private const val CAPTCHA_TIMEOUT_MS = 30_000L

        private const val K_STEP = "mayak_reg_step"
        private const val K_NUMBER = "mayak_reg_number"
        private const val K_TRIAL = "mayak_reg_trial"
        private const val K_SIGNED_IN = "mayak_reg_signed_in"

        /** Открыть экран регистрации (с экрана входа). */
        fun open(activity: AppCompatActivity) {
            activity.startActivity(Intent(activity, MayakRegisterActivity::class.java))
            MayakTransitions.applyAxis(activity)
        }
    }
}
