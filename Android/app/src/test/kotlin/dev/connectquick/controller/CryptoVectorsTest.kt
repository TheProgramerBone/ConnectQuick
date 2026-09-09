package dev.connectquick.controller

import dev.connectquick.controller.crypto.CryptoUtil
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Asserts the Kotlin crypto layer reproduces `Protocol/test-vectors/crypto-vectors.json`
 * bit-for-bit, exactly like `Backend/test/unit/testVectors.test.ts` (TypeScript) and
 * `WindowsHost/.../CryptoVectorsTests.cs` (C#) do. This is what makes it safe to claim the
 * Android Controller's pairing handshake is wire-compatible with the real backend and
 * WindowsHost, not just "compiles". Runs as a plain JVM unit test — no emulator/device needed.
 */
class CryptoVectorsTest {

    private val vectors: JsonObject by lazy {
        val repoRoot = System.getProperty("connectquick.repoRoot")
            ?: error("connectquick.repoRoot system property not set — see app/build.gradle.kts")
        val file = File(repoRoot, "Protocol/test-vectors/crypto-vectors.json")
        Json.parseToJsonElement(file.readText()) as JsonObject
    }

    private fun JsonObject.str(key: String): String = getValue(key).jsonPrimitive.content

    @Test
    fun deriveDeviceId_matches_named_identities() {
        val identities = vectors.getValue("identities") as JsonObject
        val host = identities.getValue("host") as JsonObject
        val controller = identities.getValue("controller") as JsonObject

        assertEquals(host.str("expectedDeviceId"), CryptoUtil.deriveDeviceId(host.str("publicKeyB64Url")))
        assertEquals(controller.str("expectedDeviceId"), CryptoUtil.deriveDeviceId(controller.str("publicKeyB64Url")))
    }

    @Test
    fun deriveDeviceId_matches_all_listed_cases() {
        val cases = (vectors.getValue("deriveDeviceId") as JsonObject).getValue("cases").jsonArray
        for (c in cases) {
            val obj = c as JsonObject
            assertEquals(obj.str("expected"), CryptoUtil.deriveDeviceId(obj.str("identityPub")))
        }
    }

    @Test
    fun challengeSignature_matches_for_host_and_controller() {
        val v = vectors.getValue("challengeSignature") as JsonObject
        val challenge = CryptoUtil.fromBase64Url(v.str("serverChallengeB64Url"))

        val identities = vectors.getValue("identities") as JsonObject
        val host = identities.getValue("host") as JsonObject
        val hostKeyPair = CryptoUtil.ed25519KeyPairFromSeed(CryptoUtil.fromBase64Url(host.str("privateJwkD")))
        assertEquals(v.str("hostSignatureB64Url"), hostKeyPair.sign(challenge))

        val controller = identities.getValue("controller") as JsonObject
        val controllerKeyPair = CryptoUtil.ed25519KeyPairFromSeed(CryptoUtil.fromBase64Url(controller.str("privateJwkD")))
        assertEquals(v.str("controllerSignatureB64Url"), controllerKeyPair.sign(challenge))

        // A verifier (the backend's role) must also accept these signatures over the public keys alone.
        assertTrue(CryptoUtil.verifyEd25519(host.str("publicKeyB64Url"), challenge, v.str("hostSignatureB64Url")))
        assertTrue(CryptoUtil.verifyEd25519(controller.str("publicKeyB64Url"), challenge, v.str("controllerSignatureB64Url")))
    }

    @Test
    fun computeSas_matches_vector() {
        val v = vectors.getValue("sas") as JsonObject
        val sas = CryptoUtil.computeSas(v.str("hostPub"), v.str("deviceEphemeralPub"), v.str("nonceB64Url"))
        assertEquals(v.str("expectedSasCode"), sas)
    }

    @Test
    fun pairingDecisionSignature_matches_approve_and_reject_vectors() {
        val root = vectors.getValue("pairingDecisionSignature") as JsonObject
        val pairingId = root.str("pairingId")
        val deviceId = root.str("deviceId")
        val host = (vectors.getValue("identities") as JsonObject).getValue("host") as JsonObject
        val hostKeyPair = CryptoUtil.ed25519KeyPairFromSeed(CryptoUtil.fromBase64Url(host.str("privateJwkD")))

        for (caseName in listOf("approve", "reject")) {
            val c = root.getValue(caseName) as JsonObject
            val approved = c.getValue("approved").jsonPrimitive.content.toBoolean()
            val permissions = c.getValue("permissions").jsonArray.map { it.jsonPrimitive.content }

            val message = CryptoUtil.pairingDecisionMessage(pairingId, deviceId, approved, permissions)
            assertEquals(c.str("signedMessage"), String(message, Charsets.UTF_8))
            assertEquals(c.str("signatureB64Url"), hostKeyPair.sign(message))
        }
    }
}
