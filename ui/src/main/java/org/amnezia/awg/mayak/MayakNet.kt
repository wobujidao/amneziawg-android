// Есть ли у телефона СВОЯ сеть (Wi-Fi/сота/кабель) — то есть может ли туннель в принципе работать.
//
// Зачем отдельная проверка. Аудит 2026-07-31: при полностью выключенной сети приложение честно
// проходило всю лестницу подключения и рассказывало человеку про UDP и «пути», ни разу не спросив
// у системы самого простого — есть ли вообще связь. Сначала отличаем «у телефона нет сети» от
// «сеть есть, но наш путь не работает», и только потом ставим диагноз.
//
// Считаем ФИЗИЧЕСКИЕ сети перебором всех (а не activeNetwork): под поднятым VPN активной становится
// сама VPN-сеть, и по ней о наличии связи судить нельзя. VPN-транспорт пропускаем — как в DiagCollector.
//
// ⚠️ НЕ требуем NET_CAPABILITY_VALIDATED. Валидация — это успешный запрос системы к серверам Google;
// в РФ он регулярно не проходит на живой сети. Требовать его значило бы объявлять «нет интернета»
// там, где всё работает. Нам нужен ровно один факт: есть ли у телефона хоть какая-то сеть наружу.
package org.amnezia.awg.mayak

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities

object MayakNet {

    /** true — у телефона есть хотя бы одна не-VPN сеть с выходом в интернет. Ошибку читаем как «есть»:
     *  лучше пройти лестницу зря, чем отказать в подключении на живой сети. */
    fun hasNetwork(context: Context): Boolean {
        val cm = context.applicationContext
            .getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return true
        return try {
            @Suppress("DEPRECATION") // allNetworks: замены для «перечислить сети под VPN» нет
            cm.allNetworks.any { n ->
                val caps = cm.getNetworkCapabilities(n) ?: return@any false
                caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                    !caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
            }
        } catch (_: Exception) {
            true
        }
    }

    /**
     * Поднят ли СЕЙЧАС какой-то VPN — наш или чужой (2026-08-03, авто-включение РФ-пресета).
     * Смотрим ИМЕННО активную сеть (в отличие от [hasNetwork], который перебирает все): под поднятым
     * VPN активной сетью становится сама VPN — ровно её и нужно поймать. NET_CAPABILITY_NOT_VPN
     * отсутствует ИЛИ явно есть TRANSPORT_VPN → VPN поднят.
     *
     * Ошибку/отсутствие активной сети читаем как «VPN есть»: безопаснее отложить проверку страны
     * до следующего запуска, чем один раз ошибочно включить пресет по IP чужого VPN.
     */
    fun vpnActive(context: Context): Boolean {
        val cm = context.applicationContext
            .getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return true
        val net = cm.activeNetwork ?: return true
        val caps = cm.getNetworkCapabilities(net) ?: return true
        return caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN) ||
            !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
    }
}
