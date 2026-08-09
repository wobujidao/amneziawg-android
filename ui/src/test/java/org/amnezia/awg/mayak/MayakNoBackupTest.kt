// Сторож переноса кэшей в noBackupFilesDir (MayakNoBackup): люди обновляются с версий, где кэши
// лежали в filesDir — файл обязан ПЕРЕЕХАТЬ (не потеряться), а в старом каталоге не должно остаться
// ни его, ни осиротевшего .tmp. Чистый JVM-тест: логика в resolve() нарочно не трогает Android.
package org.amnezia.awg.mayak

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class MayakNoBackupTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var noBackup: File
    private lateinit var legacy: File

    private fun dirs(): Pair<File, File> {
        noBackup = tmp.newFolder("no_backup")
        legacy = tmp.newFolder("files")
        return noBackup to legacy
    }

    @Test
    fun `новый путь — в noBackupFilesDir`() {
        dirs()
        val f = MayakNoBackup.resolve(noBackup, legacy, "presets_cache.json")
        assertEquals(File(noBackup, "presets_cache.json"), f)
    }

    @Test
    fun `старый файл переезжает с содержимым, в filesDir не остаётся`() {
        dirs()
        File(legacy, "presets_cache.json").writeText("[{\"id\":1}]")
        val f = MayakNoBackup.resolve(noBackup, legacy, "presets_cache.json")
        assertTrue("кэш обязан пережить перенос", f.exists())
        assertEquals("[{\"id\":1}]", f.readText())
        assertFalse("старый файл не должен болтаться", File(legacy, "presets_cache.json").exists())
    }

    @Test
    fun `если новый файл уже есть — он не затирается старым, старый прибирается`() {
        dirs()
        File(noBackup, "c.json").writeText("новый")
        File(legacy, "c.json").writeText("старый")
        val f = MayakNoBackup.resolve(noBackup, legacy, "c.json")
        assertEquals("новый", f.readText())
        assertFalse(File(legacy, "c.json").exists())
    }

    @Test
    fun `осиротевший tmp в старом каталоге прибирается`() {
        dirs()
        File(legacy, "c.json.tmp").writeText("огрызок незавершённой записи")
        MayakNoBackup.resolve(noBackup, legacy, "c.json")
        assertFalse(File(legacy, "c.json.tmp").exists())
    }

    @Test
    fun `повторный вызов после переноса — просто путь, ничего не ломает`() {
        dirs()
        File(legacy, "c.json").writeText("данные")
        val first = MayakNoBackup.resolve(noBackup, legacy, "c.json")
        val second = MayakNoBackup.resolve(noBackup, legacy, "c.json")
        assertEquals(first, second)
        assertEquals("данные", second.readText())
    }
}
