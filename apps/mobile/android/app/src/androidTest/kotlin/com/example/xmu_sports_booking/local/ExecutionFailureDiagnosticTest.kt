package com.example.xmu_sports_booking.local

import android.os.Bundle
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import org.junit.Assume
import org.junit.Test
import org.junit.runner.RunWith

/** Opt-in diagnostic of the failed task's read/preparation stages. Never calls submit or execute. */
@RunWith(AndroidJUnit4::class)
class ExecutionFailureDiagnosticTest {
    @Test fun inspectModelRuntimeWithoutSchoolRequests() {
        Assume.assumeTrue(InstrumentationRegistry.getArguments().getString("authorizeR64Inspect") == "true")
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val image = android.graphics.Bitmap.createBitmap(160, 64, android.graphics.Bitmap.Config.ARGB_8888)
        image.eraseColor(android.graphics.Color.WHITE)
        val buffer = java.io.ByteArrayOutputStream()
        image.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, buffer); image.recycle()
        try {
            Captcha(instrumentation.targetContext).use { model ->
                val prediction = model.predict(buffer.toByteArray())
                instrumentation.sendStatus(0, Bundle().apply {
                    putString("phase", "SYNTHETIC_MODEL_SELF_CHECK")
                    putBoolean("model_runtime_ok", prediction.matches(Regex("[A-Za-z0-9]{4}")))
                })
            }
        } catch (error: Exception) {
            instrumentation.sendStatus(0, Bundle().apply {
                putString("phase", "SYNTHETIC_MODEL_SELF_CHECK")
                putString("error_type", error.javaClass.name)
            })
        }
    }

    @Test fun inspectLatestFailureWithoutSubmitting() {
        Assume.assumeTrue(InstrumentationRegistry.getArguments().getString("authorizeR64Inspect") == "true")
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val task = LocalDatabase.get(context).store().tasks()
            .filter { it.reason.contains("LOCAL_OPERATION_FAILED") }.maxByOrNull { it.updatedAt }
            ?: error("NO_GENERIC_FAILURE")
        val target = JSONObject(task.target)
        var phase = "VERIFY_AUTHORIZATION"
        fun report(extra: Bundle = Bundle()) {
            extra.putString("phase", phase)
            instrumentation.sendStatus(0, extra)
        }
        try {
            report()
            SchoolSession.open(context).use { school ->
                phase = "READ_BASELINE_ORDERS"; report()
                val orders = school.orders(true)
                val matches = school.matchesTask(orders, target, JSONObject(task.receipt))
                report(Bundle().apply {
                    putInt("orders_count", orders.length())
                    putInt("matching_orders", matches.length())
                })
                if (matches.length() > 0) { phase = "EXISTING_TARGET_ORDER_FOUND"; report(); return@use }
                phase = "READ_INVENTORY"; report()
                val inventory = school.inventory(target)
                report(Bundle().apply {
                    putInt("is_can_choose", inventory.optInt("is_can_choose", -1))
                    putInt("time_rows", inventory.optJSONArray("time_slot_list")?.length() ?: -1)
                    putString("capacity", inventory.optString("personal_num"))
                    val rows = inventory.optJSONArray("time_slot_list")
                    val status = mutableListOf<String>()
                    if (rows != null) for (i in 0 until rows.length()) {
                        val row = rows.getJSONObject(i)
                        if (row.optString("date") != target.optString("date") ||
                            (0 until target.getJSONArray("ranges").length()).none {
                                target.getJSONArray("ranges").getString(it) == SchoolSession.normalizedRange(row.optString("time_range")) }) continue
                        val slots = row.optJSONArray("slots") ?: org.json.JSONArray().put(row)
                        for (j in 0 until slots.length()) {
                            val slot = slots.getJSONObject(j)
                            status.add("${slot.optString("status")}:${slot.optString("selected")}")
                        }
                    }
                    putString("target_slot_status_and_selected", status.joinToString(","))
                })
                phase = "SELECT_SLOT"; report()
                val ranges = target.getJSONArray("ranges")
                var selected: JSONObject? = null
                for (i in 0 until ranges.length()) {
                    try { selected = SchoolSession.select(inventory, target, ranges.getString(i)); break }
                    catch (error: Exception) { if (error.message != "NO_AVAILABLE_TARGET") throw error }
                }
                check(selected != null) { "NO_AVAILABLE_TARGET" }
                phase = "PREPARE_WITHOUT_SUBMITTING"; report()
                val image = school.prepare(target, selected)
                phase = "RECOGNIZE_CAPTCHA"; report()
                Captcha(context).use { model ->
                    val prediction = model.predict(image)
                    report(Bundle().apply { putBoolean("prediction_shape_valid", prediction.matches(Regex("[A-Za-z0-9]{4}"))) })
                }
                phase = "PRE_SUBMISSION_CHECKS_COMPLETED"; report()
            }
        } catch (error: Exception) {
            report(Bundle().apply {
                putString("error_type", error.javaClass.name)
                putString("error_code", error.message?.takeIf { it.matches(Regex("[A-Z_]{3,64}")) } ?: "UNCLASSIFIED")
                putString("cause_type", error.cause?.javaClass?.name ?: "none")
                putString("app_sites", error.stackTrace.filter { it.className.startsWith("com.example.xmu_sports_booking") }
                    .take(4).joinToString(",") { "${it.className.substringAfterLast('.')}.${it.methodName}:${it.lineNumber}" })
                putString("known_response_field", listOf("data", "detail", "details", "selected", "price", "personal_num", "time_slot_list", "slots")
                    .firstOrNull { (error.message ?: "").contains(it) } ?: "unknown")
            })
        }
    }
}
