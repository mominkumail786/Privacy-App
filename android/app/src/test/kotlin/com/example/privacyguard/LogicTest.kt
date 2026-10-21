package com.example.privacyguard

import com.example.privacyguard.data.AesGcmBox
import com.example.privacyguard.data.FloatBytes
import com.example.privacyguard.logic.AlertCooldown
import com.example.privacyguard.logic.FaceMath
import com.example.privacyguard.logic.FacePose
import com.example.privacyguard.logic.ImageQuality
import com.example.privacyguard.logic.OtherPersonDetector
import com.example.privacyguard.logic.QualityProblem
import com.example.privacyguard.logic.Sensitivity
import com.example.privacyguard.logic.TemporalFilter
import com.example.privacyguard.logic.Thresholds
import javax.crypto.KeyGenerator
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class FaceMathTest {
    @Test fun cosineOfSameVectorIsOne() {
        assertEquals(1f, FaceMath.cosineSimilarity(floatArrayOf(1f, 2f, 3f), floatArrayOf(2f, 4f, 6f)), 1e-6f)
    }

    @Test fun cosineOfPerpendicularVectorsIsZero() {
        assertEquals(0f, FaceMath.cosineSimilarity(floatArrayOf(1f, 0f), floatArrayOf(0f, 1f)), 1e-6f)
    }

    @Test fun cosineOfOppositeVectorsIsMinusOne() {
        assertEquals(-1f, FaceMath.cosineSimilarity(floatArrayOf(1f, 1f), floatArrayOf(-1f, -1f)), 1e-6f)
    }

    @Test fun zeroVectorGivesZeroNotNaN() {
        assertEquals(0f, FaceMath.cosineSimilarity(floatArrayOf(0f, 0f), floatArrayOf(1f, 1f)), 0f)
    }

    @Test fun averageProfileHasLengthOne() {
        val p = FaceMath.averageProfile(listOf(floatArrayOf(3f, 0f), floatArrayOf(0f, 4f)))
        assertEquals(1f, p[0] * p[0] + p[1] * p[1], 1e-5f)
        assertEquals(p[0], p[1], 1e-5f) // both inputs count the same after normalising
    }
}

class OtherPersonDetectorTest {
    private val me = FaceMath.l2Normalize(floatArrayOf(1f, 0f, 0f))
    private val stranger = FaceMath.l2Normalize(floatArrayOf(0f, 1f, 0f))
    private val t = Thresholds.forSensitivity(Sensitivity.MEDIUM)
    private val detector = OtherPersonDetector(t, me)

    private fun facing(size: Float = 0.3f) = FacePose(size, 0f, 0f)

    @Test fun onlyMeIsNotAlarm() {
        val r = detector.analyze(listOf(facing())) { me }
        assertFalse(r.otherPersonLooking)
        assertEquals(1, r.facingFaces)
        assertEquals(1f, r.bestMatch!!, 1e-5f)
    }

    @Test fun meAndFacingStrangerIsAlarm() {
        val r = detector.analyze(listOf(facing(0.4f), facing(0.2f))) { i -> if (i == 0) me else stranger }
        assertTrue(r.otherPersonLooking)
        assertEquals(2, r.facingFaces)
    }

    @Test fun strangerTurnedAwayIsNotAlarm() {
        val turned = FacePose(0.3f, t.maxHeadAngle + 10f, 0f)
        val r = detector.analyze(listOf(facing(0.4f), turned)) { me }
        assertFalse(r.otherPersonLooking)
        assertEquals(1, r.facingFaces)
    }

    @Test fun strangerLookingUpOrDownTooMuchIsNotAlarm() {
        val r = detector.analyze(listOf(FacePose(0.3f, 0f, -(t.maxHeadAngle + 5f)))) { stranger }
        assertFalse(r.otherPersonLooking)
    }

    @Test fun tinyFarAwayFaceIsIgnored() {
        val r = detector.analyze(listOf(facing(t.minFaceSize / 2))) { stranger }
        assertFalse(r.otherPersonLooking)
        assertEquals(0, r.facingFaces)
    }

    @Test fun embeddingIsOnlyComputedForFacingFaces() {
        var calls = 0
        val faces = listOf(facing(0.4f), FacePose(0.3f, 80f, 0f), facing(0.05f))
        detector.analyze(faces) { calls++; me }
        assertEquals(1, calls)
    }

    @Test fun neverChecksMoreThanThreeFaces() {
        var calls = 0
        detector.analyze(List(6) { facing(0.2f + it * 0.01f) }) { calls++; me }
        assertEquals(OtherPersonDetector.MAX_FACES_CHECKED, calls)
    }

    @Test fun noFacesIsNoAlarm() {
        val r = detector.analyze(emptyList()) { me }
        assertFalse(r.otherPersonLooking)
        assertNull(r.bestMatch)
    }

    @Test fun sensitivityLevelsAreOrdered() {
        val low = Thresholds.forSensitivity(Sensitivity.LOW)
        val high = Thresholds.forSensitivity(Sensitivity.HIGH)
        assertTrue(high.maxHeadAngle > low.maxHeadAngle)
        assertTrue(high.minFaceSize < low.minFaceSize)
        assertTrue(high.requiredCount <= low.requiredCount)
    }
}

class TemporalFilterTest {
    @Test fun needsThreeOutOfFive() {
        val f = TemporalFilter(5, 3)
        assertFalse(f.add(true))
        assertFalse(f.add(false))
        assertFalse(f.add(true))
        assertTrue(f.add(true)) // 3 true in the last 4
    }

    @Test fun oldFramesFallOutOfWindow() {
        val f = TemporalFilter(3, 2)
        f.add(true); f.add(true)
        assertTrue(f.add(false))   // true,true,false
        assertFalse(f.add(false))  // true,false,false
    }

    @Test fun singleOddFrameDoesNotAlarm() {
        val f = TemporalFilter(5, 3)
        repeat(4) { f.add(false) }
        assertFalse(f.add(true))
    }

    @Test fun resetForgetsEverything() {
        val f = TemporalFilter(3, 2)
        f.add(true); f.add(true)
        f.reset()
        assertFalse(f.add(true))
    }
}

class AlertCooldownTest {
    @Test fun secondAlertInsideCooldownIsBlocked() {
        val c = AlertCooldown { 30_000L }
        assertTrue(c.tryAlert(1_000))
        assertFalse(c.tryAlert(20_000))
        assertTrue(c.tryAlert(31_000))
    }

    @Test fun cooldownChangeAppliesImmediately() {
        var ms = 30_000L
        val c = AlertCooldown { ms }
        assertTrue(c.tryAlert(0))
        ms = 5_000L
        assertTrue(c.tryAlert(6_000))
    }
}

class ImageQualityTest {
    @Test fun darkPictureIsRejected() {
        val gray = IntArray(100) { 10 }
        assertEquals(QualityProblem.TOO_DARK, ImageQuality.problem(gray, 10, 10))
    }

    @Test fun brightPictureIsRejected() {
        val gray = IntArray(100) { 250 }
        assertEquals(QualityProblem.TOO_BRIGHT, ImageQuality.problem(gray, 10, 10))
    }

    @Test fun flatPictureIsBlurry() {
        val gray = IntArray(100) { 120 }
        assertEquals(QualityProblem.BLURRY, ImageQuality.problem(gray, 10, 10))
    }

    @Test fun texturedPictureIsFine() {
        val gray = IntArray(100) { if (it % 2 == 0) 90 else 150 }
        assertNull(ImageQuality.problem(gray, 10, 10))
    }
}

class AesGcmBoxTest {
    private fun box() = AesGcmBox(KeyGenerator.getInstance("AES").apply { init(256) }.generateKey())

    @Test fun roundTrip() {
        val b = box()
        val plain = FloatBytes.toBytes(floatArrayOf(0.5f, -1.25f, 3f))
        val back = FloatBytes.toFloats(b.decrypt(b.encrypt(plain)))
        assertArrayEquals(floatArrayOf(0.5f, -1.25f, 3f), back, 0f)
    }

    @Test fun encryptedBytesDoNotContainPlainText() {
        val b = box()
        val plain = FloatBytes.toBytes(FloatArray(192) { 0.123f })
        val enc = b.encrypt(plain)
        assertFalse(enc.toList().windowed(8).any { w -> w == plain.toList().take(8) })
    }

    @Test fun sameInputGivesDifferentOutputBecauseOfRandomIv() {
        val b = box()
        val plain = ByteArray(32) { it.toByte() }
        assertFalse(b.encrypt(plain).contentEquals(b.encrypt(plain)))
    }

    @Test fun changedFileIsDetected() {
        val b = box()
        val enc = b.encrypt(ByteArray(32) { it.toByte() })
        enc[enc.size - 1] = (enc[enc.size - 1].toInt() xor 1).toByte()
        try {
            b.decrypt(enc)
            fail("A changed file must not decrypt")
        } catch (expected: Exception) {
            // good
        }
    }

    @Test fun wrongKeyCannotDecrypt() {
        val enc = box().encrypt(ByteArray(32))
        try {
            box().decrypt(enc)
            fail("Wrong key must not decrypt")
        } catch (expected: Exception) {
            // good
        }
    }
}
