/*
 * Copyright © 2017-2023 WireGuard LLC. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package org.amnezia.awg.util

import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.os.Build
import android.os.PersistableBundle
import android.view.View
import android.widget.TextView
import androidx.core.content.getSystemService
import com.google.android.material.snackbar.Snackbar
import com.google.android.material.textfield.TextInputEditText
import org.amnezia.awg.R

/**
 * Standalone utilities for interacting with the system clipboard.
 */
object ClipboardUtils {
    @JvmStatic
    fun copyTextView(view: View) {
        val data = when (view) {
            is TextInputEditText -> Pair(view.editableText, view.hint)
            is TextView -> Pair(view.text, view.contentDescription)
            else -> return
        }
        if (data.first == null || data.first.isEmpty()) {
            return
        }
        val service = view.context.getSystemService<ClipboardManager>() ?: return
        val clip = ClipData.newPlainText(data.second, data.first)
        // Помечаем скопированное как чувствительное (аудит 19-08, A4): отсюда копируют приватные
        // ключи и адреса туннеля, а на Android 13+ система показывает превью содержимого буфера
        // всем, кто смотрит на экран, и складывает значение в историю буфера.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            clip.description.extras = PersistableBundle().apply {
                putBoolean(ClipDescription.EXTRA_IS_SENSITIVE, true)
            }
        }
        service.setPrimaryClip(clip)
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            Snackbar.make(view, view.context.getString(R.string.copied_to_clipboard, data.second), Snackbar.LENGTH_LONG).show()
        }
    }
}
