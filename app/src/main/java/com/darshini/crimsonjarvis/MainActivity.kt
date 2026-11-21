package com.darshini.crimsonjarvis

import android.Manifest
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

    private lateinit var tvStatus: TextView
    private lateinit var tvReply: TextView
    private lateinit var tvHeard: TextView
    private lateinit var btnSpeak: Button
    private lateinit var btnService: Button

    private var tts: TextToSpeech? = null
    private var recognizer: SpeechRecognizer? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        tvStatus = findViewById(R.id.tvStatus)
        tvReply = findViewById(R.id.tvReply)
        tvHeard = findViewById(R.id.tvHeard)
        btnSpeak = findViewById(R.id.btnSpeak)
        btnService = findViewById(R.id.btnService)

        tts = TextToSpeech(this, this)
        requestPerms()
        updateUI()

        btnSpeak.setOnClickListener { startVoiceCommand() }

        findViewById<Button>(R.id.btnRun).setOnClickListener {
            val et = findViewById<EditText>(R.id.etCommand)
            val cmd = et.text.toString().trim()
            if (cmd.isNotEmpty()) {
                et.setText("")
                tvHeard.text = "Command: $cmd"
                CommandExecutor.execute(this, cmd) { reply ->
                    tvReply.text = reply
                    speak(reply)
                }
            }
        }

        findViewById<Button>(R.id.btnEnroll).setOnClickListener {
            val btn = it as Button
            btn.isEnabled = false
            btn.text = "🎙️ Speak for 3s..."
            tvReply.text = "Say: 'Hey Jarvis, this is Darshini.'"
            CoroutineScope(Dispatchers.Main).launch {
                val vec = VoiceAuthManager.captureVoiceSignature(2600L)
                if (vec != null) {
                    VoiceAuthManager.saveVoiceprint(this@MainActivity, vec)
                    val msg = "Voiceprint enrolled for Darshini."
                    tvReply.text = msg
                    speak(msg)
                    updateUI()
                } else {
                    tvReply.text = "Try speaking a bit closer to the mic."
                }
                btn.isEnabled = true
                btn.text = "🎙️ Enroll Darshini's Voice Lock (3s)"
            }
        }

        btnService.setOnClickListener {
            val intent = Intent(this, JarvisBackgroundService::class.java)
            if (JarvisBackgroundService.isRunning) {
                stopService(intent)
                tvReply.text = "Background service stopped."
            } else {
                ContextCompat.startForegroundService(this, intent)
                val msg = "24/7 wake service active."
                tvReply.text = msg
                speak(msg)
            }
            updateUI()
        }

        findViewById<Button>(R.id.btnAccessibility).setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }

        findViewById<Button>(R.id.btnOverlay).setOnClickListener {
            if (!Settings.canDrawOverlays(this)) {
                startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
            } else {
                tvReply.text = "Overlay permission already enabled."
            }
        }
    }

    private fun updateUI() {
        tvStatus.text = if (VoiceAuthManager.isEnrolled(this)) {
            "OWNER: DARSHINI • VOICE LOCK: ARMED"
        } else {
            "OWNER: DARSHINI • VOICE LOCK: UNENROLLED"
        }
        btnService.text = if (JarvisBackgroundService.isRunning) {
            "🔴 Stop 24/7 Wake Service"
        } else {
            "🟢 Start 24/7 Wake Service ('Hey Jarvis')"
        }
    }

    private fun startVoiceCommand() {
        btnSpeak.text = "🩸
LISTENING"
        tvReply.text = "Listening..."
        recognizer?.destroy()
        recognizer = SpeechRecognizer.createSpeechRecognizer(this)
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "en-IN")
        }
        recognizer?.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {}
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() { btnSpeak.text = "🩸
SPEAK" }
            override fun onError(error: Int) {
                btnSpeak.text = "🩸
SPEAK"
                tvReply.text = "Did not catch that. Tap SPEAK again."
            }
            override fun onResults(results: Bundle?) {
                btnSpeak.text = "🩸
SPEAK"
                val heard = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty()
                if (heard.isNotEmpty()) {
                    tvHeard.text = "Heard: "$heard""
                    CommandExecutor.execute(this@MainActivity, heard) { reply ->
                        tvReply.text = reply
                        speak(reply)
                    }
                }
            }
            override fun onPartialResults(partialResults: Bundle?) {}
            override fun onEvent(eventType: Int, params: Bundle?) {}
        })
        recognizer?.startListening(intent)
    }

    private fun requestPerms() {
        val list = mutableListOf(
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.READ_CONTACTS,
            Manifest.permission.CALL_PHONE,
            Manifest.permission.CAMERA
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            list.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        val needed = list.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (needed.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, needed.toTypedArray(), 100)
        }
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            tts?.language = Locale.UK
        }
    }

    private fun speak(text: String) {
        tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "JARVIS_MAIN")
    }

    override fun onDestroy() {
        recognizer?.destroy()
        tts?.shutdown()
        super.onDestroy()
    }
}
