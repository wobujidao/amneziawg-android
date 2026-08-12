// Контракт push-уведомлений (ускоритель ящика SPEC-0047). Пуш НИЧЕГО НЕ НЕСЁТ, кроме «загляни в
// ящик»: текст сообщения приложение берёт из ящика само.
//
// 🔴 Почему без текста. Пуш идёт ЧЕРЕЗ GOOGLE, а уведомление читают через плечо — приложение умеет
// прятаться под «Погоду»/«Заметки» (MayakDisguise), и одна строка «Осталось дней: 3» в шторке
// отменяет всю эту работу разом. Плюс замерено 12-08: `VISIBILITY_PRIVATE` текст на локскрине НЕ
// прячет, пока человек сам не включил системную настройку (а она выключена по умолчанию). Значит
// единственная надёжная защита — не отдавать текст никому, включая транспорт.
//
// 🔴 Почему контракт живёт ЗДЕСЬ, в :core, а не строками по месту вызова. Имена полей — это стык с
// ДРУГИМ репозиторием, и ломается он МОЛЧА: 12-08 приложение спрашивало `days`, а сервер клал
// `grace_days` — ничего не падало, просто человек получал не тот текст. Отсюда правило: имена
// сложены в одном файле рядом с DTO и закреплены сторожем (MayakPushContractTest в :ui).
package org.amnezia.awg.mayak.core

import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Платформа устройства для регистрации токена. Сервер по ней решает, каким транспортом отправлять;
 * сейчас у нас только Android, iOS/десктоп появятся отдельными значениями (docs/PLATFORM-VERSIONS-AND-GATE.md).
 */
object PushPlatforms {
    const val ANDROID = "android"
}

/**
 * Полезная нагрузка пуша: РОВНО два поля, оба служебные.
 *
 * `kind` = [KIND_MAILBOX] значит «в ящике для тебя что-то есть» — приложение идёт в ящик обычным
 * запросом и показывает уведомление само. `id` — только для лога и для отсечения повторов; текст по
 * нему не рисуется (в пуше его нет вовсе).
 *
 * Сообщение отправляется data-only (без блока `notification`) и с `android.priority = HIGH`:
 *  - data-only — потому что блок `notification` система показала бы САМА, серверным текстом и в обход
 *    приложения; на фоне мы даже не узнали бы об этом (`onMessageReceived` не зовётся);
 *  - HIGH — потому что normal в Doze складывается в пачку до выхода из сна, а мы будим ради того,
 *    о чём человеку надо знать сейчас (истёк доступ, чек, ответ поддержки).
 */
object PushData {
    const val KIND = "kind"
    const val ID = "id"

    /** Единственный известный повод: «загляни в ящик». Незнакомый `kind` приложение игнорирует. */
    const val KIND_MAILBOX = "mailbox"
}

/**
 * Регистрация адреса доставки: POST /v1/client/push/register.
 *
 * `token` — адрес устройства у транспорта (FCM), НЕ наш токен сессии: тот уходит заголовком, как во
 * всех клиентских ручках. `appVersion` сервер использует, чтобы не будить сборки, которые про ящик
 * ещё не знают.
 */
@Serializable
@OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)
data class PushRegisterRequest(
    val token: String,
    // 🔴 @EncodeDefault(ALWAYS) — не украшение. У значения по умолчанию есть ловушка: обычный
    // `Json {}` умолчания НЕ сериализует, и запрос уехал бы БЕЗ поля `platform`. У нас он уходит
    // через MayakBackend.defaultJson (там encodeDefaults = true), то есть беда проявилась бы не
    // сейчас, а когда кто-нибудь передаст другой Json — и проявилась бы молчанием, а не ошибкой.
    // Поймано сторожем MayakPushContractTest на первом же прогоне.
    @EncodeDefault(EncodeDefault.Mode.ALWAYS)
    val platform: String = PushPlatforms.ANDROID,
    @SerialName("app_version") val appVersion: String,
)

/** Снятие адреса: POST /v1/client/push/unregister. Одно поле — тот же адрес доставки. */
@Serializable
data class PushUnregisterRequest(
    val token: String,
)

/** Что делать с адресом доставки: см. [pushAction]. */
enum class PushAction { REGISTER, UNREGISTER, NOTHING }

/**
 * Решение «регистрировать адрес доставки, снимать или не делать ничего» — БЕЗ Android, чтобы его
 * можно было проверить тестом, а не телефоном. Ровно тут живут два правила, которые легко потерять.
 *
 * 1️⃣ Выключены уведомления — адрес НЕ НУЖЕН, а ранее отправленный надо снять. Причина не только
 * «показать всё равно нечем»: Google понижает приоритет пушам приложения, чьи high-priority
 * сообщения не заканчиваются видимым уведомлением (решение принимается по 7 дням поведения). То есть
 * регистрация там, где показать нельзя, портит доставку ОСТАЛЬНЫМ людям.
 *
 * 2️⃣ Транспорта нет вовсе ([available] = false: телефон без сервисов Google или сборка не боевая) —
 * не делаем НИЧЕГО и ничем не падаем. Ящик в этом случае работает опросом, как работал до пуша.
 *
 * @param notificationsEnabled разрешены ли уведомления у приложения (у нас это и есть выключатель).
 * @param available есть ли транспорт: конфигурация Firebase + сервисы Google на телефоне.
 * @param loggedIn есть ли вход (адрес доставки принадлежит УЧЁТКЕ, а не телефону).
 * @param alreadySent отправляли ли мы уже адрес ядру.
 * @param upToDate нет ли известной причины идти заново — то есть совпадает ли версия сборки с той, с
 *   которой адрес отправляли. САМ адрес сверяется позже, уже в [PushAction.REGISTER]: до запроса к
 *   транспорту он неизвестен, а запрос стоит делать последним.
 */
fun pushAction(
    notificationsEnabled: Boolean,
    available: Boolean,
    loggedIn: Boolean,
    alreadySent: Boolean,
    upToDate: Boolean,
): PushAction = when {
    // Снятие идёт ПЕРВЫМ правилом и не спрашивает про транспорт: человек мог запретить уведомления,
    // а мог и вынуть сервисы Google — адрес на ядре в обоих случаях лишний.
    !notificationsEnabled -> if (alreadySent) PushAction.UNREGISTER else PushAction.NOTHING
    !available -> PushAction.NOTHING
    !loggedIn -> PushAction.NOTHING
    alreadySent && upToDate -> PushAction.NOTHING
    else -> PushAction.REGISTER
}
