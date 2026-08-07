// ПРОД-адреса ядра «Маяк», ЗАШИТЫЕ в сборку buildType prodRelease
// (см. ui/build.gradle.kts, BuildConfig.MAYAK_PROD_TARGET). Дев-адреса живут отдельно —
// org.amnezia.awg.mayak.core.MayakHosts в :core, регенерируется scripts/gen-app-hosts.sh из
// реестра mayakvpn.ru — и НЕ подмешиваются сюда: разводит их сборочный вариант (buildType),
// а не константа рядом с константой в одном файле. Единственная точка выбора — MayakHostList.
//
// ⚠️ 2026-08-07: ядро переехало с Melbicom (de1, 213.226.71.181 — недоступен с сотовых сетей РФ)
// на Senko, Амстердам (IPv4 2.26.77.243, IPv6 2a12:bec4:1de0:434::2). IP-фолбэк ниже обновлён
// на новый адрес; старый адрес больше никуда не ведёт. Список НЕ сгенерирован скриптом
// gen-app-hosts.sh (это дало бы дев-адреса в прод-сборке — ровно тот класс бага, которого
// избегаем), а взят из значений, которые владелец подтвердил явно.
package org.amnezia.awg.mayak

object MayakProdHosts {
    val baked: List<String> = listOf(
        "https://api.mayaknetworks.com",
        // IP-фолбэк прод-ядра (Senko, Амстердам, с 2026-08-07): свой CA (CN=Mayak Prod CA,
        // ui/src/prodRelease/res/raw/mayak_ca.pem + ui/src/prodRelease/res/xml/network_security_config.xml),
        // лиф-серт client-api ОБЯЗАН иметь SAN=IP:2.26.77.243. Держим последним — только на случай,
        // когда домен не резолвится.
        "https://2.26.77.243:8443",
    )

    // Веб-кабинет прод-ядра. cabinet.mayaknetworks.com — за Cloudflare (в отличие от api-домена,
    // который DNS-only), обычный публичный LE-серт, спец-CA не нужен.
    const val bakedCabinet: String = "https://cabinet.mayaknetworks.com"
}
