package dev.connectquick.controller.crypto

import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters
import org.bouncycastle.crypto.params.Ed25519PublicKeyParameters
import org.bouncycastle.crypto.params.X25519PrivateKeyParameters
import org.bouncycastle.crypto.signers.Ed25519Signer
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

/**
 * Wire-format crypto primitives shared with the Backend (`Backend/src/crypto.ts`) and WindowsHost
 * (`WindowsHost/.../Crypto/CryptoUtil.cs`) — see `Protocol/protocol-spec.md` §3/§5 and
 * `Protocol/test-vectors/crypto-vectors.json`, which [dev.connectquick.controller.CryptoVectorsTest]
 * pins bit-for-bit. Do not change encoding, byte order, or field order without updating the
 * vectors file and the other two platforms.
 *
 * Uses BouncyCastle (pure JVM, RFC 8032/7748) rather than the lazysodium/libsodium binding the
 * protocol spec suggests for Android. Ed25519/X25519 are deterministic standards, so any correct
 * implementation reproduces identical bytes for identical inputs; BouncyCastle additionally runs
 * unmodified in a plain JVM unit test (no emulator/device needed), which is what makes
 * [dev.connectquick.controller.PairingEndToEndTest] possible in this environment.
 */
object CryptoUtil {
    private val b64UrlEncoder = Base64.getUrlEncoder().withoutPadding()
    private val b64UrlDecoder = Base64.getUrlDecoder()

    fun toBase64Url(bytes: ByteArray): String = b64UrlEncoder.encodeToString(bytes)

    fun fromBase64Url(s: String): ByteArray = b64UrlDecoder.decode(s)

    fun sha256(data: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(data)

    /** deviceId = base64url(SHA-256("connectquick:device:" || identityPub))[0:22] */
    fun deriveDeviceId(identityPubB64Url: String): String {
        val raw = fromBase64Url(identityPubB64Url)
        val prefix = "connectquick:device:".toByteArray(Charsets.UTF_8)
        val digest = sha256(prefix + raw)
        return toBase64Url(digest).substring(0, 22)
    }

    /** 6-digit SAS shown on the Host's "¿Autorizar?" dialog for out-of-band comparison. */
    fun computeSas(hostPubB64Url: String, deviceEphemeralPubB64Url: String, nonceB64Url: String): String {
        val digest = sha256(
            fromBase64Url(hostPubB64Url) + fromBase64Url(deviceEphemeralPubB64Url) + fromBase64Url(nonceB64Url)
        )
        val num = ((digest[0].toLong() and 0xFF) shl 24) or
            ((digest[1].toLong() and 0xFF) shl 16) or
            ((digest[2].toLong() and 0xFF) shl 8) or
            (digest[3].toLong() and 0xFF)
        return (num % 1_000_000).toString().padStart(6, '0')
    }

    /**
     * Canonical bytes a Host signs over a pairing decision:
     * `${pairingId}|${deviceId}|${approved?1:0}|${permissions.join(",")}` — must match
     * `Backend/src/ws/handlers/util.ts#pairingDecisionMessage` exactly. The Controller never
     * signs this (only Hosts issue `pairing.decision`), but it must reconstruct the same bytes to
     * verify a Host's signature over `pairing.result`.
     */
    fun pairingDecisionMessage(pairingId: String, deviceId: String, approved: Boolean, permissions: List<String>): ByteArray =
        "$pairingId|$deviceId|${if (approved) 1 else 0}|${permissions.joinToString(",")}".toByteArray(Charsets.UTF_8)

    class Ed25519KeyPair(val privateSeed: ByteArray, val publicKeyB64Url: String) {
        fun sign(message: ByteArray): String {
            val signer = Ed25519Signer()
            signer.init(true, Ed25519PrivateKeyParameters(privateSeed, 0))
            signer.update(message, 0, message.size)
            return toBase64Url(signer.generateSignature())
        }
    }

    fun generateEd25519KeyPair(): Ed25519KeyPair =
        ed25519KeyPairFromSeed(ByteArray(32).also { SecureRandom().nextBytes(it) })

    fun ed25519KeyPairFromSeed(seed: ByteArray): Ed25519KeyPair {
        val priv = Ed25519PrivateKeyParameters(seed, 0)
        val pub = priv.generatePublicKey()
        return Ed25519KeyPair(seed, toBase64Url(pub.encoded))
    }

    fun verifyEd25519(pubB64Url: String, message: ByteArray, signatureB64Url: String): Boolean = try {
        val raw = fromBase64Url(pubB64Url)
        if (raw.size != 32) {
            false
        } else {
            val signer = Ed25519Signer()
            signer.init(false, Ed25519PublicKeyParameters(raw, 0))
            signer.update(message, 0, message.size)
            signer.verifySignature(fromBase64Url(signatureB64Url))
        }
    } catch (e: Exception) {
        false
    }

    class X25519KeyPair(val publicKeyB64Url: String)

    fun generateX25519KeyPair(): X25519KeyPair {
        val priv = X25519PrivateKeyParameters(SecureRandom())
        val pub = priv.generatePublicKey()
        return X25519KeyPair(toBase64Url(pub.encoded))
    }
}
