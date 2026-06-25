// Android-реализация KeyProvider (:core): генерация пары ключей AmneziaWG на устройстве поверх
// штатной crypto форка (Curve25519). Приватный ключ остаётся на устройстве (ADR-0004) — наружу
// (в ядро) уходит только публичный.
package org.amnezia.awg.mayak

import org.amnezia.awg.crypto.KeyPair
import org.amnezia.awg.mayak.core.KeyMaterial
import org.amnezia.awg.mayak.core.KeyProvider

class AwgKeyProvider : KeyProvider {
    override fun generate(): KeyMaterial {
        val kp = KeyPair()
        return KeyMaterial(
            privateKeyBase64 = kp.privateKey.toBase64(),
            publicKeyBase64 = kp.publicKey.toBase64(),
        )
    }
}
