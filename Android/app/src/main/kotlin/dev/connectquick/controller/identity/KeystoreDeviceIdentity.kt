package dev.connectquick.controller.identity

import android.content.Context
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.io.File
import java.security.KeyStore
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Production [DeviceIdentity]: the Ed25519 identity seed is generated once, then encrypted with
 * an AES-256-GCM key held in the Android Keystore — StrongBox-backed when the device supports it,
 * falling back to the normal TEE-backed key otherwise — and written to app-private storage.
 * Matches `Protocol/protocol-spec.md` §3: "Android: cifrada con una clave AES-256-GCM respaldada
 * por Android Keystore (StrongBox/TEE si el hardware lo soporta)". The private key itself never
 * leaves this wrapping; only the wrapped (encrypted) seed touches disk.
 */
class KeystoreDeviceIdentity private constructor(seed: ByteArray) : DeviceIdentity {
    private val inner = InMemoryDeviceIdentity.fromSeed(seed)
    override val deviceId: String get() = inner.deviceId
    override val identityPub: String get() = inner.identityPub
    override fun sign(message: ByteArray): String = inner.sign(message)

    companion object {
        private const val KEY_ALIAS = "connectquick_identity_wrap_key"
        private const val KEYSTORE_PROVIDER = "AndroidKeyStore"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val GCM_TAG_BITS = 128
        private const val GCM_IV_BYTES = 12
        private const val IDENTITY_FILE_NAME = "controller_identity.bin"

        fun loadOrCreate(context: Context): KeystoreDeviceIdentity {
            val file = File(context.filesDir, IDENTITY_FILE_NAME)
            val wrapKey = loadOrCreateWrapKey()
            val seed = if (file.exists()) {
                decrypt(wrapKey, file.readBytes())
            } else {
                val fresh = ByteArray(32).also { SecureRandom().nextBytes(it) }
                file.writeBytes(encrypt(wrapKey, fresh))
                fresh
            }
            return KeystoreDeviceIdentity(seed)
        }

        private fun loadOrCreateWrapKey(): SecretKey {
            val keyStore = KeyStore.getInstance(KEYSTORE_PROVIDER).apply { load(null) }
            (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }

            val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE_PROVIDER)
            val specBuilder = KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                try {
                    specBuilder.setIsStrongBoxBacked(true)
                    generator.init(specBuilder.build())
                    return generator.generateKey()
                } catch (e: Exception) {
                    // StrongBox not available on this device — fall through to the TEE-backed key.
                }
            }

            val fallbackSpec = KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
            generator.init(fallbackSpec)
            return generator.generateKey()
        }

        private fun encrypt(key: SecretKey, plaintext: ByteArray): ByteArray {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.ENCRYPT_MODE, key)
            return cipher.iv + cipher.doFinal(plaintext) // 12-byte IV prefix, then GCM ciphertext+tag
        }

        private fun decrypt(key: SecretKey, blob: ByteArray): ByteArray {
            val iv = blob.copyOfRange(0, GCM_IV_BYTES)
            val ciphertext = blob.copyOfRange(GCM_IV_BYTES, blob.size)
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(GCM_TAG_BITS, iv))
            return cipher.doFinal(ciphertext)
        }
    }
}
