package com.example.xmu_sports_booking.local

import android.content.Intent
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.util.UUID
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** All plans are in memory; no real token, school connection, alarm or reservation is used. */
@RunWith(AndroidJUnit4::class)
class BackgroundSupervisionTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test fun supervisorRemainsForegroundWhileAppIsInBackground() {
        val db = Room.inMemoryDatabaseBuilder(context, LocalDatabase::class.java).build()
        val name = "isolated_reconciliation_test_supervision_" + UUID.randomUUID()
        context.getSharedPreferences(name, 0).edit().putBoolean("migrated", true)
            .putBoolean("automatic_execution_default", true).putBoolean("real_execution", true).commit()
        val engine = LocalEngine(context, db, { error("HTTP_FORBIDDEN") },
            { error("CAPTCHA_FORBIDDEN") }, "synthetic-owner", name)
        db.store().addTask(LocalDatabase.Task().apply {
            id = "synthetic-future"; planId = "synthetic"; date = "2099-01-01"
            prepareAt = System.currentTimeMillis() + 86400000; runAt = prepareAt + 15000
            deadline = runAt + 120000
        })
        try {
            LocalEngine.installTestInstance(engine)
            context.startForegroundService(Intent(context, LocalKeepAliveService::class.java))
            val until = System.currentTimeMillis() + 5000
            while (!LocalKeepAliveService.active && System.currentTimeMillis() < until) Thread.sleep(50)
            assertTrue(LocalKeepAliveService.active)
            InstrumentationRegistry.getInstrumentation().uiAutomation.performGlobalAction(
                android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_HOME)
            Thread.sleep(1500)
            assertTrue(LocalKeepAliveService.active)
            @Suppress("DEPRECATION")
            val services = context.getSystemService(android.app.ActivityManager::class.java).getRunningServices(100)
            assertTrue(services.any { it.service.className == LocalKeepAliveService::class.java.name && it.foreground })
            assertEquals("PENDING", db.store().task("synthetic-future").state)
        } finally {
            context.stopService(Intent(context, LocalKeepAliveService::class.java))
            val until = System.currentTimeMillis() + 5000
            while (LocalKeepAliveService.active && System.currentTimeMillis() < until) Thread.sleep(50)
            LocalEngine.installTestInstance(null)
            db.close(); context.deleteSharedPreferences(name)
            LocalScheduler.schedule(context)
        }
    }

    @Test fun cleanupRejectsOutsidePathsAndPreservesOtherFiles() {
        val root = File(context.cacheDir, "cleanup-test-" + UUID.randomUUID()).apply { mkdirs() }
        try {
            val keep = File(root, "keep.db").apply { writeText("preserved") }
            val temp = File(root, "temporary").apply { mkdirs() }
            File(temp, "old.bin").writeBytes(ByteArray(2048))
            assertEquals(2048L, LocalStorage.removeOwnedTree(root, temp))
            assertFalse(temp.exists()); assertEquals("preserved", keep.readText())
            assertThrows(IllegalStateException::class.java) { LocalStorage.removeOwnedTree(root, root.parentFile!!) }
        } finally { root.deleteRecursively() }
    }

    @Test fun activeMonitoringHoldsAndReleasesItsCpuWakeLock() {
        val db = Room.inMemoryDatabaseBuilder(context, LocalDatabase::class.java).build()
        val name = "isolated_reconciliation_test_monitor_wake_" + UUID.randomUUID()
        context.getSharedPreferences(name, 0).edit().putBoolean("migrated", true)
            .putBoolean("automatic_execution_default", true).putBoolean("real_execution", true).commit()
        val engine = LocalEngine(context, db, { error("HTTP_FORBIDDEN") },
            { error("CAPTCHA_FORBIDDEN") }, "synthetic-owner", name)
        val date = LocalEngine.nowDate().plusDays(1)
        val plan = org.json.JSONObject().put("id", UUID.randomUUID().toString()).put("name", "Synthetic monitoring")
            .put("repeat", "ONCE").put("date", date.toString()).put("execution_mode", "MONITOR")
            .put("monitor_start_at", System.currentTimeMillis()-1000).put("monitor_end_at", System.currentTimeMillis()+7200000)
            .put("poll_seconds", 30).put("venue", org.json.JSONObject("{stadium_id:16,venue_id:14,category_id:8,stadium_name:'Synthetic Gym',project_name:'Fitness'}"))
            .put("weekdays", org.json.JSONObject().put(date.dayOfWeek.value.toString(), org.json.JSONArray().put("16:30-18:00")))
            .put("release_time", "00:00:00").put("execution_time", "00:00:00").put("days_before", 0)
            .put("window_seconds", 120).put("prepare_lead_seconds", 0).put("priority", 1).put("rule_confirmed", true)
        engine.savePlan(plan)
        db.openHelper.writableDatabase.execSQL("UPDATE tasks SET prepareAt=?", arrayOf(System.currentTimeMillis()+600000))
        fun hasWake(): Boolean {
            val fd = InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand("dumpsys power")
            val dump = android.os.ParcelFileDescriptor.AutoCloseInputStream(fd).bufferedReader().use { it.readText() }
            return dump.lineSequence().any { it.contains("PARTIAL_WAKE_LOCK") && it.contains("xmu:monitoring") }
        }
        try {
            LocalEngine.installTestInstance(engine)
            context.startForegroundService(Intent(context, LocalKeepAliveService::class.java))
            val until = System.currentTimeMillis()+5000
            while (!hasWake() && System.currentTimeMillis()<until) Thread.sleep(100)
            assertTrue(hasWake())
            assertEquals("PENDING", db.store().tasks().single().state)
        } finally {
            context.stopService(Intent(context, LocalKeepAliveService::class.java))
            val until = System.currentTimeMillis()+5000
            while (LocalKeepAliveService.active && System.currentTimeMillis()<until) Thread.sleep(100)
            assertFalse(hasWake())
            LocalEngine.installTestInstance(null)
            db.close(); context.deleteSharedPreferences(name)
            LocalScheduler.schedule(context)
        }
    }
}
