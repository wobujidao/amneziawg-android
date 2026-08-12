// Сторож пуш-уведомлений (ускоритель ящика SPEC-0047). Охраняет три вещи, каждая из которых ломается
// МОЛЧА — то есть без падений, без красного и без единой строки в логе.
//
// 1️⃣ ИМЕНА ПОЛЕЙ. Это стык с ДРУГИМ репозиторием: серверную половину пишет другой человек по тому же
//    контракту. 12-08 у нас уже было ровно это: приложение спрашивало `days`, сервер клал
//    `grace_days` — ничего не упало, просто человек получал не тот текст. Поэтому имена проверяются
//    сериализацией НАСТОЯЩИХ запросов, а не чтением глазами.
//
// 2️⃣ ПУШ НЕ ПОКАЗЫВАЕТ ТЕКСТ САМ. В пуше текста нет вовсе (он идёт через Google, а уведомление
//    читают через плечо), и появиться он там не должен ни завтра, ни «на минутку для отладки».
//    Сторож смотрит на исходник приёмника: он обязан ходить в ящик и не обязан уметь рисовать.
//
// 3️⃣ БЕЗ СЕРВИСОВ GOOGLE НИЧЕГО НЕ ПАДАЕТ. Телефоны без GMS есть у части людей, и для них ящик
//    должен работать опросом, как работал. Решение вынесено чистой функцией (pushAction) ровно
//    затем, чтобы это проверялось тестом, а не рассуждением.
package org.amnezia.awg.mayak

import java.io.File
import kotlinx.serialization.json.Json
import org.amnezia.awg.mayak.core.MayakBackend
import org.amnezia.awg.mayak.core.PushAction
import org.amnezia.awg.mayak.core.PushData
import org.amnezia.awg.mayak.core.PushPlatforms
import org.amnezia.awg.mayak.core.PushRegisterRequest
import org.amnezia.awg.mayak.core.PushUnregisterRequest
import org.amnezia.awg.mayak.core.pushAction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MayakPushContractTest {

    // ===== 1. Контракт с сервером =====

    /**
     * Тем же кодировщиком, каким запрос уходит НА САМОМ ДЕЛЕ (MayakBackend.defaultJson), и вторым —
     * голым `Json`. Второй здесь не для красоты: у `platform` есть значение по умолчанию, а обычный
     * `Json` умолчания не сериализует — и первая версия DTO уезжала БЕЗ поля `platform`. Поймано
     * этим тестом на первом прогоне, вылечено @EncodeDefault(ALWAYS).
     */
    private fun encodings(request: PushRegisterRequest): List<String> = listOf(
        MayakBackend.defaultJson.encodeToString(PushRegisterRequest.serializer(), request),
        Json.encodeToString(PushRegisterRequest.serializer(), request),
    )

    @Test
    fun `запрос регистрации несёт РОВНО поля контракта`() {
        val request = PushRegisterRequest(token = "тест-адрес", appVersion = "0.4.12")
        // Дословно из контракта: {"token":"…","platform":"android","app_version":"…"}.
        for (json in encodings(request)) {
            assertEquals(
                """{"token":"тест-адрес","platform":"android","app_version":"0.4.12"}""",
                json,
            )
        }
    }

    @Test
    fun `запрос снятия несёт РОВНО поле контракта`() {
        val request = PushUnregisterRequest("тест-адрес")
        assertEquals(
            """{"token":"тест-адрес"}""",
            MayakBackend.defaultJson.encodeToString(PushUnregisterRequest.serializer(), request),
        )
        assertEquals(
            """{"token":"тест-адрес"}""",
            Json.encodeToString(PushUnregisterRequest.serializer(), request),
        )
    }

    @Test
    fun `платформа называется так, как её ждёт сервер`() {
        assertEquals("android", PushPlatforms.ANDROID)
    }

    @Test
    fun `имена полей самого пуша совпадают с контрактом`() {
        // Сервер кладёт data = {"kind":"mailbox","id":"<id сообщения>"}. Разъедется — приложение
        // получит толчок и молча его выбросит как «незнакомый повод».
        assertEquals("kind", PushData.KIND)
        assertEquals("id", PushData.ID)
        assertEquals("mailbox", PushData.KIND_MAILBOX)
    }

    // ===== 2. Пуш не показывает текст сам =====

    @Test
    fun `приёмник пуша не умеет рисовать уведомление`() {
        val src = source("MayakPushService.kt")
        // Ни построения уведомления, ни канала, ни заголовка — показ живёт в ОДНОЙ двери
        // (MayakMessages), где уже есть тихие часы, лимит на пачку и отметка «об этом уже сказали».
        for (forbidden in listOf(
            "NotificationCompat",
            "NotificationManagerCompat",
            "setContentTitle",
            "setContentText",
            "notify(",
        )) {
            assertFalse(
                "MayakPushService умеет показывать сам ($forbidden) — тогда правил показа станет два, " +
                    "и они разъедутся; хуже того, показывать он будет ТЕКСТОМ ИЗ ПУША",
                src.contains(forbidden),
            )
        }
    }

    @Test
    fun `приёмник пуша не читает из пуша ничего, кроме повода и id`() {
        val src = source("MayakPushService.kt")
        // Обращения к данным пуша — только по именам контракта. Появится message.data["title"] или
        // message.notification — значит текст поехал через Google, чего мы не допускаем.
        val reads = Regex("""message\.data\[([^\]]+)\]""").findAll(src).map { it.groupValues[1].trim() }.toList()
        assertEquals(
            "приёмник читает из пуша посторонние поля: $reads",
            listOf("PushData.KIND", "PushData.ID"),
            reads,
        )
        assertFalse(
            "приёмник смотрит в message.notification — это блок, который система показывает САМА, " +
                "серверным текстом и в обход приложения",
            src.contains("message.notification"),
        )
    }

    @Test
    fun `приёмник пуша идёт в ящик через общую дверь`() {
        val src = source("MayakPushService.kt")
        assertTrue(
            "MayakPushService не зовёт MayakMessages.sync — тогда пуш вообще ничего не делает",
            src.contains("MayakMessages.sync("),
        )
        assertTrue(
            "забор ящика по пушу должен идти поводом ALWAYS: свой потолок частоты означал бы " +
                "«толчок пришёл, а мы решили не ходить»",
            src.contains("SyncTrigger.ALWAYS"),
        )
    }

    // ===== 3. Решение о регистрации: без Google-сервисов ничего не делаем =====

    @Test
    fun `без сервисов Google не регистрируемся и не падаем`() {
        assertEquals(
            "нет транспорта — делать нечего, ящик работает опросом",
            PushAction.NOTHING,
            pushAction(
                notificationsEnabled = true,
                available = false, // телефон без GMS или сборка не боевая
                loggedIn = true,
                alreadySent = false,
                upToDate = false,
            ),
        )
    }

    @Test
    fun `выключенные уведомления снимают ранее отправленный адрес`() {
        assertEquals(
            "уведомления запретили, а адрес на ядре остался — телефон будят впустую, и Google за такие " +
                "пуши понижает приоритет ВСЕМ нашим",
            PushAction.UNREGISTER,
            pushAction(
                notificationsEnabled = false,
                available = true,
                loggedIn = true,
                alreadySent = true,
                upToDate = true,
            ),
        )
        assertEquals(
            "уведомления выключены и адрес не отправляли — снимать нечего",
            PushAction.NOTHING,
            pushAction(
                notificationsEnabled = false,
                available = true,
                loggedIn = true,
                alreadySent = false,
                upToDate = false,
            ),
        )
    }

    @Test
    fun `без входа адрес доставки не регистрируем`() {
        // Адрес принадлежит УЧЁТКЕ, а не телефону: ядро узнаёт устройство по токену сессии.
        assertEquals(
            PushAction.NOTHING,
            pushAction(
                notificationsEnabled = true,
                available = true,
                loggedIn = false,
                alreadySent = false,
                upToDate = false,
            ),
        )
    }

    @Test
    fun `повторное открытие приложения не дёргает сервер зря`() {
        assertEquals(
            "тот же адрес и та же версия — на ядро идти не за чем",
            PushAction.NOTHING,
            pushAction(
                notificationsEnabled = true,
                available = true,
                loggedIn = true,
                alreadySent = true,
                upToDate = true,
            ),
        )
        assertEquals(
            "адрес отправлен, но приложение обновилось — ядро должно узнать новую версию",
            PushAction.REGISTER,
            pushAction(
                notificationsEnabled = true,
                available = true,
                loggedIn = true,
                alreadySent = true,
                upToDate = false,
            ),
        )
        assertEquals(
            "первый раз — регистрируемся",
            PushAction.REGISTER,
            pushAction(
                notificationsEnabled = true,
                available = true,
                loggedIn = true,
                alreadySent = false,
                upToDate = false,
            ),
        )
    }

    // ===== 4. Firebase подключён так, как решено (и не иначе) =====

    @Test
    fun `плагин google-services не подключён, а конфигурация не в git`() {
        val build = moduleFile("build.gradle.kts").readText()
        assertFalse(
            "подключён плагин google-services: он падает на applicationId debug-варианта " +
                "(«No matching client found») и требует в репозитории google-services.json с ключом",
            build.contains("google-services") && !build.contains("НЕ ПОДКЛЮЧЁН"),
        )
        val ignore = repoFile(".gitignore").readText()
        assertTrue(
            "сгенерированный firebase.xml не закрыт .gitignore — ключ AIzaSy… уедет в публичный репозиторий",
            ignore.contains("firebase.xml"),
        )
        assertTrue(
            "нет скрипта-генератора scripts/firebase-res.sh — тогда конфигурацию придётся класть руками",
            repoFile("scripts/firebase-res.sh").isFile,
        )
    }

    @Test
    fun `служба приёма пуша объявлена в манифесте и закрыта наружу`() {
        val manifest = moduleFile("src/main/AndroidManifest.xml").readText()
        assertTrue(
            "нет службы MayakPushService в манифесте — пуш не придёт никогда, и это молчаливый отказ",
            manifest.contains("org.amnezia.awg.mayak.MayakPushService"),
        )
        assertTrue(
            "у службы нет фильтра com.google.firebase.MESSAGING_EVENT — транспорт её не найдёт",
            manifest.contains("com.google.firebase.MESSAGING_EVENT"),
        )
        // Служба обязана быть exported="false": будить её снаружи не должен никто, кроме транспорта.
        val block = manifest.substringAfter("org.amnezia.awg.mayak.MayakPushService").substringBefore("</service>")
        assertTrue(
            "служба приёма пуша объявлена exported=true — её сможет дёрнуть любое приложение на телефоне",
            block.contains("""android:exported="false""""),
        )
    }

    // ===== Файлы =====

    /** Рабочий каталог юнит-тестов AGP — каталог модуля (ui/), но не полагаемся на это. */
    private fun moduleFile(relative: String): File {
        var dir: File? = File("").absoluteFile
        repeat(6) {
            val direct = File(dir, relative)
            if (direct.isFile) return direct
            val underUi = File(dir, "ui/$relative")
            if (underUi.isFile) return underUi
            dir = dir?.parentFile
        }
        throw AssertionError("не нашёл $relative — тест запущен не из дерева репозитория")
    }

    /** Файл в КОРНЕ репозитория (.gitignore, scripts/…). */
    private fun repoFile(relative: String): File {
        var dir: File? = File("").absoluteFile
        repeat(6) {
            val candidate = File(dir, relative)
            if (candidate.exists()) return candidate
            dir = dir?.parentFile
        }
        throw AssertionError("не нашёл $relative — тест запущен не из дерева репозитория")
    }

    private fun source(name: String): String =
        moduleFile("src/main/java/org/amnezia/awg/mayak/$name").readText()
}
