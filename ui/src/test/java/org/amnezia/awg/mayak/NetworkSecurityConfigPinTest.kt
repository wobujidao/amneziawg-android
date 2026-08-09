// Сторож пиннинга сертификата в ПРОД-конфиге сети (ui/src/prodRelease/res/xml/network_security_config.xml).
//
// Пиннинг — это способ запереть людей насмерть, если ошибиться: один кривой пин или пин не на том
// уровне цепочки, и приложение перестаёт ходить к ядру у ВСЕХ до следующего релиза. Поэтому конфиг
// сторожится тестом по принципу двойной записи: значения пинов здесь продублированы НАМЕРЕННО —
// поменять их можно только осознанно, в двух местах сразу.
//
// Тест — обычный JVM-юнит (без Android): читает файл варианта prodRelease напрямую из дерева
// исходников и разбирает штатным XML-парсером. Это проверяет ровно то, что уедет в сборку: AGP
// кладёт res/xml как есть, вариант prodRelease перекрывает main по одноимённому пути.
package org.amnezia.awg.mayak

import java.io.File
import java.time.LocalDate
import java.util.Base64
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element

class NetworkSecurityConfigPinTest {

    private companion object {
        const val CONFIG_RELATIVE = "src/prodRelease/res/xml/network_security_config.xml"
        const val PINNED_DOMAIN = "api.mayaknetworks.com"
        const val IP_FALLBACK = "2.26.77.243"

        // Эталонные пины (SPKI SHA-256, base64) — сверены с живой цепочкой 2026-08-09.
        const val PIN_ROOT_YE = "sCkq5UWXjg+7mKu9lMhhYF5bGLsy7VI/UNW3tccdR7w=" // C=US, O=ISRG, CN=Root YE
        const val PIN_ISRG_X2 = "diGVwiVYbubAI3RW4hB9xU8e/CH2GnkuvVFZE8zmgzI=" // ISRG Root X2
        const val PIN_ISRG_X1 = "C5+lpZ7tcVwmwQIMcRtPbsQtWLABXhQzejna0wHFr8M=" // ISRG Root X1
    }

    // Рабочий каталог юнит-тестов AGP — каталог модуля (ui/), но на всякий случай поднимаемся
    // вверх до корня репозитория: тест не должен зависеть от того, откуда его запустили.
    private fun configFile(): File {
        var dir: File? = File("").absoluteFile
        repeat(6) {
            val d = dir ?: return@repeat
            for (candidate in listOf(File(d, CONFIG_RELATIVE), File(d, "ui/$CONFIG_RELATIVE"))) {
                if (candidate.isFile) return candidate
            }
            dir = d.parentFile
        }
        error("не найден $CONFIG_RELATIVE (запуск из ${File("").absolutePath})")
    }

    private fun domainConfigs(): List<Element> {
        val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(configFile())
        val nodes = doc.getElementsByTagName("domain-config")
        return (0 until nodes.length).map { nodes.item(it) as Element }
    }

    private fun configFor(host: String): Element {
        val match = domainConfigs().firstOrNull { cfg ->
            val domains = cfg.getElementsByTagName("domain")
            (0 until domains.length).any { domains.item(it).textContent.trim() == host }
        }
        return requireNotNull(match) { "нет domain-config для $host" }
    }

    private fun pinSet(cfg: Element): Element? =
        cfg.getElementsByTagName("pin-set").let { if (it.length == 0) null else it.item(0) as Element }

    @Test
    fun доменЯдраЗапинен_иОбаРезервныхПинаНаМесте() {
        val cfg = configFor(PINNED_DOMAIN)
        val pins = pinSet(cfg) ?: error("у $PINNED_DOMAIN пропал <pin-set> — домен остался без пиннинга")
        val values = pins.getElementsByTagName("pin").let { list ->
            (0 until list.length).map { list.item(it) as Element }
        }
        // Минимум два пина — требование Android (одиночный pin-set без резерва система игнорирует),
        // и наш способ пережить ротацию: рабочий корень + резервные.
        assertTrue("нужно минимум два пина (рабочий + резервный), нашли ${values.size}", values.size >= 2)
        for (pin in values) {
            assertEquals("digest пина обязан быть SHA-256", "SHA-256", pin.getAttribute("digest"))
            val raw = Base64.getDecoder().decode(pin.textContent.trim())
            assertEquals("пин обязан быть SHA-256 (32 байта), а не хэш другой длины", 32, raw.size)
        }
        val set = values.map { it.textContent.trim() }.toSet()
        assertTrue("пропал рабочий пин Root YE (корень текущей цепочки LE)", PIN_ROOT_YE in set)
        assertTrue("пропал резервный пин ISRG Root X2", PIN_ISRG_X2 in set)
        assertTrue("пропал резервный пин ISRG Root X1", PIN_ISRG_X1 in set)
    }

    // Просроченный pin-set Android молча перестаёт применять — защита исчезает без единой ошибки.
    // Покраснел этот тест — пора пересчитать пины по живой цепочке и продлить expiration.
    @Test
    fun срокДействияПинов_ещёНеИстёк() {
        val cfg = configFor(PINNED_DOMAIN)
        val exp = pinSet(cfg)?.getAttribute("expiration").orEmpty()
        assertTrue("у pin-set нет expiration — обязателен как спасательный люк", exp.isNotBlank())
        assertTrue(
            "pin-set истёк ($exp): пиннинг уже не применяется — пересчитай пины и продли срок",
            LocalDate.parse(exp).isAfter(LocalDate.now()),
        )
    }

    // Пин должен применяться при СИСТЕМНЫХ якорях — иначе доверия к LE-цепочке нет вовсе
    // и домен просто перестаёт открываться.
    @Test
    fun уЗапиненногоДомена_системныеЯкоряДоверия() {
        val cfg = configFor(PINNED_DOMAIN)
        val certs = cfg.getElementsByTagName("certificates")
        val sources = (0 until certs.length).map { (certs.item(it) as Element).getAttribute("src") }
        assertTrue("у $PINNED_DOMAIN пропали системные корни (src=\"system\")", "system" in sources)
    }

    // IP-фолбэк — спасательный люк пиннинга: отказ пина на домене уводит фейловер сюда.
    // Появится pin-set и здесь — оба пути к ядру окажутся заперты одной ошибкой.
    @Test
    fun ipФолбэк_безПиновИСоСвоимCa() {
        val cfg = configFor(IP_FALLBACK)
        assertNull("у IP-фолбэка $IP_FALLBACK появился pin-set — спасательный люк заперт", pinSet(cfg))
        val certs = cfg.getElementsByTagName("certificates")
        val sources = (0 until certs.length).map { (certs.item(it) as Element).getAttribute("src") }
        assertTrue("IP-фолбэк потерял доверие к своему CA (@raw/mayak_ca)", "@raw/mayak_ca" in sources)
    }
}
