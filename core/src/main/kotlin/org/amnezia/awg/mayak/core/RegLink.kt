// Санитайзинг регистрационной ссылки mayak://reg?email=..&password=..[&server=..].
// Чистая логика (JVM, без Android) — юнит-тестируемый кусок core.
package org.amnezia.awg.mayak.core

object RegLink {

    /**
     * Проверяет server-параметр рег-ссылки: принимаем ТОЛЬКО валидный `https://`-URL с хостом.
     * Зачем: server из ссылки становится ПРИОРИТЕТНЫМ адресом ядра (сохраняется, идёт в HostProvider).
     * Без проверки `mayak://reg?...&server=http://evil` (или `server=javascript:..`, мусор) сохранился бы
     * как адрес ядра → Bearer-токен/логин ушли бы plaintext либо на подставной сервер. require(https) в
     * MayakBackend ловит это лишь в момент запроса (поздно, с плохим UX и уже сохранённым адресом) — здесь
     * фильтруем на входе. Возвращает нормализованный URL (без хвостового '/') либо null (→ адреса по умолч.).
     */
    fun sanitizeServer(server: String?): String? {
        val s = server?.trim()?.trimEnd('/').orEmpty()
        if (s.isEmpty()) return null
        val uri = runCatching { java.net.URI(s) }.getOrNull() ?: return null
        if (!"https".equals(uri.scheme, ignoreCase = true)) return null
        if (uri.host.isNullOrBlank()) return null
        return s
    }
}
