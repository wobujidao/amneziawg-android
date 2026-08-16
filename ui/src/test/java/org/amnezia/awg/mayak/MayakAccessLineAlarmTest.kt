// Когда строка про доступ красится тревожно (красным), а когда нет.
//
// Повод. Пробный доступ без почты с 16-08 длится ровно ТРИ дня (решение владельца,
// `registration.anon_trial_days`), а порог тревоги в приложении — тоже три. Получалось, что человек,
// только что заведший учётку, видел на первом же экране красную строку «осталось 3 дня» ещё до
// первого подключения (снято на эмуляторе 16-08). Цвет, который горит ВСЕГДА, ничего не сообщает —
// а тревога нужна там, где от неё есть польза: доступ кончается, пора продлевать.
//
// Правило: пробный кончается по замыслу → тревожим только в последний день; оплаченный → за три дня,
// как было. Всё, что не «active», тревожно всегда: у человека прямо сейчас нет доступа.
package org.amnezia.awg.mayak

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MayakAccessLineAlarmTest {

    @Test
    fun `пробный не тревожит в первый день трёхдневного срока`() {
        assertFalse(
            "новичок с трёхдневным пробным не должен видеть красную строку сразу после регистрации",
            MayakAccessLine.alarming(access = "active", days = 3, trial = true),
        )
        assertFalse(MayakAccessLine.alarming(access = "active", days = 2, trial = true))
    }

    @Test
    fun `пробный тревожит в последний день`() {
        assertTrue(MayakAccessLine.alarming(access = "active", days = 1, trial = true))
        assertTrue(MayakAccessLine.alarming(access = "active", days = 0, trial = true))
    }

    @Test
    fun `оплаченный тревожит за три дня — как было`() {
        assertTrue(MayakAccessLine.alarming(access = "active", days = 3, trial = false))
        assertTrue(MayakAccessLine.alarming(access = "active", days = 1, trial = false))
        assertFalse(MayakAccessLine.alarming(access = "active", days = 4, trial = false))
        assertFalse(MayakAccessLine.alarming(access = "active", days = 30, trial = false))
    }

    @Test
    fun `бессрочный доступ не тревожит`() {
        // days = null — срока нет вовсе (выдан админом бессрочно).
        assertFalse(MayakAccessLine.alarming(access = "active", days = null, trial = false))
        assertFalse(MayakAccessLine.alarming(access = "active", days = null, trial = true))
    }

    @Test
    fun `нет доступа или истёк — тревожно всегда, даже у пробного`() {
        assertTrue(MayakAccessLine.alarming(access = "expired", days = 0, trial = true))
        assertTrue(MayakAccessLine.alarming(access = "none", days = null, trial = true))
        assertTrue(MayakAccessLine.alarming(access = "expired", days = 10, trial = false))
    }
}
