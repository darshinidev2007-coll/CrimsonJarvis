package com.darshini.crimsonjarvis

import android.Manifest
import android.app.KeyguardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.util.Locale

class MainActivity : AppCompatActivity(), TextToSpeech.OnInitListener {

    private lateinit var tvOwnerStatus: TextView
    private lateinit var tvJarvisReply: TextView
    private lateinit var tvTranscript: TextView
    private lateinit var btnSpeakOrb: Button
    private lateinit var btnToggleBackground: Button

    private var tts: TextToSpeech? = null
    private var speechRecognizer: SpeechRecognizer? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
            val km = getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
            km.requestDismissKeyguard(this, null)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                    WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON or
                    WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD
            )
        }

        setContentView(R.layout.activity_main)

        tvOwnerStatus = findViewById(R.id.tvOwnerStatus)
        tvJarvisReply = findViewById(R.id.tvJarvisReply)
        tvTranscript = findViewById(R.id.tvTranscript)
        btnSpeakOrb = findViewById(R.id.btnSpeakOrb)
        btnToggleBackground = findViewById(R.id.btnToggleBackground)

        tts = TextToSpeech(this, this)
        requestAllJarvisPermissions()
        refreshStatusBanner()

        btnSpeakOrb.setOnClickListener { startInteractiveCommand() }

        findViewById<Button>(R.id.btnRunCmd).setOnClickListener {
            val et = findViewById<EditText>(R.id.etManualCmd)
            val cmd = et.text.toString().trim()
            if (cmd.isNotEmpty()) {
                et.setText("")
                tvTranscript.text = "Command: $cmd"
                CommandExecutor.execute(this, cmd) { reply ->
                    tvJarvisReply.text = reply
                    speakJarvis(reply)
                }
            }
        }

        findViewById<Button>(R.id.btnEnrollVoice).setOnClickListener {
            val btn = it as Button
            btn.isEnabled = false
            btn.text = "🎙️ Speak for 3 seconds..."
            tvJarvisReply.text = "Say: 'Hey Jarvis, this is Darshini, lock to my voice.'"

            CoroutineScope(Dispatchers.Main).launch {
                val vec = VoiceAuthManager.captureVoiceSignature(2800L)
                if (vec != null) {
                    VoiceAuthManager.saveVoiceprint(this@MainActivity, vec)
                    val msg = "Voiceprint locked to Darshini. I obey only your voice now."
                    tvJarvisReply.text = msg
                    speakJarvis(msg)
                    refreshStatusBanner()
                } else {
                    tvJarvisReply.text = "Could not hear voice clearly — speak louder and try again."
                }
                btn.isEnabled = true
                btn.text = "🎙️ Re-Enroll Darshini's Voice Lock (3s)"
            }
        }

        btnToggleBackground.setOnClickListener {
            val serviceIntent = Intent(this, JarvisBackgroundService::class.java)
            if (JarvisBackgroundService.isRunning) {
                stopService(serviceIntent)
                btnToggleBackground.text = "🟢 Start 24/7 Screen-Off Wake ('Hey Jarvis')"
                tvJarvisReply.text = "24/7 background wake paused."
            } else {
                ContextCompat.startForegroundService(this, serviceIntent)
                btnToggleBackground.text = "🔴 Stop 24/7 Screen-Off Wake"
                val msg = "24/7 wake word 'Hey Jarvis' is now armed, Darshini."
                tvJarvisReply.text = msg
                speakJarvis(msg)
            }
        }

        findViewById<Button>(R.id.btnAccessibility).setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }

        findViewById<Button>(R.id.btnOverlayPerm).setOnClickListener {
            if (!Settings.canDrawOverlays(this)) {
                val intent = Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:$packageName")
                )
                startActivity(intent)
            } else {
                tvJarvisReply.text = "Lockscreen overlay permission is already active!"
            }
        }

        val etGeminiKey = findViewById<EditText>(R.id.etGeminiKey)
        etGeminiKey.setText(
            getSharedPreferences("crimson_jarvis_voice_prefs", Context.MODE_PRIVATE)
                .getString("gemini_api_key", "")
        )
        findViewById<Button>(R.id.btnSaveKey).setOnClickListener {
            val key = etGeminiKey.text.toString().trim()
            getSharedPreferences("crimson_jarvis_voice_prefs", Context.MODE_PRIVATE)
                .edit()
                .putString("gemini_api_key", key)
                .apply()
            val msg = if (key.isNotEmpty()) "Gemini AI Brain connected, Darshini." else "Gemini key cleared."
            tvJarvisReply.text = msg
            speakJarvis(msg)
        }
    }

    private fun refreshStatusBanner() {
        val enrolled = VoiceAuthManager.isEnrolled(this)
        tvOwnerStatus.text = if (enrolled) {
            "OWNER: DARSHINI • VOICE LOCK: ARMED"
        } else {
            "OWNER: DARSHINI • VOICE LOCK: UNENROLLED"
        }
        btnToggleBackground.text = if (JarvisBackgroundService.isRunning) {
            "🔴 Stop 24/7 Screen-Off Wake"
        } else {
            "🟢 Start 24/7 Screen-Off Wake ('Hey Jarvis')"
        }
    }

    private fun startInteractiveCommand() {
        btnSpeakOrb.text = "🩸
LISTENING"
        tvJarvisReply.text = "Listening for your command, Darshini..."

        speechRecognizer?.destroy()
        speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this)
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "en-IN")
        }

        speechRecognizer?.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {}
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() { btnSpeakOrb.text = "🩸
SPEAK" }
            override fun onError(error: Int) {
                btnSpeakOrb.text = "🩸
SPEAK"
                tvJarvisReply.text = "Tap SPEAK and try again."
            }

            override fun onResults(results: Bundle?) {
                btnSpeakOrb.text = "🩸
SPEAK"
                val heard = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    ?.firstOrNull().orEmpty()
                if (heard.isNotEmpty()) {
                    tvTranscript.text = "Heard: "$heard""
                    CommandExecutor.execute(this@MainActivity, heard) { reply ->
                        tvJarvisReply.text = reply
                        speakJarvis(reply)
                    }
                }
            }

            override fun onPartialResults(partialResults: Bundle?) {}
            override fun onEvent(eventType: Int, params: Bundle?) {}
        })

        speechRecognizer?.startListening(intent)
    }

    private fun requestAllJarvisPermissions() {
        val perms = mutableListOf(
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.READ_CONTACTS,
            Manifest.permission.CALL_PHONE,
            Manifest.permission.CAMERA
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            perms.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        val missing = perms.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, missing.toTypedArray(), 200)
        }
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            tts?.language = Locale.UK
            tts?.setPitch(0.88f)
            tts?.setSpeechRate(1.02f)
        }
    }

    private fun speakJarvis(text: String) {
        tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "JARVIS_MAIN_UTTERANCE")
    }

    override fun onDestroy() {
        speechRecognizer?.destroy()
        tts?.shutdown()
        super.onDestroy()
    }
}
