package org.amnezia.awg.mayak.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RegLinkTest {

    @Test
    fun sanitizeServer_acceptsHttpsAndStripsTrailingSlash() {
        assertEquals("https://api.mayakvpn.ru", RegLink.sanitizeServer("https://api.mayakvpn.ru/"))
        assertEquals("https://api.mayakvpn.ru:8443", RegLink.sanitizeServer("  https://api.mayakvpn.ru:8443  "))
    }

    @Test
    fun sanitizeServer_rejectsNonHttps() {
        assertNull(RegLink.sanitizeServer("http://evil.example"))     // plaintext — токен утёк бы
        assertNull(RegLink.sanitizeServer("javascript:alert(1)"))     // не URL ядра
        assertNull(RegLink.sanitizeServer("ftp://api.mayakvpn.ru"))
        assertNull(RegLink.sanitizeServer("api.mayakvpn.ru"))         // без схемы
    }

    @Test
    fun sanitizeServer_rejectsHttpsWithoutHostOrGarbage() {
        assertNull(RegLink.sanitizeServer("https://"))                // нет хоста
        assertNull(RegLink.sanitizeServer("https:// bad host"))       // непарсимо
        assertNull(RegLink.sanitizeServer(""))
        assertNull(RegLink.sanitizeServer("   "))
        assertNull(RegLink.sanitizeServer(null))
    }
}
