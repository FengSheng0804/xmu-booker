package com.example.xmu_sports_booking.local

import android.os.Bundle
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import org.junit.Assume
import org.junit.Test
import org.junit.runner.RunWith

/** Reads own orders only; never prepares or re-submits any task. */
@RunWith(AndroidJUnit4::class)
class AutomaticReconciliationDeviceTest {
    @Test fun inspectInstalledStorageWithoutChangingOrders() {
        Assume.assumeTrue(InstrumentationRegistry.getArguments().getString("authorizeR61StorageRead") == "true")
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val stats = context.getSystemService(android.app.usage.StorageStatsManager::class.java)
            .queryStatsForUid(android.os.storage.StorageManager.UUID_DEFAULT, android.os.Process.myUid())
        instrumentation.sendStatus(0, Bundle().apply {
            putLong("app_bytes", stats.appBytes); putLong("data_bytes", stats.dataBytes)
            putLong("cache_bytes", stats.cacheBytes)
            putBoolean("authorization_preserved", com.example.xmu_sports_booking.capture.TokenVault(context).metadata().optBoolean("has_token"))
            putInt("plan_count", LocalDatabase.get(context).store().plans().size)
        })
    }

    @Test fun fetchSchoolOrderEntryPassWithoutSubmitting() {
        Assume.assumeTrue(InstrumentationRegistry.getArguments().getString("authorizeR61OrderRead") == "true")
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val engine = LocalEngine.get(instrumentation.targetContext)
        val task = engine.db.store().tasks().filter { it.state == "SUCCEEDED" && it.planId != it.id }
            .maxByOrNull { it.runAt } ?: error("NO_CONFIRMED_TASK")
        val id = JSONObject(task.receipt).getJSONObject("order").getString("order_id")
        var cancelled = false
        SchoolSession.open(instrumentation.targetContext).use { school ->
            val orders = school.orders(false)
            val owned = (0 until orders.length()).map { orders.getJSONObject(it) }
            cancelled = owned.firstOrNull { it.optString("order_id") == id }?.optString("audit_status_text") == "已取消"
            instrumentation.sendStatus(0, Bundle().apply {
                putString("selected_order_state", owned.firstOrNull { it.optString("order_id") == id }?.optString("audit_status_text") ?: "not_found")
                putString("order_state_counts", owned.groupingBy { it.optString("audit_status_text") }.eachCount().toString())
            })
            val detail = school.read("/index/stadium/orderDetails", mutableMapOf("order_id" to id, "order_num" to "")).getJSONObject("data")
            val rows = detail.optJSONArray("details")
            instrumentation.sendStatus(0, Bundle().apply {
                putBoolean("school_code_present", detail.optString("qrcode").isNotEmpty())
                putString("response_fields", detail.keys().asSequence().toList().sorted().joinToString(","))
                putInt("details_count", rows?.length() ?: -1)
                putString("details_type", detail.opt("details")?.javaClass?.simpleName ?: "absent")
                if (rows != null && rows.length() > 0) {
                    val row = rows.getJSONObject(0)
                    putString("detail_fields", row.keys().asSequence().toList().sorted().joinToString(","))
                    putString("detail_status_text", row.optString("status_text"))
                }
                putString("detail_states", (0 until (rows?.length() ?: 0)).joinToString(",") { rows!!.getJSONObject(it).optString("status") })
            })
        }
        if (cancelled) {
            org.junit.Assert.assertEquals("ENTRY_PASS_UNAVAILABLE",
                org.junit.Assert.assertThrows(Exception::class.java) { engine.orderEntryPass(id) }.message)
            instrumentation.sendStatus(0, Bundle().apply { putBoolean("cancelled_order_entry_hidden", true) })
            return
        }
        val passes = engine.orderEntryPass(id).getJSONArray("passes")
        instrumentation.sendStatus(0, Bundle().apply {
            putInt("order_entry_pass_count", passes.length())
            putBoolean("official_payload_shape", (0 until passes.length()).all {
                passes.getJSONObject(it).getString("payload").matches(Regex("[1-9][0-9]{0,31}\\|.+")) })
        })
    }

    @Test fun fetchConfirmedEntryPassWithoutSubmitting() {
        Assume.assumeTrue(InstrumentationRegistry.getArguments().getString("authorizeR60EntryRead") == "true")
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val engine = LocalEngine.get(instrumentation.targetContext)
        val task = engine.db.store().tasks().filter { it.state == "SUCCEEDED" && it.planId != it.id }
            .maxByOrNull { it.runAt } ?: error("NO_CONFIRMED_TASK")
        val passes = engine.entryPass(task.id).getJSONArray("passes")
        instrumentation.sendStatus(0, Bundle().apply {
            putInt("entry_pass_count", passes.length())
            putBoolean("official_payload_shape", (0 until passes.length()).all {
                passes.getJSONObject(it).getString("payload").matches(Regex("[1-9][0-9]{0,31}\\|.+")) })
        })
    }

    @Test fun reconcileExistingResultsWithoutSubmitting() {
        Assume.assumeTrue(InstrumentationRegistry.getArguments().getString("authorizeR58AutomaticLookup") == "true")
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val engine = LocalEngine.get(context)
        val before = engine.db.store().pendingReconciliation().map { it.id }.toSet()
        engine.reconcilePending()
        for (id in before) {
            val task = engine.db.store().task(id) ?: continue
            instrumentation.sendStatus(0, Bundle().apply {
                putString("result_state", task.state)
                putString("stadium", JSONObject(task.target).optString("stadium_name"))
                putString("date", task.date)
                putBoolean("success_reason", task.reason.contains("预约成功"))
            })
        }
    }

    @Test fun inspectLatestPendingResult() {
        Assume.assumeTrue(InstrumentationRegistry.getArguments().getString("authorizeR58OrderCheck") == "true")
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val task = LocalDatabase.get(context).store().tasks()
            .filter { it.state in setOf("SENT", "UNKNOWN") && it.planId != it.id }
            .maxByOrNull { it.runAt } ?: error("NO_PENDING_RESULT")
        val target = JSONObject(task.target)
        val receipt = JSONObject(task.receipt)
        SchoolSession.open(context).use { school ->
            val orders = school.orders(true)
            val matches = school.matchesTask(orders, target, receipt)
            instrumentation.sendStatus(0, Bundle().apply {
                putString("task_state", task.state)
                putString("stadium", target.optString("stadium_name"))
                putString("task_date", task.date)
                putInt("orders_read", orders.length())
                putInt("matched_orders", matches.length())
            })
            for (i in 0 until orders.length()) {
                val row = orders.getJSONObject(i)
                val details = row.optJSONArray("detail") ?: continue
                for (j in 0 until details.length()) {
                    val detail = details.getJSONObject(j)
                    if (detail.optString("date") != task.date) continue
                    instrumentation.sendStatus(0, Bundle().apply {
                        putString("order_fields", row.keys().asSequence().toList().sorted().joinToString(","))
                        putString("detail_fields", detail.keys().asSequence().toList().sorted().joinToString(","))
                        putString("order_status", row.optString("audit_status_text"))
                        putString("order_stadium", row.optString("stadium_name"))
                        putString("order_venue_id", row.optString("venue_id"))
                        putString("order_stadium_id", row.optString("stadium_id"))
                        putString("order_category_id", row.optString("category_id"))
                        putString("detail_status", detail.optString("status_text"))
                        putString("range", detail.optString("interval_time"))
                        putBoolean("same_names", row.optString("stadium_name") == target.optString("stadium_name") &&
                            row.optString("project_name") == target.optString("project_name"))
                    })
                }
            }
        }
    }
}
