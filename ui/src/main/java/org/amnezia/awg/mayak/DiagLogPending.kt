// Мост Context → :core.PendingDiagLog (0.3.99): вычисляет путь на диске и оркестрирует «сначала
// дошлём то, что осталось с прошлого раза». Само чтение/запись/удаление файла — в :core (проверено
// юнит-тестом на JVM, PendingDiagLogTest); здесь только то, про что :core ничего не знает — cacheDir
// и сеть (session.sendDiagLog ходит через MayakBackend, живущий в :ui).
package org.amnezia.awg.mayak

import android.content.Context
import android.net.Uri
import androidx.core.content.FileProvider
import org.amnezia.awg.mayak.core.DiagLogRequest
import org.amnezia.awg.mayak.core.MayakBackend
import org.amnezia.awg.mayak.core.PendingDiagLog
import java.io.File

object DiagLogPending {
    // Тот же авторитет, что у самообновления (mayak_update_paths.xml) — заводить отдельный
    // <provider> ради одного JSON-файла незачем, там уже заведён путь "diag/".
    private const val AUTHORITY_SUFFIX = ".updates"
    private const val FILE_NAME = "mayak-diag-log.json"

    fun file(context: Context): File = File(File(context.cacheDir, "diag"), FILE_NAME)

    /** Есть ли на диске лог с прошлой неудачной попытки — решает, показывать ли «Поделиться логом». */
    fun exists(context: Context): Boolean = PendingDiagLog.exists(file(context))

    /** Сохранить запрос, который не удалось отправить (перезаписывает прошлый). */
    fun save(context: Context, req: DiagLogRequest) = PendingDiagLog.save(file(context), req)

    fun clear(context: Context) = PendingDiagLog.clear(file(context))

    /**
     * Дослать то, что осталось с прошлого раза, — ПЕРЕД сбором нового лога (и в ручной кнопке
     * «Отправить лог», и в авто-заливке MayakActivity.maybeAutoSendDiag). Успех — файл стирается,
     * чтобы не копился. Сбой — тихо оставляем файл на следующий раз: это фоновая подстраховка перед
     * основной попыткой, а не то, о чём стоит отдельно докладывать человеку прямо сейчас.
     */
    suspend fun flush(context: Context, session: MayakSession, backend: MayakBackend) {
        val f = file(context)
        val pending = PendingDiagLog.read(f) ?: return
        try {
            session.sendDiagLog(backend, pending)
            PendingDiagLog.clear(f)
        } catch (_: Exception) { /* сеть по-прежнему не работает — оставляем файл на следующий раз */ }
    }

    /** Uri для «Поделиться» через FileProvider (человек пересылает файл в мессенджер сам). */
    fun shareUri(context: Context): Uri =
        FileProvider.getUriForFile(context, context.packageName + AUTHORITY_SUFFIX, file(context))
}
