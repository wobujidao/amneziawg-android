// Список адресов ядра: что зашито в сборку, что узнали от сервера и в каком порядке пробовать.
//
// Зачем отдельный объект. Раньше список был константой MayakActivity.DEFAULT_HOSTS, и её копировали
// в четырёх местах (главный экран, настройки, keepalive, телеметрия). Пока домен один, это работало;
// но по директиве владельца 2026-07-26 у проекта появился РЕЕСТР доменов (админка → «Домены»,
// миграция 0089), и приложение обязано (1) собираться с актуальным списком и (2) подхватывать его
// живьём — иначе после блокировки основного домена установленные приложения останутся с мёртвым
// адресом до следующего релиза в маркете.
//
// Порядок сборки итогового списка:
//   1. сервер, заданный руками в настройках (или пришедший в рег-ссылке) — воля пользователя выше всего;
//   2. адреса, полученные от ядра (GET /v1/client/hosts) и сохранённые локально — самые свежие;
//   3. зашитые в сборку (MayakHosts.baked) — работают, даже когда сеть не пускает никуда.
// Дубликаты убираем с сохранением порядка: HostProvider обходит список по кругу, и повтор означал бы
// лишний таймаут на том же мёртвом адресе.
package org.amnezia.awg.mayak

import android.content.Context
import android.util.Log
import org.amnezia.awg.mayak.core.MayakBackend
import org.amnezia.awg.mayak.core.MayakHosts

object MayakHostList {
    private const val TAG = "Mayak/Hosts"

    /** Итоговый список базовых URL для HostProvider. savedServer — сервер из настроек (может быть null). */
    fun effective(context: Context, savedServer: String?): List<String> {
        val out = LinkedHashSet<String>()
        savedServer?.trimEnd('/')?.takeIf { it.isNotBlank() }?.let { out.add(it) }
        out.addAll(MayakPrefs.learnedHosts(context))
        out.addAll(MayakHosts.baked)
        return out.toList()
    }

    /** Спросить у ядра актуальный реестр и запомнить. Best-effort: ошибка/пустой ответ — молча оставляем
     *  прежний список (пустой список от сервера означал бы «адресов нет» и отрезал бы клиента от ядра). */
    suspend fun refresh(context: Context, backend: MayakBackend) {
        val list = backend.hosts() ?: return
        val urls = list.api.mapNotNull { h ->
            val host = h.trim().removeSuffix("/")
            if (host.isEmpty()) null else if (host.startsWith("http")) host else "https://$host"
        }
        if (urls.isEmpty()) {
            Log.i(TAG, "сервер вернул пустой список адресов — оставляю прежний")
            return
        }
        if (urls == MayakPrefs.learnedHosts(context)) return
        MayakPrefs.setLearnedHosts(context, urls)
        Log.i(TAG, "список адресов ядра обновлён: ${urls.size} шт")
    }
}
