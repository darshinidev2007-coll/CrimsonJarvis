package com.darshini.crimsonjarvis

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.camera2.CameraManager
import android.net.Uri
import android.provider.ContactsContract
import android.provider.MediaStore
import java.util.Locale

object CommandExecutor {

    fun execute(context: Context, rawText: String, onReply: (String) -> Unit) {
        val prefixes = listOf("hey jarvis", "ok jarvis", "jarvis", "hey babe", "babe")
        var cleaned = rawText.trim()
        val lowerStart = cleaned.lowercase(Locale.ROOT)
        for (p in prefixes) {
            if (lowerStart.startsWith(p)) {
                cleaned = cleaned.substring(p.length).trimStart(',', ' ', ':').trim()
                break
            }
        }
        if (cleaned.isEmpty()) {
            onReply("Yes Darshini, I am listening.")
            return
        }

        val lower = cleaned.lowercase(Locale.ROOT)

        if (lower.contains("flashlight") || lower.contains("torch")) {
            val enable = !lower.contains("off")
            try {
                val cm = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
                val camId = cm.cameraIdList.firstOrNull()
                if (camId != null) {
                    cm.setTorchMode(camId, enable)
                    onReply(if (enable) "Flashlight turned on." else "Flashlight turned off.")
                    return
                }
            } catch (_: Exception) {}
        }

        if (lower.contains("camera") || lower.contains("selfie")) {
            val intent = Intent(MediaStore.ACTION_IMAGE_CAPTURE).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            onReply("Opening camera.")
            return
        }

        if (lower.startsWith("call ") || lower.startsWith("dial ")) {
            val targetName = cleaned.substringAfter(" ").replace("my ", "", ignoreCase = true).trim()
            val found = lookupContact(context, targetName)
            if (found != null) {
                dialNumber(context, found.second)
                onReply("Calling ${found.first}, Darshini.")
            } else {
                onReply("Could not find $targetName in your contacts.")
            }
            return
        }

        if (lower.startsWith("open ") || lower.startsWith("launch ") || lower.startsWith("go to ")) {
            var appName = cleaned.substringAfter(" ").trim().lowercase(Locale.ROOT)
            if (appName.startsWith("to ")) appName = appName.removePrefix("to ").trim()
            if (appName == "phone pay") appName = "phonepe"
            if (appName == "gpay") appName = "google pay"

            if (openAppByName(context, appName)) {
                onReply("Opening $appName.")
            } else {
                val web = Intent(Intent.ACTION_VIEW, Uri.parse("https://www.google.com/search?q=" + Uri.encode(appName))).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(web)
                onReply("Searching for $appName.")
            }
            return
        }

        onReply("At your service, Darshini. Say 'Call Mom', 'Open WhatsApp', or 'Open YouTube'.")
    }

    @SuppressLint("Range")
    private fun lookupContact(context: Context, query: String): Pair<String, String>? {
        val cursor = context.contentResolver.query(
            ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
            arrayOf(
                ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                ContactsContract.CommonDataKinds.Phone.NUMBER
            ),
            "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} LIKE ?",
            arrayOf("%$query%"),
            null
        )
        cursor?.use {
            if (it.moveToFirst()) {
                val name = it.getString(it.getColumnIndex(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME)) ?: query
                val num = it.getString(it.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER)) ?: ""
                if (num.isNotBlank()) return Pair(name, num)
            }
        }
        return null
    }

    @SuppressLint("MissingPermission")
    private fun dialNumber(context: Context, number: String) {
        val intent = Intent(Intent.ACTION_CALL, Uri.parse("tel:" + Uri.encode(number))).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        try {
            context.startActivity(intent)
        } catch (_: Exception) {
            val dial = Intent(Intent.ACTION_DIAL, Uri.parse("tel:" + Uri.encode(number))).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(dial)
        }
    }

    private fun openAppByName(context: Context, name: String): Boolean {
        val pm = context.packageManager
        val apps = pm.getInstalledApplications(PackageManager.GET_META_DATA)
        val cleanTarget = name.replace(" ", "")
        for (info in apps) {
            val label = pm.getApplicationLabel(info).toString().lowercase(Locale.ROOT).replace(" ", "")
            if (label == cleanTarget || label.contains(cleanTarget)) {
                val launch = pm.getLaunchIntentForPackage(info.packageName)
                if (launch != null) {
                    launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    context.startActivity(launch)
                    return true
                }
            }
        }
        return false
    }
}
