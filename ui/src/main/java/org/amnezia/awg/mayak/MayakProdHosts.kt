// ПРОД-адреса ядра «Маяк» (de1, поднято 2026-08-06), ЗАШИТЫЕ в сборку buildType prodRelease
// (см. ui/build.gradle.kts, BuildConfig.MAYAK_PROD_TARGET). Дев-адреса живут отдельно —
// org.amnezia.awg.mayak.core.MayakHosts в :core, регенерируется scripts/gen-app-hosts.sh из
// реестра mayakvpn.ru — и НЕ подмешиваются сюда: разводит их сборочный вариант (buildType),
// а не константа рядом с константой в одном файле. Единственная точка выбора — MayakHostList.
//
// ⚠️ 2026-08-06: живой GET https://api.mayaknetworks.com/v1/client/hosts (и тот же запрос
// напрямую на 213.226.71.181:8443 с валидацией прод-CA) СЕЙЧАС отдаёт ДЕВ-домены
// ({"api":["api.mayakvpn.ru"],"cabinet":"cabinet.mayakvpn.ru"}) — реестр доменов на самом
// прод-ядре ещё не заполнен своими значениями. Поэтому список ниже НЕ сгенерирован скриптом
// (это дало бы дев-адреса в прод-сборке — ровно тот класс бага, которого избегаем), а взят из
// значений, которые владелец подтвердил явно. Когда реестр на прод-ядре поправят — можно будет
// сгенерировать этот файл по аналогии с gen-app-hosts.sh (тем же скриптом, с APP_DIR/OUT/API_BASE,
// указывающими сюда).
package org.amnezia.awg.mayak

object MayakProdHosts {
    val baked: List<String> = listOf(
        "https://api.mayaknetworks.com",
        // IP-фолбэк прод-ядра de1: свой CA (CN=Mayak Prod CA, ui/src/prodRelease/res/raw/mayak_ca.pem +
        // ui/src/prodRelease/res/xml/network_security_config.xml), лиф-серт client-api имеет SAN=IP
        // (проверено живьём 2026-08-06). Держим последним — только на случай, когда домен не резолвится.
        "https://213.226.71.181:8443",
    )

    // Веб-кабинет прод-ядра. cabinet.mayaknetworks.com — за Cloudflare (в отличие от api-домена,
    // который DNS-only), обычный публичный LE-серт, спец-CA не нужен.
    const val bakedCabinet: String = "https://cabinet.mayaknetworks.com"
}
