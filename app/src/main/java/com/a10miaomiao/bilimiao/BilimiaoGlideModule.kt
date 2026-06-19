package com.a10miaomiao.bilimiao

import android.content.Context
import com.a10miaomiao.bilimiao.comm.datastore.SettingPreferences
import com.bumptech.glide.GlideBuilder
import com.bumptech.glide.annotation.GlideModule
import com.bumptech.glide.load.DecodeFormat
import com.bumptech.glide.load.engine.bitmap_recycle.LruBitmapPool
import com.bumptech.glide.load.engine.cache.DiskLruCacheFactory
import com.bumptech.glide.load.engine.cache.LruResourceCache
import com.bumptech.glide.module.AppGlideModule
import com.bumptech.glide.request.RequestOptions
import kotlinx.coroutines.runBlocking

@GlideModule
class BilimiaoGlideModule : AppGlideModule() {

    override fun applyOptions(context: Context, builder: GlideBuilder) {
        // 读取用户设置的图片缓存大小，默认50MB
        val cacheSizeMb = try {
            runBlocking {
                SettingPreferences.mapData(context) { prefs ->
                    prefs[SettingPreferences.ImageDiskCacheSize] ?: 50
                }
            }
        } catch (e: Exception) {
            50
        }
        // 设置磁盘缓存路径和上限
        val cacheDir = context.cacheDir.path + "/image_manager_disk_cache"
        builder.setDiskCache(
            DiskLruCacheFactory(cacheDir, cacheSizeMb * 1024 * 1024L)
        )
    }

    override fun isManifestParsingEnabled(): Boolean = false
}
