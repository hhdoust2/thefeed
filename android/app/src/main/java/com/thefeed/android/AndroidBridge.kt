package com.thefeed.android

import android.app.Activity
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Base64
import android.webkit.JavascriptInterface
import androidx.core.content.FileProvider
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest

class AndroidBridge(private val activity: Activity) {

    private val prefs by lazy {
        activity.getSharedPreferences(ThefeedService.PREFS_NAME, Context.MODE_PRIVATE)
    }

    @JavascriptInterface
    fun isAndroid(): Boolean = true

    // ===== Language =====

    @JavascriptInterface
    fun setLang(lang: String) {
        prefs.edit().putString(PREF_LANG, lang).apply()
    }

    @JavascriptInterface
    fun getLang(): String {
        return prefs.getString(PREF_LANG, "fa") ?: "fa"
    }

    // ===== Password =====

    @JavascriptInterface
    fun hasPassword(): Boolean {
        return prefs.getString(PREF_PASSWORD_HASH, null) != null
    }

    @JavascriptInterface
    fun setPassword(password: String): Boolean {
        if (password.isEmpty()) return false
        prefs.edit().putString(PREF_PASSWORD_HASH, sha256(password)).apply()
        return true
    }

    @JavascriptInterface
    fun removePassword(currentPassword: String): Boolean {
        val stored = prefs.getString(PREF_PASSWORD_HASH, null) ?: return false
        if (sha256(currentPassword) != stored) return false
        prefs.edit().remove(PREF_PASSWORD_HASH).apply()
        return true
    }

    @JavascriptInterface
    fun checkPassword(password: String): Boolean {
        val stored = prefs.getString(PREF_PASSWORD_HASH, null) ?: return true
        return sha256(password) == stored
    }

    // ===== Media handoff to system apps =====
    // The web frontend calls these for save / open / share when it
    // detects window.Android — WebView can't natively download blob URLs,
    // navigate to blob URLs in new tabs, or do navigator.share with files.

    @JavascriptInterface
    fun openMedia(base64: String, mime: String, filename: String): Boolean {
        return try {
            val uri = writeToCache(base64, filename)
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, sanitiseMime(mime))
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            activity.startActivity(Intent.createChooser(intent, filename))
            true
        } catch (_: Exception) { false }
    }

    @JavascriptInterface
    fun shareMedia(base64: String, mime: String, filename: String): Boolean {
        return try {
            val uri = writeToCache(base64, filename)
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = sanitiseMime(mime)
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            activity.startActivity(Intent.createChooser(intent, filename))
            true
        } catch (_: Exception) { false }
    }

    @JavascriptInterface
    fun saveMedia(base64: String, mime: String, filename: String): Boolean {
        return try {
            val bytes = Base64.decode(base64, Base64.DEFAULT)
            val safe = sanitiseFilename(filename)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val resolver = activity.contentResolver
                val collection = MediaStore.Downloads.EXTERNAL_CONTENT_URI
                val values = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, safe)
                    put(MediaStore.MediaColumns.MIME_TYPE, sanitiseMime(mime))
                    put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
                }
                val target = resolver.insert(collection, values) ?: return false
                resolver.openOutputStream(target)?.use { it.write(bytes) }
                true
            } else {
                @Suppress("DEPRECATION")
                val dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                if (!dir.exists()) dir.mkdirs()
                val out = File(dir, safe)
                FileOutputStream(out).use { it.write(bytes) }
                true
            }
        } catch (_: Exception) { false }
    }

    private fun writeToCache(base64: String, filename: String): Uri {
        val bytes = Base64.decode(base64, Base64.DEFAULT)
        val dir = File(activity.cacheDir, "shared")
        dir.mkdirs()
        val out = File(dir, sanitiseFilename(filename))
        FileOutputStream(out).use { it.write(bytes) }
        return FileProvider.getUriForFile(activity, activity.packageName + ".fileprovider", out)
    }

    private fun sanitiseFilename(name: String): String {
        val cleaned = name.replace(Regex("[^A-Za-z0-9._-]"), "_").trim('_', '.')
        return if (cleaned.isEmpty()) "media" else cleaned
    }

    private fun sanitiseMime(m: String): String {
        return if (m.matches(Regex("^[\\w./+-]+$"))) m else "application/octet-stream"
    }

    private fun sha256(input: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val hash = digest.digest(input.toByteArray(Charsets.UTF_8))
        return hash.joinToString("") { "%02x".format(it) }
    }

    companion object {
        const val PREF_PASSWORD_HASH = "password_hash"
        const val PREF_LANG = "app_lang"
    }
}
