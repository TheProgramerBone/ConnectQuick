package dev.connectquick.controller.identity

import dev.connectquick.controller.crypto.CryptoUtil

/**
 * A [DeviceIdentity] backed by a plain in-memory keypair — used directly by JVM unit tests (no
 * Android Keystore available there), and wrapped by [KeystoreDeviceIdentity] in production once
 * the seed has been unwrapped.
 */
class InMemoryDeviceIdentity private constructor(private val keyPair: CryptoUtil.Ed25519KeyPair) : DeviceIdentity {
    override val identityPub: String = keyPair.publicKeyB64Url
    override val deviceId: String = CryptoUtil.deriveDeviceId(identityPub)
    override fun sign(message: ByteArray): String = keyPair.sign(message)

    companion object {
        fun generate(): InMemoryDeviceIdentity = InMemoryDeviceIdentity(CryptoUtil.generateEd25519KeyPair())
        fun fromSeed(seed: ByteArray): InMemoryDeviceIdentity = InMemoryDeviceIdentity(CryptoUtil.ed25519KeyPairFromSeed(seed))
    }
}
