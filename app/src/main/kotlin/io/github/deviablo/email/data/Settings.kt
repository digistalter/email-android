// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.deviablo.email.data

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit

enum class AutoEmpty { NEVER, AFTER_30_DAYS }

/** General settings (Einstellungen → Allgemein). Per-account settings live in the database. */
class Settings(context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    var groupConversations: Boolean
        get() = prefs.getBoolean("group", true)
        set(v) = prefs.edit { putBoolean("group", v) }

    var notifications: Boolean
        get() = prefs.getBoolean("notify", true)
        set(v) = prefs.edit { putBoolean("notify", v) }

    /** Keeps a permanent connection (foreground service) for instant notifications. */
    var instantPush: Boolean
        get() = prefs.getBoolean("push", false)
        set(v) = prefs.edit { putBoolean("push", v) }

    var alwaysShowImages: Boolean
        get() = prefs.getBoolean("images", false)
        set(v) = prefs.edit { putBoolean("images", v) }

    var emptyTrash: AutoEmpty
        get() = AutoEmpty.valueOf(prefs.getString("emptyTrash", AutoEmpty.NEVER.name)!!)
        set(v) = prefs.edit { putString("emptyTrash", v.name) }

    var emptySpam: AutoEmpty
        get() = AutoEmpty.valueOf(prefs.getString("emptySpam", AutoEmpty.NEVER.name)!!)
        set(v) = prefs.edit { putString("emptySpam", v.name) }

    var askedForNotifications: Boolean
        get() = prefs.getBoolean("askedNotify", false)
        set(v) = prefs.edit { putBoolean("askedNotify", v) }

    var activeAccountId: Long
        get() = prefs.getLong("active", -1)
        set(v) = prefs.edit { putLong("active", v) }
}
