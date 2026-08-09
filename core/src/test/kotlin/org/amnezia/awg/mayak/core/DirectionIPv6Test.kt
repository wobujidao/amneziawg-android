package org.amnezia.awg.mayak.core

import kotlinx.serialization.builtins.ListSerializer
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Бейдж «IPv6» у строки направления зажигается ТОЛЬКО по явному `ipv6:true` из
 * GET /v1/client/directions (директива владельца 01-07; тот же признак живёт на лендинге и в
 * кабинете). true ядро ставит по ПРОВЕРЕННОМУ egress-сигналу выходной ноды (curl -6,
 * nodes.ipv6_ok — cprepo/clientdata.go), а не по наличию AAAA.
 *
 * Здесь фиксируем клиентскую половину контракта: имя JSON-поля и честный дефолт. Поле у ядра
 * omitempty, то есть «false» и «поля нет» — одно и то же «бейдж не рисуем»; старое ядро без поля
 * не должно ни ронять разбор, ни, тем более, зажигать бейдж.
 */
class DirectionIPv6Test {

    private val json = MayakBackend.defaultJson
    private val listSer = ListSerializer(Direction.serializer())

    @Test
    fun `явный true доезжает до клиента`() {
        val dirs = json.decodeFromString(
            listSer,
            """[{"id":1,"code":"nl","name":"Нидерланды","p2p":true,"ipv6":true}]""",
        )
        assertTrue(dirs[0].ipv6)
    }

    @Test
    fun `нет поля (старое ядро или egress не подтверждён) — бейджа нет`() {
        val dirs = json.decodeFromString(
            listSer,
            """[{"id":2,"code":"ru","name":"Россия","p2p":false}]""",
        )
        assertFalse(dirs[0].ipv6)
    }

    @Test
    fun `явный false — бейджа нет`() {
        val dirs = json.decodeFromString(
            listSer,
            """[{"id":3,"code":"pl","name":"Польша","p2p":true,"ipv6":false}]""",
        )
        assertFalse(dirs[0].ipv6)
    }
}
