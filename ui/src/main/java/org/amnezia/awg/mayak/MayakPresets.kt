// Пресеты split-туннеля на клиенте (SPEC-0028). Тянет пресеты с ядра (системные «РФ напрямую» + свои),
// кэширует в filesDir, резолвит АКТИВНЫЙ пресет в (приложения, excluded) для коннекта. Фолбэк офлайн —
// системный пресет из зашитого ассета mayak_ru_direct.json. Синхрон — на старте/после логина (НЕ при
// коннекте: DPI палит домен рядом с хендшейком, см. MayakSession).
package org.amnezia.awg.mayak

import android.Manifest
import android.content.Context
import android.util.Log
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import org.amnezia.awg.mayak.core.MayakBackend
import org.amnezia.awg.mayak.core.Preset
import org.json.JSONObject
import java.io.File

object MayakPresets {
    private const val TAG = "AmneziaWG/Presets"
    private const val CACHE = "presets_cache.json"
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    @Volatile private var memCache: List<Preset>? = null

    private fun cacheFile(ctx: Context) = File(ctx.applicationContext.filesDir, CACHE)

    /** Синхронизация с сервером: тянет пресеты (системные+свои), кэширует. Best-effort (ошибка → тихо). */
    suspend fun sync(ctx: Context, backend: MayakBackend, token: String) {
        val list = runCatching { backend.listPresets(token) }.getOrNull() ?: return
        if (list.isEmpty()) return
        runCatching {
            val tmp = File(ctx.applicationContext.filesDir, "$CACHE.tmp")
            tmp.writeText(json.encodeToString(ListSerializer(Preset.serializer()), list))
            if (!tmp.renameTo(cacheFile(ctx))) { tmp.copyTo(cacheFile(ctx), overwrite = true); tmp.delete() }
            memCache = list
            Log.i(TAG, "пресеты синхронизированы: ${list.size}")
        }.onFailure { Log.w(TAG, "не смог записать кэш пресетов: ${it.message}") }
    }

    fun invalidate() { memCache = null }

    /** Список пресетов из кэша; фолбэк — синтетический системный «РФ напрямую» из зашитого ассета. */
    fun cached(ctx: Context): List<Preset> {
        memCache?.let { return it }
        val f = cacheFile(ctx)
        if (f.exists() && f.length() > 0) {
            runCatching {
                val list = json.decodeFromString(ListSerializer(Preset.serializer()), f.readText())
                memCache = list
                return list
            }.onFailure { Log.w(TAG, "битый кэш пресетов, беру фолбэк: ${it.message}") }
        }
        return fallbackSystem(ctx)?.let { listOf(it) } ?: emptyList()
    }

    private fun fallbackSystem(ctx: Context): Preset? = runCatching {
        val text = ctx.applicationContext.assets.open("mayak_ru_direct.json").bufferedReader().use { it.readText() }
        val o = JSONObject(text)
        fun arr(k: String): List<String> {
            val a = o.optJSONArray(k) ?: return emptyList()
            return (0 until a.length()).map { a.getString(it) }
        }
        Preset(id = -1L, name = "РФ напрямую", mode = "exclude", source = "system", owned = false,
            regex = arr("regex"), exceptions = arr("exceptions"), apps = arr("apps"))
    }.getOrNull()

    /** Активный пресет (по сохранённому id); если не найден — первый системный (дефолт). */
    fun activePreset(ctx: Context): Preset? {
        val id = MayakPrefs.activePresetId(ctx)
        val list = cached(ctx)
        return list.firstOrNull { it.id == id } ?: list.firstOrNull { it.source == "system" } ?: list.firstOrNull()
    }

    /** Эффективный split для коннекта: тумблер пресета ВКЛ → режим+приложения активного пресета,
     *  иначе весь трафик в VPN. Возвращает (пакеты, excluded) для ConfRenderer.withSplitTunnel. */
    fun effectiveSplit(ctx: Context): Pair<List<String>, Boolean> {
        if (!MayakPrefs.presetEnabled(ctx)) return emptyList<String>() to true
        val p = activePreset(ctx) ?: return emptyList<String>() to true
        return when (p.mode) {
            "all" -> emptyList<String>() to true                 // весь трафик в VPN
            "include" -> resolveApps(ctx, p).toList() to false   // только выбранные — В VPN
            else -> resolveApps(ctx, p).toList() to true         // exclude: выбранные — МИМО VPN
        }
    }

    /** Приложения пресета среди УСТАНОВЛЕННЫХ (addDisallowedApplication кидает на неустановленный пакет).
     *  Системный (rule-based): (matches regex И не в исключениях) ИЛИ в явных. Пользовательский: явные пакеты. */
    fun resolveApps(ctx: Context, p: Preset): Set<String> {
        val pm = ctx.packageManager
        val self = ctx.packageName
        val installed = runCatching {
            pm.getPackagesHoldingPermissions(arrayOf(Manifest.permission.INTERNET), 0).mapNotNull { it.packageName }.toSet()
        }.getOrElse { emptySet() }
        if (p.regex.isEmpty() && p.exceptions.isEmpty()) {
            return p.apps.filter { it != self && installed.contains(it) }.toSet()
        }
        val regexes = p.regex.mapNotNull { runCatching { Regex(it) }.getOrNull() }
        val exc = p.exceptions.toSet()
        val explicit = p.apps.toSet()
        val out = HashSet<String>()
        for (pkg in installed) {
            if (pkg == self || pkg in exc) continue
            if (explicit.contains(pkg) || regexes.any { it.matches(pkg) }) out.add(pkg)
        }
        return out
    }
}
