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
        scrollPixelsPerNotch = prefs.getFloat(KEY_SCROLL, 36f).coerceIn(12f, 96f),
        acceleration = prefs.getFloat(KEY_ACCEL, 0.5f).coerceIn(0f, 1.5f),
    )

    fun saveTouchpad(config: TouchpadConfig) {
        prefs.edit().putFloat(KEY_SENS, config.sensitivity).putBoolean(KEY_NATURAL, config.naturalScroll)
            .putFloat(KEY_SCROLL, config.scrollPixelsPerNotch).putFloat(KEY_ACCEL, config.acceleration).apply()
    }

    /** Screen view input mode: true = touchpad (relative, local pointer), false = direct touch. */
    fun screenTouchpadMode(): Boolean = prefs.getBoolean(KEY_SCREEN_PAD, false)

    fun saveScreenTouchpadMode(on: Boolean) {
        prefs.edit().putBoolean(KEY_SCREEN_PAD, on).apply()
    }

    /** Last stream profile chosen on the screen view (enum name), or null for the default. */
    fun streamProfile(): String? = prefs.getString(KEY_PROFILE, null)

    fun saveStreamProfile(name: String) {
        prefs.edit().putString(KEY_PROFILE, name).apply()
    }

    /** "Light" theme preference; dark is the default. */
    fun lightTheme(): Boolean = prefs.getBoolean(KEY_LIGHT, false)

    fun saveLightTheme(light: Boolean) {
        prefs.edit().putBoolean(KEY_LIGHT, light).apply()
    }

    private companion object {
        const val KEY_LIGHT = "theme_light"
        const val KEY_PROFILE = "stream_profile"
        const val KEY_SCROLL = "touchpad_scroll_px"
        const val KEY_ACCEL = "touchpad_acceleration"
        const val KEY_SCREEN_PAD = "screen_touchpad_mode"
        const val KEY_HOST = "paired_host"
        const val KEY_SENS = "touchpad_sensitivity"
        const val KEY_NATURAL = "touchpad_natural_scroll"
    }
}
