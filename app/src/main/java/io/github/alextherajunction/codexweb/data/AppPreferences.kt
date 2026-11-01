package io.github.alextherajunction.codexweb.data

import android.content.Context

class AppPreferences(context: Context) {
    private val preferences = context.getSharedPreferences("codex_web_android", Context.MODE_PRIVATE)

    var deploymentUrl: String?
        get() = preferences.getString(KEY_DEPLOYMENT_URL, null)
        set(value) {
            preferences.edit().apply {
                if (value.isNullOrBlank()) remove(KEY_DEPLOYMENT_URL) else putString(KEY_DEPLOYMENT_URL, value)
            }.apply()
        }

    var lastModelId: String?
        get() = preferences.getString(KEY_LAST_MODEL, null)
        set(value) {
            preferences.edit().apply {
                if (value.isNullOrBlank()) remove(KEY_LAST_MODEL) else putString(KEY_LAST_MODEL, value)
            }.apply()
        }

    var lastProjectPath: String?
        get() = preferences.getString(KEY_LAST_PROJECT, null)
        set(value) {
            preferences.edit().apply {
                if (value.isNullOrBlank()) remove(KEY_LAST_PROJECT) else putString(KEY_LAST_PROJECT, value)
            }.apply()
        }

    var lastReasoningEffort: String?
        get() = preferences.getString(KEY_LAST_REASONING_EFFORT, null)
        set(value) {
            preferences.edit().apply {
                if (value.isNullOrBlank()) remove(KEY_LAST_REASONING_EFFORT) else putString(KEY_LAST_REASONING_EFFORT, value)
            }.apply()
        }

    companion object {
        private const val KEY_DEPLOYMENT_URL = "deployment_url"
        private const val KEY_LAST_MODEL = "last_model"
        private const val KEY_LAST_PROJECT = "last_project"
        private const val KEY_LAST_REASONING_EFFORT = "last_reasoning_effort"
    }
}
