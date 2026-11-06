package com.beezync.sdk

import android.content.Context

class SharedPrefsStore(context: Context) : BeezyncStore {
    private val prefs = context.applicationContext.getSharedPreferences("beezync_sdk", Context.MODE_PRIVATE)

    override fun get(key: String): String? = prefs.getString(key, null)

    override fun put(key: String, value: String?) {
        prefs.edit().apply { if (value == null) remove(key) else putString(key, value) }.apply()
    }
}
