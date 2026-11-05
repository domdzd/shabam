package app.nanotune.core.security

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import app.nanotune.data.clips.ClipCodec
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Encrypts saved recordings at rest with AES-256-GCM. The key is generated inside the Android
 * Keystore under its own alias and never leaves it; the IV is chosen by the Keystore for every file.
 * File layout: `NTA1` magic, IV length (1 byte), IV, ciphertext + GCM tag. Standard platform
 * primitives only; nothing here is custom cryptography.
 */
class KeystoreClipCodec(private val alias: String = "nanotune_recordings_v1") : ClipCodec {
    override val extension: String = ".nta"

    override fun seal(plain: ByteArray): ByteArray {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val iv = cipher.iv
        val body = cipher.doFinal(plain)
        val out = ByteArray(MAGIC.size + 1 + iv.size + body.size)
        System.arraycopy(MAGIC, 0, out, 0, MAGIC.size)
        out[MAGIC.size] = iv.size.toByte()
        System.arraycopy(iv, 0, out, MAGIC.size + 1, iv.size)
        System.arraycopy(body, 0, out, MAGIC.size + 1 + iv.size, body.size)
        return out
    }

    override fun open(sealed: ByteArray): ByteArray {
        require(sealed.size > MAGIC.size + 1 && MAGIC.indices.all { sealed[it] == MAGIC[it] }) { "not an encrypted recording" }
        val ivLength = sealed[MAGIC.size].toInt() and 0xFF
        val ivStart = MAGIC.size + 1
        require(ivLength in 12..16 && sealed.size > ivStart + ivLength) { "corrupt recording header" }
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(TAG_BITS, sealed, ivStart, ivLength))
        return cipher.doFinal(sealed, ivStart + ivLength, sealed.size - ivStart - ivLength)
    }

    private fun key(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (keyStore.getEntry(alias, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return generator.generateKey()
    }

    private companion object {
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val TAG_BITS = 128
        val MAGIC = byteArrayOf('N'.code.toByte(), 'T'.code.toByte(), 'A'.code.toByte(), '1'.code.toByte())
    }
}
