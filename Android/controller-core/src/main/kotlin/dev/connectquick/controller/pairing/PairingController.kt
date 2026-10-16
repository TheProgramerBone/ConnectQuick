package dev.connectquick.controller.pairing

import dev.connectquick.controller.crypto.CryptoUtil
import dev.connectquick.controller.identity.DeviceIdentity
import dev.connectquick.controller.net.BackendClient
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

sealed class PairingOutcome {
    data class Success(val hostId: String, val hostName: String, val permissions: List<String>) : PairingOutcome()
    data class Failure(val reason: String) : PairingOutcome()
}

/**
 * Wires the raw [BackendClient] envelope traffic to the Controller-side pairing flow
 * (Protocol §4 "Pairing", §7 handshake steps 1-6): transport `hello`, then `pairing.init` (built
 * from a scanned [QrPairingPayload]) → `pairing.result`. UI code only ever sees [onReady] /
 * [onOutcome] — it never touches the wire format.
 *
 * Mirrors `WindowsHost/.../Pairing/PairingCoordinator.cs` but for the opposite role: this side
 * never signs a `pairing.decision` (only a Host does), but it must independently verify the
 * Host's signature over `pairing.result` — never just trust the backend's word (Docs/SECURITY.md,
 * "What the backend never sees").
 */
class PairingController(
    private val client: BackendClient,
    private val identity: DeviceIdentity,
    private val deviceName: String,
    private val clientVersion: String = "0.1.0",
) {
    /** Fires once transport authentication (`hello`/`hello.ack`) completes — safe to call [submitPairing] after this. */
    var onReady: (() -> Unit)? = null
    var onOutcome: ((PairingOutcome) -> Unit)? = null

    private var pendingPairingId: String? = null

    init {
        client.on("hello.challenge") { payload -> onHelloChallenge(payload) }
        client.on("hello.ack") { onReady?.invoke() }
        client.on("pairing.result") { payload -> onPairingResult(payload) }
        client.on("error") { payload -> onError(payload) }
    }

    private fun onHelloChallenge(payload: JsonObject) {
        val challenge = CryptoUtil.fromBase64Url(payload.getValue("serverChallenge").jsonPrimitive.content)
        val signature = identity.sign(challenge)
        client.send(
            "hello",
            buildJsonObject {
                put("role", "controller")
                put("deviceId", identity.deviceId)
                put("identityPub", identity.identityPub)
                put("challengeSig", signature)
                put("clientVersion", clientVersion)
            },
        )
    }

    /** Sends `pairing.init` for a QR the user just scanned. Call only after [onReady] has fired. */
    fun submitPairing(qr: QrPairingPayload) {
        pendingPairingId = qr.pairingId
        val ephemeral = CryptoUtil.generateX25519KeyPair()
        client.send(
            "pairing.init",
            buildJsonObject {
                put("pairingId", qr.pairingId)
                put("deviceId", identity.deviceId)
                put("deviceName", deviceName)
                put("identityPub", identity.identityPub)
                put("ephemeralPub", ephemeral.publicKeyB64Url)
            },
        )
    }

    private fun onPairingResult(payload: JsonObject) {
        val pairingId = pendingPairingId
        if (pairingId == null || payload.getValue("pairingId").jsonPrimitive.content != pairingId) {
            return // stray/duplicate result, nothing pending for it
        }
        val success = payload.getValue("success").jsonPrimitive.boolean
        if (!success) {
            val reason = payload["reason"]?.jsonPrimitive?.content ?: "unknown"
            pendingPairingId = null
            onOutcome?.invoke(PairingOutcome.Failure(reason))
            return
        }

        val hostId = payload.getValue("hostId").jsonPrimitive.content
        val hostName = payload.getValue("hostName").jsonPrimitive.content
        val hostPub = payload.getValue("hostPub").jsonPrimitive.content
        val hostSignature = payload.getValue("hostSignature").jsonPrimitive.content
        val permissions = payload.getValue("permissions").jsonArray.map { it.jsonPrimitive.content }
        pendingPairingId = null

        // The Host's signature over its own decision must verify independently — a compromised
        // backend must not be able to fabricate an authorization the Host never actually granted.
        val message = CryptoUtil.pairingDecisionMessage(pairingId, identity.deviceId, approved = true, permissions)
        if (!CryptoUtil.verifyEd25519(hostPub, message, hostSignature)) {
            onOutcome?.invoke(PairingOutcome.Failure("invalid_host_signature"))
            return
        }
        onOutcome?.invoke(PairingOutcome.Success(hostId, hostName, permissions))
    }

    private fun onError(payload: JsonObject) {
        if (pendingPairingId == null) return
        val code = payload["code"]?.jsonPrimitive?.content ?: "error"
        val message = payload["message"]?.jsonPrimitive?.content ?: ""
        pendingPairingId = null
        onOutcome?.invoke(PairingOutcome.Failure("$code: $message"))
    }
}
