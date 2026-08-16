// Ящик сообщений (SPEC-0047): коды контракта и решения, которые можно проверить БЕЗ Android.
//
// Зачем отдельный файл, а не всё в :ui. Здесь живут ровно те три вещи, в которых легко ошибиться и
// которые дорого ловить руками на телефоне: белые списки кодов (расходятся с сервером молча),
// «сейчас тихий час?» (граница ЧЕРЕЗ полночь — классическая ошибка на единицу) и чтение параметра
// из jsonb (сервер вправе прислать и число, и строку). Всё это — чистые функции под тестами.
package org.amnezia.awg.mayak.core

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** Категории сообщений (SPEC-0047 §2.1). `account` выключателя не имеет — это работа сервиса. */
object MessageCategories {
    const val ACCOUNT = "account"
    const val SERVICE = "service"
    const val NEWS = "news"
}

/**
 * Что открыть по нажатию (SPEC-0047 §2.5). Наружу, в браузер, уводит ТОЛЬКО [BILLING] — оплата живёт
 * в кабинете; остальное открывается внутри приложения.
 */
object MessageActions {
    const val NONE = "none"
    const val BILLING = "billing"
    const val SUPPORT = "support"
    const val GROUP = "group"
    const val DEVICES = "devices"
    const val CONNECT = "connect"
    const val SETTINGS = "settings"
}

/**
 * Коды поводов (SPEC-0047 §2.4) — те, для которых у приложения ЕСТЬ свой перевод. Незнакомый код
 * сюда не попадает и показывается серверным текстом: белый список нужен, чтобы новый повод на
 * сервере не превратился в пустой экран у человека со старой сборкой.
 *
 * `custom` в списке нет намеренно: у него текст всегда серверный (ручное сообщение или рассылка).
 */
object MessageKinds {
    const val SUBSCRIPTION_EXPIRING = "subscription_expiring"
    const val SUBSCRIPTION_EXPIRED = "subscription_expired"
    /** Кончился ПРОБНЫЙ доступ: ни подписки («продлить» нечего), ни льготных дней. */
    const val TRIAL_EXPIRED = "trial_expired"
    const val ACCESS_REVOKED = "access_revoked"
    const val PAYMENT_RECEIPT = "payment_receipt"
    const val PAYMENT_REFUND = "payment_refund"
    const val BALANCE_TOPUP = "balance_topup"
    const val BALANCE_CHARGE = "balance_charge"
    const val AUTORENEW_OK = "autorenew_ok"
    const val AUTORENEW_FAILED = "autorenew_failed"
    const val PLAN_CHANGED = "plan_changed"
    const val GROUP_INVITED = "group_invited"
    const val GROUP_INVITE_ACCEPTED = "group_invite_accepted"
    const val GROUP_SLOT_REVOKED = "group_slot_revoked"
    const val GROUP_ROOT_EXPIRING = "group_root_expiring"
    const val NEW_DEVICE_LOGIN = "new_device_login"
    const val PASSWORD_CHANGED = "password_changed"
    const val SUPPORT_REPLY = "support_reply"
    const val INACTIVE_WARNING = "inactive_warning"
    const val NEVER_CONNECTED = "never_connected"
    const val MAINTENANCE = "maintenance"

    // Приглашения (SPEC-0049). Три РАЗНЫХ повода намеренно: «пришёл друг» — это ожидание, а две
    // награды — деньги на счету, и путать «за друга» с «за то, что ты сам пришёл по приглашению»
    // нельзя, иначе приглашённый пойдёт искать, кого же он позвал.
    const val REFERRAL_FRIEND_JOINED = "referral_friend_joined"
    const val REFERRAL_REWARD = "referral_reward"
    const val REFERRAL_BONUS = "referral_bonus"

    /** Ручное сообщение или массовая рассылка: текст ВСЕГДА серверный, перевода у нас нет. */
    const val CUSTOM = "custom"
}

/**
 * Значение параметра сообщения строкой. Числа и логические значения приводим к строке сами: в базе
 * это `jsonb`, и «3» с сервера законно приезжает и как `"3"`, и как `3`. Объект/массив/`null` →
 * null: подставить их в текст всё равно нечем, и лучше показать серверный текст целиком.
 */
fun paramString(params: JsonObject, key: String): String? {
    val v = params[key] as? JsonPrimitive ?: return null
    if (v.isString) return v.content.takeIf { it.isNotBlank() }
    return v.content.takeIf { it.isNotBlank() && it != "null" }
}

/** Начало и конец тихих часов (SPEC-0047 §4): показываем без звука с 23:00 до 09:00 ПО ТЕЛЕФОНУ. */
const val QUIET_HOURS_FROM = 23
const val QUIET_HOURS_TO = 9

/**
 * Тихий ли сейчас час. Отрезок ПЕРЕСЕКАЕТ полночь, поэтому это «или», а не «и»: наивное
 * `hour in 23..9` не истинно никогда, и уведомления звенели бы ночью при включённой настройке.
 *
 * @param hour час суток на телефоне человека, 0..23.
 */
fun quietHourNow(hour: Int): Boolean = hour >= QUIET_HOURS_FROM || hour < QUIET_HOURS_TO
