// Блокировка приложения по биометрии/PIN устройства (запрос владельца 2026-07-06). ТОЛЬКО UI-гейт:
// закрывает доступ к кабинету/настройкам/смене конфига, НЕ трогает VPN (туннель живёт, пока заперто).
// Свой PIN НЕ храним — используем СИСТЕМНЫЙ (BiometricPrompt с DEVICE_CREDENTIAL → отпечаток/лицо ИЛИ
// системный PIN/паттерн). Fail-open: если на устройстве нет ни биометрии, ни экран-блокировки — НЕ запираем
// (нечем проверить → нельзя оставить владельца без доступа). По умолчанию ВЫКЛ.
//
// Состояние процесс-скоупное (companion-подобный object): пересоздание Activity (смена темы/языка) НЕ
// пере-спрашивает; возврат из фона дольше GRACE — пере-спрашивает. Гейт вешается в MayakActivity.onStart
// (покрывает и cold-start, и возврат из фона); сам экран блокировки — отдельная MayakLockActivity ПОВЕРХ.
package org.amnezia.awg.mayak

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricManager.Authenticators
import androidx.biometric.BiometricPrompt
import androidx.fragment.app.FragmentActivity
import org.amnezia.awg.R

object MayakLock {
    // DEVICE_CREDENTIAL → всегда есть системный PIN как fallback (владелец НИКОГДА не залочится намертво).
    // BIOMETRIC_WEAK — как в апстрим-BiometricAuthenticator (не все устройства имеют strong).
    private const val AUTHENTICATORS = Authenticators.DEVICE_CREDENTIAL or Authenticators.BIOMETRIC_WEAK
    // Короткий фон (переключение приложений, сам системный диалог согласия) НЕ пере-запирает; дольше — да.
    private const val GRACE_MS = 30_000L

    @Volatile var unlocked = false            // разблокировано в текущем «сеансе переднего плана»
    @Volatile var lockShowing = false         // MayakLockActivity сейчас показана (анти-дабл-лаунч)
    @Volatile private var backgroundedAt = 0L // SystemClock.elapsedRealtime ухода в фон (0 = не были)

    fun enabled(ctx: Context): Boolean = MayakPrefs.appLock(ctx)

    /** Можно ли вообще проверить личность (есть биометрия ИЛИ системный экран-блок). Нет → fail-open. */
    fun canAuthenticate(ctx: Context): Boolean =
        BiometricManager.from(ctx).canAuthenticate(AUTHENTICATORS) == BiometricManager.BIOMETRIC_SUCCESS

    /** Момент ухода в фон (MayakActivity.onStop). */
    fun noteBackground() {
        backgroundedAt = SystemClock.elapsedRealtime()
    }

    /** Пометить разблокированным и СБРОСИТЬ метку фона (иначе долгая аутентификация > GRACE пере-заперла бы). */
    private fun markUnlocked() {
        unlocked = true
        backgroundedAt = 0L
    }

    private fun maybeRelock() {
        if (backgroundedAt != 0L && SystemClock.elapsedRealtime() - backgroundedAt > GRACE_MS) {
            unlocked = false
        }
    }

    /** Нужно ли показать экран блокировки СЕЙЧАС (в onStart главной). Fail-open, если выкл/нечем проверить. */
    fun shouldLock(ctx: Context): Boolean {
        if (!enabled(ctx)) { unlocked = true; return false }
        if (!canAuthenticate(ctx)) { unlocked = true; return false } // нечем проверить → не запираем
        maybeRelock()
        return !unlocked && !lockShowing
    }

    /** Системный BiometricPrompt (из MayakLockActivity). onResult(true) = разблокировано (успех ИЛИ fail-open);
     *  onResult(false) = отмена/ошибка (остаёмся на экране блокировки — кнопка «Разблокировать»/«Выйти»). */
    fun authenticate(activity: FragmentActivity, onResult: (Boolean) -> Unit) {
        if (!canAuthenticate(activity)) { markUnlocked(); onResult(true); return } // fail-open
        val executor = java.util.concurrent.Executor { r -> Handler(Looper.getMainLooper()).post(r) }
        val cb = object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                markUnlocked(); onResult(true)
            }
            override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                when (errorCode) {
                    // Нечем проверить (сняли биометрию/экран-блок на лету) → fail-open.
                    BiometricPrompt.ERROR_HW_NOT_PRESENT, BiometricPrompt.ERROR_HW_UNAVAILABLE,
                    BiometricPrompt.ERROR_NO_BIOMETRICS, BiometricPrompt.ERROR_NO_DEVICE_CREDENTIAL ->
                        { markUnlocked(); onResult(true) }
                    else -> onResult(false) // отмена/лочаут — остаёмся заперты
                }
            }
            // onAuthenticationFailed (неверный отпечаток) — промпт сам даёт повтор; отдельно не обрабатываем.
        }
        val prompt = BiometricPrompt(activity, executor, cb)
        val info = BiometricPrompt.PromptInfo.Builder()
            .setTitle(activity.getString(R.string.mayak_lock_title))
            .setSubtitle(activity.getString(R.string.mayak_lock_subtitle))
            .setAllowedAuthenticators(AUTHENTICATORS)
            .build()
        prompt.authenticate(info)
    }
}
