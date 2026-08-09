// MayakDelivery — забор и применение ПОДПИСАННОГО документа доставки (F-T8, SPEC-0009 T5).
//
// Что делает: тянет configsign.Envelope с GET /v1/client/delivery, проверяет подпись Ed25519 по
// вшитому trust-anchor (:core Delivery — там же срок и анти-откат), персистит принятый конверт и
// подмешивает адреса в существующие слои: cores → MayakHostList.effective, DoH → DohResolver,
// seed-IP → хвост списка хостов. Смысл: заблокируют домен — свежие адреса доезжают БЕЗ релиза в
// маркете, а подпись не даёт каналу доставки подсунуть чужие.
//
// Отсутствие документа (боевое ядро сейчас отдаёт 404 — каналы отложены владельцем) — ШТАТНО:
// молча живём на прежнем списке, человеку не показываем ничего. Отвергнутый документ — тоже:
// это повод для лога, но не для дырки в связи.
//
// Хранение: конверт целиком (verbatim) + максимальная ПРИНЯТАЯ версия (анти-откат живёт между
// запусками — иначе rollback-защита обнулялась бы перезапуском процесса). При чтении конверт
// проверяется ЗАНОВО (подпись+срок+эпоха): протухший кэш отваливается сам, а результат проверки
// кэшируется в памяти процесса, чтобы не гонять Ed25519 на каждом построении списка хостов.
package org.amnezia.awg.mayak

import android.content.Context
import android.util.Log
import org.amnezia.awg.mayak.core.Delivery
import org.amnezia.awg.mayak.core.DeliveryOutcome
import org.amnezia.awg.mayak.core.DohResolver
import org.amnezia.awg.mayak.core.MayakBackend

object MayakDelivery {
    private const val TAG = "Mayak/Delivery"

    /** Кэш последней УСПЕШНОЙ проверки (конверт → исход): подпись неизменного конверта не
     *  перепроверяется, но срок сверяется с текущим временем на КАЖДОМ чтении. */
    @Volatile private var verified: Pair<String, DeliveryOutcome.Ok>? = null

    /** Принятый и НЕ протухший документ; null — документа нет/протух/ключей не вшили. */
    private fun accepted(context: Context): DeliveryOutcome.Ok? {
        val anchors = MayakDeliveryTrust.anchorsByEpoch
        if (anchors.isEmpty()) return null // ключи ещё не вшиты (церемония не проводилась)
        val raw = MayakPrefs.deliveryEnvelope(context) ?: return null
        val now = System.currentTimeMillis() / 1000
        verified?.let { (cachedRaw, ok) ->
            if (cachedRaw == raw) return if (now < ok.expiresAt) ok else null
        }
        return when (val out = Delivery.verify(raw, anchors, now, MayakPrefs.deliveryVersion(context))) {
            is DeliveryOutcome.Ok -> {
                verified = raw to out
                out
            }
            is DeliveryOutcome.Rejected -> null // протух/чужое — живём без него, список не трогаем
        }
    }

    /** Базовые URL ядра из документа (L0/L1) — для MayakHostList.effective. */
    fun cores(context: Context): List<String> = accepted(context)?.doc?.cores.orEmpty()

    /** Seed-IP (L4) как базовые URL client-api — последний резерв, ПОСЛЕ вшитых адресов.
     *  ⚠️ Новому seed-IP нужен серт с SAN=IP под вшитым CA (network_security_config статичен,
     *  SPEC-0009 T5 §4) — иначе TLS его отвергнет и перебор просто пойдёт дальше. */
    fun seedUrls(context: Context): List<String> =
        accepted(context)?.doc?.seeds.orEmpty().map { Delivery.seedUrl(it) }

    /** Отдать DoH-резолверы документа в DohResolver (bootstrap-IP + путь, без системного DNS). */
    fun applyDoh(context: Context) {
        val ok = accepted(context) ?: return
        DohResolver.setExtraEndpoints(Delivery.dohEndpoints(ok.doc))
    }

    /**
     * Забрать свежий документ с ядра и, если он проходит проверку, принять. Best-effort: сюда
     * приходят и 404 (документ не заведён — сейчас это боевая норма), и сеть, и отвергнутые
     * конверты — во всех случаях прежний список остаётся как был. Пустой ответ ничего не затирает.
     */
    suspend fun refresh(context: Context, backend: MayakBackend) {
        if (MayakDeliveryTrust.anchorsByEpoch.isEmpty()) return // некому верить — не ходим зря
        val raw = backend.deliveryEnvelope() ?: return
        val now = System.currentTimeMillis() / 1000
        when (val out = Delivery.verify(raw, MayakDeliveryTrust.anchorsByEpoch, now, MayakPrefs.deliveryVersion(context))) {
            is DeliveryOutcome.Ok -> {
                MayakPrefs.setDelivery(context, raw, out.version)
                verified = raw to out
                applyDoh(context)
                Log.i(TAG, "подписанный документ доставки принят: v${out.version}, эпоха ${out.keyEpoch}, " +
                    "ядер ${out.doc.cores.size}, doh ${out.doc.doh.size}, seed ${out.doc.seeds.size}")
            }
            is DeliveryOutcome.Rejected ->
                // Отказ — не ошибка человека и не повод трогать рабочий список. Логи чистые:
                // причина — enum, содержимое конверта не печатаем.
                Log.w(TAG, "документ доставки отвергнут (${out.reason}) — остаёмся на прежнем списке")
        }
    }
}
