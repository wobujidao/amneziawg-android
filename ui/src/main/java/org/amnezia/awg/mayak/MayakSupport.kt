// Помощь и поддержка — единственная точка входа человека, у которого не работает.
//
// Разбор 08-08 (APP-BACKLOG, блокер №3): справочного центра и кнопки поддержки в приложении не было
// вовсе, а адрес поддержки встречался ровно один раз — внутри текста ошибки «аккаунт заблокирован».
// То есть сценарий «у меня не работает, куда писать» в приложении был пустым, и это тот самый
// сценарий, ради которого приложение открывают, когда всё плохо.
//
// 🔴 08-08: `mailto:` перестал быть ЕДИНСТВЕННЫМ путём. У человека без настроенного почтового клиента
// (телефон с одним веб-Gmail — типовая ситуация) нажатие на такую ссылку не делает РОВНО НИЧЕГО:
// система не знает, какому приложению её отдать. То есть путь был пуст ровно в той ситуации, ради
// которой поддержка и существует. Главный путь теперь — ФОРМА (MayakSupportActivity → ядро,
// POST /v1/client/support), а письмо осталось запасной кнопкой: у кого клиент есть, тому так привычнее.
//
// 🔴 Адрес поддержки берём ТОЛЬКО из BuildConfig.MAYAK_SUPPORT_EMAIL: он разный у прод-сборки и у
// дефолтной (у дефолтной — снятый дев-домен). Захардкоженный адрес уже уводил людей на мёртвый
// домен (найдено 07-08), поэтому строкой в переводе он больше не живёт.
// Адрес справки — из реестра доменов (MayakHostList.helpUrl), по той же причине.
package org.amnezia.awg.mayak

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.widget.Toast
import org.amnezia.awg.BuildConfig
import org.amnezia.awg.R
import org.amnezia.awg.mayak.core.MayakApiException
import org.amnezia.awg.mayak.core.SupportFailure
import org.amnezia.awg.mayak.core.SupportLimits
import org.amnezia.awg.mayak.core.retryAfterMinutes
import org.amnezia.awg.mayak.core.supportFailure

object MayakSupport {

    /** Адрес поддержки СВОЕЙ сборки. */
    val email: String get() = BuildConfig.MAYAK_SUPPORT_EMAIL

    /** Открыть справочный центр. false — адреса сайта нет (кнопку в этом случае и не показываем). */
    fun openHelp(context: Context): Boolean {
        val url = MayakHostList.helpUrl(context) ?: return false
        return openExternal(context, Intent(Intent.ACTION_VIEW, Uri.parse(url)))
    }

    /**
     * Письмо в поддержку с уже заполненными темой и «хвостом» — тем, что мы всё равно спросим первым
     * (версия, аппарат, Android, направление, аккаунт). Человек дописывает только суть.
     *
     * Секретов в письме нет и быть не должно: токен, приватный ключ, адреса нод и конфиг туннеля
     * сюда не попадают — письмо уходит через почтовое приложение, то есть через третьи руки.
     */
    fun writeToSupport(context: Context, accountEmail: String?) {
        val intent = Intent(Intent.ACTION_SENDTO).apply {
            // mailto: — чтобы предложение ушло ТОЛЬКО почтовым приложениям, а не всему, что умеет
            // «поделиться» (ACTION_SEND показал бы мессенджеры, и письмо не дошло бы никуда).
            data = Uri.fromParts("mailto", email, null)
            putExtra(Intent.EXTRA_SUBJECT, context.getString(R.string.mayak_support_subject))
            putExtra(Intent.EXTRA_TEXT, letterBody(context, accountEmail))
        }
        // БЕЗ createChooser намеренно: чузер — это отдельная системная activity, она находится
        // ВСЕГДА, и при отсутствии почтового приложения человек получил бы пустое окно «нет
        // приложений», а наш запасной путь ниже не сработал бы никогда. Прямой ACTION_SENDTO даёт
        // честный ActivityNotFoundException (и системный выбор, если почтовых приложений несколько).
        if (openExternal(context, intent)) return
        // Почтового приложения нет (бывает на «чистых» прошивках и в эмуляторе) — тупика человеку
        // не оставляем: кладём адрес в буфер и говорим об этом.
        copyEmail(context)
    }

    /** Адрес поддержки в буфер обмена + сообщение об этом. Запасной путь, когда почты на аппарате нет. */
    fun copyEmail(context: Context) = copyEmail(context, R.string.mayak_support_no_mail_app)

    /**
     * То же, но своим сообщением. Отдельно от [copyEmail] потому, что текст «почтового приложения на
     * телефоне нет» — это ОБЪЯСНЕНИЕ неудачи, и на экране поддержки, где человек сам нажал
     * «Скопировать адрес», он читался бы как поломка там, где всё сработало.
     */
    fun copyEmail(context: Context, toast: Int) {
        runCatching {
            val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText("email", email))
        }
        Toast.makeText(context, context.getString(toast, email), Toast.LENGTH_LONG).show()
    }

    /**
     * Отказ ядра — СЛОВАМИ и с подсказкой, что делать. Одна функция на оба экрана поддержки: два
     * набора формулировок для одних и тех же причин разъехались бы, и человек читал бы про лимит
     * по-разному в форме и в переписке.
     *
     * Само РЕШЕНИЕ (какая это причина) принимается в :core под тестами — здесь только слова.
     */
    fun failureText(context: Context, e: Throwable): String {
        val f = supportFailure(e)
        val retryMin = (e as? MayakApiException)?.let { retryAfterMinutes(it.retryAfterSec) } ?: 0
        return when (f) {
            SupportFailure.TOPIC_REJECTED -> context.getString(R.string.mayak_support_err_topic)
            SupportFailure.TOO_SHORT -> context.getString(R.string.mayak_support_err_short)
            SupportFailure.TOO_LONG ->
                context.getString(R.string.mayak_support_err_long, SupportLimits.MAX_CHARS)
            // «Можно снова через N мин» берём из Retry-After ядра. Не прислало (0) — не выдумываем
            // число: «попробуйте позже» честнее, чем обещание, которое мы не держим.
            SupportFailure.RATE_LIMITED ->
                if (retryMin > 0) context.getString(R.string.mayak_support_err_rate, retryMin)
                else context.getString(R.string.mayak_support_err_rate_soon)

            SupportFailure.CHANNEL_OFF -> context.getString(R.string.mayak_support_err_channel_off)
            SupportFailure.RETRY_LATER -> context.getString(R.string.mayak_support_err_retry)
            SupportFailure.NO_CONNECTION -> context.getString(R.string.mayak_support_err_offline)
            SupportFailure.NEED_LOGIN -> context.getString(R.string.mayak_support_err_login)
            SupportFailure.NOT_FOUND -> context.getString(R.string.mayak_support_err_not_found)
            // Причина нам неизвестна — печатаем то, что сказало ядро, и НЕ подсказываем действие.
            SupportFailure.UNKNOWN -> context.getString(
                R.string.mayak_support_err_unknown,
                e.message ?: context.getString(R.string.mayak_err_generic),
            )
        }
    }

    /** Текст локальной проверки (до сети) — теми же словами, что и отказ ядра. */
    fun localProblemText(context: Context, f: SupportFailure): String = when (f) {
        SupportFailure.TOO_LONG -> context.getString(R.string.mayak_support_err_long, SupportLimits.MAX_CHARS)
        else -> context.getString(R.string.mayak_support_err_short)
    }

    /**
     * Текст письма. Отдельной функцией (не внутри Intent) — чтобы состав «хвоста» было видно одним
     * куском и в него нельзя было случайно уронить что-то секретное.
     */
    internal fun letterBody(context: Context, accountEmail: String?): String {
        val version = "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})"
        val device = "${Build.MANUFACTURER} ${Build.MODEL}".trim()
        val android = "Android ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})"
        val direction = MayakPrefs.lastConnLabel(context)
            ?: context.getString(R.string.mayak_support_direction_none)
        // Номер аккаунта — то, чем поддержка ищет человека (почта в схеме необязательна: учётки из
        // бота, анонимные и подарочные заводятся без неё).
        val number = MayakAccountNumber.display(context)
        // 🔴 accountEmail — это ВВЕДЁННЫЙ логин, а он может быть тем же номером, только без дефисов.
        // Печатали его как есть — и письмо называло человека дважды и в двух разных форматах
        // («Аккаунт: 848681728» + «Номер аккаунта: 848-681-728»), заставляя сличать два числа.
        // Почтой считаем только то, что ею выглядит; иначе называем человека номером — один раз.
        val account = accountEmail?.takeIf { it.isNotBlank() && it.contains('@') }
            ?: number
            ?: context.getString(R.string.mayak_support_account_none)
        val body = context.getString(R.string.mayak_support_body, version, device, android, direction, account)
        // Дописываем строкой, а не параметром в mayak_support_body: тот шаблон переводится на два
        // десятка языков, и лишний %6$s в нём означал бы двадцать правок и падение на любом пропуске.
        if (number == null || number == account) return body
        return body + context.getString(R.string.mayak_support_body_account_number, number) + "\n"
    }

    private fun openExternal(context: Context, intent: Intent): Boolean = try {
        // Из не-Activity контекста система требует свою задачу; из Activity флаг безвреден.
        context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        true
    } catch (_: ActivityNotFoundException) {
        false
    }
}
