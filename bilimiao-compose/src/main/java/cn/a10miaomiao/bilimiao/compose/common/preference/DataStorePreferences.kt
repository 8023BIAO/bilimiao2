package cn.a10miaomiao.bilimiao.compose.common.preference

import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import me.zhanghai.compose.preference.MutablePreferences
import me.zhanghai.compose.preference.Preferences

private typealias AndroidXPreferences = androidx.datastore.preferences.core.Preferences
private typealias AndroidXMutablePreferences = androidx.datastore.preferences.core.MutablePreferences

internal class DataStorePreferences(
    val preferences: AndroidXPreferences? = null,
) : Preferences {

    private val map = preferences?.run {
        asMap().mapKeys { it.key.name }
    } ?: mapOf()

    override fun <T> get(key: String): T? = map[key] as T

    override fun asMap(): Map<String, Any> = map

    override fun toMutablePreferences(): MutablePreferences {
        return MutableDataStorePreferences(
            preferences?.toMutablePreferences(),
        )
    }
}

internal class MutableDataStorePreferences(
    val preferences: AndroidXMutablePreferences? = null,
) : MutablePreferences {

    private val map = preferences?.run {
        asMap().mapKeys { it.key.name }.toMutableMap()
    } ?: mutableMapOf()

    @Suppress("UNCHECKED_CAST")
    override fun <T> get(key: String): T? = map[key] as T?

    override fun asMap(): Map<String, Any> = map

    override fun toMutablePreferences(): MutablePreferences =
        MutableDataStorePreferences(preferences?.toMutablePreferences())

    override fun <T> set(key: String, value: T?) {
        if (map[key] == value) {
            return
        }
        if (value == null) {
            // ★ 删除某个 key（value == null）时原来是 `map[key] = value as Any`，
            //   而 `null as Any` 会直接抛 NullPointerException；DataStore 侧也要一起删，
            //   否则这次"删除"在下次读盘后又回来了。旧值的类型从本地 map 里取。
            val old = map.remove(key)
            preferences?.let {
                when (old) {
                    is Boolean -> it.remove(booleanPreferencesKey(key))
                    is Int -> it.remove(intPreferencesKey(key))
                    is Long -> it.remove(longPreferencesKey(key))
                    is Float -> it.remove(floatPreferencesKey(key))
                    is String -> it.remove(stringPreferencesKey(key))
                    is Set<*> -> it.remove(stringSetPreferencesKey(key))
                }
            }
            return
        }
        map[key] = value
        preferences?.let {
            when (value) {
                is Boolean -> it[booleanPreferencesKey(key)] = value
                is Int -> it[intPreferencesKey(key)] = value
                is Long -> it[longPreferencesKey(key)] = value
                is Float -> it[floatPreferencesKey(key)] = value
                is String -> it[stringPreferencesKey(key)] = value
                is Set<*> -> @Suppress("UNCHECKED_CAST") {
                    it[stringSetPreferencesKey(key)] = value as Set<String>
                }
            }
        }
    }

    override fun clear() {
        // 本地 map 也要清：只清 DataStore 的话，同一次会话里 asMap()/get() 还能读到旧值
        map.clear()
        preferences?.clear()
    }
}
