// Соединение, которое ГАРАНТИРОВАННО НЕ ПЕРЕИСПОЛЬЗУЕТ чужой сокет.
//
// 🔴 ЗАЧЕМ. `HttpURLConnection` на Android — это OkHttp с ОБЩИМ пулом keep-alive: сокет, открытый
// пять минут назад, молча достаётся следующему запросу к тому же адресу. Для обычного запроса это
// подарок, для пробы «а вышли ли мы в интернет ЧЕРЕЗ ТУННЕЛЬ» — ловушка, потому что сокет помнит
// маршрут, по которому его создали.
//
// Что это стоило людям (разбор диаг-логов 21-08, телефоны владельца #77 и #75). Приложение перед
// подключением ходит к ядру обычными запросами — туннеля ещё нет, сокет ложится в пул мимо туннеля.
// Затем поднимается туннель, и проба выхода берёт из пула ИМЕННО ЕГО. Дальше одно из двух:
//   • сокет уже мёртв (маршрут сменился) — запрос уходит в никуда и умирает по таймауту 4 с, а на
//     сервере его нет вовсе (сверено с журналом обращений ядра: строки за это время нет);
//   • сокет ещё жив МИМО туннеля — проба вернула бы собственный адрес телефона, и приложение сочло
//     бы выход подтверждённым при мёртвом туннеле. Это ровно та ложь, ради которой проба и заведена.
// Первое наблюдалось живьём: рукопожатие занимало 251 мс, а «Защищено» приходило через 5 с — все
// пять секунд уходили на протухший сокет. На перебор путей это множилось на три: каждая ступень
// клала в пул свой сокет, и следующая на нём же спотыкалась. Отсюда «у владельца 6,6 с».
//
// КАК ЧИНИМ. Ключ пула у OkHttp включает `SSLSocketFactory`: свой ЭКЗЕМПЛЯР фабрики на каждую пробу
// означает свой ключ, то есть соединение из чужого пула не достанется никогда. Фабрику НЕ пишем
// свою — оборачиваем системную, чтобы доверие осталось ровно тем же (вшитый `Mayak Prod CA E1` из
// network_security_config продолжает работать: проверку сертификата делает платформенный
// TrustManager внутри делегата, мы к ней не прикасаемся). Плюс `Connection: close` — свой сокет
// после ответа тоже не оставляем в пуле, иначе на нём споткнётся следующая ступень.
package org.amnezia.awg.mayak

import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.Socket
import java.net.URL
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLSocketFactory

object FreshConnection {

    /**
     * Открыть соединение, не беря сокет из общего пула и не оставляя свой.
     *
     * @param opener чем открывать (обход туннеля и т.п.); по умолчанию — обычное соединение.
     */
    fun open(url: URL, opener: ((URL) -> HttpURLConnection)? = null): HttpURLConnection {
        if (opener != null) {
            // 🔴 СОЕДИНЕНИЕ В ОБХОД ТУННЕЛЯ ФАБРИКУ НЕ ТРОГАЕМ. Его открывает `Network.openConnection`
            // (OutsideTunnel), и привязка к конкретной сети держится на сокет-фабрике САМОЙ сети —
            // а такие соединения и так не берутся из общего пула, у каждой Network пул свой. То есть
            // подменять здесь нечего, а риск сломать обход есть: обход существует ровно затем, чтобы
            // поддержка и диагностика работали при МЁРТВОМ туннеле, и тихо потерять его нельзя.
            val conn = opener(url)
            conn.setRequestProperty("Connection", "close")
            return conn
        }
        val conn = url.openConnection() as HttpURLConnection
        // Своя обёртка на КАЖДЫЙ вызов: именно её отличие от прошлой и разводит пулы.
        (conn as? HttpsURLConnection)?.sslSocketFactory =
            NotShared(HttpsURLConnection.getDefaultSSLSocketFactory())
        conn.setRequestProperty("Connection", "close")
        return conn
    }

    /**
     * Обёртка над системной фабрикой: поведение делегата один в один, отличается только тем, что
     * это ДРУГОЙ объект. Больше от неё ничего и не требуется.
     */
    private class NotShared(private val d: SSLSocketFactory) : SSLSocketFactory() {
        override fun getDefaultCipherSuites(): Array<String> = d.defaultCipherSuites
        override fun getSupportedCipherSuites(): Array<String> = d.supportedCipherSuites
        override fun createSocket(s: Socket?, host: String?, port: Int, autoClose: Boolean): Socket =
            d.createSocket(s, host, port, autoClose)
        override fun createSocket(host: String?, port: Int): Socket = d.createSocket(host, port)
        override fun createSocket(host: String?, port: Int, localHost: InetAddress?, localPort: Int): Socket =
            d.createSocket(host, port, localHost, localPort)
        override fun createSocket(host: InetAddress?, port: Int): Socket = d.createSocket(host, port)
        override fun createSocket(address: InetAddress?, port: Int, localAddress: InetAddress?, localPort: Int): Socket =
            d.createSocket(address, port, localAddress, localPort)
    }
}
