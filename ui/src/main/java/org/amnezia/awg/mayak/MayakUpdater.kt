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
import android.util.Log
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.core.content.FileProvider
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.amnezia.awg.R
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import kotlin.coroutines.coroutineContext

object MayakUpdater {
    private const val DIR = "updates"
    private const val APK = "mayak-update.apk"

    private fun dir(context: Context) = File(context.cacheDir, DIR)

    /**
     * Установлено ли приложение из Google Play.
     *
     * Зачем это апдейтеру: сборка из Play подписана ключом Google (Play App Signing), а APK с сайта —
     * нашим. Предлагать такому человеку «обновиться» с сайта бессмысленно и вредно: он скачивает файл,
     * ждёт, а установщик отказывает по несовпадению подписи — и выглядит это как поломка приложения,
     * а не как разные каналы. Владелец наткнулся сам 2026-08-06: «нажимаю обновить, она качает версию
     * с сайта, хотя я обновлялся через Google Play, и не ставит — что за бред».
     *
     * Способ определения уже есть в проекте (DiagCollector/телеметрия) — апдейтер просто не спрашивал.
     */
    fun installedFromPlay(context: Context): Boolean = try {
        val pm = context.packageManager
        val installer = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R)
            pm.getInstallSourceInfo(context.packageName).installingPackageName
        else @Suppress("DEPRECATION") pm.getInstallerPackageName(context.packageName)
        installer == "com.android.vending" || installer == "com.google.android.feedback"
    } catch (e: Exception) {
        // Не смогли определить — считаем, что НЕ из Play: это возвращает прежнее поведение
        // (обновление с сайта), а не отключает обновления совсем.
        false
    }

    /** Открыть карточку приложения в Play (обновление там делает сам Play). */
    fun openPlay(context: Context) {
        val market = Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=" + context.packageName))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val web = Intent(Intent.ACTION_VIEW,
            Uri.parse("https://play.google.com/store/apps/details?id=" + context.packageName))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { context.startActivity(market) }.recoverCatching { context.startActivity(web) }
    }

    /** Удалить скачанные APK (вызов на старте — «подчистить лишнее» после обновления). */
    fun cleanup(context: Context) {
        runCatching { dir(context).deleteRecursively() }
    }

    /**
     * Скачать APK по url в кэш с прогрессом (0..100). null при ошибке/отмене.
     *
     * Только https И только с НАШЕГО домена: `apkUrl` приходит из version.json, то есть снаружи, и
     * раньше принимался любой https-хост (ревью #3). Настоящий гейт — совпадение подписи (isTrusted),
     * это второй слой: не тянуть чужой файл вообще.
     *
     * Домен не зашит константой намеренно. Прод-домены ещё поменяются (решение владельца о .com), а
     * зашитый список ломает самообновление молча: люди остаются на старой версии, и никто не узнает.
     * Поэтому сверяем с доменами ЯДРА, с которыми приложение уже работает (`coreBases` — весь список
     * из реестра/сборки, а не только текущий): apk обязан лежать на том же домене второго уровня.
     * Переедет ядро — переедет и разрешённый домен.
     *
     * ⚠️ Именно СПИСОК, а не текущий адрес: при заблокированном домене приложение работает по
     * IP-фолбэку, и «домен второго уровня» текущего адреса — `128.138`. Сверка с ним отвергла бы
     * законную ссылку на APK, то есть выключила бы самообновление ровно там, где оно нужнее всего.
     */
    suspend fun download(context: Context, url: String, coreBases: List<String>, onProgress: (Int) -> Unit): File? =
        withContext(Dispatchers.IO) {
            runCatching {
                require(url.startsWith("https://")) { "нужен https" }
                require(sameSite(url, coreBases)) { "apk не с нашего домена" }
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
    fun isTrusted(context: Context, apk: File): Boolean = checkTrust(context, apk) == null

    /**
     * Та же проверка, но с ПРИЧИНОЙ отказа: null — можно ставить, иначе короткая строка «что не
     * сошлось».
     *
     * Зачем причина. 12-08 на Galaxy S8+ (Android 9) обновление с сайта упиралось в «Проверка подписи
     * не пройдена — установка отменена», и по этой надписи нельзя было понять НИЧЕГО: ключ тот же,
     * пакет тот же, версия новее. Разбирать пришлось по чужим машинам, а приложение молчало даже в
     * диагностике, которую само же и присылает. Проверка, которая умеет только «нет», — это проверка,
     * которую нечем чинить.
     */
    fun checkTrust(context: Context, apk: File): String? = runCatching {
        val pm = context.packageManager
        val dl = pm.getPackageArchiveInfo(apk.path, sigFlag())
            ?: return context.getString(R.string.mayak_update_untrusted_unreadable)
        if (dl.packageName != context.packageName) {
            return context.getString(R.string.mayak_update_untrusted_other_app, dl.packageName)
        }
        val me = pm.getPackageInfo(context.packageName, sigFlag())
        // «не даунгрейд» обещал KDoc, а проверялись только пакет и подпись (ревью #4). Android сам
        // режет откат при той же подписи, но полагаться на это — значит держать обещание чужими
        // руками: подсунутый старый (наш же, подписанный) APK проходил бы наш гейт.
        if (versionCode(dl) < versionCode(me)) {
            return context.getString(
                R.string.mayak_update_untrusted_downgrade, versionCode(dl), versionCode(me),
            )
        }
        val a = certHashes(dl)
        val b = certHashes(me)
        when {
            // 🔴 Пустой набор — НЕ «подпись чужая». Это «мы не смогли её прочитать», и до 12-08 два
            // этих случая были для нас одним: обе ветки давали одинаковый отказ, а причина у них
            // разная и лечение тоже. Разделяем явно, иначе следующий такой случай снова уйдёт в
            // «наверное, ключ не тот».
            a.isEmpty() -> context.getString(R.string.mayak_update_untrusted_no_sig_file)
            b.isEmpty() -> context.getString(R.string.mayak_update_untrusted_no_sig_app)
            a != b -> context.getString(R.string.mayak_update_untrusted_other_key)
            else -> null
        }
    }.getOrElse { e ->
        context.getString(R.string.mayak_update_untrusted_crashed, e.javaClass.simpleName)
    }

    /**
     * Один ли домен второго уровня у ссылки на APK и у ядра. Сравниваем ровно две последние метки
     * («mayakvpn.ru»), потому что раздача APK живёт на apex, а API — на `api.`; публичного списка
     * суффиксов в приложении нет и тащить его ради одной проверки незачем.
     */
    private fun sameSite(url: String, coreBases: List<String>): Boolean = runCatching {
        val apk = URL(url).host.lowercase()
        if (apk.isEmpty() || isIpLiteral(apk)) return false // APK по голому IP не принимаем никогда
        val site = { h: String -> h.split('.').takeLast(2).joinToString(".") }
        val want = site(apk)
        coreBases.any { base ->
            val h = runCatching { URL(base).host.lowercase() }.getOrDefault("")
            h.isNotEmpty() && !isIpLiteral(h) && site(h) == want
        }
    }.getOrDefault(false)

    /** Хост — голый IP (v4 или v6-в-скобках)? У таких «домена второго уровня» нет. */
    private fun isIpLiteral(host: String): Boolean =
        host.startsWith("[") || host.all { it.isDigit() || it == '.' }

    @Suppress("DEPRECATION")
    private fun versionCode(pi: PackageInfo): Long =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) pi.longVersionCode else pi.versionCode.toLong()

    /** Может ли приложение запускать установку APK (Android 8+ требует разрешения источника)? */
    fun canInstall(context: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.O || context.packageManager.canRequestPackageInstalls()

    /** Экран системных настроек «Разрешить установку из этого источника». */
    fun installPermissionIntent(context: Context): Intent =
        Intent(android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))

    /**
     * Весь путь обновления с сайта одной кнопкой: разрешение → скачивание с прогрессом → проверка
     * подписи → системный установщик.
     *
     * Вынесено сюда из MayakActivity, когда появился второй вызывающий (неотменяемый экран
     * «сборка больше не работает», MayakOutdatedActivity). Двух копий этого куска быть не должно:
     * внутри него живёт [isTrusted] — гейт, который не даёт поставить чужой APK. Починив его однажды
     * в одном месте из двух, мы получили бы экран, устанавливающий непроверенный файл, и заметить это
     * было бы нечем.
     */
    fun runUpdate(activity: androidx.appcompat.app.AppCompatActivity, apkUrl: String, knownBases: List<String>) {
        if (apkUrl.isBlank()) return
        if (!canInstall(activity)) {
            // Android 8+: нужно разрешение «установка из этого источника» — ведём в настройки, затем повтор.
            Toast.makeText(activity, R.string.mayak_update_need_perm, Toast.LENGTH_LONG).show()
            runCatching { activity.startActivity(installPermissionIntent(activity)) }
            return
        }
        val bar = android.widget.ProgressBar(activity, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100; isIndeterminate = false
            val p = (16 * activity.resources.displayMetrics.density).toInt()
            setPadding(p * 3, p, p * 3, p)
        }
        val dlg = AlertDialog.Builder(activity)
            .setTitle(R.string.mayak_update_downloading)
            .setView(bar)
            .setCancelable(false)
            .create()
        dlg.show()
        activity.lifecycleScope.launch {
            val apk = download(activity, apkUrl, knownBases) { pct ->
                activity.runOnUiThread { bar.progress = pct }
            }
            runCatching { dlg.dismiss() }
            if (apk == null) {
                Toast.makeText(activity, R.string.mayak_update_download_failed, Toast.LENGTH_LONG).show()
                return@launch
            }
            val why = checkTrust(activity, apk)
            if (why != null) {
                apk.delete() // чужая подпись/пакет/нечитаемый файл — не ставим
                // Причину говорим ВСЛУХ. Раньше здесь была одна строка на все случаи, и человек с
                // рабочим файлом и верным ключом видел ровно то же, что человек с подделкой.
                Toast.makeText(activity,
                    activity.getString(R.string.mayak_update_untrusted) + ": " + why,
                    Toast.LENGTH_LONG).show()
                // В лог — тем же тегом, что и остальная наша диагностика: он уезжает к нам
                // авто-заливкой, и в следующий раз причина будет видна без телефона в руках.
                Log.w("mayak", "самообновление: файл отклонён — $why")
                return@launch
            }
            runCatching { install(activity, apk) }
        }
    }

    /** Запустить системный установщик для скачанного APK (через FileProvider). */
    fun install(context: Context, apk: File) {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.updates", apk)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    }

    /**
     * Флаги запроса подписи. С Android 9 просим ОБА, и это не «на всякий случай».
     *
     * `GET_SIGNING_CERTIFICATES` — правильный современный флаг, но заполняет он поле `signingInfo`,
     * а его для СКАЧАННОГО ФАЙЛА (getPackageArchiveInfo, а не установленного пакета) отдают не все
     * прошивки: на части девяток оно приходит пустым, и тогда единственный источник — старое поле
     * `signatures`, которое живёт под `GET_SIGNATURES`. Одного флага мало: с новым остаёмся без
     * ответа на старых прошивках, со старым — теряем историю ротации ключа на новых.
     */
    @Suppress("DEPRECATION")
    private fun sigFlag(): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P)
            PackageManager.GET_SIGNING_CERTIFICATES or PackageManager.GET_SIGNATURES
        else PackageManager.GET_SIGNATURES

    /**
     * Отпечатки сертификатов, которыми подписан пакет. Берём `signingInfo`, если он есть, иначе
     * откатываемся на `signatures` — см. sigFlag(): пустой `signingInfo` у скачанного файла на
     * Android 9 и есть та самая поломка, из-за которой самообновление молча отказывало.
     */
    @Suppress("DEPRECATION")
    private fun certHashes(pi: PackageInfo): Set<String> {
        val modern = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P)
            pi.signingInfo?.apkContentsSigners else null
        val sigs = if (!modern.isNullOrEmpty()) modern else pi.signatures
        return sigs?.filterNotNull()?.map { sha256(it.toByteArray()) }?.toSet() ?: emptySet()
    }

    private fun sha256(b: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(b).joinToString("") { "%02x".format(it) }
}
