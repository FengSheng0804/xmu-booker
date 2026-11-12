package com.example.xmu_sports_booking.local

import android.Manifest
import android.app.*
import android.content.*
import android.content.pm.PackageManager
import android.os.*
import java.util.concurrent.Executors
import org.json.JSONObject

object LocalScheduler {
    private const val CHANNEL = "local-booking"

    fun notifications(context: Context): Boolean =
        (Build.VERSION.SDK_INT < 33 ||
            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) ==
                PackageManager.PERMISSION_GRANTED) &&
            context.getSystemService(NotificationManager::class.java).areNotificationsEnabled()

    fun exact(context: Context): Boolean =
        Build.VERSION.SDK_INT < 31 ||
            context.getSystemService(AlarmManager::class.java).canScheduleExactAlarms()

    fun permissions(context: Context): JSONObject =
        JSONObject()
            .put("notifications", notifications(context))
            .put("exact_alarm", exact(context))
            .put(
                "battery_unrestricted",
                context
                    .getSystemService(PowerManager::class.java)
                    .isIgnoringBatteryOptimizations(context.packageName),
            )

    fun notification(context: Context, text: String): Notification {
        context
            .getSystemService(NotificationManager::class.java)
            .createNotificationChannel(
                NotificationChannel(CHANNEL, "本地预约", NotificationManager.IMPORTANCE_DEFAULT)
            )
        val launch = context.packageManager.getLaunchIntentForPackage(context.packageName)!!
        val pending =
            PendingIntent.getActivity(
                context,
                0,
                launch,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
        return Notification.Builder(context, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_menu_today)
            .setContentTitle("厦大体育预约")
            .setContentText(text)
            .setContentIntent(pending)
            .setAutoCancel(true)
            .build()
    }

    fun notifyResult(context: Context, text: String, id: Int) {
        if (notifications(context))
            context
                .getSystemService(NotificationManager::class.java)
                .notify(id, notification(context, text))
    }

    fun schedule(context: Context) {
        val alarm = context.getSystemService(AlarmManager::class.java)
        val pending =
            PendingIntent.getBroadcast(
                context,
                41,
                Intent(context, LocalAlarmReceiver::class.java).setAction("LOCAL_BOOKING_ALARM"),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
        alarm.cancel(pending)
        val engine = LocalEngine.get(context)
        if (!engine.enabled || !exact(context) || !notifications(context)) return
        val next = engine.db.store().next() ?: return
        // One exact wake starts the preparation window; no second alarm inside Doze is needed.
        alarm.setExactAndAllowWhileIdle(
            AlarmManager.RTC_WAKEUP,
            maxOf(System.currentTimeMillis() + 1000, next.prepareAt),
            pending,
        )
    }

    fun start(context: Context, id: String? = null) {
        context.startForegroundService(
            Intent(context, LocalBookingService::class.java).apply {
                if (id != null) putExtra("task_id", id)
            }
        )
    }
}

class LocalAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        try {
            LocalScheduler.start(context)
        } catch (_: Exception) {
            LocalScheduler.notifyResult(context, "系统未允许后台执行，请打开App检查", 4141)
        }
    }
}

class LocalRestoreReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val result = goAsync()
        Thread {
                try {
                    LocalEngine.get(context).initialize()
                    LocalEngine.get(context).generate()
                    LocalScheduler.schedule(context)
                } finally {
                    result.finish()
                }
            }
            .start()
    }
}

class LocalBookingService : Service() {
    companion object {
        @Volatile
        var active = false
            private set
    }

    private val executor = Executors.newSingleThreadExecutor()
    private var wake: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?) = null

    override fun onCreate() {
        super.onCreate()
        active = true
        startForeground(4100, LocalScheduler.notification(this, "正在执行本机预约窗口，可在App中暂停"))
        wake =
            getSystemService(PowerManager::class.java)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "xmu:booking")
                .apply { acquire(3_720_000L) }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        executor.execute {
            val engine = LocalEngine.get(this)
            try {
                engine.initialize()
                val requested = intent?.getStringExtra("task_id")
                if (requested != null) engine.execute(requested)
                else {
                    val due =
                        engine.db
                            .store()
                            .due(System.currentTimeMillis() + 1000)
                            .sortedWith(
                                compareBy<LocalDatabase.Task> { it.runAt }
                                    .thenBy { JSONObject(it.target).optInt("priority", 1) }
                            )
                    for (task in due) engine.execute(task.id)
                }
            } finally {
                engine.generate()
                LocalScheduler.schedule(this)
                stopSelfResult(startId)
            }
        }
        return START_NOT_STICKY
    }

    override fun onTimeout(startId: Int, fgsType: Int) {
        stopSelf()
    }

    override fun onDestroy() {
        active = false
        executor.shutdownNow()
        wake?.let { if (it.isHeld) it.release() }
        super.onDestroy()
    }
}
