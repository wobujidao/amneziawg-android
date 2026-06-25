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

    /** Показать диалог выбора языка и применить выбор. */
    fun showDialog(context: Context) {
        val names = LANGS.map { it.second }.toTypedArray()
        AlertDialog.Builder(context)
            .setTitle(context.getString(R.string.mayak_language))
            .setItems(names) { _, which ->
                val tag = LANGS[which].first
                AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags(tag))
            }
            .setNegativeButton(context.getString(R.string.mayak_cancel), null)
            .show()
    }
}
