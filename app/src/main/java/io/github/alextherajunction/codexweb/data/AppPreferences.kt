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

    var notificationsEnabled: Boolean
        get() = preferences.getBoolean(KEY_NOTIFICATIONS_ENABLED, false)
        set(value) {
            preferences.edit().putBoolean(KEY_NOTIFICATIONS_ENABLED, value).apply()
        }

    var pushDistributor: String?
        get() = preferences.getString(KEY_PUSH_DISTRIBUTOR, null)
        set(value) {
            preferences.edit().apply {
                if (value.isNullOrBlank()) remove(KEY_PUSH_DISTRIBUTOR) else putString(KEY_PUSH_DISTRIBUTOR, value)
            }.apply()
        }

    var pushStatus: String?
        get() = preferences.getString(KEY_PUSH_STATUS, null)
        set(value) {
            preferences.edit().apply {
                if (value.isNullOrBlank()) remove(KEY_PUSH_STATUS) else putString(KEY_PUSH_STATUS, value)
            }.apply()
        }

    var pushError: String?
        get() = preferences.getString(KEY_PUSH_ERROR, null)
        set(value) {
            preferences.edit().apply {
                if (value.isNullOrBlank()) remove(KEY_PUSH_ERROR) else putString(KEY_PUSH_ERROR, value)
            }.apply()
        }

    var pushVapidPublicKey: String?
        get() = preferences.getString(KEY_PUSH_VAPID_PUBLIC_KEY, null)
        set(value) {
            preferences.edit().apply {
                if (value.isNullOrBlank()) remove(KEY_PUSH_VAPID_PUBLIC_KEY) else putString(KEY_PUSH_VAPID_PUBLIC_KEY, value)
            }.apply()
        }

    var pushInstance: String?
        get() = preferences.getString(KEY_PUSH_INSTANCE, null)
        set(value) {
            preferences.edit().apply {
                if (value.isNullOrBlank()) remove(KEY_PUSH_INSTANCE) else putString(KEY_PUSH_INSTANCE, value)
            }.apply()
        }

    fun registerChangeListener(listener: android.content.SharedPreferences.OnSharedPreferenceChangeListener) {
        preferences.registerOnSharedPreferenceChangeListener(listener)
    }

    fun unregisterChangeListener(listener: android.content.SharedPreferences.OnSharedPreferenceChangeListener) {
        preferences.unregisterOnSharedPreferenceChangeListener(listener)
    }

    companion object {
        private const val KEY_DEPLOYMENT_URL = "deployment_url"
        private const val KEY_LAST_MODEL = "last_model"
        private const val KEY_LAST_PROJECT = "last_project"
        private const val KEY_LAST_REASONING_EFFORT = "last_reasoning_effort"
        private const val KEY_NOTIFICATIONS_ENABLED = "notifications_enabled"
        private const val KEY_PUSH_DISTRIBUTOR = "push_distributor"
        private const val KEY_PUSH_STATUS = "push_status"
        private const val KEY_PUSH_ERROR = "push_error"
        private const val KEY_PUSH_VAPID_PUBLIC_KEY = "push_vapid_public_key"
        private const val KEY_PUSH_INSTANCE = "push_instance"
    }
}
