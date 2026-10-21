package com.example.privacyguard.data

import java.io.File
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * Replaces a file safely, so a failed or interrupted save can never destroy a good existing file.
 * Steps: write to a temporary file next to it, read it back, let the caller check the content,
 * and only then move it over the real file.
 */
object SafeFile {

    /** Returns true if [target] now holds [bytes]. On false, the old [target] (if any) is untouched. */
    fun writeVerified(target: File, bytes: ByteArray, verify: (ByteArray) -> Boolean): Boolean {
        val temp = File(target.parentFile, target.name + ".tmp")
        try {
            temp.writeBytes(bytes)
            val readBack = temp.readBytes()
            if (!readBack.contentEquals(bytes) || !verify(readBack)) {
                temp.delete()
                return false
            }
            try {
                Files.move(temp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
            } catch (e: AtomicMoveNotSupportedException) {
                Files.move(temp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
            return true
        } catch (e: Exception) {
            temp.delete()
            return false
        }
    }
}
