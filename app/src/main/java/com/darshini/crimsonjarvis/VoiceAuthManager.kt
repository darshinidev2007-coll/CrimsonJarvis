package com.darshini.crimsonjarvis

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.PI
import kotlin.math.sin
import kotlin.math.sqrt

object VoiceAuthManager {
    private const val PREFS = "crimson_jarvis_prefs"
    private const val KEY_VEC = "voice_vector"
    private const val SAMPLE_RATE = 16000
    private const val BINS = 20

    fun isEnrolled(context: Context): Boolean {
        return !context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_VEC, null).isNullOrBlank()
    }

    fun saveVoiceprint(context: Context, vector: FloatArray) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_VEC, vector.joinToString(","))
            .apply()
    }

    @SuppressLint("MissingPermission")
    suspend fun captureVoiceSignature(durationMs: Long = 2600L): FloatArray? =
        withContext(Dispatchers.IO) {
            val minBuf = AudioRecord.getMinBufferSize(
                SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT
            )
            if (minBuf <= 0) return@withContext null

            val recorder = try {
                AudioRecord(
                    MediaRecorder.AudioSource.VOICE_RECOGNITION,
                    SAMPLE_RATE,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT,
                    minBuf * 2
                )
            } catch (_: Exception) {
                return@withContext null
            }

            if (recorder.state != AudioRecord.STATE_INITIALIZED) {
                recorder.release()
                return@withContext null
            }

            val frameSize = 512
            val buffer = ShortArray(frameSize)
            val accum = DoubleArray(BINS)
            var count = 0

            try {
                recorder.startRecording()
                val endAt = System.currentTimeMillis() + durationMs
                while (System.currentTimeMillis() < endAt) {
                    val read = recorder.read(buffer, 0, frameSize)
                    if (read == frameSize) {
                        var energy = 0.0
                        for (s in buffer) energy += abs(s.toDouble())
                        if (energy / frameSize > 200.0) {
                            for (b in 0 until BINS) {
                                val freq = 130.0 + b * 160.0
                                var re = 0.0
                                var im = 0.0
                                for (n in 0 until frameSize) {
                                    val ang = 2.0 * PI * freq * n / SAMPLE_RATE
                                    val x = buffer[n].toDouble() / 32768.0
                                    re += x * cos(ang)
                                    im += x * sin(ang)
                                }
                                accum[b] += sqrt(re * re + im * im)
                            }
                            count++
                        }
                    }
                }
            } catch (_: Exception) {
            } finally {
                try { recorder.stop() } catch (_: Exception) {}
                recorder.release()
            }

            if (count < 2) return@withContext null

            val out = FloatArray(BINS) { i -> (accum[i] / count).toFloat() }
            var norm = 0f
            for (v in out) norm += v * v
            norm = sqrt(norm).coerceAtLeast(1e-6f)
            for (i in out.indices) out[i] /= norm
            out
        }
}
