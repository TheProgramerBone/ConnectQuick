package dev.connectquick.controller.identity

/** The Controller's long-term Ed25519 identity — self-certifying deviceId, per Protocol §3. */
interface DeviceIdentity {
    val deviceId: String
    val identityPub: String
    fun sign(message: ByteArray): String
}
