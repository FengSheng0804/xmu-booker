package com.example.xmu_sports_booking.local

import android.os.Bundle
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.xmu_sports_booking.capture.TokenVault
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume
import org.junit.Test
import org.junit.runner.RunWith

/** Opt-in prepare-only check. Never runs the synthetic fixture's token setup/cleanup. */
@RunWith(AndroidJUnit4::class)
class OfficialPreparationProbeTest {
    private class PreparationChecked : Exception()

    @Test
    fun soloSelectionMatchesOfficialPageWithoutCreatingAnOrder() {
        Assume.assumeTrue(InstrumentationRegistry.getArguments()
            .getString("realSchoolPrepareProbe") == "true")
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        check(context.packageName.endsWith(".debug"))
        val store = LocalDatabase.get(context).store()
        check(store.plans().none { it.enabled }) { "ACTIVE_PLAN_PRESENT" }
        check(store.tasks().none { it.state in setOf("PENDING", "CLAIMED", "SENT", "UNKNOWN") }) {
            "ACTIVE_TASK_PRESENT"
        }
        val task = store.tasks().first { it.state == "FAILED" }
        val target = JSONObject(task.target)
        // Use credentials only inside their owning app; never log or export them.
        val record = JSONObject(TokenVault(context).loadSecret("sealed") ?: error("AUTH_REQUIRED"))
        val http = SchoolSession.HttpWire()
        var checked = false
        val wire = SchoolSession.Wire { route, values, cookie, referer, image ->
            check(!image && route in setOf(
                "/index/index/checklogin", "/index/Stadium/getStadiumDetails",
                "/stadium/interval/getInterval", "/index/Stadium/getVenueConfig",
                "/index/stadium/chooseVerify")) { "PROBE_ROUTE_REFUSED" }
            val outgoing = java.util.TreeMap(values)
            if (route.endsWith("chooseVerify")) {
                check(!checked) { "ONE_PREPARE_ONLY" }
                checked = true
                // Also supports probing against the installed R50 app without replacing it.
                outgoing.remove("ids")
                outgoing.remove("category_id")
            }
            val reply = http.call(route, outgoing, cookie, referer, image)
            if (route.endsWith("chooseVerify")) {
                val status = reply.json?.optInt("status", -1) ?: -1
                val message = reply.json?.optString("info", "") ?: ""
                val illegalField = Regex("Illegal string offset ['\"]([A-Za-z_]+)['\"]")
                    .find(message)?.groupValues?.get(1) ?: ""
                instrumentation.sendStatus(0, Bundle().apply {
                    putInt("chooseVerify_status", status)
                    putString("request_fields", outgoing.keys.joinToString(","))
                    putString("illegal_string_field", illegalField)
                })
                if (status == 1) throw PreparationChecked()
            }
            reply
        }
        SchoolSession(record, wire).use { session ->
            val inventory = session.inventory(target)
            val ranges = target.getJSONArray("ranges")
            var detail: JSONObject? = null
            for (i in 0 until ranges.length()) {
                try {
                    detail = SchoolSession.select(inventory, target, ranges.getString(i))
                    break
                } catch (error: Exception) {
                    if (error.message != "NO_AVAILABLE_TARGET") throw error
                }
            }
            assertNotNull("NO_AVAILABLE_TARGET", detail)
            try {
                session.prepare(target, detail!!)
                fail("PROBE_DID_NOT_STOP_AFTER_SELECTION_CHECK")
            } catch (_: PreparationChecked) {
                assertTrue(checked)
            }
        }
        assertEquals("FAILED", store.task(task.id).state)
    }
}
