package com.example.xmu_sports_booking.local

import android.app.ActivityManager
import android.app.job.JobScheduler
import android.os.Bundle
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.UUID
import org.json.*
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith

/** Explicit opt-in, synthetic-owner fixture: stillAllowed fails before opening any school session. */
@RunWith(AndroidJUnit4::class)
class BackgroundRecoveryDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val prefs get() = context.getSharedPreferences("r65_background_fixture", 0)
    private fun mode(value: String) = Assume.assumeTrue(
        InstrumentationRegistry.getArguments().getString("authorizeR65Recovery") == value)

    @Test fun prepareSyntheticTaskAndRemoveUi() {
        mode("prepare")
        val engine = LocalEngine.get(context); engine.initialize()
        val dao = engine.db.store()
        check(dao.plans().none { it.enabled } && dao.activeUnsent().isEmpty() && dao.pendingReconciliation().isEmpty()) { "REAL_ACTIVE_WORK_PRESENT" }
        check(LocalScheduler.notifications(context) && LocalScheduler.exact(context)) { "PERMISSIONS_REQUIRED" }
        val id = UUID.randomUUID().toString()
        val run = ZonedDateTime.now(LocalEngine.zone).plusSeconds(45).withNano(0)
        val day = run.toLocalDate().plusDays(1)
        val p = JSONObject().put("id", id).put("diagnostic_fixture", "R65_BACKGROUND")
            .put("name", "后台恢复验证（不会预约）").put("owner_digest", "synthetic-background-" + id)
            .put("venue", JSONObject("{stadium_id:16,venue_id:14,category_id:8,stadium_name:'Synthetic Gym',project_name:'Fitness'}"))
            .put("repeat", "ONCE").put("date", day.toString()).put("enabled", true)
            .put("execution_mode", "TIMED").put("weekdays", JSONObject().put(day.dayOfWeek.value.toString(), JSONArray().put("16:30-18:00")))
            .put("release_time", "07:00:00").put("execution_time", run.format(DateTimeFormatter.ofPattern("HH:mm:ss")))
            .put("days_before", 1).put("window_seconds", 180).put("prepare_lead_seconds", 0).put("priority", 1).put("rule_confirmed", true)
        dao.savePlan(LocalDatabase.Plan().apply { this.id = id; json = p.toString(); enabled = true; updatedAt = System.currentTimeMillis() })
        prefs.edit().putString("plan", id).commit()
        engine.generate(); LocalScheduler.schedule(context)
        val task = dao.taskForDate(id, day.toString())
        prefs.edit().putString("task", task.id).commit()
        val claimed = InstrumentationRegistry.getArguments().getString("simulateUnsentClaim") == "true"
        if (claimed) {
            assertEquals(1, dao.claim(task.id, System.currentTimeMillis(), "{\"claim\":\"synthetic-unsent\"}"))
            LocalScheduler.schedule(context)
        }
        assertNotNull(context.getSystemService(JobScheduler::class.java).getPendingJob(LocalRecoveryJob.ID))
        instrumentation.runOnMainSync {
            context.getSystemService(ActivityManager::class.java).appTasks.forEach { it.finishAndRemoveTask() }
        }
        instrumentation.sendStatus(0, Bundle().apply {
            putString("fixture_task", task.id); putLong("run_at", task.runAt)
            putBoolean("recovery_job_registered", true)
            putBoolean("fixture_claimed_before_exit", claimed)
            putInt("fixture_process", android.os.Process.myPid())
        })
    }

    @Test fun inspectSyntheticExecutionAfterProcessExit() {
        mode("inspect")
        val task = LocalDatabase.get(context).store().task(prefs.getString("task", "")!!) ?: error("FIXTURE_MISSING")
        val failure = JSONObject(task.receipt).getJSONObject("failure")
        assertEquals("FAILED", task.state)
        assertEquals("CHECK_PLAN", failure.getString("phase"))
        assertEquals("EXECUTION_STOPPED", failure.getString("code"))
        instrumentation.sendStatus(0, Bundle().apply {
            putString("fixture_state", task.state); putString("stopped_before_school_session", failure.getString("phase"))
            putLong("delay_after_run_ms", task.updatedAt - task.runAt)
        })
    }

    @Test fun cleanupSyntheticFixtureOnly() {
        mode("cleanup")
        val engine = LocalEngine.get(context)
        val id = prefs.getString("plan", null) ?: return
        val plan = engine.db.store().plan(id) ?: error("FIXTURE_MISSING")
        val json = JSONObject(plan.json)
        check(json.optString("diagnostic_fixture") == "R65_BACKGROUND" && json.optString("owner_digest") == "synthetic-background-" + id)
        val task = engine.db.store().tasks().firstOrNull { it.planId == id }
        engine.db.runInTransaction {
            engine.db.openHelper.writableDatabase.execSQL("DELETE FROM tasks WHERE planId=?", arrayOf(id))
            engine.db.store().deletePlan(id)
        }
        task?.let { context.getSystemService(android.app.NotificationManager::class.java).cancel(it.id.hashCode()) }
        prefs.edit().clear().commit(); LocalScheduler.schedule(context)
    }
}
