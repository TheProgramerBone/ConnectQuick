package dev.connectquick.controller.session

import kotlinx.serialization.Serializable

/**
 * A PC this phone has been paired with: everything needed to reconnect and to verify, without
 * trusting the backend, that a session really is with that PC (its identity key and the
 * permissions its user granted at pairing).
 */
@Serializable
data class PairedHost(
    val hostId: String,
    val hostName: String,
    val hostPub: String,
    val relay: String,
    val permissions: List<String>,
    val pairedAtMs: Long,
    /** Every relay that reaches this PC, best first (its LAN relay, then the cloud one). Empty for phones paired before this existed. */
    val relays: List<String> = emptyList(),
)

/** What the PC reports about itself in its first encrypted frame (`ctl.caps`). */
data class HostCaps(
    val hostVersion: String,
    val permissions: Set<String>,
    val features: Set<String>,
    val screenWidth: Int?,
    val screenHeight: Int?,
    val maxFileBytes: Long,
    val clipboardAutoSync: Boolean,
    /** Relays the PC says reach it, best first (empty: it did not say). */
    val relays: List<String> = emptyList(),
)
