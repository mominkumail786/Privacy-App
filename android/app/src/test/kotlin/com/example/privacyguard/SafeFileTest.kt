package com.example.privacyguard

import com.example.privacyguard.data.SafeFile
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class SafeFileTest {
    @get:Rule val folder = TemporaryFolder()

    @Test fun writesAndReplaces() {
        val target = File(folder.root, "profile.enc")
        assertTrue(SafeFile.writeVerified(target, byteArrayOf(1, 2, 3)) { true })
        assertTrue(target.readBytes().contentEquals(byteArrayOf(1, 2, 3)))
        assertTrue(SafeFile.writeVerified(target, byteArrayOf(9, 9)) { true })
        assertTrue(target.readBytes().contentEquals(byteArrayOf(9, 9)))
        assertFalse(File(folder.root, "profile.enc.tmp").exists())
    }

    @Test fun failedVerificationKeepsTheOldFileUntouched() {
        val target = File(folder.root, "profile.enc")
        SafeFile.writeVerified(target, byteArrayOf(1, 2, 3)) { true }
        assertFalse(SafeFile.writeVerified(target, byteArrayOf(7, 7, 7)) { false })
        assertTrue("old profile must survive", target.readBytes().contentEquals(byteArrayOf(1, 2, 3)))
        assertFalse(File(folder.root, "profile.enc.tmp").exists())
    }

    @Test fun exceptionInVerifyKeepsTheOldFileUntouched() {
        val target = File(folder.root, "profile.enc")
        SafeFile.writeVerified(target, byteArrayOf(1), { true })
        assertFalse(SafeFile.writeVerified(target, byteArrayOf(2)) { error("boom") })
        assertTrue(target.readBytes().contentEquals(byteArrayOf(1)))
    }

    @Test fun unwritableFolderReturnsFalse() {
        val missing = File(folder.root, "nope/deeper/profile.enc")
        assertFalse(SafeFile.writeVerified(missing, byteArrayOf(1)) { true })
        assertNull(missing.takeIf { it.exists() })
    }
}
