package com.xong.driveupload

import android.content.Context

class SessionStore(context: Context) {
    private val prefs = context.getSharedPreferences("drive_upload_session", Context.MODE_PRIVATE)

    var accountEmail: String?
        get() = prefs.getString(KEY_EMAIL, null)
        set(value) {
            prefs.edit().apply {
                if (value == null) remove(KEY_EMAIL) else putString(KEY_EMAIL, value)
            }.apply()
        }

    companion object {
        private const val KEY_EMAIL = "account_email"
    }
}
