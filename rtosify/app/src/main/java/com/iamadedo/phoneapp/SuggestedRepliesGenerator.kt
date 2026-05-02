package com.iamadedo.phoneapp

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL

/**
 * Suggested Replies Generator — WearOS / Pixel Watch feature.
 *
 * When a notification arrives that supports inline reply, generates 2-3 short
 * contextual reply suggestions using the Anthropic Claude API and pushes them
 * to the watch as SUGGESTED_REPLY so the user can tap-to-reply without typing.
 *
 * Privacy: only the notification title + text are sent to the API.
 * The API key must be stored in SharedPreferences by the user
 * (Settings → AI Features → Anthropic API Key).
 *
 * Graceful degradation: if no API key or network failure, falls back to
 * a set of generic responses ("👍", "OK!", "On my way").
 */
class SuggestedRepliesGenerator(private val context: Context) {

    companion object {
        private const val PREFS         = "ai_features_prefs"
        private const val KEY_API_KEY   = "anthropic_api_key"
        private const val API_URL       = "https://api.anthropic.com/v1/messages"
        private const val MODEL         = "claude-haiku-4-5-20251001"   // fastest/cheapest
        private const val MAX_TOKENS    = 150
        private val FALLBACK_REPLIES    = listOf("👍", "OK!", "On my way")
    }

    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /**
     * Generate suggested replies for an incoming notification.
     * @param appName      e.g. "WhatsApp", "SMS"
     * @param senderName   e.g. "Alice"
     * @param messageText  the notification body text
     * @return 2-3 short reply strings
     */
    suspend fun generate(
        appName: String,
        senderName: String,
        messageText: String
    ): List<String> = withContext(Dispatchers.IO) {
        val apiKey = prefs.getString(KEY_API_KEY, "") ?: ""
        if (apiKey.isBlank()) return@withContext FALLBACK_REPLIES

        try {
            val prompt = buildPrompt(appName, senderName, messageText)
            val responseText = callApi(apiKey, prompt)
            parseReplies(responseText)
        } catch (e: Exception) {
            FALLBACK_REPLIES
        }
    }

    private fun buildPrompt(app: String, sender: String, text: String): String =
        """You are a smart reply generator for a smartwatch. Given a message, generate exactly 3 very short reply options (max 6 words each). Return ONLY a JSON array of strings, no other text.

App: $app
From: $sender
Message: $text"""

    private fun callApi(apiKey: String, prompt: String): String {
        val url = URL(API_URL)
        val conn = url.openConnection() as HttpURLConnection
        conn.requestMethod = "POST"
        conn.setRequestProperty("Content-Type", "application/json")
        conn.setRequestProperty("x-api-key", apiKey)
        conn.setRequestProperty("anthropic-version", "2023-06-01")
        conn.doOutput = true
        conn.connectTimeout = 5_000
        conn.readTimeout    = 8_000

        val body = JSONObject().apply {
            put("model", MODEL)
            put("max_tokens", MAX_TOKENS)
            put("messages", JSONArray().put(
                JSONObject().apply {
                    put("role", "user")
                    put("content", prompt)
                }
            ))
        }.toString()

        OutputStreamWriter(conn.outputStream).use { it.write(body) }

        if (conn.responseCode != 200) return ""
        val response = conn.inputStream.bufferedReader().readText()
        val json = JSONObject(response)
        return json.getJSONArray("content")
            .getJSONObject(0)
            .getString("text")
    }

    private fun parseReplies(raw: String): List<String> {
        if (raw.isBlank()) return FALLBACK_REPLIES
        return try {
            val arr = JSONArray(raw.trim())
            (0 until arr.length()).map { arr.getString(it) }.take(3)
                .filter { it.isNotBlank() }
                .takeIf { it.isNotEmpty() } ?: FALLBACK_REPLIES
        } catch (e: Exception) {
            FALLBACK_REPLIES
        }
    }
}
