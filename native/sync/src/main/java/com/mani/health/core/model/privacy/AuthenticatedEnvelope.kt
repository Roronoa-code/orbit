package com.mani.health.core.model.privacy

import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/** Small raw chunks and archive segments; the caller supplies a Keystore or portable key. */
object AuthenticatedEnvelope {
    const val MAX_PLAINTEXT_BYTES = 4 * 1024 * 1024
    private val header = byteArrayOf(0x4d,0x48,0x41,1)
    private const val IV_BYTES = 12
    private const val TAG_BITS = 128

    fun encrypt(plaintext: ByteArray, key: SecretKey, context: String): ByteArray {
        require(plaintext.size <= MAX_PLAINTEXT_BYTES)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE,key)
        check(cipher.iv.size == IV_BYTES)
        cipher.updateAAD(associatedData(context))
        return header + cipher.iv + cipher.doFinal(plaintext)
    }

    fun decrypt(envelope: ByteArray, key: SecretKey, context: String): ByteArray {
        require(envelope.size in (header.size+IV_BYTES+TAG_BITS/8)..(MAX_PLAINTEXT_BYTES+header.size+IV_BYTES+TAG_BITS/8))
        require(envelope.copyOfRange(0,header.size).contentEquals(header)) { "Unsupported encrypted envelope version" }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE,key,GCMParameterSpec(TAG_BITS,envelope.copyOfRange(header.size,header.size+IV_BYTES)))
        cipher.updateAAD(associatedData(context))
        return cipher.doFinal(envelope,header.size+IV_BYTES,envelope.size-header.size-IV_BYTES)
    }

    fun newPortableKey(): SecretKey = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
    fun exportRecoveryKey(key: SecretKey): String {
        val encoded = requireNotNull(key.encoded) { "Device Keystore keys are not portable" }
        require(encoded.size == 32 && key.algorithm == "AES")
        return Base64.getUrlEncoder().withoutPadding().encodeToString(encoded)
    }
    fun importRecoveryKey(encoded: String): SecretKey {
        require(encoded.length == 43 && encoded.matches(Regex("[A-Za-z0-9_-]{43}")))
        val bytes = Base64.getUrlDecoder().decode(encoded)
        require(bytes.size == 32)
        require(Base64.getUrlEncoder().withoutPadding().encodeToString(bytes) == encoded)
        return SecretKeySpec(bytes,"AES")
    }

    private fun associatedData(context: String): ByteArray {
        require(context.isNotBlank() && context.length <= 1024)
        return header + context.toByteArray(Charsets.UTF_8)
    }
}
