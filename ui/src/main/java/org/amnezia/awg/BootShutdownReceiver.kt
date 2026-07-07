/*
 * Copyright © 2017-2023 WireGuard LLC. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package org.amnezia.awg

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import org.amnezia.awg.backend.AwgQuickBackend
import org.amnezia.awg.util.applicationScope
import kotlinx.coroutines.launch

class BootShutdownReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        applicationScope.launch {
            if (Application.getBackend() !is AwgQuickBackend) {
                // «Маяк» использует GoBackend: апстримный TunnelManager/restoreState неприменим (конфиги
                // приходят из /connect, а не из FileConfigStore). На буте пробуем F3-автоподключение из
                // сохранённого рабочего конфига. Best-effort: на Android O+ фон-старт VpnService может не
                // пройти вне Always-On — основной путь автоподъёма всё равно системный Always-On VPN, который
                // сам стартует наш VpnService (см. Application.setAlwaysOnCallback). Гейт — MayakPrefs.autoConnect.
                if (Intent.ACTION_BOOT_COMPLETED == action) {
                    org.amnezia.awg.mayak.MayakAutoConnect.bringUpIfEnabled(context)
                }
                return@launch
            }
            val tunnelManager = Application.getTunnelManager()
            if (Intent.ACTION_BOOT_COMPLETED == action) {
                Log.i(TAG, "Broadcast receiver restoring state (boot)")
                tunnelManager.restoreState(false)
            } else if (Intent.ACTION_SHUTDOWN == action) {
                Log.i(TAG, "Broadcast receiver saving state (shutdown)")
                tunnelManager.saveState()
            }
        }
    }

    companion object {
        private const val TAG = "AmneziaWG/BootShutdownReceiver"
    }
}
