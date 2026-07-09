// RU-пресет split-туннеля (BlancVPN-parity, 2026-07-09): «Открывать российские сервисы напрямую».
// Одной кнопкой пускает установленные РФ-приложения (банки/госуслуги/маркетплейсы/стриминги) МИМО
// VPN — на них загран-IP часто режется/просит доп. проверки, а с прямым выходом они работают как дома.
// Правила ведём СВОИ в ui/assets/mayak_ru_direct.json (сид — разбор конкурента: regex ^ru\. + исключения
// + явный список не-ru.* РФ-пакетов). В будущем список делаем OTA-обновляемым (manifest+sha256).
package org.amnezia.awg.mayak

import android.Manifest
import android.content.Context
import android.util.Log
import org.json.JSONObject

object MayakRuDirect {
    private const val TAG = "AmneziaWG/RuDirect"
    private const val ASSET = "mayak_ru_direct.json"

    private data class Rules(val regexes: List<Regex>, val exceptions: Set<String>, val explicit: Set<String>)

    @Volatile private var cache: Rules? = null

    private fun rules(ctx: Context): Rules {
        cache?.let { return it }
        return synchronized(this) {
            cache ?: run {
                val text = ctx.applicationContext.assets.open(ASSET).bufferedReader().use { it.readText() }
                val o = JSONObject(text)
                val rx = ArrayList<Regex>()
                o.optJSONArray("regex")?.let { arr -> for (i in 0 until arr.length()) rx.add(Regex(arr.getString(i))) }
                val exc = HashSet<String>()
                o.optJSONArray("exceptions")?.let { arr -> for (i in 0 until arr.length()) exc.add(arr.getString(i)) }
                val exp = HashSet<String>()
                o.optJSONArray("apps")?.let { arr -> for (i in 0 until arr.length()) exp.add(arr.getString(i)) }
                Rules(rx, exc, exp).also { cache = it }
            }
        }
    }

    /** Установленные РФ-приложения (держащие INTERNET, как в AppListDialogFragment), которые по правилам
     *  идут напрямую: (matches regex И не в exceptions) ИЛИ в явном списке. Сам Маяк не исключаем. */
    fun installedDirectApps(ctx: Context): Set<String> {
        val r = runCatching { rules(ctx) }.getOrElse {
            Log.w(TAG, "не смог прочитать $ASSET: ${it.message}")
            return emptySet()
        }
        val pm = ctx.packageManager
        val holders = runCatching {
            pm.getPackagesHoldingPermissions(arrayOf(Manifest.permission.INTERNET), 0)
        }.getOrElse {
            Log.w(TAG, "getPackagesHoldingPermissions упал: ${it.message}")
            emptyList()
        }
        val out = HashSet<String>()
        for (pi in holders) {
            val p = pi.packageName ?: continue
            if (p == ctx.packageName) continue
            if (r.exceptions.contains(p)) continue
            if (r.explicit.contains(p) || r.regexes.any { rx -> rx.matches(p) }) out.add(p)
        }
        Log.i(TAG, "RU-пресет: установлено РФ-приложений для прямого выхода: ${out.size}") // диаг (без имён — ПДн)
        return out
    }

    /** Эффективный split-туннель для коннекта: ручной split (SPEC-0018 F1) ∪ RU-пресет.
     *  Возвращает (пакеты, excluded). RU-пресет = РФ мимо туннеля (excluded=true). Если ручной режим
     *  инверсный (only-these) и пресет ВКЛ — приоритет у пресета (excluded), т.к. смешать
     *  Included+Excluded в одном .conf нельзя. Пресет ВЫКЛ → отдаём ручной split как есть. */
    fun effectiveSplit(ctx: Context): Pair<List<String>, Boolean> {
        val manual = MayakPrefs.splitApps(ctx)
        val manualExcluded = MayakPrefs.splitExcluded(ctx)
        if (!MayakPrefs.ruDirect(ctx)) return manual.toList() to manualExcluded
        val ru = installedDirectApps(ctx)
        return if (manualExcluded) (manual + ru).distinct() to true
        else ru.toList() to true
    }
}
