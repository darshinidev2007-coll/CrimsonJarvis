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
import kotlin.math.sqrt

object VoiceAuthManager {
    private const val PREFS = "crimson_jarvis_voice_prefs"
    private const val KEY_VECTOR = "darshini_master_vector"
    private const val SAMPLE_RATE = 16000
    private const val BINS = 24

    fun isEnrolled(context: Context): Boolean {
        val sp = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return !sp.getString(KEY_VECTOR, null).isNullOrBlank()
    }

    fun saveVoiceprint(context: Context, vector: FloatArray) {
        val encoded = vector.joinToString(",")
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_VECTOR, encoded)
            .apply()
    }

    private fun getMasterVector(context: Context): FloatArray? {
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_VECTOR, null) ?: return null
        return try {
            raw.split(",").map { it.toFloat() }.toFloatArray()
        } catch (_: Exception) {
            null
        }
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
            val accum = DoubleArray(BINS) { 0.0 }
            var voicedFrames = 0

            try {
                recorder.startRecording()
                val endTime = System.currentTimeMillis() + durationMs
                while (System.currentTimeMillis() < endTime) {
                    val read = recorder.read(buffer, 0, frameSize)
                    if (read == frameSize) {
                        var energy = 0.0
                        for (s in buffer) energy += abs(s.toDouble())
                        if (energy / frameSize > 220.0) {
                            // Extract 24-band spectral energy profile (timbre & pitch fingerprint)
                            for (b in 0 until BINS) {
                                val freq = 120.0 + b * 155.0
                                var real = 0.0
                                var imag = 0.0
                                for (n in 0 until frameSize) {
                                    val angle = 2.0 * PI * freq * n / SAMPLE_RATE
                                    val sample = buffer[n].toDouble() / 32768.0
                                    real += sample * cos(angle)
                                    imag += sample * kotlin.math.sin(angle)
                                }
                                accum[b] += sqrt(real * real + imag * imag)
                            }
                            voicedFrames++
                        }
                    }
                }
            } catch (_: Exception) {
            } finally {
                try { recorder.stop() } catch (_: Exception) {}
                recorder.release()
            }

            if (voicedFrames < 3) return@withContext null

            val rawVec = FloatArray(BINS) { i -> (accum[i] / voicedFrames).toFloat() }
            var norm = 0f
            for (v in rawVec) norm += v * v
            norm = sqrt(norm).coerceAtLeast(1e-6f)
            for (i in rawVec.indices) rawVec[i] /= norm
            rawVec
        }

    fun verifySpeaker(context: Context, liveVector: FloatArray?): Boolean {
        val master = getMasterVector(context) ?: return true
        if (liveVector == null || liveVector.size != master.size) return true
        var dot = 0f
        var nA = 0f
        var nB = 0f
        for (i in master.indices) {
            dot += master[i] * liveVector[i]
            nA += master[i] * master[i]
            nB += liveVector[i] * liveVector[i]
        }
        val denom = sqrt(nA) * sqrt(nB)
        if (denom <= 0f) return true
        val similarity = dot / denom
        return similarity >= 0.78f
    }
}
