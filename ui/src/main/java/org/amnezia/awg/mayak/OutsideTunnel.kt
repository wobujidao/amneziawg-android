// Второй заход к ядру — МИМО туннеля.
//
// Зачем это вообще есть. Запросы к ядру уходят обычными сокетами, а обычный сокет при поднятом
// туннеле идёт ВНУТРИ него. Пока туннель жив, это даже хорошо (управляющий канал спрятан). Но самый
// частый отказ у людей — не «не подключилось», а «подключилось и тихо умерло»: рукопожатие прошло,
// обратный поток не доходит. В этом состоянии умирал ВЕСЬ управляющий канал: вход, продление аренды
// адреса, самообновление и — обиднее всего — отправка диагностики. Человек не мог пожаловаться ровно
// потому, что у него сломалось. Поймано живьём владельцем 2026-08-07 на сотовой при сломанной
// немецкой линии: «все домены недоступны». Доказательство, что дело в туннеле, а не в блокировке:
// в базе прода часть диаг-логов пришла с ВЫХОДНЫХ адресов наших же нод.
//
// Почему не VpnService.protect(). Он точнее (исключает сокет только из НАШЕГО туннеля), но
// применяется к сокету, а HttpURLConnection свой сокет наружу не отдаёт. Поэтому берём привязку
// соединения к конкретной сети (Network.openConnection) — и, чтобы не обходить ЧУЖОЙ VPN человека,
// включаем обход ТОЛЬКО когда поднят наш собственный VpnService.
package org.amnezia.awg.mayak

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import org.amnezia.awg.backend.GoBackend
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

object OutsideTunnel {

    /**
     * Фабрика соединений в обход туннеля для [org.amnezia.awg.mayak.core.MayakBackend].
     *
     * Контракт бэкенда: падать МГНОВЕННО, когда обходить нечего или некуда — иначе на устройстве
     * без интернета человек прождёт круг по доменам дважды. Обе проверки ниже дешёвые и локальные.
     */
    fun opener(context: Context): (URL) -> HttpURLConnection {
        val app = context.applicationContext
        return { url ->
            // 1. Наш туннель не поднят — значит соединение и так прямое, обходить нечего.
            //    Заодно это защита от обхода ЧУЖОГО VPN: без нашего сервиса мы сюда не заходим.
            if (!GoBackend.isTunnelServiceUp()) throw IOException("туннель не поднят — обходить нечего")

            val cm = app.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
                ?: throw IOException("нет ConnectivityManager")

            @Suppress("DEPRECATION") // allNetworks: замены для «покажи ВСЕ сети» в публичном API нет
            val underlying = cm.allNetworks.firstOrNull { n ->
                val caps = cm.getNetworkCapabilities(n)
                caps != null &&
                    caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                    // именно НЕ-VPN: сеть самого туннеля нам и мешает
                    !caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
            } ?: throw IOException("нет сети мимо туннеля")

            underlying.openConnection(url) as HttpURLConnection
        }
    }
}
