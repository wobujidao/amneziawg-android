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
    // BCP-47 тег → отображаемое имя (на своём языке).
    val LANGS = listOf(
        "ru" to "Русский",
        "be" to "Беларуская",
        "kk" to "Қазақша",
        "uz" to "Oʻzbekcha",
        "en" to "English",
        "de" to "Deutsch",
        "fr" to "Français",
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
