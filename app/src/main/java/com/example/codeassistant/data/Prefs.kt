package com.example.codeassistant.data

import android.content.Context
import androidx.core.content.edit

/** Stores the API key locally on the device (app-private storage). */
class Prefs(context: Context) {
    private val sp = context.getSharedPreferences("code_assistant", Context.MODE_PRIVATE)

    var apiKey: String
        get() = sp.getString(KEY_API, "").orEmpty()
        set(value) = sp.edit { putString(KEY_API, value.trim()) }

    var model: String
        get() = sp.getString(KEY_MODEL, ClaudeClient.DEFAULT_MODEL) ?: ClaudeClient.DEFAULT_MODEL
        set(value) = sp.edit { putString(KEY_MODEL, value.trim().ifEmpty { ClaudeClient.DEFAULT_MODEL }) }

    private companion object {
        const val KEY_API = "api_key"
        const val KEY_MODEL = "model"
    }
}
