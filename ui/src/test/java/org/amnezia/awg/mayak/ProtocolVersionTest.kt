// Версия протокола на экране «О приложении» выводится из версии движка, а не пишется второй
// константой. Повод: 16-08 движок подняли до 3.1.20260814, а строкой ниже так и осталось
// «Протокол: AmneziaWG 3.0» — ровно та же беда, из-за которой версию движка когда-то стали читать
// из go.mod (аудит 31-07, п. 19: в приложении годами стояло «v0.2.18» при собранном v0.2.19).
package org.amnezia.awg.mayak

import org.junit.Assert.assertEquals
import org.junit.Test

class ProtocolVersionTest {

    @Test
    fun `берёт мажор и минор, дату сборки движка отбрасывает`() {
        assertEquals("3.1", MayakAboutActivity.protocolVersion("v3.1.20260814"))
        assertEquals("3.0", MayakAboutActivity.protocolVersion("v3.0.20260805"))
        assertEquals("3.1", MayakAboutActivity.protocolVersion("3.1.20260814"))
    }

    @Test
    fun `непонятную строку не выдумывает`() {
        // Пусто → строка скажет «Протокол: AmneziaWG» без числа. Соврать номером хуже.
        assertEquals("", MayakAboutActivity.protocolVersion(""))
        assertEquals("", MayakAboutActivity.protocolVersion("v3"))
        assertEquals("", MayakAboutActivity.protocolVersion("неизвестно"))
    }
}
