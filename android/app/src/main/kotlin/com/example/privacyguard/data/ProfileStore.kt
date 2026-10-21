package com.example.privacyguard.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import com.example.privacyguard.logic.FaceMath
import java.io.File
import java.io.RandomAccessFile
import java.security.KeyStore
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey

/**
 * Saves your face profile (one list of numbers, NOT a photo) encrypted with AES-GCM.
 * The AES key lives in the Android Keystore, so it never leaves the secure hardware/system area
 * and the file is useless if someone copies it off the phone.
 */
class ProfileStore(context: Context) {
    private val file = File(context.applicationContext.filesDir, FILE_NAME)

    fun hasProfile(): Boolean = file.exists()

    /**
     * Saves the profile encrypted. Returns true only if the saved file was read back and decrypted to the same numbers.
     * If anything fails, an existing valid profile stays untouched (see [SafeFile]).
     */
    fun save(profile: FloatArray): Boolean {
        return try {
            val box = AesGcmBox(getOrCreateKey())
            val encrypted = box.encrypt(FloatBytes.toBytes(profile))
            SafeFile.writeVerified(file, encrypted) { written ->
                val back = FloatBytes.toFloats(box.decrypt(written))
                back.size == profile.size && FaceMath.cosineSimilarity(back, profile) > 0.999f
            }
        } catch (e: Exception) {
            false
        }
    }

    /** Returns the profile, or null if there is none or it cannot be read (for example the key was lost). */
    fun load(): FloatArray? {
        if (!file.exists()) return null
        return try {
            FloatBytes.toFloats(AesGcmBox(getOrCreateKey()).decrypt(file.readBytes()))
        } catch (e: Exception) {
            null
        }
    }

    /** Really erases the profile: overwrites the file with zeros, deletes it, and deletes the Keystore key. */
    fun delete() {
        File(file.parentFile, file.name + ".tmp").delete()
        if (file.exists()) {
            try {
                RandomAccessFile(file, "rw").use { f ->
                    f.write(ByteArray(f.length().toInt()))
                    f.fd.sync()
                }
            } catch (e: Exception) {
                // If overwriting fails we still delete below. The key deletion makes the data unreadable anyway.
            }
            file.delete()
        }
        try {
            val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE)
            keyStore.load(null)
            if (keyStore.containsAlias(KEY_ALIAS)) keyStore.deleteEntry(KEY_ALIAS)
        } catch (e: Exception) {
            // Nothing more we can do.
        }
    }

    private fun getOrCreateKey(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE)
        keyStore.load(null)
        (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }

        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return generator.generateKey()
    }

    companion object {
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val KEY_ALIAS = "privacy_guard_profile_key"
        private const val FILE_NAME = "face_profile.enc"
    }
}
