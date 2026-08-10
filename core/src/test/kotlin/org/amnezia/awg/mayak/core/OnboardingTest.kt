package org.amnezia.awg.mayak.core

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OnboardingTest {

    @Test
    fun `новичок после входа видит знакомство`() {
        assertTrue(Onboarding.shouldShow(alreadyShown = false, signedIn = true, connectCount = 0))
    }

    @Test
    fun `до входа не показываем`() {
        // Лист поверх экрана входа — дверь перед дверью: человек ещё не наш пользователь.
        assertFalse(Onboarding.shouldShow(alreadyShown = false, signedIn = false, connectCount = 0))
    }

    @Test
    fun `показываем ровно один раз`() {
        assertFalse(Onboarding.shouldShow(alreadyShown = true, signedIn = true, connectCount = 0))
    }

    @Test
    fun `тому кто уже подключался не рассказываем как подключаться`() {
        // Так выглядит человек, обновившийся со сборки, где листа не было: он всё умеет,
        // и «нажмите большую кнопку» ему только мешает.
        assertFalse(Onboarding.shouldShow(alreadyShown = false, signedIn = true, connectCount = 7))
    }
}
