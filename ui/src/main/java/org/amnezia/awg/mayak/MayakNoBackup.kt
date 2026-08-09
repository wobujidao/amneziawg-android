// Кэш-файлы Маяка (пресеты split-туннеля, OTA-список «РФ напрямую») живут в noBackupFilesDir.
// В манифесте и так allowBackup="false", но каталог noBackup — это гарантия ПО МЕСТУ, а не по флагу:
// содержимое не попадает ни в облачный бэкап, ни в device-to-device перенос, даже если флаг когда-то
// дрогнет (Auto Backup, ADB backup, миграция на новый телефон). Кэши — это серверные данные аккаунта,
// им в чужих бэкапах делать нечего.
//
// Люди обновляются с версий, где кэши писались в filesDir, поэтому файл ПЕРЕНОСИТСЯ (rename в
// пределах одного тома /data — атомарен и дёшев), а не сбрасывается: пресеты нужны офлайн-фолбэку
// при коннекте без сети. Старый файл и его .tmp после переноса прибираются — в filesDir ничего
// не остаётся.
package org.amnezia.awg.mayak

import android.content.Context
import java.io.File

object MayakNoBackup {
    /** Файл кэша по имени: новый путь в noBackupFilesDir + одноразовый перенос из старого filesDir. */
    fun file(ctx: Context, name: String): File {
        val app = ctx.applicationContext
        return resolve(app.noBackupFilesDir, app.filesDir, name)
    }

    /**
     * Чистая логика (без Android — под JVM-тестом): возвращает целевой файл в [noBackupDir],
     * предварительно перенеся туда одноимённый файл из [legacyDir], если тот остался от прежних
     * версий. Если целевой файл уже есть, старый просто удаляется (новый свежее по построению:
     * писать по новому пути начали позже). Осиротевший `<name>.tmp` в старом каталоге тоже прибирается.
     */
    fun resolve(noBackupDir: File, legacyDir: File, name: String): File {
        val target = File(noBackupDir, name)
        val legacy = File(legacyDir, name)
        if (legacy.exists()) {
            runCatching {
                if (!target.exists() && !legacy.renameTo(target)) legacy.copyTo(target, overwrite = false)
            }
            runCatching { legacy.delete() }
        }
        val legacyTmp = File(legacyDir, "$name.tmp")
        if (legacyTmp.exists()) runCatching { legacyTmp.delete() }
        return target
    }
}
