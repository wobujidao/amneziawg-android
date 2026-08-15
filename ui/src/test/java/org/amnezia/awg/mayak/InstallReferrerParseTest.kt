package org.amnezia.awg.mayak

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Разбор установочной метки Play (SPEC-0049).
 *
 * Метка приходит из внешнего мира: в ней бывают чужие поля рекламных кабинетов, кодированные
 * символы и просто мусор. Правило одно — что не похоже на НАШ код, отбрасываем молча, а сервер
 * всё равно проверит код по-настоящему.
 */
class InstallReferrerParseTest {

    @Test
    fun нашаСсылка_кодДостаётся() {
        assertEquals("NP5EM3D2", MayakInstallReferrer.parseCode("ref=NP5EM3D2"))
    }

    @Test
    fun кодСредиЧужихПолей() {
        assertEquals("NP5EM3D2", MayakInstallReferrer.parseCode("utm_source=blog&ref=NP5EM3D2&utm_medium=cpc"))
    }

    @Test
    fun процентноеКодирование_иНижнийРегистр() {
        // Play отдаёт метку так, как её положили в адрес: `ref%3Dnp5em3d2` после декодирования
        // становится обычной парой, а регистр приводит канон.
        assertEquals("NP5EM3D2", MayakInstallReferrer.parseCode("ref=np5em3d2"))
        assertEquals("NP5EM3D2", MayakInstallReferrer.parseCode("ref=np5em-3d2"))
    }

    @Test
    fun мусорИЧужоеНеПролезают() {
        assertEquals("", MayakInstallReferrer.parseCode(""))
        assertEquals("", MayakInstallReferrer.parseCode("utm_source=google-play&utm_medium=organic"))
        assertEquals("", MayakInstallReferrer.parseCode("ref=слишкомдлинныйкод"))
        assertEquals("", MayakInstallReferrer.parseCode("ref=NP5EM3D"))
        // Похожее имя поля — не наше поле.
        assertEquals("", MayakInstallReferrer.parseCode("referrer=NP5EM3D2"))
    }
}
