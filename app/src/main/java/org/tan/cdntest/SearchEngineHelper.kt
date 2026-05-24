package org.tan.cdntest

import android.content.Context

object SearchEngineHelper {

    data class SearchEngine(val name: String, val urlTemplate: String)

    private const val PREF_NAME = "search_engine_prefs"
    private const val KEY_CURRENT = "current_engine"
    private const val KEY_CUSTOM_NAME = "custom_engine_name"
    private const val KEY_CUSTOM_URL = "custom_engine_url"

    val presets = listOf(
        SearchEngine("百度", "https://www.baidu.com/s?wd=%s"),
        SearchEngine("搜狗", "https://www.sogou.com/web?query=%s"),
        SearchEngine("Google", "https://www.google.com/search?q=%s"),
        SearchEngine("Bing", "https://www.bing.com/search?q=%s")
    )

    fun getCurrentEngine(context: Context): SearchEngine {
        val prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        val index = prefs.getInt(KEY_CURRENT, 0)
        if (index < presets.size) return presets[index]
        // 自定义引擎
        val name = prefs.getString(KEY_CUSTOM_NAME, "") ?: ""
        val url = prefs.getString(KEY_CUSTOM_URL, "") ?: ""
        if (name.isNotEmpty() && url.isNotEmpty()) return SearchEngine(name, url)
        return presets[0]
    }

    fun getCurrentIndex(context: Context): Int {
        val prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        return prefs.getInt(KEY_CURRENT, 0)
    }

    fun setCurrentEngine(context: Context, index: Int) {
        context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            .edit().putInt(KEY_CURRENT, index).apply()
    }

    fun setCustomEngine(context: Context, name: String, urlTemplate: String) {
        val prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        prefs.edit()
            .putInt(KEY_CURRENT, presets.size)
            .putString(KEY_CUSTOM_NAME, name)
            .putString(KEY_CUSTOM_URL, urlTemplate)
            .apply()
    }

    fun isUrl(input: String): Boolean {
        val trimmed = input.trim()
        if (trimmed.contains(" ") && !trimmed.startsWith("http")) return false
        return trimmed.startsWith("http://") || trimmed.startsWith("https://") ||
            (trimmed.contains(".") && !trimmed.contains(" ") && trimmed.length > 3)
    }

    fun buildSearchUrl(context: Context, query: String): String {
        val engine = getCurrentEngine(context)
        return engine.urlTemplate.replace("%s", java.net.URLEncoder.encode(query, "UTF-8"))
    }
}
