package com.signalalert.app

import android.content.Context
import android.content.SharedPreferences

class Prefs(context: Context) {
    private val sp: SharedPreferences =
        context.getSharedPreferences("signal_alert_prefs", Context.MODE_PRIVATE)

    var serverUrl: String
        get() = sp.getString(KEY_URL, BuildConfig.DEFAULT_SERVER_URL)
            ?.trim()
            ?.trimEnd('/')
            ?: BuildConfig.DEFAULT_SERVER_URL
        set(value) = sp.edit().putString(KEY_URL, value.trim().trimEnd('/')).apply()

    var biometricEnabled: Boolean
        get() = sp.getBoolean(KEY_BIO, true)
        set(value) = sp.edit().putBoolean(KEY_BIO, value).apply()

    companion object {
        private const val KEY_URL = "server_url"
        private const val KEY_BIO = "biometric_enabled"
    }
}
