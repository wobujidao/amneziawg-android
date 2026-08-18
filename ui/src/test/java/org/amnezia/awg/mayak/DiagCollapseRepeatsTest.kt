// Сторож сжатия повторов в диаг-логе.
//
// Повод — присланный лог vivo (16-08): тридцать одинаковых строк «network is unreachable» в одну
// миллисекунду выдавили из кольцевого буфера то, ради чего лог и прислали. Проверяем, что повтор
// схлопывается, а РАЗНЫЕ строки — никогда: потерять контекст ради красоты хуже, чем не сжимать.
package org.amnezia.awg.mayak

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DiagCollapseRepeatsTest {

    private fun line(time: String, msg: String) = "08-16 $time  1234  5678 D AmneziaWG/awg0: $msg"

    @Test
    fun `подряд идущие одинаковые строки схлопываются в одну с отметкой`() {
        val src = listOf(
            line("21:03:44.510", "Failed to send data packets: network is unreachable"),
            line("21:03:44.510", "Failed to send data packets: network is unreachable"),
            line("21:03:44.511", "Failed to send data packets: network is unreachable"),
        ).joinToString("\n")

        val out = DiagCollector.collapseRepeats(src)

        assertEquals(
            "сама строка обязана остаться ровно одна",
            1,
            out.lines().count { it.contains("network is unreachable") },
        )
        assertTrue("нет отметки о повторах: $out", out.contains("[то же самое ещё 2 раз"))
        assertTrue("отметка обязана назвать время последнего повтора: $out", out.contains("21:03:44.511"))
    }

    @Test
    fun `разные строки не схлопываются даже при одинаковом времени`() {
        val src = listOf(
            line("21:03:44.510", "Sending handshake initiation"),
            line("21:03:44.510", "Received handshake response"),
            line("21:03:44.510", "Sending handshake initiation"),
        ).joinToString("\n")

        assertEquals("ни одна строка не должна пропасть", src, DiagCollector.collapseRepeats(src))
    }

    @Test
    fun `повтор после чужой строки считается заново, а не продолжает прежний`() {
        val src = listOf(
            line("21:03:44.510", "network is unreachable"),
            line("21:03:44.511", "network is unreachable"),
            line("21:03:44.512", "Routine: receive incoming - stopped"),
            line("21:03:44.513", "network is unreachable"),
        ).joinToString("\n")

        val out = DiagCollector.collapseRepeats(src)

        assertEquals(
            "первая пара схлопнута, третье вхождение осталось само по себе",
            2,
            out.lines().count { it.contains("network is unreachable") && !it.contains("то же самое") },
        )
        assertEquals("отметка о повторах ровно одна", 1, out.lines().count { it.contains("то же самое") })
        assertTrue("чужая строка обязана уцелеть: $out", out.contains("Routine: receive incoming - stopped"))
    }

    @Test
    fun `пустой лог не ломает сжатие`() {
        assertEquals("", DiagCollector.collapseRepeats(""))
    }
}
