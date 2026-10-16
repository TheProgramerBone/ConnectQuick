package dev.connectquick.controller

import dev.connectquick.controller.crypto.CryptoUtil
import dev.connectquick.controller.identity.InMemoryDeviceIdentity
import dev.connectquick.controller.net.BackendClient
import dev.connectquick.controller.pairing.PairingController
import dev.connectquick.controller.pairing.PairingOutcome
import dev.connectquick.controller.pairing.QrPairingPayload
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.net.Socket
import java.net.ServerSocket

/**
 * Runs the real §6-7 pairing handshake end-to-end against the actual Node backend (spawned as a
 * real subprocess, in-memory DB, no mocks) — mirrors `Backend/test-support/flows.ts` and
 * `WindowsHost/.../PairingEndToEndTests.cs`, except here the Controller half is the *real*
 * [PairingController] this app ships, not a test double. The Host side is simulated with the
 * same vector-verified [CryptoUtil] primitives, standing in for the real WindowsHost app.
 */
class PairingEndToEndTest {

    private lateinit var backendProcess: Process
    private var port: Int = 0

    @Before
    fun startBackend() {
        port = findFreeTcpPort()
        val repoRoot = System.getProperty("connectquick.repoRoot")
            ?: error("connectquick.repoRoot system property not set — see app/build.gradle.kts")
        val backendDir = File(repoRoot, "Backend")

        val builder = ProcessBuilder("node", "src/server.ts")
            .directory(backendDir)
            .redirectErrorStream(true)
        builder.environment()["PORT"] = port.toString()
        builder.environment()["DB_PATH"] = ":memory:"
        builder.environment()["LOG_LEVEL"] = "WARN"
        backendProcess = builder.start()
        // Drain stdout so the child process never blocks on a full pipe.
        Thread { backendProcess.inputStream.bufferedReader().lineSequence().forEach { } }.apply { isDaemon = true }.start()

        waitForPort(port, timeoutMs = 15_000)
    }

    @After
    fun stopBackend() {
        backendProcess.destroyForcibly()
    }

    @Test
    fun controller_pairing_flow_completes_against_the_real_backend() = runBlocking {
        val relay = "ws://localhost:$port"

        // --- Host side: simulated (WindowsHost isn't spawnable headlessly here), same
        //     vector-verified crypto primitives as the real WindowsHost app uses.
        val hostIdentity = InMemoryDeviceIdentity.generate()
        val hostEphemeral = CryptoUtil.generateX25519KeyPair()
        val hostClient = BackendClient(relay)
        val hostName = "E2E Simulated PC"

        val pairingCreated = CompletableDeferred<JsonObject>()
        hostClient.on("hello.challenge") { payload ->
            val challenge = CryptoUtil.fromBase64Url(payload.getValue("serverChallenge").jsonPrimitive.content)
            hostClient.send(
                "hello",
                buildJsonObject {
                    put("role", "host")
                    put("deviceId", hostIdentity.deviceId)
                    put("identityPub", hostIdentity.identityPub)
                    put("challengeSig", hostIdentity.sign(challenge))
                    put("clientVersion", "0.1.0-e2e")
                    put("hostName", hostName)
                },
            )
        }
        val helloAck = CompletableDeferred<Unit>()
        hostClient.on("hello.ack") { helloAck.complete(Unit) }
        hostClient.on("pairing.created") { payload -> pairingCreated.complete(payload) }
        hostClient.on("pairing.incoming") { payload ->
            val pairingId = payload.getValue("pairingId").jsonPrimitive.content
            val deviceId = payload.getValue("deviceId").jsonPrimitive.content
            val permissions = listOf("remote_desktop", "mouse", "keyboard", "clipboard", "files", "media", "apps", "power")
            val message = CryptoUtil.pairingDecisionMessage(pairingId, deviceId, approved = true, permissions)
            hostClient.send(
                "pairing.decision",
                buildJsonObject {
                    put("pairingId", pairingId)
                    put("approved", true)
                    put("permissions", kotlinx.serialization.json.JsonArray(permissions.map { kotlinx.serialization.json.JsonPrimitive(it) }))
                    put("hostSignature", hostIdentity.sign(message))
                },
            )
        }

        val hostJob: Job = launch { hostClient.runForever() }
        withTimeout(5_000) { helloAck.await() }
        hostClient.send(
            "pairing.create",
            buildJsonObject {
                put("hostName", hostName)
                put("hostEphPub", hostEphemeral.publicKeyB64Url)
            },
        )
        val created = withTimeout(5_000) { pairingCreated.await() }
        val qr = QrPairingPayload(
            v = 1,
            pairingId = created.getValue("pairingId").jsonPrimitive.content,
            relay = relay,
            hostId = hostIdentity.deviceId,
            hostName = hostName,
            hostPub = hostIdentity.identityPub,
            hostEphPub = hostEphemeral.publicKeyB64Url,
            nonce = created.getValue("nonce").jsonPrimitive.content,
            exp = created.getValue("expiresAt").jsonPrimitive.content.toLong() / 1000,
        )

        // --- Controller side: the real production code this app ships. ---
        val controllerIdentity = InMemoryDeviceIdentity.generate()
        val controllerClient = BackendClient(relay)
        val controller = PairingController(controllerClient, controllerIdentity, deviceName = "E2E JVM Controller")

        val ready = CompletableDeferred<Unit>()
        val outcome = CompletableDeferred<PairingOutcome>()
        controller.onReady = { ready.complete(Unit) }
        controller.onOutcome = { outcome.complete(it) }

        val controllerJob: Job = launch { controllerClient.runForever() }
        withTimeout(5_000) { ready.await() }
        controller.submitPairing(qr)

        val result = withTimeout(5_000) { outcome.await() }
        assertTrue("expected Success, got $result", result is PairingOutcome.Success)
        val success = result as PairingOutcome.Success
        assertEquals(hostIdentity.deviceId, success.hostId)
        assertEquals(hostName, success.hostName)
        assertEquals(
            listOf("remote_desktop", "mouse", "keyboard", "clipboard", "files", "media", "apps", "power"),
            success.permissions,
        )

        hostJob.cancel()
        controllerJob.cancel()
    }

    private fun findFreeTcpPort(): Int = ServerSocket(0).use { it.localPort }

    private fun waitForPort(port: Int, timeoutMs: Long) {
        val deadline = System.currentTimeMillis() + timeoutMs
        var lastError: Exception? = null
        while (System.currentTimeMillis() < deadline) {
            try {
                Socket("localhost", port).close()
                return
            } catch (e: Exception) {
                lastError = e
                Thread.sleep(200)
            }
        }
        throw IllegalStateException("backend did not open port $port in time", lastError)
    }
}
