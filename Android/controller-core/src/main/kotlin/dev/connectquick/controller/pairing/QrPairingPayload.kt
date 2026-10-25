package dev.connectquick.controller.pairing

import kotlinx.serialization.Serializable

/** QR content per `Protocol/protocol-spec.md` §5 — never carries private material. */
@Serializable
data class QrPairingPayload(
    val v: Int,
    val pairingId: String,
    val relay: String,
    val hostId: String,
    val hostName: String,
    val hostPub: String,
    val hostEphPub: String,
    val nonce: String,
    val exp: Long,
    /** Optional: every address to try, best first. [relay] is where this pairing happens. */
    val relays: List<String> = emptyList(),
)
