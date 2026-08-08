// Постоянное подключение (системный Always-On VPN): попытка ЧЕСТНО узнать, включено ли оно, и вход
// в системный раздел, где его включают. Само включение — только руками человека: приложение такой
// настройкой не управляет и управлять не должно.
package org.amnezia.awg.mayak

import android.content.Context
import android.content.Intent
import android.provider.Settings

object MayakAlwaysOn {

    /**
     * Ключ в Settings.Secure, куда система пишет package того приложения, которому разрешено
     * постоянное подключение. В публичном API его нет (в AOSP он `@hide`), поэтому читаем по имени.
     */
    private const val SETTING_ALWAYS_ON_VPN_APP = "always_on_vpn_app"

    /**
     * ДОКАЗАНО ли, что постоянное подключение включено и включено именно для нас.
     *
     * Читается это далеко не везде. С Android 12 провайдер настроек пускает приложение только к
     * настройкам, помеченным как публично читаемые, а этой среди них нет — вызов отдаёт отказ
     * (SecurityException), и мы не узнаём НИЧЕГО. То есть:
     *   • true  — настройка точно включена (такое бывает на прошивках до Android 12);
     *   • false — «выключено» ИЛИ «прочитать не дали». Эти два случая приложению неразличимы.
     *
     * Отсюда правило: положительному ответу верим (и молчим, не беспокоя человека), отрицательный
     * за ответ не считаем. Врать «у вас выключено» на основании отказа в чтении мы не будем — вместо
     * этого в карточке есть кнопка «Уже включено», которой человек закрывает разговор сам.
     */
    fun isProvenEnabled(context: Context): Boolean = runCatching {
        Settings.Secure.getString(context.contentResolver, SETTING_ALWAYS_ON_VPN_APP)
    }.getOrNull() == context.packageName

    /**
     * Открыть системный раздел, где включают постоянное подключение. Экрана «сразу нужный тумблер»
     * в Android нет: раздел общий, дальше человек выбирает «Маяк» и включает настройку сам.
     * На части прошивок этого экрана нет вовсе → возвращаем false, и звонящий говорит, куда идти руками.
     */
    fun openSystemSettings(context: Context): Boolean = runCatching {
        context.startActivity(Intent(Settings.ACTION_VPN_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        true
    }.getOrDefault(false)
}
