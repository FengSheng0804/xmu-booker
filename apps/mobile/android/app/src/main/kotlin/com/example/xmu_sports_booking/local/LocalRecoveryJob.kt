package com.example.xmu_sports_booking.local

import android.app.job.*
import android.content.*
import android.os.PersistableBundle
import java.util.concurrent.Executors

/** Persisted fallback for a removed task or lost process; exact alarms remain the primary timer. */
class LocalRecoveryJob : JobService() {
    companion object {
        const val ID = 4102
        fun schedule(context: Context, task: LocalDatabase.Task?) {
            val jobs = context.getSystemService(JobScheduler::class.java)
            if (task == null) { jobs.cancel(ID); return }
            val previous = jobs.getPendingJob(ID)?.extras
            if (previous?.getString("task") == task.id && previous.getLong("prepare_at") == task.prepareAt) return
            val extras = PersistableBundle().apply { putString("task", task.id); putLong("prepare_at", task.prepareAt) }
            val delay = maxOf(60_000L, task.prepareAt - System.currentTimeMillis() + 60_000L)
            jobs.schedule(JobInfo.Builder(ID, ComponentName(context, LocalRecoveryJob::class.java))
                .setPersisted(true).setMinimumLatency(delay).setOverrideDeadline(delay + 60_000L)
                .setExtras(extras).build())
        }
    }
    private val worker = Executors.newSingleThreadExecutor()
    override fun onStartJob(params: JobParameters): Boolean {
        worker.execute {
            try {
                val engine = LocalEngine.get(this)
                engine.initialize(); engine.generate(); engine.requestReconciliation()
                if (engine.enabled && LocalScheduler.notifications(this) && !LocalBookingService.active &&
                    engine.db.store().due(System.currentTimeMillis() + 1000).isNotEmpty()) {
                    try { LocalScheduler.start(this) } catch (_: Exception) {
                        LocalScheduler.notifyResult(this, "后台执行受系统限制，请打开 App 检查后台权限", 4141)
                    }
                }
                LocalScheduler.schedule(this)
            } finally { jobFinished(params, false) }
        }
        return true
    }
    override fun onStopJob(params: JobParameters) = true
    override fun onDestroy() { worker.shutdownNow(); super.onDestroy() }
}
