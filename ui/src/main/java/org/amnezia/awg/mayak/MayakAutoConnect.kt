// Автоподключение «Маяк» БЕЗ UI (SPEC-0018 F3, kill-switch + автоподключение). Поднимает туннель из
// ПОСЛЕДНЕГО РАБОЧЕГО конфига на диске (MayakSession.lastGoodPaths — переживает ребут/смерть процесса),
// когда система стартует наш VpnService по Always-On VPN или после загрузки устройства (BOOT_COMPLETED).
//
// Почему это работает без сети: туннель идёт устройство→ЭКЗИТ, ядро лишь ВЫДАЁТ конфиг. Раз мы уже
// подключались к этой стране, .conf лежит зашифрованным на диске (overlay-IP на устройство стабилен,
// SPEC-0015) → авто-подъём не дёргает api.mayakvpn.ru (важно: РФ-DPI палит наш домен рядом с хендшейком).
//
// Гейтится MayakPrefs.autoConnect (по умолчанию ВЫКЛ) — фича включается пользователем в Настройках вместе
// с системным Always-On VPN. Идемпотентно: если туннель уже поднят — no-op.
package org.amnezia.awg.mayak

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.amnezia.awg.mayak.core.ConfRenderer

object MayakAutoConnect {
    private const val TAG = "Mayak/AutoConnect"

    /** Поднять последний рабочий туннель, если включено автоподключение и есть сохранённый конфиг.
     *  Ничего не делает (и НЕ бросает) при: выкл автоподключении, уже поднятом туннеле, отсутствии
     *  сохранённого рабочего конфига, любой ошибке подъёма. Возвращает true, если туннель в итоге поднят. */
    suspend fun bringUpIfEnabled(context: Context): Boolean = withContext(Dispatchers.IO) {
        val ctx = context.applicationContext
        if (!MayakPrefs.autoConnect(ctx)) {
            Log.i(TAG, "автоподключение выключено — пропуск")
            return@withContext false
        }
        val tunnel = GoTunnel(ctx)
        if (tunnel.isUp()) {
            Log.i(TAG, "туннель уже поднят — пропуск")
            return@withContext true
        }
        val dirId = MayakPrefs.lastDirectionId(ctx)
        if (dirId < 0L) {
            Log.i(TAG, "нет последней выбранной страны — нечего поднимать")
            return@withContext false
        }
        val store = KeystoreSecureStore(ctx)
        val session = MayakSession(store, AwgKeyProvider(), AndroidHwidProvider(ctx, store))
        val paths = session.lastGoodPaths(dirId)
        if (paths == null) {
            Log.i(TAG, "нет сохранённого рабочего конфига (нужно раз подключиться вручную) — пропуск")
            return@withContext false
        }
        // Прямой путь приоритетен (как в MayakActivity.doConnect); резерв — релей.
        val conf = paths.directConf ?: paths.relayConf
        val endpoint = paths.directEndpoint ?: paths.relayEndpoint
        if (conf == null) {
            Log.i(TAG, "сохранённый конфиг без плеч — пропуск")
            return@withContext false
        }
        return@withContext try {
            tunnel.up(prepareConf(ctx, conf))
            // Метки для уведомления «Подключено» (процесс-скоупны в GoTunnel): на headless-подъёме
            // Activity нет, поэтому проставляем здесь, чтобы шторка/повторное открытие показали страну.
            GoTunnel.connectedLabel = paths.directionName
            GoTunnel.connectedServerHost = MayakPing.hostOf(endpoint)
            runCatching { MayakNotification.show(ctx, paths.directionName, null, ipv6 = false) }
            Log.i(TAG, "туннель поднят автоподключением: ${paths.directionName}")
            true
        } catch (e: Exception) {
            // Возможные причины: нет согласия на VPN (ревокнули), фон-старт VpnService зарезан системой
            // (Android O+ вне Always-On), протухший конфиг. Не критично — тихо, основной путь всё равно
            // системный Always-On VPN; пользователь увидит отсутствие коннекта и подключится вручную.
            Log.w(TAG, "автоподключение не удалось: ${e.message}")
            false
        }
    }

    /** Готовит .conf к подъёму так же, как MayakActivity.prepareConf: IPv6-тумблер (SPEC-0014) + split-
     *  туннель (SPEC-0018 F1). Оба — трансформы строки из настроек пользователя; пустой split — no-op. */
    private fun prepareConf(ctx: Context, conf: String): String {
        val stripped = if (MayakPrefs.useIpv6(ctx)) conf else ConfRenderer.stripIpv6(conf)
        // split-туннель: ручной (SPEC-0018 F1) ∪ RU-пресет «российские сервисы напрямую» (2026-07-09).
        val (apps, excluded) = MayakRuDirect.effectiveSplit(ctx)
        return ConfRenderer.withSplitTunnel(stripped, apps, excluded)
    }
}
