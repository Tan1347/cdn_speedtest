package org.tan.cdntest

import android.content.Context

object UserAgentHelper {

    data class UaPreset(val name: String, val ua: String)

    private const val PREF_NAME = "ua_prefs"
    private const val KEY_CURRENT_UA = "current_ua"
    private const val KEY_CUSTOM_LIST = "custom_ua_list"

    val presets = listOf(
        UaPreset("Android 手机",
            "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"),
        UaPreset("iPhone",
            "Mozilla/5.0 (iPhone; CPU iPhone OS 17_0 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/17.0 Mobile/15E148 Safari/604.1"),
        UaPreset("iPad",
            "Mozilla/5.0 (iPad; CPU OS 17_0 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/17.0 Mobile/15E148 Safari/604.1"),
        UaPreset("电脑 Chrome",
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"),
        UaPreset("电脑 Edge",
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36 Edg/120.0.0.0"),
        UaPreset("电脑 Firefox",
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:121.0) Gecko/20100101 Firefox/121.0")
    )

    fun getCurrentUa(context: Context): String {
        val prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        return prefs.getString(KEY_CURRENT_UA, presets[0].ua) ?: presets[0].ua
    }

    fun setCurrentUa(context: Context, ua: String) {
        context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            .edit().putString(KEY_CURRENT_UA, ua).apply()
    }

    fun getCurrentUaName(context: Context): String {
        val ua = getCurrentUa(context)
        presets.find { it.ua == ua }?.let { return it.name }
        getCustomList(context).find { it.ua == ua }?.let { return it.name }
        return "自定义"
    }

    fun getCustomList(context: Context): List<UaPreset> {
        val prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        val raw = prefs.getString(KEY_CUSTOM_LIST, "") ?: ""
        if (raw.isBlank()) return emptyList()
        return raw.split("|||").mapNotNull {
            val parts = it.split(":::", limit = 2)
            if (parts.size == 2) UaPreset(parts[0], parts[1]) else null
        }
    }

    fun addCustom(context: Context, name: String, ua: String) {
        val list = getCustomList(context).toMutableList()
        list.removeAll { it.ua == ua }
        list.add(0, UaPreset(name, ua))
        val raw = list.joinToString("|||") { "${it.name}:::${it.ua}" }
        context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            .edit().putString(KEY_CUSTOM_LIST, raw).apply()
    }

    fun removeCustom(context: Context, ua: String) {
        val list = getCustomList(context).filter { it.ua != ua }
        val raw = list.joinToString("|||") { "${it.name}:::${it.ua}" }
        context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            .edit().putString(KEY_CUSTOM_LIST, raw).apply()
    }
}
