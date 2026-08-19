// Сторож фильтра контура (аудит безопасности 19-08, находка A3).
//
// Что он держит. Приложение открывает наружу два вида адресов: выученный адрес КАБИНЕТА (он же
// уходит в WebView шага регистрации — appCaptchaUrl строится от него, то есть чужая страница
// получила бы наш JS-мост) и ОДНОРАЗОВЫЙ вход в кабинет, присланный ядром в ответе
// /v1/client/cabinet-link. До 19-08 первый брался как есть, а у второго не проверялась даже схема:
// `http://` увёл бы одноразовый вход открытым текстом — cleartext запрещён нашему процессу
// настройкой сети, но не браузеру, которому мы отдали интент.
//
// Почему тест со СПИСКОМ АРГУМЕНТОМ, а не просто MayakHostList.ownContour(url). Юнит-тесты AGP
// гоняются на buildType `debug` (:ui:testDebugUnitTest), а в нём MAYAK_PROD_TARGET=false — то есть
// сторож без аргумента видел бы только дев-список и про боевой контур молчал бы. Здесь оба.
package org.amnezia.awg.mayak

import org.amnezia.awg.mayak.core.MayakHosts
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MayakContourGuardTest {

    /** Вшитые адреса берём из тех же объектов, что и приложение: сменится домен — тест поедет за ним. */
    private val prod = MayakProdHosts.baked + MayakProdHosts.bakedCabinet
    private val dev = MayakHosts.baked + MayakHosts.bakedCabinet

    @Test
    fun `свой кабинет принимается — и это боевая ссылка входа`() {
        val link = "https://cabinet.mayaknetworks.com/#/magic?t=abc123"
        assertTrue(MayakHostList.ownContour(link, prod))
        assertTrue(MayakHostList.ownHttpsUrl(link, prod))
        assertTrue(MayakHostList.ownHttpsUrl("https://api.mayaknetworks.com", prod))
        assertTrue(MayakHostList.ownHttpsUrl("https://cabinet.mayakvpn.ru/#/login", dev))
        // Ровно то, что боевое ядро отдаёт на GET /v1/client/hosts сегодня (живой запрос 19-08:
        // {"api":["api.mayaknetworks.com"],"cabinet":"cabinet.mayaknetworks.com","site":"mayaknetworks.com"}).
        // Приходят они БЕЗ схемы — фильтр обязан их принимать, иначе правка A3 молча отрезала бы
        // приложение от реестра доменов и увела всех на вшитый адрес.
        assertTrue(MayakHostList.ownContour("cabinet.mayaknetworks.com", prod))
        assertTrue(MayakHostList.ownContour("mayaknetworks.com", prod))
        assertTrue(MayakHostList.ownContour("api.mayaknetworks.com", prod))
    }

    @Test
    fun `ЧУЖОЙ контур отвергается — в том числе соседний наш`() {
        for (url in listOf(
            "https://cabinet.evil.com/#/magic?t=abc123",
            "https://evil.com",
            "https://mayaknetworks.com.evil.com/#/magic",   // наш домен внутри чужого
            "https://xmayaknetworks.com",                   // приклеенная буква
            "https://1.2.3.4:8443",                         // чужой адрес-IP
        )) {
            assertFalse("должен быть отвергнут: $url", MayakHostList.ownContour(url, prod))
            assertFalse("должен быть отвергнут: $url", MayakHostList.ownHttpsUrl(url, prod))
        }
        // Контуры не смешиваются в обе стороны: боевая сборка не пойдёт по дев-адресу и наоборот.
        assertFalse(MayakHostList.ownHttpsUrl("https://cabinet.mayakvpn.ru", prod))
        assertFalse(MayakHostList.ownHttpsUrl("https://cabinet.mayaknetworks.com", dev))
    }

    @Test
    fun `http отвергается даже у СВОЕГО домена — вход уехал бы открытым текстом`() {
        val plain = "http://cabinet.mayaknetworks.com/#/magic?t=abc123"
        assertTrue("контур-то свой", MayakHostList.ownContour(plain, prod))
        assertFalse("но схема не та", MayakHostList.ownHttpsUrl(plain, prod))
        // И всё остальное, что схемой не является: интент наружу отдаём только по https.
        for (url in listOf(
            "javascript:alert(1)",
            "intent://cabinet.mayaknetworks.com#Intent;scheme=https;end",
            "file:///data/data/mayaknetworks.app/x",
            "",
            "   ",
        )) {
            assertFalse("должен быть отвергнут: $url", MayakHostList.ownHttpsUrl(url, prod))
        }
    }

    @Test
    fun `подделки хоста не проходят за свой домен`() {
        // Все три строки БРАУЗЕР открывает на evil.com, а наивный разбор считал их своими:
        // якорь и обратная косая не отрезались, а часть до '@' — это логин, а не хост.
        for (url in listOf(
            "https://evil.com#cabinet.mayaknetworks.com",
            "https://evil.com\\cabinet.mayaknetworks.com",
            "https://evil.com?x=.mayaknetworks.com",
            "https://cabinet.mayaknetworks.com@evil.com/#/magic",
        )) {
            assertFalse("должен быть отвергнут: $url", MayakHostList.ownContour(url, prod))
            assertFalse("должен быть отвергнут: $url", MayakHostList.ownHttpsUrl(url, prod))
        }
        // А вот логин ПЕРЕД нашим хостом — это по-прежнему наш хост, отвергать нечего.
        assertTrue(MayakHostList.ownHttpsUrl("https://user@cabinet.mayaknetworks.com/", prod))
    }
}
