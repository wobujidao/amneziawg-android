// Android-реализация Tunnel (:core) поверх GoBackend форка (движок amneziawg-go).
// up(confText): парсим наш .conf штатным парсером форка (он же знает поля AWG 2.0) и поднимаем
// туннель. Согласие на VPN (GoBackend.VpnService.prepare) запрашивается в Activity ДО up().
package org.amnezia.awg.mayak

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.amnezia.awg.backend.Backend
import org.amnezia.awg.backend.GoBackend
import org.amnezia.awg.backend.Tunnel
import org.amnezia.awg.config.Config
import org.amnezia.awg.mayak.core.Tunnel as MayakCoreTunnel
import java.io.BufferedReader
import java.io.StringReader

/** Имя туннеля в движке + приёмник смены состояния (нам колбэк не нужен — состояние тянем сами). */
private class NamedTunnel(private val name: String) : Tunnel {
    override fun getName(): String = name
    override fun onStateChange(newState: Tunnel.State) {}
}

class GoTunnel(context: Context, private val tunnelName: String = "mayak") : MayakCoreTunnel {
    private val backend: Backend = GoBackend(context.applicationContext)
    private val tunnel = NamedTunnel(tunnelName)

    override suspend fun up(confText: String) = withContext(Dispatchers.IO) {
        val config = Config.parse(BufferedReader(StringReader(confText)))
        backend.setState(tunnel, Tunnel.State.UP, config)
        Unit
    }

    override suspend fun down() = withContext(Dispatchers.IO) {
        backend.setState(tunnel, Tunnel.State.DOWN, null)
        Unit
    }

    fun isUp(): Boolean = runCatching { backend.getState(tunnel) == Tunnel.State.UP }.getOrDefault(false)
}
