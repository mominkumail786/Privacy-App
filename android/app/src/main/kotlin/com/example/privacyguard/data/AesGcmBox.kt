package com.example.privacyguard.data

import java.nio.ByteBuffer
import java.nio.ByteOrder
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Encrypts and decrypts bytes with AES-GCM using the key you give it.
 * On the phone the key comes from the Android Keystore (see KeystoreKey). This class only uses
 * plain Java crypto, so it can also be unit-tested on a computer.
 *
 * Stored format:  [1 byte: IV length][IV][encrypted data + 16 byte GCM tag]
 * GCM also detects changes: if the file is edited, decrypt() throws an exception.
 */
class AesGcmBox(private val key: SecretKey) {

    fun encrypt(plain: ByteArray): ByteArray {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key) // a fresh random IV is created for us
        val iv = cipher.iv
        val encrypted = cipher.doFinal(plain)
        return byteArrayOf(iv.size.toByte()) + iv + encrypted
    }

    fun decrypt(data: ByteArray): ByteArray {
        require(data.size > 1) { "Data is too short" }
        val ivLength = data[0].toInt()
        require(ivLength in 1..32 && data.size > 1 + ivLength) { "Data is damaged" }
        val iv = data.copyOfRange(1, 1 + ivLength)
        val encrypted = data.copyOfRange(1 + ivLength, data.size)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_BITS, iv))
        return cipher.doFinal(encrypted)
    }

    companion object {
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val TAG_BITS = 128
    }
}

/** Converts a float vector to bytes and back (for saving the face profile). */
object FloatBytes {
    fun toBytes(values: FloatArray): ByteArray {
        val buffer = ByteBuffer.allocate(values.size * 4).order(ByteOrder.LITTLE_ENDIAN)
        for (v in values) buffer.putFloat(v)
        return buffer.array()
    }

    fun toFloats(bytes: ByteArray): FloatArray {
        require(bytes.size % 4 == 0) { "Not a float array" }
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        return FloatArray(bytes.size / 4) { buffer.getFloat() }
    }
}
