package com.example.xmu_sports_booking.local

import ai.onnxruntime.*
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.nio.FloatBuffer
import java.security.MessageDigest

/** CPU-only model, with area-weighted grayscale resize matching the reference preprocessing. */
class Captcha(context: Context) : AutoCloseable {
    private val env = OrtEnvironment.getEnvironment()
    private val session: OrtSession

    init {
        val bytes = context.assets.open("models/captcha_cnn.onnx").use { it.readBytes() }
        check(
            SchoolSession.hex(MessageDigest.getInstance("SHA-256").digest(bytes)) ==
                "b21da9cac2dc0f3b42b56f4eb6ffd519581a95fb0ba3afeec4330dc45f6718a0"
        ) {
            "MODEL_HASH_MISMATCH"
        }
        session =
            env.createSession(bytes, OrtSession.SessionOptions().apply { setIntraOpNumThreads(1) })
    }

    fun predict(bytes: ByteArray): String {
        require(bytes.size in 16..2_000_000) { "CAPTCHA_IMAGE_INVALID" }
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        require(
            bounds.outWidth > 0 &&
                bounds.outHeight > 0 &&
                bounds.outWidth.toLong() * bounds.outHeight <= 2_097_152
        ) {
            "CAPTCHA_IMAGE_INVALID"
        }
        val image =
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: error("CAPTCHA_IMAGE_INVALID")
        val input = preprocess(image)
        image.recycle()
        OnnxTensor.createTensor(env, FloatBuffer.wrap(input), longArrayOf(1, 1, 64, 160)).use {
            tensor ->
            session.run(mapOf(session.inputNames.first() to tensor)).use { result ->
                val output = result[0] as OnnxTensor
                check(output.info.shape.contentEquals(longArrayOf(1, 4, 36))) {
                    "MODEL_OUTPUT_INVALID"
                }
                val scores = output.floatBuffer
                val alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789"
                return (0..3)
                    .map { position ->
                        var best = 0
                        for (letter in 0..35) {
                            check(scores.get(position * 36 + letter).isFinite()) {
                                "MODEL_OUTPUT_INVALID"
                            }
                            if (
                                scores.get(position * 36 + letter) >
                                    scores.get(position * 36 + best)
                            )
                                best = letter
                        }
                        alphabet[best]
                    }
                    .joinToString("")
            }
        }
    }

    companion object {
        fun preprocess(image: Bitmap): FloatArray {
            val width = image.width
            val height = image.height
            val pixels = IntArray(width * height)
            image.getPixels(pixels, 0, width, 0, 0, width, height)
            val gray =
                DoubleArray(pixels.size) { i ->
                    val p = pixels[i]
                    kotlin.math.floor(
                        (0.299 * (p shr 16 and 255) +
                            0.587 * (p shr 8 and 255) +
                            0.114 * (p and 255)) + 0.5
                    )
                }
            return FloatArray(160 * 64) { i ->
                val x = i % 160
                val y = i / 160
                val x0 = x * width / 160.0
                val x1 = (x + 1) * width / 160.0
                val y0 = y * height / 64.0
                val y1 = (y + 1) * height / 64.0
                var total = 0.0
                for (sy in y0.toInt() until kotlin.math.ceil(y1).toInt()) for (sx in
                    x0.toInt() until kotlin.math.ceil(x1).toInt()) {
                    val weight =
                        (minOf(x1, sx + 1.0) - maxOf(x0, sx.toDouble())) *
                            (minOf(y1, sy + 1.0) - maxOf(y0, sy.toDouble()))
                    total +=
                        gray[sy.coerceIn(0, height - 1) * width + sx.coerceIn(0, width - 1)] *
                            weight
                }
                (kotlin.math.floor(total / ((x1 - x0) * (y1 - y0)) + 0.5) / 255.0).toFloat()
            }
        }
    }

    override fun close() {
        session.close()
    }
}
