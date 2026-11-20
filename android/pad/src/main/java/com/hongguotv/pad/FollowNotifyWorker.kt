package com.hongguotv.pad

import android.Manifest
import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ListenableWorker
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

/**
 * 追更/预约【系统通知】。
 *
 * 后台每约 12 小时查一次「追更更新数」——复用 Store.checkFollowUpdates()（它就是
 * 侧栏未读角标用的同一个来源：对比服务端集数与本机基线，返回本轮新增条数）。
 * 有更新就发一条通知。全部静默容错：未登录 / 无网 / 无通知权限 / 接口失败都直接跳过，不崩。
 *
 * 不碰任何 UI 主流程：只读 Store/Api，写 followBadge 也是它原本就负责的计数。
 */
class FollowNotifyWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): ListenableWorker.Result {
        return try {
            // WorkManager 可能在「没有 Activity」的后台进程里拉起本进程：
            // Store / App 全局态都还没初始化，这里先补齐（都是从 BuildConfig 读的固定值，重复设置无副作用）。
            Store.init(applicationContext)
            App.state = Store
            App.playToken = BuildConfig.PLAY_TOKEN
            App.playBases = BuildConfig.DEFAULT_PLAY_BASES.split(',').map { it.trim() }.filter { it.isNotEmpty() }
            App.historyBase = BuildConfig.HISTORY_BASE
            App.deviceIid = BuildConfig.DEVICE_IID
            App.deviceId = BuildConfig.DEVICE_ID
            App.deviceCdid = BuildConfig.DEVICE_CDID
            if (!Store.loggedIn) return ListenableWorker.Result.success()
            val added = withContext(Dispatchers.IO) { Store.checkFollowUpdates() }
            if (added > 0) FollowNotify.notify(applicationContext, added)
            ListenableWorker.Result.success()
        } catch (t: Throwable) {
            if (t is kotlinx.coroutines.CancellationException) throw t
            Log.e("FollowNotify", "worker 失败：${t.message}")
            // 不重试：无网/上游风控时不反复打扰，等下一个周期自然再来
            ListenableWorker.Result.success()
        }
    }

    companion object {
        const val UNIQUE_NAME = "follow_notify_periodic"

        /** 调度周期检查（KEEP：已排过就保持不动，App 每次启动调用也不会重复排队）。 */
        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<FollowNotifyWorker>(12L, TimeUnit.HOURS)
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .build()
                )
                .build()
            runCatching {
                WorkManager.getInstance(context.applicationContext)
                    .enqueueUniquePeriodicWork(
                        UNIQUE_NAME,
                        ExistingPeriodicWorkPolicy.KEEP,
                        request,
                    )
            }
        }
    }
}

/** 通知渠道与发送（渠道名「追更提醒」）。全部包在容错里，权限被拒/系统限制都不崩。 */
object FollowNotify {
    private const val CHANNEL_ID = "follow_updates"
    private const val CHANNEL_NAME = "追更提醒"
    private const val NOTIFY_ID = 1001

    fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        runCatching {
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            val ch = NotificationChannel(CHANNEL_ID, CHANNEL_NAME, NotificationManager.IMPORTANCE_DEFAULT)
            ch.description = "追更的剧有新集时提醒"
            nm.createNotificationChannel(ch)
        }
    }

    /** Android 13+ 需要 POST_NOTIFICATIONS；没授权就返回 false（跳过发送，不崩）。 */
    fun canNotify(context: Context): Boolean {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            return false
        }
        return runCatching { NotificationManagerCompat.from(context).areNotificationsEnabled() }
            .getOrDefault(false)
    }

    @SuppressLint("MissingPermission")
    fun notify(context: Context, added: Int) {
        if (!canNotify(context)) return
        ensureChannel(context)
        runCatching {
            val intent = Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            }
            val pending = PendingIntent.getActivity(
                context, 0, intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            val text = if (added == 1) "有 1 部追更的剧更新了" else "有 $added 部追更的剧更新了"
            val notification = NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentTitle(CHANNEL_NAME)
                .setContentText(text)
                .setAutoCancel(true)
                .setContentIntent(pending)
                .build()
            NotificationManagerCompat.from(context).notify(NOTIFY_ID, notification)
        }
    }
}
