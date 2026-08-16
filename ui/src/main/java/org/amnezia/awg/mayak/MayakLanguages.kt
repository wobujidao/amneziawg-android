// Языки интерфейса «Маяк» и общий диалог их выбора. Меняем локаль рантайм через
// AppCompatDelegate.setApplicationLocales(...) — appcompat сам её персистит
// (AppLocalesMetadataHolderService autoStoreLocales в манифесте).
package org.amnezia.awg.mayak

import android.content.Context
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import org.amnezia.awg.R

object MayakLanguages {
    /**
     * BCP-47 тег → отображаемое имя (на своём языке).
     *
     * 🔴 ЗДЕСЬ ТОЛЬКО ТЕ ЯЗЫКИ, НА КОТОРЫХ ПРИЛОЖЕНИЕ ГОВОРИТ ЦЕЛИКОМ. До 16-08 список предлагал
     * ещё беларускую, қазақша, oʻzbekcha, Deutsch и Français — а переведено на них было по 28 наших
     * строк из 513 (5 %). Человек выбирал свой язык и получал мешанину: «Логин», «Құпиясөз», «Кіру»
     * рядом с «Forgot password?» и «By signing in you accept the Privacy Policy» — на ПЕРВОМ экране
     * (снято на эмуляторе 16-08). Это тот же дефект, что владелец нашёл в шторке 14-08
     * («🇳🇱 Netherlands · Защищено · Пинг: 94 мс»), только на входе и в пять раз шире.
     *
     * Полный английский лучше половинчатого родного: неполный перевод читается как «приложение
     * сломано», а не как «перевода нет».
     *
     * ⚠️ Добавлять язык в этот список — ТОЛЬКО вместе с полным `values-<тег>/strings.xml`
     * (все `mayak_*`, включая plurals). Половина строк здесь дороже, чем их отсутствие.
     */
    val LANGS = listOf(
        "ru" to "Русский",
        "en" to "English",
    )

    /**
     * Показать диалог выбора языка и применить выбор.
     *
     * Список — с отметкой текущего языка (`setSingleChoiceItems`, не `setItems`): было
     * без неё, и открывшему диалог человеку неоткуда узнать, какой пункт активен сейчас,
     * кроме как закрыть диалог и сверить с уже применённым интерфейсом. Для настройки,
     * которая ЗАПОМИНАЕТ состояние (а не разовое действие вроде «Отправить лог»),
     * непомеченный список — не то же самое, что и на выбор.
     */
    fun showDialog(context: Context) {
        val names = LANGS.map { it.second }.toTypedArray()
        // Не AppCompatDelegate.getApplicationLocales(): он отражает язык, только если его
        // САМ выставлял setApplicationLocales в этом же процессе. Локаль, пришедшую от
        // системы (LocaleManager — так её ставит и adb, и системный экран «Язык приложения»),
        // appcompat в это поле не подтягивает, хотя ресурсы уже резолвятся по ней и весь
        // остальной экран уже нарисован на нужном языке. Берём фактически применённую локаль
        // из конфигурации ресурсов — она всегда совпадает с тем, что человек видит на экране.
        val currentTag = context.resources.configuration.locales.get(0)?.language.orEmpty()
        val currentIndex = LANGS.indexOfFirst { it.first == currentTag }
        AlertDialog.Builder(context)
            .setTitle(context.getString(R.string.mayak_language))
            .setSingleChoiceItems(names, currentIndex) { dialog, which ->
                val tag = LANGS[which].first
                AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags(tag))
                dialog.dismiss()
            }
            .setNegativeButton(context.getString(R.string.mayak_cancel), null)
            .show()
    }
}
