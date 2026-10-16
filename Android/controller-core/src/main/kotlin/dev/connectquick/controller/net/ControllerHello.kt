package dev.connectquick.controller.net

import dev.connectquick.controller.crypto.CryptoUtil
import dev.connectquick.controller.identity.DeviceIdentity
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Answers the backend's `hello.challenge` for this phone: signs the challenge with the device
 * identity (proving key possession without ever sending the key). Install it on the
 * [BackendClient] once, at creation, so the signalling link authenticates on every (re)connect —
 * pairing and sessions then share that one authenticated connection.
 */
class ControllerHello(
    private val identity: DeviceIdentity,
    private val clientVersion: String = "0.2.0",
) : HelloAuthenticator {
    override fun buildHello(serverChallenge: String): JsonObject = buildJsonObject {
        put("role", "controller")
        put("deviceId", identity.deviceId)
        put("identityPub", identity.identityPub)
        put("challengeSig", identity.sign(CryptoUtil.fromBase64Url(serverChallenge)))
        put("clientVersion", clientVersion)
    }
}
