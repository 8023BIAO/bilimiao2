package com.a10miaomiao.bilimiao.comm.datastore

import android.content.Context
import android.preference.PreferenceManager
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import com.a10miaomiao.bilimiao.comm.BilimiaoCommApp
import com.a10miaomiao.bilimiao.comm.db.FilterTagDB
import com.a10miaomiao.bilimiao.comm.db.FilterUpperDB
import com.a10miaomiao.bilimiao.comm.db.FilterUpperNameDB
import com.a10miaomiao.bilimiao.comm.db.FilterWordDB
import com.a10miaomiao.bilimiao.comm.utils.ErrorLogCollector
import kotlinx.coroutines.flow.first
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

// v3: 新增 filterUpperNames（UP主名称屏蔽数据库）
@Serializable
data class SettingsExport(
    val version: Int = 3,
    val values: Map<String, SettingValue> = emptyMap(),
    // SQLite filter_db
    val filterWords: List<String> = emptyList(),
    val filterUppers: List<ExportFilterUpper> = emptyList(),
    val filterTags: List<String> = emptyList(),
    val filterUpperNames: List<String> = emptyList(),
    // SharedPreferences (bilimiao)
    val spTimeType: Int = 0,
    val spTimeFrom: String = "",
    val spTimeTo: String = "",
    val spProxyUpos: String = "none",
    // Default SharedPreferences (DPI + download quality)
    val spAppDpi: Int = 0,
    val spAppFontScale: Float = 0f,
    val spPlayerQuality: Int = 64,
    // Proxy JSON file
    val proxyServersJson: String = "[]"
)

@Serializable
data class ExportFilterUpper(
    val mid: Long,
    val name: String
)

@Serializable
sealed class SettingValue {
    @Serializable
    data class BoolVal(val value: Boolean) : SettingValue()
    @Serializable
    data class IntVal(val value: Int) : SettingValue()
    @Serializable
    data class LongVal(val value: Long) : SettingValue()
    @Serializable
    data class FloatVal(val value: Float) : SettingValue()
    @Serializable
    data class StringVal(val value: String) : SettingValue()
    @Serializable
    data class StringSetVal(val value: Set<String>) : SettingValue()
}

object SettingsExporter {

    private val json = Json { prettyPrint = true; ignoreUnknownKeys = true }

    suspend fun exportToJson(context: Context): String {
        // 1. DataStore
        val ds = SettingPreferences.run { context.dataStore }
        val prefs: Preferences = ds.data.first()
        val map: Map<Preferences.Key<*>, Any> = prefs.asMap()
        val values = mutableMapOf<String, SettingValue>()
        map.forEach { (key: Preferences.Key<*>, value: Any) ->
            val keyName = key.name
            val sv = when (value) {
                is Boolean -> SettingValue.BoolVal(value)
                is Int -> SettingValue.IntVal(value)
                is Long -> SettingValue.LongVal(value)
                is Float -> SettingValue.FloatVal(value)
                is String -> SettingValue.StringVal(value)
                is Set<*> -> SettingValue.StringSetVal(
                    value.filterIsInstance<String>().toSet()
                )
                else -> null
            }
            if (sv != null) {
                values[keyName] = sv
            }
        }

        // 2. SQLite filter_db
        val filterWords = FilterWordDB(context).queryAll()
        val filterUppers = FilterUpperDB(context).queryAll().map {
            ExportFilterUpper(it.mid, it.name)
        }
        val filterTags = FilterTagDB(context).queryAll()

        // v3: 新增 UP主名称屏蔽
        val filterUpperNames = FilterUpperNameDB(context).queryAll()

        // 3. SharedPreferences (bilimiao) - 时光姬时间 + 代理UPOS
        val sp = context.getSharedPreferences(BilimiaoCommApp.APP_NAME, Context.MODE_PRIVATE)
        val spTimeType = sp.getInt("timeType", 0)
        val spTimeFrom = sp.getString("timeFrom", "") ?: ""
        val spTimeTo = sp.getString("timeTo", "") ?: ""
        val spProxyUpos = sp.getString("proxy_upos", "none") ?: "none"

        // 4. Default SharedPreferences - DPI
        val defSp = PreferenceManager.getDefaultSharedPreferences(context)
        val spAppDpi = defSp.getInt("app_dpi", 0)
        val spAppFontScale = defSp.getFloat("app_font_scale", 0f)
        val spPlayerQuality = defSp.getInt("player_quality", 64)

        // 5. 代理服务器列表 JSON
        val proxyFile = File(context.filesDir.path + "/proxy_server_list.json")
        val proxyServersJson = if (proxyFile.exists() && proxyFile.isFile)
            proxyFile.readText() else "[]"

        return json.encodeToString(SettingsExport(
            values = values,
            filterWords = filterWords,
            filterUppers = filterUppers,
            filterTags = filterTags,
            filterUpperNames = filterUpperNames,
            spTimeType = spTimeType,
            spTimeFrom = spTimeFrom,
            spTimeTo = spTimeTo,
            spProxyUpos = spProxyUpos,
            spAppDpi = spAppDpi,
            spAppFontScale = spAppFontScale,
            spPlayerQuality = spPlayerQuality,
            proxyServersJson = proxyServersJson
        ))
    }

    suspend fun importFromJson(context: Context, jsonString: String): Int {
        // 智能截断：追踪括号深度，找到最外层 JSON 真正闭合的位置
        val cleanJson = truncateToValidJson(jsonString)
        val export = try {
            json.decodeFromString<SettingsExport>(cleanJson)
        } catch (e: Exception) {
            ErrorLogCollector.logError(
                error = "设置导入失败: JSON解析错误",
                stackTrace = e.toString() + "\nJSON前100字符: " + cleanJson.take(100)
            )
            throw e
        }
        var count = 0

        // 1. DataStore
        if (export.values.isNotEmpty()) {
            SettingPreferences.run {
                context.dataStore.edit { prefs ->
                    prefs.clear()
                    export.values.forEach { (keyName, settingValue) ->
                        try {
                            when (settingValue) {
                                is SettingValue.BoolVal -> {
                                    prefs[booleanPreferencesKey(keyName)] = settingValue.value
                                    count++
                                }
                                is SettingValue.IntVal -> {
                                    prefs[intPreferencesKey(keyName)] = settingValue.value
                                    count++
                                }
                                is SettingValue.LongVal -> {
                                    prefs[longPreferencesKey(keyName)] = settingValue.value
                                    count++
                                }
                                is SettingValue.FloatVal -> {
                                    prefs[floatPreferencesKey(keyName)] = settingValue.value
                                    count++
                                }
                                is SettingValue.StringVal -> {
                                    prefs[stringPreferencesKey(keyName)] = settingValue.value
                                    count++
                                }
                                is SettingValue.StringSetVal -> {
                                    prefs[stringSetPreferencesKey(keyName)] = settingValue.value
                                    count++
                                }
                            }
                        } catch (e: Exception) {
                            // Skip invalid keys silently
                        }
                    }
                }
            }
        }

        // v2+ 额外存储
        if (export.version >= 2) {
            // 2. SQLite filter_db
            val wordDb = FilterWordDB(context)
            wordDb.deleteAll()
            export.filterWords.forEach { wordDb.insert(it) }
            count += export.filterWords.size

            // FilterUpperDB 没有 deleteAll，直接用 SQL
            val upperDb = FilterUpperDB(context)
            upperDb.writableDatabase.use { db ->
                db.execSQL("delete from filter_upper")
            }
            export.filterUppers.forEach { upperDb.insert(it.mid, it.name) }
            count += export.filterUppers.size

            val tagDb = FilterTagDB(context)
            tagDb.deleteAll()
            export.filterTags.forEach { tagDb.insert(it) }
            count += export.filterTags.size

            // v3: UP主名称屏蔽
            val upperNameDb = FilterUpperNameDB(context)
            upperNameDb.deleteAll()
            export.filterUpperNames.forEach { upperNameDb.insert(it) }
            count += export.filterUpperNames.size

            // 3. SharedPreferences (bilimiao)
            val sp = context.getSharedPreferences(BilimiaoCommApp.APP_NAME, Context.MODE_PRIVATE)
            sp.edit().apply {
                putInt("timeType", export.spTimeType)
                putString("timeFrom", export.spTimeFrom)
                putString("timeTo", export.spTimeTo)
                putString("proxy_upos", export.spProxyUpos)
            }.apply()
            count += 4

            // 4. Default SharedPreferences (DPI)
            val defSp = PreferenceManager.getDefaultSharedPreferences(context)
            defSp.edit().apply {
                putInt("app_dpi", export.spAppDpi)
                putFloat("app_font_scale", export.spAppFontScale)
                putInt("player_quality", export.spPlayerQuality)
            }.apply()
            count += 3

            // 5. 代理服务器 JSON
            val proxyFile = File(context.filesDir.path + "/proxy_server_list.json")
            proxyFile.writeText(export.proxyServersJson)
            count++
        }

        return count
    }

    /**
     * 智能截断：追踪括号深度，找到最外层 JSON 对象/数组真正闭合的位置。
     * 处理文件末尾被追加垃圾数据的情况（ContentResolver 写入 bug）。
     */
    fun truncateToValidJson(raw: String): String {
        var depth = 0
        var inString = false
        var escape = false
        for ((i, c) in raw.withIndex()) {
            if (escape) { escape = false; continue }
            if (c == '\\') { escape = true; continue }
            if (c == '"') { inString = !inString; continue }
            if (inString) continue
            if (c == '{' || c == '[') depth++
            if (c == '}' || c == ']') {
                depth--
                if (depth == 0) return raw.substring(0, i + 1)
            }
        }
        return raw // 没找到闭合点，返回原文让 parser 报错
    }

    suspend fun resetAll(context: Context) {
        // 1. DataStore (所有偏好设置)
        SettingPreferences.run {
            context.dataStore.edit { prefs ->
                prefs.clear()
            }
        }
        // 2. SharedPreferences (时光姬等)
        context.getSharedPreferences(BilimiaoCommApp.APP_NAME, Context.MODE_PRIVATE)
            .edit().clear().apply()
        // 3. 默认 SharedPreferences (DPI)
        PreferenceManager.getDefaultSharedPreferences(context)
            .edit().clear().apply()
        // 4. 屏蔽词数据库
        FilterWordDB(context).deleteAll()
        FilterTagDB(context).deleteAll()
        FilterUpperDB(context).writableDatabase.use { db ->
            db.execSQL("delete from filter_upper")
        }
        // v3: UP主名称屏蔽
        FilterUpperNameDB(context).deleteAll()
        // 5. 代理服务器列表
        val proxyFile = File(context.filesDir.path + "/proxy_server_list.json")
        if (proxyFile.exists()) proxyFile.delete()
    }
}
