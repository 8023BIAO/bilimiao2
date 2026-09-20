package cn.a10miaomiao.bilimiao.download

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Environment
import androidx.core.content.ContextCompat
import java.io.File

/**
 * 下载"存哪里"的策略（只管判断，不做任何创建/写入）。
 *
 * 背景：Android 10（API 29）起公共的 `/sdcard/Download/` 只能用 MediaStore 写，
 * 好处是**不需要任何权限**；而 Android 9（API 28）及以下没有 `MediaStore.Downloads`，
 * 只能走真实文件路径，需要 `WRITE_EXTERNAL_STORAGE`（manifest 里已按 maxSdkVersion=28 声明）。
 *
 * 所以这里只回答两个问题：
 *  1. 现在能不能往公共目录发布（[canUsePublicStorage]）——任何不确定一律 false，调用方退回私有目录；
 *  2. 老系统还要不要弹一次存储权限（[shouldRequestLegacyPermission]）——用户拒绝过就不再弹。
 */
object DownloadStoragePolicy {

    /** 老系统"用户拒绝过存储权限"的标记：拒绝之后所有下载一律私有目录，不再打扰用户 */
    private const val PREFS = "bilimiao_download_storage"
    private const val KEY_LEGACY_DENIED = "legacy_write_permission_denied"

    /** Android 6.0~9：写公共目录需要运行时申请 WRITE_EXTERNAL_STORAGE（6.0 以下安装即授予，不用问） */
    private fun isLegacyRuntimePermission(): Boolean =
        Build.VERSION.SDK_INT in Build.VERSION_CODES.M..Build.VERSION_CODES.P

    fun hasLegacyWritePermission(context: Context): Boolean =
        ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.WRITE_EXTERNAL_STORAGE,
        ) == PackageManager.PERMISSION_GRANTED

    fun isLegacyPermissionDenied(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_LEGACY_DENIED, false)

    fun markLegacyPermissionDenied(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_LEGACY_DENIED, true).apply()
    }

    fun clearLegacyPermissionDenied(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_LEGACY_DENIED, false).apply()
    }

    /** 需不需要弹一次存储权限（只有 API 23~28、没授权、且没被拒绝过才弹） */
    fun shouldRequestLegacyPermission(context: Context): Boolean =
        isLegacyRuntimePermission() &&
            !hasLegacyWritePermission(context) &&
            !isLegacyPermissionDenied(context)

    /**
     * 现在能不能把文件发布到公共目录 `Download/BiliMiao`。
     *  - API 29+：MediaStore，永远可以（不需要权限）；
     *  - API 23~28：必须已授权 WRITE_EXTERNAL_STORAGE（拒绝了就老实待私有目录）；
     *  - API 21~22：安装即授予，可以。
     */
    fun canUsePublicStorage(context: Context): Boolean = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q -> true
        isLegacyRuntimePermission() -> hasLegacyWritePermission(context)
        else -> true
    }

    /**
     * 公共目录的人类可读路径，给 UI 显示"保存位置"用。
     * 只算路径，**不创建目录、不写测试文件**（每次打开下载页都 mkdir/写探针是没必要的 IO）。
     */
    @Suppress("DEPRECATION")
    fun publicDirPath(): String = File(
        Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
        PublicDownloadStore.ROOT,
    ).path
}
