package com.darshini.crimsonjarvis

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.ContactsContract
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale

object JarvisCommandEngine {

    fun executeCommand(context: Context, rawSpeech: String, onReply: (String) -> Unit) {
        val cleaned = rawSpeech
            .replace(Regex("^(hey\s+jarvis|ok\s+jarvis|jarvis)[,\s]*", RegexOption.IGNORE_CASE), "")
            .trim()
        val lower = cleaned.lowercase(Locale.getDefault())

        if (lower.isEmpty()) {
            onReply("Yes Darshini, I am listening.")
            return
        }

        // 1. IN-APP CHAT NAVIGATION: "open whatsapp and go to amma"
        val waChatMatch = Regex("whatsapp.*?(?:go to|chat with|message|open)\s+(.+)").find(lower)
        if (waChatMatch != null) {
            val person = waChatMatch.groupValues[1]
                .replace("'s chat", "")
                .replace("chat", "")
                .trim()
            JarvisAccessibilityService.pendingSearchQuery = person
            JarvisAccessibilityService.pendingTargetPackage = "com.whatsapp"
            if (launchInstalledApp(context, "whatsapp")) {
                onReply("Opening WhatsApp and navigating to $person's chat.")
            } else {
                onReply("WhatsApp is not installed on this device.")
            }
            return
        }

        // 2. YOUTUBE SEARCH: "open youtube and search ..."
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

        // 3. REAL ANDROID CONTACTS LOOKUP & DIRECT CALL: "call mom" / "call amma" / "call dad"
        val callMatch = Regex("^(?:call|dial|ring|phone)\s+(.+)").find(lower)
        if (callMatch != null) {
            val targetName = callMatch.groupValues[1].trim()
            val number = lookupContactPhoneNumber(context, targetName)
            if (number != null) {
                val callIntent = Intent(Intent.ACTION_CALL, Uri.parse("tel:$number")).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(callIntent)
                onReply("Calling $targetName right now, Darshini.")
            } else {
                onReply("I could not find $targetName in your phone contacts.")
            }
            return
        }

        // 4. OPEN ANY INSTALLED APP ON THE PHONE
        val openMatch = Regex("^(?:open|launch|start|go to)\s+(.+)").find(lower)
        if (openMatch != null) {
            val appQuery = openMatch.groupValues[1].trim()
            if (launchInstalledApp(context, appQuery)) {
                onReply("Opening $appQuery for you now.")
            } else {
                onReply("I couldn't find an installed app named $appQuery.")
            }
            return
        }

        // 5. OPTIONAL GEMINI AI BRAIN FOR GENERAL QUESTIONS
        val apiKey = context.getSharedPreferences("crimson_voice_prefs", Context.MODE_PRIVATE)
            .getString("gemini_api_key", "") ?: ""
        if (apiKey.isNotEmpty()) {
            askGeminiAsync(apiKey, cleaned, onReply)
        } else {
            onReply("I heard '$cleaned'. Say 'Call Mom', 'Open Instagram', or 'Open WhatsApp and go to Amma'.")
        }
    }

    private fun lookupContactPhoneNumber(context: Context, spokenName: String): String? {
        val aliases = when (spokenName.lowercase(Locale.getDefault())) {
            "mom", "mum", "mother", "amma" -> listOf("mom", "amma", "mother", "mummy")
            "dad", "father", "appa", "papa" -> listOf("dad", "appa", "papa", "father", "daddy")
            else -> listOf(spokenName.lowercase(Locale.getDefault()))
        }

        val cursor = context.contentResolver.query(
            ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
            arrayOf(
                ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                ContactsContract.CommonDataKinds.Phone.NUMBER
            ),
            null,
            null,
            null
        )

        cursor?.use {
            while (it.moveToNext()) {
                val name = it.getString(0)?.lowercase(Locale.getDefault()) ?: continue
                val number = it.getString(1) ?: continue
                if (aliases.any { alias -> name.contains(alias) }) {
                    return number
                }
            }
        }
        return null
    }

    private fun launchInstalledApp(context: Context, spokenApp: String): Boolean {
        val normalized = spokenApp.replace(" ", "").lowercase(Locale.getDefault())
        val pm = context.packageManager
        val packages = pm.getInstalledApplications(0)

        for (appInfo in packages) {
            val label = pm.getApplicationLabel(appInfo).toString()
                .replace(" ", "")
                .lowercase(Locale.getDefault())
            if (label == normalized || label.contains(normalized)) {
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

    private fun askGeminiAsync(apiKey: String, question: String, onReply: (String) -> Unit) {
        Thread {
            try {
                val url = URL("https://generativelanguage.googleapis.com/v1beta/models/gemini-2.0-flash:generateContent?key=$apiKey")
                val conn = url.openConnection() as HttpURLConnection
                conn.requestMethod = "POST"
                conn.setRequestProperty("Content-Type", "application/json")
                conn.doOutput = true

                val body = JSONObject().put(
                    "contents",
                    JSONArray().put(
                        JSONObject().put(
                            "parts",
                            JSONArray().put(
                                JSONObject().put(
                                    "text",
                                    "You are JARVIS, Darshini's loyal AI assistant. Reply concisely in 1-2 sentences: $question"
                                )
                            )
                        )
                    )
                )
                conn.outputStream.use { it.write(body.toString().toByteArray()) }
                val resp = conn.inputStream.bufferedReader().readText()
                val text = JSONObject(resp)
                    .getJSONArray("candidates")
                    .getJSONObject(0)
                    .getJSONObject("content")
                    .getJSONArray("parts")
                    .getJSONObject(0)
                    .getString("text")
                onReply(text.trim())
            } catch (_: Exception) {
                onReply("Network unavailable for Gemini AI right now, Darshini.")
            }
        }.start()
    }
}
