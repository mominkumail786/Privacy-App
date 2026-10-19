package com.example.privacyguard.face

import android.content.Context
import android.graphics.Bitmap
import com.example.privacyguard.logic.FaceMath
import java.io.Closeable
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.tensorflow.lite.Interpreter

/**
 * Turns a face picture into a list of 192 numbers (an "embedding") using the MobileFaceNet model.
 * Pictures of the same person give similar numbers; different people give different numbers.
 *
 * Model facts (measured on the real file, see README.md):
 *   file   : app/src/main/assets/MobileFaceNet.tflite
 *   input  : float32 [2, 112, 112, 3]  (batch of 2 is fixed inside this model), RGB, value = (pixel - 127.5) / 128
 *   output : float32 [2, 192]
 * Because the batch size is fixed at 2, we put the same face into both slots and use the first result.
 */
class FaceEmbedder(context: Context) : Closeable {

    private val interpreter: Interpreter
    private val batchSize: Int
    private val embeddingSize: Int
    private val input: ByteBuffer
    private val pixels = IntArray(INPUT_SIZE * INPUT_SIZE)

    init {
        // Read the model file into memory (it is about 5 MB).
        val bytes = context.applicationContext.assets.open(MODEL_FILE).use { it.readBytes() }
        val model = ByteBuffer.allocateDirect(bytes.size).order(ByteOrder.nativeOrder())
        model.put(bytes)
        model.rewind()

        interpreter = Interpreter(model, Interpreter.Options().setNumThreads(2))
        batchSize = interpreter.getInputTensor(0).shape()[0]
        embeddingSize = interpreter.getOutputTensor(0).shape().last()
        input = ByteBuffer.allocateDirect(batchSize * INPUT_SIZE * INPUT_SIZE * 3 * 4)
            .order(ByteOrder.nativeOrder())
    }

    /** [face] is a picture of one cropped face (any size). Returns a vector of length 1. */
    @Synchronized
    fun embed(face: Bitmap): FloatArray {
        val small =
            if (face.width == INPUT_SIZE && face.height == INPUT_SIZE) face
            else Bitmap.createScaledBitmap(face, INPUT_SIZE, INPUT_SIZE, true)
        small.getPixels(pixels, 0, INPUT_SIZE, 0, 0, INPUT_SIZE, INPUT_SIZE)

        input.rewind()
        repeat(batchSize) {
            for (p in pixels) {
                input.putFloat((((p shr 16) and 0xFF) - 127.5f) / 128f) // red
                input.putFloat((((p shr 8) and 0xFF) - 127.5f) / 128f)  // green
                input.putFloat(((p and 0xFF) - 127.5f) / 128f)          // blue
            }
        }
        input.rewind()

        val output = Array(batchSize) { FloatArray(embeddingSize) }
        interpreter.run(input, output)
        if (small !== face) small.recycle()
        return FaceMath.l2Normalize(output[0])
    }

    override fun close() {
        interpreter.close()
    }

    companion object {
        const val MODEL_FILE = "MobileFaceNet.tflite"
        const val INPUT_SIZE = 112

        /** False if you have not put the model file in the assets folder yet. */
        fun isModelAvailable(context: Context): Boolean = try {
            context.applicationContext.assets.open(MODEL_FILE).close()
            true
        } catch (e: IOException) {
            false
        }
    }
}
