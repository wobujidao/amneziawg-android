// Плитка «Маяк» в шторке Android: подключить/отключить, не открывая приложение.
//
// Зачем своя, а не апстримная QuickTileService: та работает с туннелями TunnelManager (файлы
// конфигов в приложении), а у нас конфиг приходит из /connect и живёт в GoTunnel — для неё туннеля
// «нет», и тап уводил бы в апстримный экран со списком туннелей, которого в продукте вообще нет.
//
// Подключение здесь — тот же путь, что у автоподключения (MayakAutoConnect): поднимаем ПОСЛЕДНИЙ
// РАБОЧИЙ конфиг с диска. Это сознательно: из шторки нельзя показать выбор страны и обработать
// ошибку сети, а сохранённый конфиг поднимается без обращения к ядру. Если поднимать нечего (ни
// разу не подключались) или система не даёт стартовать VPN из фона — открываем приложение, там
// пользователь доведёт дело до конца сам.
package org.amnezia.awg.mayak

import android.content.Intent
import android.graphics.drawable.Icon
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.amnezia.awg.R

class MayakQuickTileService : TileService() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    override fun onStartListening() {
        super.onStartListening()
        render()
    }

    override fun onClick() {
        val up = GoTunnel(this).isUp()
        // Пока идёт операция — плитка «в процессе»: без этого шторка секунду-две выглядит так, будто
        // тап не сработал, и человек жмёт второй раз.
        qsTile?.apply { state = Tile.STATE_UNAVAILABLE; updateTile() }
        scope.launch {
            if (up) {
                withContext(Dispatchers.IO) {
                    runCatching { GoTunnel(this@MayakQuickTileService).down() }
                    MayakFallbackTransport.stop()
                }
                MayakNotification.clear(this@MayakQuickTileService)
            } else {
                val ok = MayakAutoConnect.bringUpOnDemand(this@MayakQuickTileService)
                // Поднять не вышло (нечего поднимать / нет согласия на VPN / фон-старт зарезан) —
                // отправляем в приложение, а не оставляем человека с молчащей плиткой.
                if (!ok) openApp()
            }
            render()
        }
    }

    private fun render() {
        val tile = qsTile ?: return
        val up = GoTunnel(this).isUp()
        tile.state = if (up) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        tile.label = getString(R.string.app_name)
        // Подпись — страна, к которой подключены (её же показывает уведомление), иначе статус.
        tile.subtitle = if (up) {
            // Метка в памяти могла потеряться (переподъём туннеля её обнуляет) — тогда берём
            // сохранённую на диск, ту же, что и шторка. Иначе плитка теряла страну вместе с ней.
            GoTunnel.connectedLabel ?: MayakPrefs.lastConnLabel(this) ?: getString(R.string.mayak_connected)
        } else {
            getString(R.string.mayak_status_disconnected)
        }
        // Маяк, а не логотип AmneziaWG: в списке плиток Android показывает именно эту иконку.
        tile.icon = Icon.createWithResource(this, R.drawable.ic_stat_mayak)
        tile.updateTile()
    }

    private fun openApp() {
        val intent = Intent(this, MayakActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startActivityAndCollapse(
                android.app.PendingIntent.getActivity(
                    this, 0, intent, android.app.PendingIntent.FLAG_IMMUTABLE,
                )
            )
        } else {
            @Suppress("DEPRECATION", "StartActivityAndCollapseDeprecated")
            startActivityAndCollapse(intent)
        }
    }
}
