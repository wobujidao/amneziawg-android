// Trust-anchor подписанного delivery-документа (F-T8): публичные Ed25519-ключи, которым приложение
// верит. Целостность самих ключей даёт подпись APK; ПРИВАТНЫЙ ключ подписи — ОФЛАЙН-файл у владельца
// (ADR-0019, решение 2026-07-01: не YubiKey), в приложение и на серверы не попадает.
//
// 🔴 КЛЮЧЕЙ ПОКА НЕТ — И ЭТО НЕ ЗАГЛУШКА-ОБМАН, А ЧЕСТНЫЙ FAIL-CLOSED. Церемония
// `mayak-configsign keygen` владельцем не проводилась (боевой /v1/client/delivery отдаёт 404 —
// каналы отложены решением владельца, SPEC-0009 §4). Пустая карта якорей = любой конверт отвергается
// (UNKNOWN_EPOCH), сеть на /v1/client/delivery не дёргается вовсе, приложение живёт ровно как сейчас.
//
// Как ввести в строй (когда владелец сгенерит ключ):
//   1. офлайн: mayak-configsign keygen → pub.key (base64 сырых 32 байт);
//   2. вписать сюда: put(1, anchor("<содержимое pub.key>"));
//   3. на ядре: mayak-configsign gen-delivery + sign офлайн-ключом → MAYAK_DELIVERY_DOC_FILE;
//   4. релиз приложения (якорь едет только с APK — на то он и anchor).
// Ротация (стандарт TUF, две эпохи): выпустить ключ эпохи 2 → релиз с ОБОИМИ якорями → сервер
// переключает подпись на эпоху 2 → в следующем релизе эпоху 1 убрать. Выбор якоря СТРОГО по
// key_epoch конверта; неизвестная эпоха = отказ, «перепробовать все ключи» запрещено (Delivery.kt).
package org.amnezia.awg.mayak

import android.util.Base64

object MayakDeliveryTrust {
    /** эпоха ключа → сырые 32 байта публичного ключа (base64 StdEncoding — как configsign.EncodePublicKey). */
    val anchorsByEpoch: Map<Int, ByteArray> = buildMap {
        // put(1, anchor("<PUBKEY_EPOCH_1_BASE64>"))
        // put(2, anchor("<PUBKEY_EPOCH_2_BASE64>"))
    }

    @Suppress("unused") // используется, как только появится первый ключ
    private fun anchor(b64: String): ByteArray = Base64.decode(b64, Base64.DEFAULT)
}
