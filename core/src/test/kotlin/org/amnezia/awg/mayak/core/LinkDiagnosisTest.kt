// Сторожа вердикта диагностики связи (замечание владельца 21-08).
//
// Ради чего вообще: кнопка «Проверить связь» рвала живой туннель и прогоняла лестницу заново, то
// есть уничтожала ровно то состояние, которое человек хотел показать. Случай «подключено, а трафика
// нет» она убивала раньше, чем успевала измерить. Теперь диагностика идёт НА ТЕКУЩЕЙ линии, а её
// вывод собирается здесь — и здесь же проверяется, потому что ошибка в выводе дороже ошибки в
// измерении: человек по нему пойдёт чинить не то.
package org.amnezia.awg.mayak.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LinkDiagnosisTest {

    @Test
    fun `нет интернета вовсе — говорим об этом, а не про туннель`() {
        // При отсутствии сети провалятся ВСЕ пробы, и вердикт «через туннель не идёт» был бы
        // формально верным и совершенно бесполезным: человек пошёл бы чинить не то.
        val v = LinkDiagnosis.verdict(
            LinkFacts(
                internetOutside = Probe.FAIL,
                exitReachable = Probe.FAIL,
                throughTunnel = Probe.FAIL,
                tunnelAlive = Probe.FAIL,
            ),
        )
        assertEquals(LinkDiagnosis.NO_NETWORK, v.code)
    }

    @Test
    fun `чужой VPN важнее любых цифр ниже`() {
        val v = LinkDiagnosis.verdict(
            LinkFacts(internetOutside = Probe.OK, otherVpn = Probe.FAIL, throughTunnel = Probe.FAIL),
        )
        assertEquals(LinkDiagnosis.OTHER_VPN, v.code)
    }

    @Test
    fun `разъехавшиеся часы называем раньше, чем ломается защищённое соединение`() {
        val v = LinkDiagnosis.verdict(
            LinkFacts(internetOutside = Probe.OK, clockOk = Probe.FAIL, throughTunnel = Probe.FAIL),
        )
        assertEquals(LinkDiagnosis.CLOCK_SKEW, v.code)
    }

    @Test
    fun `до выхода не достучались мимо туннеля — это путь до ноды, а не туннель`() {
        val v = LinkDiagnosis.verdict(
            LinkFacts(internetOutside = Probe.OK, exitReachable = Probe.FAIL, throughTunnel = Probe.FAIL),
        )
        assertEquals(LinkDiagnosis.EXIT_UNREACHABLE, v.code)
    }

    // 🔴 Тот самый случай, ради которого владелец и просил переделку: «подключение работает, но
    // связи нет». Раньше он был неотличим от «просто не работает».
    @Test
    fun `сеть есть, выход отвечает, а через туннель не идёт — называем прямо`() {
        val v = LinkDiagnosis.verdict(
            LinkFacts(internetOutside = Probe.OK, exitReachable = Probe.OK, throughTunnel = Probe.FAIL),
        )
        assertEquals(LinkDiagnosis.TUNNEL_DEAD, v.code)
        assertTrue("вердикт должен называть все три звена", v.text.contains("через туннель"))
    }

    @Test
    fun `не проходит крупный пакет — это отдельный диагноз, а не общий провал`() {
        val v = LinkDiagnosis.verdict(
            LinkFacts(
                internetOutside = Probe.OK, exitReachable = Probe.OK,
                throughTunnel = Probe.FAIL, bigPacket = Probe.FAIL,
            ),
        )
        assertEquals(LinkDiagnosis.MTU_BROKEN, v.code)
    }

    @Test
    fun `трафик идёт, а имена не разрешаются — говорим про DNS`() {
        val v = LinkDiagnosis.verdict(
            LinkFacts(internetOutside = Probe.OK, throughTunnel = Probe.OK, dnsThroughTunnel = Probe.FAIL),
        )
        assertEquals(LinkDiagnosis.DNS_BROKEN, v.code)
    }

    @Test
    fun `всё измеренное в порядке — так и говорим`() {
        val v = LinkDiagnosis.verdict(
            LinkFacts(
                internetOutside = Probe.OK, exitReachable = Probe.OK,
                throughTunnel = Probe.OK, dnsThroughTunnel = Probe.OK,
            ),
        )
        assertEquals(LinkDiagnosis.WORKS, v.code)
    }

    // 🔴 Самая дорогая ошибка вердикта — сказать «всё хорошо», ничего не измерив. Молчание проверок
    // не означает их успех (та же ловушка, из-за которой лог из одних «ПРОВАЛ» описывал успешное
    // подключение).
    @Test
    fun `ничего не измерили — не выдаём это за успех`() {
        val v = LinkDiagnosis.verdict(LinkFacts())
        assertEquals(LinkDiagnosis.UNCLEAR, v.code)
        assertFalse("в вердикте не должно быть слова «порядке»", v.text.contains("порядке"))
    }

    @Test
    fun `главное измерено не полностью — тоже не успех`() {
        // Интернет есть, а про туннель ничего не знаем: сказать «связь в порядке» здесь было бы
        // ложью, а сказать «туннель мёртв» — выдумкой.
        val v = LinkDiagnosis.verdict(LinkFacts(internetOutside = Probe.OK))
        assertEquals(LinkDiagnosis.UNCLEAR, v.code)
    }

    @Test
    fun `в след попадает только измеренное`() {
        val след = LinkDiagnosis.trace(
            LinkFacts(internetOutside = Probe.OK, throughTunnel = Probe.FAIL),
        )
        assertEquals("интернет ✓ · через туннель ✗", след)
        assertFalse("непроверенное не должно упоминаться вовсе", след.contains("DNS"))
    }

    @Test
    fun `ограничение фона попадает в след отдельным признаком`() {
        val след = LinkDiagnosis.trace(LinkFacts(internetOutside = Probe.OK, batteryRestricted = true))
        assertTrue(след.contains("фон ограничен ✗"))
    }

    // 🔴 Веерное ограничение мобильного интернета (22-08). Наши молчат, зарубежный адрес молчит,
    // а российский из белого списка отвечает — значит интернет ЕСТЬ, и говорить «нет интернета,
    // проверьте сеть» нельзя: человека отправляют чинить исправную сеть. Владелец 22-08:
    // «в Москве включили белые списки, работает только Яндекс», и отдельно — включают их ТОЛЬКО
    // на мобильной связи.
    @Test
    fun `белый список важнее вердикта «нет интернета»`() {
        val v = LinkDiagnosis.verdict(
            LinkFacts(internetOutside = Probe.FAIL, ruAllowedHost = Probe.OK,
                apiByName = Probe.FAIL, apiByIp = Probe.FAIL),
        )
        assertEquals(LinkDiagnosis.MOBILE_ALLOWLIST, v.code)
    }

    // Обратная сторона: если из белого списка тоже никто не ответил, интернета и правда нет —
    // прежний вердикт обязан остаться. Иначе мы заменили одну неправду на другую.
    @Test
    fun `без ответа из белого списка это по-прежнему «нет интернета»`() {
        val v = LinkDiagnosis.verdict(
            LinkFacts(internetOutside = Probe.FAIL, ruAllowedHost = Probe.FAIL),
        )
        assertEquals(LinkDiagnosis.NO_NETWORK, v.code)
    }

    // Пробу белого списка мы гоняем только когда интернета «нет», поэтому обычный случай приходит
    // с UNKNOWN — и он тоже обязан читаться как «нет интернета», а не как ограничение.
    @Test
    fun `непроверенный белый список не превращается в ограничение сети`() {
        val v = LinkDiagnosis.verdict(
            LinkFacts(internetOutside = Probe.FAIL, ruAllowedHost = Probe.UNKNOWN),
        )
        assertEquals(LinkDiagnosis.NO_NETWORK, v.code)
    }

    // Ограничение сети — не повод молчать про чужой VPN: он идёт следующей веткой и не должен
    // потеряться, когда интернет есть.
    @Test
    fun `при живом интернете белый список ни на что не влияет`() {
        val v = LinkDiagnosis.verdict(
            LinkFacts(internetOutside = Probe.OK, ruAllowedHost = Probe.OK,
                otherVpn = Probe.FAIL, throughTunnel = Probe.FAIL),
        )
        assertEquals(LinkDiagnosis.OTHER_VPN, v.code)
    }
}
