package cn.a10miaomiao.bilimiao.download

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.NotificationCompat
import cn.a10miaomiao.bilimiao.download.entry.CurrentDownloadInfo

class DownloadNotify(val context: Context) {
    val ACTION_CMD = "cn.a10miaomiao.bilimiao.download.DownloadNotify"
    val notificationID = 10000
    val channelId = "cn.a10miaomiao.bilimiao.download.DownloadNotify.control"
    val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    /** 下载列表页真实存在的深链（compose 侧 BilimiaoPageRoute 注册的是 bilimiao://download）。
     *  原来拼的 bilimiao://compose?url=... 全仓没有任何路由处理，点通知没反应。 */
    private val downloadListUri = Uri.parse("bilimiao://download")

    val builder = NotificationBuilder(context, channelId).apply {
        setContentIntent(getPendingIntent(downloadListUri))
        setSmallIcon(android.R.drawable.stat_sys_download)
        priority = NotificationCompat.PRIORITY_DEFAULT
        setOnlyAlertOnce(true)
        setOngoing(true)
    }

    init {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val mChannel = NotificationChannel(channelId, "DownloadControl", NotificationManager.IMPORTANCE_DEFAULT)
            manager.createNotificationChannel(mChannel)
        }
    }

    /**
     * 构建前台服务常驻通知：与进度通知共用同一个 notificationID，
     * 这样 notifyData() 刷新的就是同一条通知，不会出现两条。
     */
    fun buildForegroundNotification(): Notification {
        // 前台服务通知必须有内容，模块内没有 strings 资源，标题用应用名
        builder.setContentTitle(context.applicationInfo.loadLabel(context.packageManager))
        builder.setContentText("正在下载")
        return builder.build()
    }

    fun notifyData(info: CurrentDownloadInfo) {
        if (builder.taskId == info.taskId) {
            builder.setContentText(info.statusText)
            builder.setProgress(info.size.toInt(), info.progress.toInt(), false)
        } else {
            builder.setContentTitle(info.name)
            builder.setContentText(info.statusText)
            builder.setProgress(info.size.toInt(), info.progress.toInt(), false)
            builder.taskId = info.taskId
        }
        val notification = builder.build()
        manager.notify(notificationID, notification)
    }

    fun showCompletedStatusNotify(info: CurrentDownloadInfo) {
        manager.notify(
            notificationID + info.taskId.toInt(),
            NotificationCompat.Builder(context, channelId).apply {
                // 下载详情页没有注册 deepLink（只有 DownloadListPage 注册了 bilimiao://download），
                // 所以完成通知退化到下载列表页，不再用无人处理的 bilimiao://compose
                setContentIntent(getPendingIntent(downloadListUri))
                setContentTitle(info.name)
                setContentText("下载完成")
                setSmallIcon(R.drawable.ic_baseline_file_download_done_24)
            }.build()
        )
    }

    fun showErrorStatusNotify(info: CurrentDownloadInfo) {
        manager.notify(
            notificationID + info.taskId.toInt(),
            NotificationCompat.Builder(context, channelId).apply {
                // 同完成通知：详情页无 deepLink，统一跳下载列表页
                setContentIntent(getPendingIntent(downloadListUri))
                setContentTitle(info.name)
                setContentText("下载出错")
                setSmallIcon(R.drawable.ic_baseline_error_24)
            }.build()
        )
    }

    fun cancel() {
        manager.cancel(notificationID)
    }

    private fun getPendingIntent(
        uri: Uri,
    ): PendingIntent {
        val intent = Intent(Intent.ACTION_VIEW).also {
            it.data = uri
        }
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            PendingIntent.getActivity(context, 0, intent, PendingIntent.FLAG_IMMUTABLE)
        } else {
            PendingIntent.getActivity(context, 0, intent, 0)
        }
    }

    class NotificationBuilder(
        context: Context,
        channelId: String,
    ) : NotificationCompat.Builder(context, channelId) {
        var taskId = 0L
    }

}