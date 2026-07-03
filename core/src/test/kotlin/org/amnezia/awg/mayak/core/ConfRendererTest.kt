package org.amnezia.awg.mayak.core

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ConfRendererTest {

    private val priv = "aGVsbG8td29ybGQtZmFrZS1wcml2YXRlLWtleS0xMjM0NTY="

    @Test
    fun direct_writesAwg2Fields_andOmitsEmptyIfields() {
        val cfg = ClientConfig(
            address = "10.8.0.2",
            dns = "1.1.1.1",
            mtu = 1280,
            obfuscation = Obfuscation(
                jc = 4, jmin = 8, jmax = 80,
                s1 = 15, s2 = 15, s3 = 0, s4 = 0,
                h1 = "1148714835", h2 = "1313472994", h3 = "1296129051", h4 = "1456016969",
                i1 = "<b 0xf1>", // chain-спека
            ),
            serverPubkey = "c2VydmVyLXB1YmtleS1mYWtlLTQ0LWNoYXJzLTAwMDAwMDA=",
            endpoint = "203.0.113.7:51820",
            allowedIps = "0.0.0.0/0, ::/0",
            persistentKeepalive = 25,
        )

        val conf = ConfRenderer.render(cfg, priv)

        // приватный ключ — локально, в [Interface]
        assertTrue(conf.contains("PrivateKey = $priv"))
        assertTrue(conf.contains("Address = 10.8.0.2"))
        assertTrue(conf.contains("DNS = 1.1.1.1"))
        assertTrue(conf.contains("MTU = 1280"))
        // S3/S4 = 0 НЕ пишем (userspace amneziawg-go отвергает ключи s3/s4 даже =0)
        assertFalse(conf.contains("S3 ="))
        assertFalse(conf.contains("S4 ="))
        assertTrue(conf.contains("H1 = 1148714835"))
        assertTrue(conf.contains("I1 = <b 0xf1>"))
        // пустые I-поля НЕ должны попадать в конфиг (issue android #56)
        assertFalse(conf.contains("I2 ="))
        assertFalse(conf.contains("I5 ="))
        // peer
        assertTrue(conf.contains("[Peer]"))
        assertTrue(conf.contains("PublicKey = ${cfg.serverPubkey}"))
        assertTrue(conf.contains("Endpoint = 203.0.113.7:51820"))
        assertTrue(conf.contains("AllowedIPs = 0.0.0.0/0, ::/0"))
        assertTrue(conf.contains("PersistentKeepalive = 25"))
    }

    @Test
    fun relay_withoutObfuscation_isPlainWireguard() {
        val cfg = ClientConfig(
            address = "10.9.0.2",
            obfuscation = null,
            serverPubkey = "cmVsYXktcHVia2V5LWZha2UtNDQtY2hhcnMtMDAwMDAwMA=",
            endpoint = "198.51.100.4:51820",
            allowedIps = "0.0.0.0/0",
        )

        val conf = ConfRenderer.render(cfg, priv)

        assertFalse(conf.contains("Jc ="))
        assertFalse(conf.contains("S1 ="))
        assertFalse(conf.contains("H1 ="))
        assertTrue(conf.contains("PrivateKey = $priv"))
        assertTrue(conf.contains("Endpoint = 198.51.100.4:51820"))
    }

    @Test
    fun connectResult_deserializesSnakeCaseFields() {
        val payload = """
            {
              "direction": "Нидерланды",
              "direct": {
                "address": "10.8.0.2/32",
                "dns": "1.1.1.1",
                "mtu": 1280,
                "obfuscation": {"jc":4,"jmin":8,"jmax":80,"s1":15,"s2":15,"s3":0,"s4":0,
                  "h1":"1148714835","h2":"1313472994","h3":"1296129051","h4":"1456016969","i1":"<b 0xf1>"},
                "server_pubkey": "c2VydmVyLXB1YmtleS1mYWtlLTQ0LWNoYXJzLTAwMDAwMDA=",
                "endpoint": "203.0.113.7:51820",
                "allowed_ips": "0.0.0.0/0, ::/0",
                "persistent_keepalive": 25
              }
            }
        """.trimIndent()

        val json = Json { ignoreUnknownKeys = true }
        val res = json.decodeFromString(ConnectResult.serializer(), payload)

        assertEquals("Нидерланды", res.direction)
        assertNull(res.relay)
        val d = requireNotNull(res.direct)
        assertEquals("10.8.0.2/32", d.address)
        // snake_case → camelCase маппинг
        assertEquals("c2VydmVyLXB1YmtleS1mYWtlLTQ0LWNoYXJzLTAwMDAwMDA=", d.serverPubkey)
        assertEquals("0.0.0.0/0, ::/0", d.allowedIps)
        assertEquals(25, d.persistentKeepalive)
        assertEquals(15, d.obfuscation?.s1)
        assertEquals("<b 0xf1>", d.obfuscation?.i1)
    }

    @Test
    fun dualStack_foldsV6IntoAddressLine() {
        // SPEC-0014: при address_v6 v4 и v6 кладутся в одну строку Address.
        val cfg = ClientConfig(
            address = "10.8.0.2",
            addressV6 = "fd00:1::2/128",
            dns = "1.1.1.1, 2606:4700:4700::1111",
            obfuscation = null,
            serverPubkey = "c2VydmVyLXB1YmtleS1mYWtlLTQ0LWNoYXJzLTAwMDAwMDA=",
            endpoint = "203.0.113.7:51820",
            allowedIps = "0.0.0.0/0, ::/0",
        )
        val conf = ConfRenderer.render(cfg, priv)
        assertTrue(conf.contains("Address = 10.8.0.2, fd00:1::2/128"))
        assertTrue(conf.contains("DNS = 1.1.1.1, 2606:4700:4700::1111"))
    }

    @Test
    fun stripIpv6_removesV6FromAddressDnsAndAllowedIps() {
        // Тумблер «Не использовать IPv6»: v6 срезается, транспорт (Endpoint) и v4 целы.
        val cfg = ClientConfig(
            address = "10.8.0.2",
            addressV6 = "fd00:1::2/128",
            dns = "1.1.1.1, 2606:4700:4700::1111",
            obfuscation = null,
            serverPubkey = "c2VydmVyLXB1YmtleS1mYWtlLTQ0LWNoYXJzLTAwMDAwMDA=",
            endpoint = "203.0.113.7:51820",
            allowedIps = "0.0.0.0/0, ::/0",
        )
        val stripped = ConfRenderer.stripIpv6(ConfRenderer.render(cfg, priv))
        assertTrue(stripped.contains("Address = 10.8.0.2"))
        assertFalse(stripped.contains("fd00:1::2")) // v6-адрес ушёл
        assertTrue(stripped.contains("DNS = 1.1.1.1"))
        assertFalse(stripped.contains("2606:4700")) // v6-DNS ушёл
        assertTrue(stripped.contains("AllowedIPs = 0.0.0.0/0"))
        assertFalse(stripped.contains("::/0")) // ::/0 ушёл → IPv6 не маршрутится в туннель
        assertTrue(stripped.contains("Endpoint = 203.0.113.7:51820")) // транспорт цел
        assertTrue(stripped.contains("PrivateKey = $priv"))
    }

    @Test
    fun stripIpv6_dropsDnsLineWhenOnlyV6() {
        // Если DNS был ТОЛЬКО IPv6 — строка убирается целиком (не остаётся пустой «DNS = »).
        val cfg = ClientConfig(
            address = "10.8.0.2",
            addressV6 = "fd00:1::2/128",
            dns = "2606:4700:4700::1111",
            obfuscation = null,
            serverPubkey = "c2VydmVyLXB1YmtleS1mYWtlLTQ0LWNoYXJzLTAwMDAwMDA=",
            endpoint = "203.0.113.7:51820",
            allowedIps = "0.0.0.0/0",
        )
        val stripped = ConfRenderer.stripIpv6(ConfRenderer.render(cfg, priv))
        assertFalse(stripped.contains("DNS =")) // строка DNS ушла целиком
        assertFalse(stripped.contains("DNS ="))
    }

    @Test
    fun withSplitTunnel_excluded_insertsIntoInterfaceSection() {
        // Split-туннель (SPEC-0018 F1): ExcludedApplications попадает в [Interface], НЕ в [Peer].
        val cfg = ClientConfig(
            address = "10.8.0.2",
            dns = "1.1.1.1",
            obfuscation = null,
            serverPubkey = "c2VydmVyLXB1YmtleS1mYWtlLTQ0LWNoYXJzLTAwMDAwMDA=",
            endpoint = "203.0.113.7:51820",
            allowedIps = "0.0.0.0/0",
        )
        val conf = ConfRenderer.render(cfg, priv)
        val out = ConfRenderer.withSplitTunnel(conf, listOf("ru.sberbank.online", "ru.gosuslugi", "ru.sberbank.online"))
        // строка есть, дубли схлопнуты
        assertTrue(out.contains("ExcludedApplications = ru.sberbank.online, ru.gosuslugi"))
        // и она в секции [Interface], до [Peer]
        val idxExcluded = out.indexOf("ExcludedApplications")
        val idxPeer = out.indexOf("[Peer]")
        assertTrue(idxExcluded in 0 until idxPeer)
        // остальной конфиг цел
        assertTrue(out.contains("PrivateKey = $priv"))
        assertTrue(out.contains("Endpoint = 203.0.113.7:51820"))
    }

    @Test
    fun withSplitTunnel_included_usesIncludedApplicationsKey() {
        // excluded=false → в туннель идут ТОЛЬКО перечисленные (IncludedApplications), не Excluded.
        val cfg = ClientConfig(
            address = "10.8.0.2", obfuscation = null,
            serverPubkey = "c2VydmVyLXB1YmtleS1mYWtlLTQ0LWNoYXJzLTAwMDAwMDA=",
            endpoint = "203.0.113.7:51820", allowedIps = "0.0.0.0/0",
        )
        val out = ConfRenderer.withSplitTunnel(ConfRenderer.render(cfg, priv), listOf("org.telegram.messenger"), excluded = false)
        assertTrue(out.contains("IncludedApplications = org.telegram.messenger"))
        assertFalse(out.contains("ExcludedApplications"))
    }

    @Test
    fun withSplitTunnel_emptyListLeavesConfUnchanged() {
        val cfg = ClientConfig(
            address = "10.8.0.2", obfuscation = null,
            serverPubkey = "c2VydmVyLXB1YmtleS1mYWtlLTQ0LWNoYXJzLTAwMDAwMDA=",
            endpoint = "203.0.113.7:51820", allowedIps = "0.0.0.0/0",
        )
        val conf = ConfRenderer.render(cfg, priv)
        // пустой список и список из пустых строк — конфиг не меняется, ключ не появляется
        assertEquals(conf, ConfRenderer.withSplitTunnel(conf, emptyList()))
        assertEquals(conf, ConfRenderer.withSplitTunnel(conf, listOf("", "  ")))
        assertFalse(ConfRenderer.withSplitTunnel(conf, emptyList()).contains("Applications"))
    }

    @Test
    fun hostProvider_rotatesAndIsSticky() {
        val hp = HostProvider(listOf("https://a.example/", "https://b.example"))
        assertEquals("https://a.example", hp.current())
        hp.rotate()
        assertEquals("https://b.example", hp.current())
        hp.rotate()
        assertEquals("https://a.example", hp.current())
    }
}
