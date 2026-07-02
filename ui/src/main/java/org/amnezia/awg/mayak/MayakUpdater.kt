// Самообновление, Вариант Б (директива владельца 2026-07-02): скачиваем APK ВНУТРИ приложения с
// прогрессом, проверяем подпись (совпадает с нашей — защита от подмены), запускаем установщик,
// после — чистим скачанное. Только HTTPS с нашего домена.
package org.amnezia.awg.mayak

import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import kotlin.coroutines.coroutineContext

object MayakUpdater {
    private const val DIR = "updates"
    private const val APK = "mayak-update.apk"

    private fun dir(context: Context) = File(context.cacheDir, DIR)

    /** Удалить скачанные APK (вызов на старте — «подчистить лишнее» после обновления). */
    fun cleanup(context: Context) {
        runCatching { dir(context).deleteRecursively() }
    }

    /** Скачать APK по url в кэш с прогрессом (0..100). null при ошибке/отмене. Только https. */
    suspend fun download(context: Context, url: String, onProgress: (Int) -> Unit): File? =
        withContext(Dispatchers.IO) {
            runCatching {
                require(url.startsWith("https://")) { "нужен https" }
                val d = dir(context).apply { mkdirs() }
                val out = File(d, APK)
                val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                    connectTimeout = 15000; readTimeout = 30000
                    setRequestProperty("Accept", "application/vnd.android.package-archive")
                }
                try {
                    if (conn.responseCode !in 200..299) return@runCatching null
                    val total = conn.contentLengthLong
                    conn.inputStream.use { input ->
                        out.outputStream().use { output ->
                            val buf = ByteArray(64 * 1024)
                            var read = 0L
                            while (true) {
                                if (!coroutineContext.isActive) return@runCatching null
                                val n = input.read(buf)
                                if (n < 0) break
                                output.write(buf, 0, n)
                                read += n
                                if (total > 0) onProgress(((read * 100) / total).toInt().coerceIn(0, 100))
                            }
                        }
                    }
                } finally {
                    conn.disconnect()
                }
                out
            }.getOrNull()
        }

    /** Подпись скачанного APK совпадает с нашей И это то же приложение (пакет), не даунгрейд? */
    fun isTrusted(context: Context, apk: File): Boolean = runCatching {
        val pm = context.packageManager
        val dl = pm.getPackageArchiveInfo(apk.path, sigFlag()) ?: return false
        if (dl.packageName != context.packageName) return false // чужой пакет — не ставим
        val me = pm.getPackageInfo(context.packageName, sigFlag())
        val a = certHashes(dl); val b = certHashes(me)
        a.isNotEmpty() && a == b
    }.getOrDefault(false)

    /** Может ли приложение запускать установку APK (Android 8+ требует разрешения источника)? */
    fun canInstall(context: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.O || context.packageManager.canRequestPackageInstalls()

    /** Экран системных настроек «Разрешить установку из этого источника». */
    fun installPermissionIntent(context: Context): Intent =
        Intent(android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))

    /** Запустить системный установщик для скачанного APK (через FileProvider). */
    fun install(context: Context, apk: File) {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.updates", apk)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    }

    @Suppress("DEPRECATION")
    private fun sigFlag(): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) PackageManager.GET_SIGNING_CERTIFICATES
        else PackageManager.GET_SIGNATURES

    @Suppress("DEPRECATION")
    private fun certHashes(pi: PackageInfo): Set<String> {
        val sigs = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P)
            pi.signingInfo?.apkContentsSigners
        else pi.signatures
        return sigs?.map { sha256(it.toByteArray()) }?.toSet() ?: emptySet()
    }

    private fun sha256(b: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(b).joinToString("") { "%02x".format(it) }
}
