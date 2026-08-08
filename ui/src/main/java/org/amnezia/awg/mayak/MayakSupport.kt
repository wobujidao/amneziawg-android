// Помощь и поддержка — единственная точка входа человека, у которого не работает.
//
// Разбор 08-08 (APP-BACKLOG, блокер №3): справочного центра и кнопки поддержки в приложении не было
// вовсе, а адрес поддержки встречался ровно один раз — внутри текста ошибки «аккаунт заблокирован».
// То есть сценарий «у меня не работает, куда писать» в приложении был пустым, и это тот самый
// сценарий, ради которого приложение открывают, когда всё плохо.
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
    fun copyEmail(context: Context) {
        runCatching {
            val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText("email", email))
        }
        Toast.makeText(context, context.getString(R.string.mayak_support_no_mail_app, email), Toast.LENGTH_LONG).show()
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
        val account = accountEmail?.takeIf { it.isNotBlank() }
            ?: context.getString(R.string.mayak_support_account_none)
        return context.getString(R.string.mayak_support_body, version, device, android, direction, account)
    }

    private fun openExternal(context: Context, intent: Intent): Boolean = try {
        // Из не-Activity контекста система требует свою задачу; из Activity флаг безвреден.
        context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        true
    } catch (_: ActivityNotFoundException) {
        false
    }
}
