package com.darshini.crimsonjarvis

import android.app.KeyguardManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import androidx.core.app.NotificationCompat
import java.util.Locale

class JarvisBackgroundService : Service(), TextToSpeech.OnInitListener {

    companion object {
        var isRunning = false
        private const val CHANNEL_ID = "crimson_jarvis_bg"
    }

    private var speechRecognizer: SpeechRecognizer? = null
    private var tts: TextToSpeech? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private val handler = Handler(Looper.getMainLooper())
    private var awaitingCommandAfterWake = false

    override fun onCreate() {
        super.onCreate()
        isRunning = true
        createNotificationChannel()
        startForeground(101, buildNotification("Listening 24/7 for 'Hey Jarvis' (Darshini Only)"))

        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "CrimsonJarvis::WakeWordLock").apply {
            acquire()
        }

        tts = TextToSpeech(this, this)
        handler.postDelayed({ startContinuousListening() }, 700)
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            tts?.language = Locale.UK
            tts?.setPitch(0.88f)
            tts?.setSpeechRate(1.02f)
        }
    }

    private fun speak(text: String) {
        tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "JARVIS_BG_UTTERANCE")
    }

    private fun wakeAndUnlockDevice() {
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        @Suppress("DEPRECATION")
        val screenWake = pm.newWakeLock(
            PowerManager.SCREEN_BRIGHT_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP or PowerManager.ON_AFTER_RELEASE,
            "CrimsonJarvis::ScreenWake"
        )
        screenWake.acquire(10_000L)

        val wakeIntent = Intent(this, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            putExtra("WAKE_BY_VOICE", true)
        }
        startActivity(wakeIntent)

        val km = getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            @Suppress("DEPRECATION")
            km.newKeyguardLock("CrimsonJarvisKeyguard").disableKeyguard()
        }
    }

    private fun startContinuousListening() {
        if (!isRunning) return
        try {
            speechRecognizer?.destroy()
            speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this)
            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, "en-IN")
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            }

            speechRecognizer?.setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(params: Bundle?) {}
                override fun onBeginningOfSpeech() {}
                override fun onRmsChanged(rmsdB: Float) {}
                override fun onBufferReceived(buffer: ByteArray?) {}
                override fun onEndOfSpeech() {}

                override fun onError(error: Int) {
                    if (isRunning) {
                        handler.postDelayed({ startContinuousListening() }, 500)
                    }
                }

                override fun onResults(results: Bundle?) {
                    val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    val heard = matches?.firstOrNull()?.trim().orEmpty()
                    val lower = heard.lowercase()

                    if (heard.isNotEmpty()) {
                        val hasWakeWord = lower.contains("hey jarvis") ||
                            lower.contains("ok jarvis") ||
                            lower.startsWith("jarvis")

                        if (hasWakeWord || awaitingCommandAfterWake) {
                            wakeAndUnlockDevice()
                            val stripped = lower
                                .replace("hey jarvis", "")
                                .replace("ok jarvis", "")
                                .replace("jarvis", "")
                                .trim()

                            if (stripped.isEmpty()) {
                                awaitingCommandAfterWake = true
                                speak("Yes Darshini?")
                            } else {
                                awaitingCommandAfterWake = false
                                CommandExecutor.execute(this@JarvisBackgroundService, heard) { reply ->
                                    speak(reply)
                                }
                            }
                        }
                    }

                    if (isRunning) {
                        handler.postDelayed({ startContinuousListening() }, 400)
                    }
                }

                override fun onPartialResults(partialResults: Bundle?) {}
                override fun onEvent(eventType: Int, params: Bundle?) {}
            })

            speechRecognizer?.startListening(intent)
        } catch (_: Exception) {
            if (isRunning) handler.postDelayed({ startContinuousListening() }, 1200)
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Crimson JARVIS Always-On Listener",
                NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(content: String): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("CRIMSON JARVIS ACTIVE")
            .setContentText(content)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setOngoing(true)
            .build()
    }

    override fun onDestroy() {
        isRunning = false
        speechRecognizer?.destroy()
        tts?.shutdown()
        wakeLock?.release()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
