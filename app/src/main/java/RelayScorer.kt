package com.example.campuslink

import android.content.Context
import org.tensorflow.lite.Interpreter
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel

class RelayScorer(context: Context) {

    private var interpreter: Interpreter? = null

    // Exact scaler values generated from your Colab script
    private val means = floatArrayOf(15.32679592f, 51.02376554f, 59.0693734f)
    private val scales = floatArrayOf(8.78485336f, 28.61194242f, 34.73076318f)

    init {
        try {
            val assetFileDescriptor = context.assets.openFd("relay_model.tflite")
            val inputStream = assetFileDescriptor.createInputStream()
            val fileChannel = inputStream.channel
            val startOffset = assetFileDescriptor.startOffset
            val declaredLength = assetFileDescriptor.declaredLength

            val mappedByteBuffer = fileChannel.map(FileChannel.MapMode.READ_ONLY, startOffset, declaredLength)
            interpreter = Interpreter(mappedByteBuffer)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun scoreRelay(rssiVariance: Float, batteryLevel: Float, uptimeMinutes: Float): Float {
        val intp = interpreter ?: return 0.5f

        // 1. Z-score normalization
        val normalizedRssi = (rssiVariance - means[0]) / scales[0]
        val normalizedBattery = (batteryLevel - means[1]) / scales[1]
        val normalizedUptime = (uptimeMinutes - means[2]) / scales[2]

        // 2. Input buffer (Shape: [1, 3])
        val inputBuffer = ByteBuffer.allocateDirect(4 * 3).order(ByteOrder.nativeOrder())
        inputBuffer.putFloat(normalizedRssi)
        inputBuffer.putFloat(normalizedBattery)
        inputBuffer.putFloat(normalizedUptime)

        // 3. Output buffer (Shape: [1, 1])
        val outputBuffer = ByteBuffer.allocateDirect(4 * 1).order(ByteOrder.nativeOrder())

        // 4. Run inference
        intp.run(inputBuffer, outputBuffer)

        // 5. Read prediction probability
        outputBuffer.rewind()
        return outputBuffer.float
    }

    fun close() {
        interpreter?.close()
    }
}