package org.amnezia.awg.mayak.core

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Порог старых сборок: приложение обязано САМО понимать, что его отрезали.
 *
 * Серверная половина (панель → `min_version_code` в version.json) сделана 08-08, клиентской не было
 * вовсе: человек на отрезанной сборке получал непонятные отказы ядра вместо «нужно обновиться».
 *
 * Тест держит ровно то, на чём стоит починка, и особенно — три предохранителя fail-open. Ошибка в
 * этом правиле дороже любой другой в приложении: она ЗАПИРАЕТ человека на экране, с которого ему
 * больше некуда деться, и починить это можно только новым релизом.
 */
class MinVersionGateTest {
    private val json = MayakBackend.defaultJson

    // Живой файл прода: порог не выставлен. Именно так version.json выглядит в обычный день.
    private val noGateFile = """
        {"latest_version_code":78,"latest_version_name":"0.4.1",
         "apk_url":"https://mayaknetworks.com/mayak-0.4.1.apk","min_version_code":0,"changelog":"мелочи"}
    """.trimIndent()

    private fun parse(body: String) = json.decodeFromString(AppVersionInfo.serializer(), body)

    @Test
    fun `порога нет — гейта нет`() {
        val info = parse(noGateFile)
        assertEquals(0, info.minVersionCode)
        assertEquals(
            OutdatedBuild.NONE,
            outdatedBuild(70, info.minVersionCode, info.latestVersionCode, fromPlay = false, apkUrl = info.apkUrl),
        )
    }

    @Test
    fun `порог выставлен — старую сборку отрезаем и ведём обновляться`() {
        val info = parse(noGateFile.replace("\"min_version_code\":0", "\"min_version_code\":78"))
        assertEquals(78, info.minVersionCode)
        assertEquals(
            OutdatedBuild.UPDATE_FROM_SITE,
            outdatedBuild(77, info.minVersionCode, info.latestVersionCode, fromPlay = false, apkUrl = info.apkUrl),
        )
    }

    @Test
    fun `версия РОВНО равна порогу — пускаем`() {
        // Порог задаёт минимально ДОПУСТИМУЮ сборку. Ошибись здесь на единицу — и мы отрежем ровно
        // ту версию, на которую сами же всех отправляем обновляться, то есть закроем продукт всем.
        assertEquals(OutdatedBuild.NONE, outdatedBuild(78, 78, 78, fromPlay = false, apkUrl = APK))
        assertEquals(OutdatedBuild.UPDATE_FROM_SITE, outdatedBuild(77, 78, 78, fromPlay = false, apkUrl = APK))
        assertEquals(OutdatedBuild.NONE, outdatedBuild(79, 78, 79, fromPlay = false, apkUrl = APK))
    }

    @Test
    fun `мусор в поле — читаем как ноль И не теряем остальной файл`() {
        // 🔴 Главное здесь — вторая половина проверки. Строгий разбор с полем `Int` уронил бы ВЕСЬ
        // version.json, и вместе с порогом человек потерял бы самообновление — единственный способ
        // починиться. Наша опечатка на сервере не имеет права этого делать.
        for (garbage in listOf("\"abc\"", "true", "1.5", "null", "{}", "[]", "\"\"")) {
            val info = parse(noGateFile.replace("\"min_version_code\":0", "\"min_version_code\":$garbage"))
            assertEquals("мусор $garbage обязан читаться как «порога нет»", 0, info.minVersionCode)
            assertEquals("остальной файл обязан выжить", 78, info.latestVersionCode)
            assertEquals("https://mayaknetworks.com/mayak-0.4.1.apk", info.apkUrl)
            assertEquals(
                OutdatedBuild.NONE,
                outdatedBuild(70, info.minVersionCode, info.latestVersionCode, fromPlay = false, apkUrl = info.apkUrl),
            )
        }
        // Порог строкой — годится: ровно те же значения принимает серверная половина (jsonInt в
        // internal/admin/appversions.go). Формула записана в двух языках и обязана совпадать.
        val asString = parse(noGateFile.replace("\"min_version_code\":0", "\"min_version_code\":\"78\""))
        assertEquals(78, asString.minVersionCode)
    }

    @Test
    fun `поля нет вовсе — гейта нет`() {
        // Старое ядро (или version.json, который никто не трогал) поля не содержит.
        val info = parse("""{"latest_version_code":78,"apk_url":"$APK"}""")
        assertEquals(0, info.minVersionCode)
        assertEquals(
            OutdatedBuild.NONE,
            outdatedBuild(1, info.minVersionCode, info.latestVersionCode, fromPlay = false, apkUrl = info.apkUrl),
        )
    }

    @Test
    fun `канал важен — Play в Play, сайт на свой APK`() {
        // Сборка из Play подписана ключом Google, наш APK с сайта — своим: предложить такому человеку
        // скачивание значит отправить его ждать загрузку, которая упадёт на несовпадении подписи, —
        // и это на экране, откуда деваться некуда. Плюс самообновление в Play запрещено политикой.
        assertEquals(OutdatedBuild.OPEN_IN_PLAY, outdatedBuild(77, 78, 78, fromPlay = true, apkUrl = APK))
        assertEquals(OutdatedBuild.UPDATE_FROM_SITE, outdatedBuild(77, 78, 78, fromPlay = false, apkUrl = APK))
        // Канал НЕ влияет на сам факт отсечения — только на кнопку.
        assertEquals(OutdatedBuild.NONE, outdatedBuild(78, 78, 78, fromPlay = true, apkUrl = APK))
    }

    @Test
    fun `ссылки на APK нет — ведём на сайт, а не показываем мёртвую кнопку`() {
        assertEquals(OutdatedBuild.OPEN_SITE, outdatedBuild(77, 78, 78, fromPlay = false, apkUrl = ""))
        // http и мусор MayakUpdater.download всё равно отвергнет — кнопка «Обновить» молча ничего
        // не сделала бы, а это тупик хуже отсутствия кнопки.
        assertEquals(OutdatedBuild.OPEN_SITE, outdatedBuild(77, 78, 78, fromPlay = false, apkUrl = "http://x.ru/a.apk"))
        assertEquals(OutdatedBuild.OPEN_SITE, outdatedBuild(77, 78, 78, fromPlay = false, apkUrl = "мусор"))
    }

    @Test
    fun `порог выше самой свежей сборки — не запираем никого`() {
        // Опечатка в панели («79» при выложенной 78) обязана остаться опечаткой, а не закрыть продукт:
        // обновляться некуда, и неотменяемый экран получили бы ВСЕ, включая только что установивших.
        assertEquals(OutdatedBuild.NONE, outdatedBuild(78, 79, 78, fromPlay = false, apkUrl = APK))
        assertEquals(OutdatedBuild.NONE, outdatedBuild(70, 999, 78, fromPlay = false, apkUrl = APK))
        assertEquals(OutdatedBuild.NONE, outdatedBuild(70, 999, 78, fromPlay = true, apkUrl = APK))
        // Не знаем боевую версию (файл битый, поля нет) — тоже не запираем: доказательства, что
        // обновление существует, у нас нет. «Нет данных» — не хорошая новость и не повод блокировать.
        assertEquals(OutdatedBuild.NONE, outdatedBuild(70, 78, 0, fromPlay = false, apkUrl = APK))
        // А когда обновляться ЕСТЬ куда — гейт работает.
        assertEquals(OutdatedBuild.UPDATE_FROM_SITE, outdatedBuild(70, 78, 78, fromPlay = false, apkUrl = APK))
    }

    @Test
    fun `отрицательный порог — гейта нет`() {
        assertEquals(OutdatedBuild.NONE, outdatedBuild(77, -1, 78, fromPlay = false, apkUrl = APK))
    }

    private companion object {
        const val APK = "https://mayaknetworks.com/mayak-0.4.1.apk"
    }
}
