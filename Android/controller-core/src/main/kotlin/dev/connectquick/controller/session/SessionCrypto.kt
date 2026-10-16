package dev.connectquick.controller.session

import dev.connectquick.controller.crypto.CryptoUtil
import org.bouncycastle.crypto.agreement.X25519Agreement
import org.bouncycastle.crypto.digests.SHA256Digest
import org.bouncycastle.crypto.generators.HKDFBytesGenerator
import org.bouncycastle.crypto.params.HKDFParameters
import org.bouncycastle.crypto.params.X25519PrivateKeyParameters
import org.bouncycastle.crypto.params.X25519PublicKeyParameters
import java.nio.ByteBuffer
import java.security.GeneralSecurityException
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

enum class Direction(val wire: String) { CONTROLLER_TO_HOST("c2h"), HOST_TO_CONTROLLER("h2c") }

/** Authentication / replay failure while opening a frame. Never carries key material. */
class FrameRejected(message: String) : GeneralSecurityException(message)

/**
 * End-to-end session layer, byte-for-byte equal to `Backend/test-support/sessionCrypto.ts` and the
 * Windows Host's `SessionCrypto.cs`, pinned by `Protocol/test-vectors/session-vectors.json`
 * (protocol-spec.md section 9). Primitives: X25519 + HKDF-SHA256 (BouncyCastle) and AES-256-GCM
 * (the JCA provider on both Android and the JVM). Nothing here is a home-made algorithm.
 */
object SessionCrypto {
    const val MAX_SEQ = 0xFFFFFFFFL

    fun connectMessage(sessionId: String, hostId: String, deviceId: String, controllerEphPub: String, issuedAt: Long): ByteArray =
        "connectquick:session.connect:v1|$sessionId|$hostId|$deviceId|$controllerEphPub|$issuedAt".toByteArray(Charsets.UTF_8)

    fun acceptMessage(
        sessionId: String, hostId: String, deviceId: String, controllerEphPub: String, hostEphPub: String, permissions: List<String>,
    ): ByteArray =
        "connectquick:session.accept:v1|$sessionId|$hostId|$deviceId|$controllerEphPub|$hostEphPub|${permissions.joinToString(",")}"
            .toByteArray(Charsets.UTF_8)

    fun transcriptHash(
        sessionId: String, hostId: String, deviceId: String, controllerEphPub: String, hostEphPub: String, permissions: List<String>,
    ): ByteArray = CryptoUtil.sha256(
        "connectquick:session.transcript:v1|$sessionId|$hostId|$deviceId|$controllerEphPub|$hostEphPub|${permissions.joinToString(",")}"
            .toByteArray(Charsets.UTF_8),
    )

    class Ephemeral(val privateSeed: ByteArray) {
        val publicB64Url: String = CryptoUtil.toBase64Url(X25519PrivateKeyParameters(privateSeed, 0).generatePublicKey().encoded)
    }

    fun generateEphemeral(): Ephemeral = Ephemeral(ByteArray(32).also { SecureRandom().nextBytes(it) })

    /** X25519 shared secret; refuses the all-zero output a low-order peer key produces. */
    fun sharedSecret(own: Ephemeral, peerPubB64Url: String): ByteArray {
        val raw = CryptoUtil.fromBase64Url(peerPubB64Url)
        require(raw.size == 32) { "peer ephemeral key must be 32 bytes" }
        val agreement = X25519Agreement()
        agreement.init(X25519PrivateKeyParameters(own.privateSeed, 0))
        val out = ByteArray(32)
        try {
            agreement.calculateAgreement(X25519PublicKeyParameters(raw, 0), out, 0)
        } catch (e: IllegalStateException) {
            throw GeneralSecurityException("degenerate X25519 shared secret")
        }
        if (out.all { it.toInt() == 0 }) throw GeneralSecurityException("degenerate X25519 shared secret")
        return out
    }

    class Keys(val c2h: ByteArray, val h2c: ByteArray)

    fun deriveKeys(shared: ByteArray, transcriptHash: ByteArray): Keys {
        fun expand(info: String): ByteArray {
            val gen = HKDFBytesGenerator(SHA256Digest())
            gen.init(HKDFParameters(shared, transcriptHash, info.toByteArray(Charsets.UTF_8)))
            return ByteArray(32).also { gen.generateBytes(it, 0, 32) }
        }
        return Keys(expand("connectquick:session:v1:c2h"), expand("connectquick:session:v1:h2c"))
    }
}

class Frame(val seq: Long, val ct: String)

/**
 * One side of an established session. Sequence numbers must strictly increase, so a replayed or
 * re-ordered frame is refused; the GCM tag binds the session id, direction and sequence number.
 */
class SessionChannel private constructor(
    private val sessionId: String,
    private val sendKey: ByteArray,
    private val recvKey: ByteArray,
    private val sendDir: Direction,
    private val recvDir: Direction,
) {
    private val lock = Any()
    private var nextSendSeq = 0L
    private var lastRecvSeq = -1L

    private fun nonce(seq: Long): ByteArray = ByteBuffer.allocate(12).putInt(0).putLong(seq).array()
    private fun aad(dir: Direction, seq: Long): ByteArray = "cq1|$sessionId|${dir.wire}|$seq".toByteArray(Charsets.UTF_8)

    /** Encrypts with the next sequence number (or an explicit one, for test vectors). */
    fun seal(plaintext: ByteArray, explicitSeq: Long? = null): Frame {
        val seq = synchronized(lock) {
            val s = explicitSeq ?: nextSendSeq
            check(s in 0..SessionCrypto.MAX_SEQ) { "sequence space exhausted: start a new session" }
            if (explicitSeq == null) nextSendSeq++
            s
        }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(sendKey, "AES"), GCMParameterSpec(128, nonce(seq)))
        cipher.updateAAD(aad(sendDir, seq))
        return Frame(seq, CryptoUtil.toBase64Url(cipher.doFinal(plaintext)))
    }

    /** Decrypts and authenticates; throws [FrameRejected] on replay, tampering, wrong direction or malformed input. */
    fun open(seq: Long, ctB64Url: String): ByteArray {
        if (seq !in 0..SessionCrypto.MAX_SEQ) throw FrameRejected("bad sequence number")
        synchronized(lock) { if (seq <= lastRecvSeq) throw FrameRejected("replayed or reordered frame") }
        val raw = try {
            CryptoUtil.fromBase64Url(ctB64Url)
        } catch (e: IllegalArgumentException) {
            throw FrameRejected("malformed frame")
        }
        if (raw.size < 16) throw FrameRejected("frame too short")
        val plain = try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(recvKey, "AES"), GCMParameterSpec(128, nonce(seq)))
            cipher.updateAAD(aad(recvDir, seq))
            cipher.doFinal(raw)
        } catch (e: GeneralSecurityException) {
            throw FrameRejected("authentication failed")
        }
        synchronized(lock) {
            if (seq <= lastRecvSeq) throw FrameRejected("replayed or reordered frame")
            lastRecvSeq = seq // advance only after the tag verified
        }
        return plain
    }

    fun wipe() {
        sendKey.fill(0)
        recvKey.fill(0)
    }

    companion object {
        fun forController(sessionId: String, keys: SessionCrypto.Keys) =
            SessionChannel(sessionId, keys.c2h.copyOf(), keys.h2c.copyOf(), Direction.CONTROLLER_TO_HOST, Direction.HOST_TO_CONTROLLER)

        fun forHost(sessionId: String, keys: SessionCrypto.Keys) =
            SessionChannel(sessionId, keys.h2c.copyOf(), keys.c2h.copyOf(), Direction.HOST_TO_CONTROLLER, Direction.CONTROLLER_TO_HOST)
    }
}
