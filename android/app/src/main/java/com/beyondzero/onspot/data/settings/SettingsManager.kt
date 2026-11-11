package com.beyondzero.onspot.data.settings

import android.content.Context
import android.os.Build

class SettingsManager(context: Context) {
    private val prefs = context.getSharedPreferences("onspot_settings", Context.MODE_PRIVATE)

    var deviceName: String
        get() = prefs.getString("device_name", Build.MODEL) ?: Build.MODEL
        set(value) {
            prefs.edit().putString("device_name", value).apply()
        }

    var hasCompletedOnboarding: Boolean
        get() = prefs.getBoolean("has_completed_onboarding", false)
        set(value) {
            prefs.edit().putBoolean("has_completed_onboarding", value).apply()
        }

    var avatarIndex: Int
        get() = prefs.getInt("avatar_index", 1)
        set(value) {
            prefs.edit().putInt("avatar_index", value).apply()
        }

    /** "wifi": find every device on the connected Wi-Fi. "hotspot": direct hotspot link (mDNS only). */
    var connectionMode: String
        get() = prefs.getString("connection_mode", MODE_WIFI) ?: MODE_WIFI
        set(value) {
            prefs.edit().putString("connection_mode", if (value == MODE_HOTSPOT) MODE_HOTSPOT else MODE_WIFI).apply()
        }

    /**
     * When on (the default), files and messages are only accepted from devices currently in the
     * Nearby list, so a random host on a public Wi-Fi can't drop files onto this phone.
     */
    var receiveFromNearbyOnly: Boolean
        get() = prefs.getBoolean("receive_nearby_only", true)
        set(value) {
            prefs.edit().putBoolean("receive_nearby_only", value).apply()
        }

    companion object {
        const val MODE_WIFI = "wifi"
        const val MODE_HOTSPOT = "hotspot"
    }
}
