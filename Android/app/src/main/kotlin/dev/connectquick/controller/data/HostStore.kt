package dev.connectquick.controller.data

import android.content.Context
import dev.connectquick.controller.input.TouchpadConfig
import dev.connectquick.controller.session.PairedHost
import kotlinx.serialization.json.Json

/**
 * What this phone remembers: the PC it is paired with (public keys and permissions only: no secrets,
 * the identity seed lives in the Android Keystore wrapping) and a few preferences. Plain app-private
 * preferences are enough because nothing here can be used to impersonate the phone or the PC.
 */
class HostStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("connectquick", Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true }

    fun load(): PairedHost? =
        prefs.getString(KEY_HOST, null)?.let { runCatching { json.decodeFromString(PairedHost.serializer(), it) }.getOrNull() }

    fun save(host: PairedHost) {
        prefs.edit().putString(KEY_HOST, json.encodeToString(PairedHost.serializer(), host)).apply()
    }

    fun clear() {
        prefs.edit().remove(KEY_HOST).apply()
    }

    fun touchpad(): TouchpadConfig = TouchpadConfig(
        sensitivity = prefs.getFloat(KEY_SENS, 1.6f).coerceIn(0.5f, 4f),
        naturalScroll = prefs.getBoolean(KEY_NATURAL, true),
    )

    fun saveTouchpad(config: TouchpadConfig) {
        prefs.edit().putFloat(KEY_SENS, config.sensitivity).putBoolean(KEY_NATURAL, config.naturalScroll).apply()
    }

    private companion object {
        const val KEY_HOST = "paired_host"
        const val KEY_SENS = "touchpad_sensitivity"
        const val KEY_NATURAL = "touchpad_natural_scroll"
    }
}
