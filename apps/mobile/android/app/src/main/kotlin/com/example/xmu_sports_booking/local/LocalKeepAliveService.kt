package com.example.xmu_sports_booking.local

import android.app.*
import android.content.*
import android.os.*
import java.util.concurrent.Executors

/** Visible supervision while scheduled plans exist. Idle supervision holds no wake lock. */
class LocalKeepAliveService : Service() {
    companion object {
        @Volatile var active = false
            private set
        @Volatile private var running: LocalKeepAliveService? = null

        fun sync(context: Context, needed: Boolean) {
            val intent = Intent(context, LocalKeepAliveService::class.java)
            if (!needed) { if (active) context.stopService(intent); return }
            if (active) { running?.updateWake(LocalEngine.get(context).activeMonitor()); return }
            if (!active) try { context.startForegroundService(intent) }
            catch (_: Exception) {
                // A background launch restriction must not break a saved plan or its exact alarm.
                android.util.Log.w("XmuLocal", "Background supervisor start deferred by system")
            }
        }
    }

    private val worker = Executors.newSingleThreadScheduledExecutor()
    private var monitoringWake: PowerManager.WakeLock? = null
    private lateinit var currentNotification: Notification
    private var monitorActive = false
    override fun onBind(intent: Intent?) = null

    override fun onCreate() {
        super.onCreate()
        val channel = "local-plan-supervision"
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(channel, "预约后台守护", NotificationManager.IMPORTANCE_LOW))
        val open = PendingIntent.getActivity(this, 4101,
            packageManager.getLaunchIntentForPackage(packageName),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        currentNotification = Notification.Builder(this, channel)
            .setSmallIcon(android.R.drawable.ic_menu_today).setContentTitle("预约后台守护已开启")
            .setContentText("等待计划执行，到时自动预约；可在计划菜单暂停")
            .setContentIntent(open).setOngoing(true).setOnlyAlertOnce(true).build()
        startForeground(4101, currentNotification)
        active = true
        monitoringWake = getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "xmu:monitoring").apply { setReferenceCounted(false) }
        running = this
        worker.scheduleWithFixedDelay({
            try {
                val engine = LocalEngine.get(this)
                engine.initialize()
                engine.generate()
                LocalScheduler.schedule(this)
                updateWake(engine.activeMonitor())
                if (active && !LocalBookingService.active && engine.enabled &&
                    LocalScheduler.notifications(this) &&
                    engine.db.store().due(System.currentTimeMillis() + 1000).isNotEmpty())
                    LocalScheduler.start(this)
                engine.requestReconciliation()
            } catch (_: Exception) {
                android.util.Log.w("XmuLocal", "Background supervision check deferred")
            }
        }, 0, 15, java.util.concurrent.TimeUnit.SECONDS)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int) = START_STICKY

    @Synchronized private fun updateWake(monitoring: Boolean) {
        val enabled = active && monitoring
        val wake = monitoringWake
        if (enabled) wake?.acquire(120_000L) else if (wake?.isHeld == true) wake.release()
        if (active && enabled != monitorActive) {
            monitorActive = enabled
            currentNotification = Notification.Builder.recoverBuilder(this, currentNotification)
                .setContentText(if (enabled) "正在监控所选时段，有空位后自动预约；可在App暂停"
                    else "等待计划执行，到时自动预约；可在计划菜单暂停").build()
            getSystemService(NotificationManager::class.java).notify(4101, currentNotification)
        }
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        worker.execute { LocalScheduler.schedule(this) }
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        active = false
        running = null
        worker.shutdownNow()
        updateWake(false)
        stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }
}
