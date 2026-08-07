// Диагностический лог, который СОБРАЛИ, но не смогли ДОСТАВИТЬ (сеть недоступна, ядро не отвечает,
// сам туннель сломан — ровно та ситуация, когда человеку нужна помощь). До 0.3.99 такой лог просто
// пропадал: POST не прошёл — и всё, ни очереди, ни файла, ни возможности переслать его самому.
//
// Чистый Kotlin (java.io.File, без Android): путь на диске вычисляет вызывающий код (:ui знает
// cacheDir), здесь только чтение/запись/удаление РОВНО ОДНОГО файла — историю неудачных попыток
// копить незачем, важен только последний снимок состояния устройства.
package org.amnezia.awg.mayak.core

import java.io.File

object PendingDiagLog {
    private val json = MayakBackend.defaultJson

    /** Сохранить запрос на диск. Перезаписывает прошлый, если он там был. */
    fun save(file: File, req: DiagLogRequest) {
        file.parentFile?.mkdirs()
        file.writeText(json.encodeToString(DiagLogRequest.serializer(), req))
    }

    /** Прочитать сохранённый запрос. null — файла нет или он повреждён (битый JSON не должен ронять
     *  приложение — считаем, что сохранённого лога просто нет). */
    fun read(file: File): DiagLogRequest? {
        if (!file.exists()) return null
        return runCatching { json.decodeFromString(DiagLogRequest.serializer(), file.readText()) }.getOrNull()
    }

    /** Есть ли сохранённый лог — этим решаем, показывать ли кнопку «Поделиться» в настройках. */
    fun exists(file: File): Boolean = file.exists()

    /** Удалить сохранённый лог (после успешной досылки или явной пересылки файлом человеком). */
    fun clear(file: File) {
        file.delete()
    }
}
