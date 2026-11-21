package com.darshini.crimsonjarvis

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.net.Uri
import android.provider.AlarmClock
import android.provider.ContactsContract
import android.provider.MediaStore
import android.provider.Settings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

object CommandExecutor {

    fun execute(context: Context, rawTranscript: String, onReply: (String) -> Unit) {
        val cleaned = rawTranscript
            .replace(Regex("^(?:hey\s+jarvis|ok\s+jarvis|jarvis|hey\s+babe|babe)[,\s]*", RegexOption.IGNORE_CASE), "")
            .trim()

        if (cleaned.isEmpty()) {
            onReply("Yes Darshini, I am listening.")
            return
        }

        val lower = cleaned.lowercase()

        // 1. SEND WHATSAPP MESSAGE: "send whatsapp message to amma saying I reached"
        val waSendMatch = Regex("(?:send\s+whatsapp|whatsapp).*?(?:to|message)\s+(.+?)\s+(?:saying|that|message)\s+(.+)").find(lower)
        if (waSendMatch != null) {
            val person = waSendMatch.groupValues[1].trim()
            val msgText = waSendMatch.groupValues[2].trim()
            JarvisAccessibilityService.prepareAction("com.whatsapp", person, msgText)
            if (launchInstalledApp(context, "whatsapp")) {
                onReply("Opening WhatsApp to send your message to $person, Darshini.")
            } else {
                onReply("WhatsApp is not installed on this phone.")
            }
            return
        }

        // 2. OPEN WHATSAPP & ENTER CHAT: "open whatsapp and go to amma's chat"
        val waChatMatch = Regex("whatsapp.*?(?:go to|chat with|message|open)\s+(.+)").find(lower)
        if (waChatMatch != null) {
            val person = waChatMatch.groupValues[1]
                .replace("'s chat", "")
                .replace("chat", "")
                .trim()
            JarvisAccessibilityService.prepareAction("com.whatsapp", person, null)
            if (launchInstalledApp(context, "whatsapp")) {
                onReply("Opening WhatsApp and entering $person's chat.")
            } else {
                onReply("WhatsApp is not installed on this phone.")
            }
            return
        }

        // 3. HARDWARE & SYSTEM CONTROLS (Torch, Camera, Volume, Alarm, Settings, Navigation)
        if (lower.contains("flashlight") || lower.contains("torch")) {
            val turnOn = !lower.contains("off")
            try {
                val camManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
                val camId = camManager.cameraIdList.firstOrNull()
                if (camId != null) {
                    camManager.setTorchMode(camId, turnOn)
                    onReply(if (turnOn) "Flashlight turned on, Darshini." else "Flashlight turned off.")
                    return
                }
            } catch (_: Exception) {}
        }

        if (lower.contains("camera") || lower.contains("selfie") || lower.contains("take a photo")) {
            val camIntent = Intent(MediaStore.ACTION_IMAGE_CAPTURE).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(camIntent)
            onReply("Opening camera now, Darshini.")
            return
        }

        if (lower.contains("volume up") || lower.contains("increase volume")) {
            val audio = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            audio.adjustVolume(AudioManager.ADJUST_RAISE, AudioManager.FLAG_SHOW_UI)
            onReply("Volume increased.")
            return
        }

        if (lower.contains("volume down") || lower.contains("decrease volume") || lower.contains("mute")) {
            val audio = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            audio.adjustVolume(AudioManager.ADJUST_LOWER, AudioManager.FLAG_SHOW_UI)
            onReply("Volume lowered.")
            return
        }

        if (lower.contains("wi-fi") || lower.contains("wifi")) {
            val intent = Intent(Settings.ACTION_WIFI_SETTINGS).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
            context.startActivity(intent)
            onReply("Opening Wi-Fi controls.")
            return
        }

        if (lower.contains("bluetooth")) {
            val intent = Intent(Settings.ACTION_BLUETOOTH_SETTINGS).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
            context.startActivity(intent)
            onReply("Opening Bluetooth controls.")
            return
        }

        val alarmMatch = Regex("set\s+(?:an\s+)?alarm\s+(?:for\s+)?(\d{1,2})(?::(\d{2}))?").find(lower)
        if (alarmMatch != null) {
            val hour = alarmMatch.groupValues[1].toIntOrNull() ?: 7
            val min = alarmMatch.groupValues[2].toIntOrNull() ?: 0
            val alarmIntent = Intent(AlarmClock.ACTION_SET_ALARM).apply {
                putExtra(AlarmClock.EXTRA_HOUR, hour)
                putExtra(AlarmClock.EXTRA_MINUTES, min)
                putExtra(AlarmClock.EXTRA_SKIP_UI, true)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(alarmIntent)
            onReply("Alarm set for $hour:${String.format("%02d", min)}, Darshini.")
            return
        }

        val navMatch = Regex("^(?:navigate to|directions to|take me to)\s+(.+)").find(lower)
        if (navMatch != null) {
            val place = navMatch.groupValues[1].trim()
            val navIntent = Intent(Intent.ACTION_VIEW, Uri.parse("google.navigation:q=" + Uri.encode(place))).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(navIntent)
            onReply("Starting navigation to $place.")
            return
        }

        // 4. YOUTUBE SEARCH & PLAY
        val ytMatch = Regex("youtube.*?(?:search|play|find|open)\s+(.+)").find(lower)
        if (ytMatch != null) {
            val query = ytMatch.groupValues[1].trim()
            val intent = Intent(
                Intent.ACTION_VIEW,
                Uri.parse("https://www.youtube.com/results?search_query=" + Uri.encode(query))
            ).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
            context.startActivity(intent)
            onReply("Opening YouTube and searching for $query.")
            return
        }

        // 5. REAL ANDROID CONTACTS LOOKUP & DIRECT CALL: "call mom" / "call amma" / "call dad"
        val callMatch = Regex("^(?:call|dial|ring|phone)\s+(.+)").find(lower)
        if (callMatch != null) {
            val rawName = callMatch.groupValues[1]
                .replace("my ", "")
                .replace("in my phone", "")
                .trim()

            val aliases = mutableListOf(rawName)
            if (rawName == "mom" || rawName == "mother") aliases.addAll(listOf("amma", "mummy", "mom"))
            if (rawName == "dad" || rawName == "father") aliases.addAll(listOf("appa", "daddy", "papa", "dad"))

            var phoneNumber: String? = null
            var matchedDisplayName = rawName.replaceFirstChar { it.uppercase() }

            for (candidate in aliases) {
                val found = findContactPhoneNumber(context, candidate)
                if (found != null) {
                    matchedDisplayName = found.first
                    phoneNumber = found.second
                    break
                }
            }

            if (phoneNumber != null) {
                placeDirectPhoneCall(context, phoneNumber)
                onReply("Calling $matchedDisplayName now, Darshini.")
            } else {
                onReply("I could not find $matchedDisplayName in your contacts.")
            }
            return
        }

        // 6. OPEN ANY INSTALLED APP ON THE PHONE
        val openMatch = Regex("^(?:open|launch|start|go to)\s+(.+)").find(lower)
        if (openMatch != null) {
            var appQuery = openMatch.groupValues[1].trim()
            if (appQuery == "phone pay") appQuery = "phonepe"
            if (appQuery == "gpay") appQuery = "google pay"
            if (appQuery == "insta") appQuery = "instagram"

            if (launchInstalledApp(context, appQuery)) {
                onReply("Launching ${appQuery.replaceFirstChar { it.uppercase() }} now.")
            } else {
                val searchIntent = Intent(
                    Intent.ACTION_VIEW,
                    Uri.parse("https://www.google.com/search?q=" + Uri.encode(appQuery))
                ).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
                context.startActivity(searchIntent)
                onReply("Searching for $appQuery.")
            }
            return
        }

        // 7. GEMINI AI CONVERSATIONAL BRAIN (If API key saved, answers anything!)
        val apiKey = context.getSharedPreferences("crimson_jarvis_voice_prefs", Context.MODE_PRIVATE)
            .getString("gemini_api_key", "") ?: ""

        if (apiKey.isNotBlank()) {
            askGeminiBrain(apiKey, cleaned, onReply)
        } else {
            onReply("At your command, Darshini. Say 'Call Mom', 'Open WhatsApp and go to Amma', 'Open Instagram', or paste a Gemini key for full AI conversation.")
        }
    }

    @SuppressLint("Range")
    private fun findContactPhoneNumber(context: Context, nameQuery: String): Pair<String, String>? {
        val resolver = context.contentResolver
        val cursor = resolver.query(
            ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
            arrayOf(
                ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                ContactsContract.CommonDataKinds.Phone.NUMBER
            ),
            "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} LIKE ?",
            arrayOf("%$nameQuery%"),
            null
        )
        cursor?.use {
            if (it.moveToFirst()) {
                val displayName = it.getString(it.getColumnIndex(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME))
                val number = it.getString(it.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER))
                if (!number.isNullOrBlank()) {
                    return Pair(displayName, number)
                }
            }
        }
        return null
    }

    @SuppressLint("MissingPermission")
    private fun placeDirectPhoneCall(context: Context, number: String) {
        val callIntent = Intent(Intent.ACTION_CALL, Uri.parse("tel:${Uri.encode(number)}")).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        try {
            context.startActivity(callIntent)
        } catch (_: Exception) {
            val dialIntent = Intent(Intent.ACTION_DIAL, Uri.parse("tel:${Uri.encode(number)}")).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(dialIntent)
        }
    }

    private fun launchInstalledApp(context: Context, spokenAppName: String): Boolean {
        val pm = context.packageManager
        val packages = pm.getInstalledApplications(PackageManager.GET_META_DATA)
        val target = spokenAppName.lowercase().replace(" ", "")

        // Exact or partial app label match across all installed Android apps
        for (appInfo in packages) {
            val label = pm.getApplicationLabel(appInfo).toString().lowercase().replace(" ", "")
            if (label == target || label.contains(target)) {
                val launchIntent = pm.getLaunchIntentForPackage(appInfo.packageName)
                if (launchIntent != null) {
                    launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    context.startActivity(launchIntent)
                    return true
                }
            }
        }
        return false
    }

    private fun askGeminiBrain(apiKey: String, question: String, onReply: (String) -> Unit) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val url = URL("https://generativelanguage.googleapis.com/v1beta/models/gemini-2.0-flash:generateContent?key=$apiKey")
                val conn = (url.openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"
                    setRequestProperty("Content-Type", "application/json")
                    doOutput = true
                }
                val prompt = "You are JARVIS, Tony Stark's loyal AI built exclusively for Darshini. Reply concisely in 1-2 spoken sentences: $question"
                val body = JSONObject().put(
                    "contents",
                    JSONArray().put(
                        JSONObject().put(
                            "parts",
                            JSONArray().put(JSONObject().put("text", prompt))
                        )
                    )
                ).toString()

                conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
                val responseStr = conn.inputStream.bufferedReader().use { it.readText() }
                val reply = JSONObject(responseStr)
                    .getJSONArray("candidates")
                    .getJSONObject(0)
                    .getJSONObject("content")
                    .getJSONArray("parts")
                    .getJSONObject(0)
                    .getString("text")
                    .trim()

                withContext(Dispatchers.Main) { onReply(reply) }
            } catch (_: Exception) {
                withContext(Dispatchers.Main) {
                    onReply("I am connected locally, Darshini, though the cloud AI link timed out.")
                }
            }
        }
    }
}
