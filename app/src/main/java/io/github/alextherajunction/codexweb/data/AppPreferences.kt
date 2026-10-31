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

    companion object {
        private const val KEY_DEPLOYMENT_URL = "deployment_url"
        private const val KEY_LAST_MODEL = "last_model"
    }
}
