package com.example.xmu_sports_booking.local

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.xmu_sports_booking.capture.SportsProtocol
import java.util.TreeMap
import java.util.UUID
import org.json.*
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith

/** In-memory school simulation: no real authorization, alarms, requests or reservations. */
@RunWith(AndroidJUnit4::class)
class MonitoringEngineTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val prefsName = "isolated_reconciliation_test_monitor_" + UUID.randomUUID()
    private lateinit var db: LocalDatabase
    private lateinit var engine: LocalEngine
    private var selected = 10
    private var submits = 0
    private var preparations = 0
    private var failSubmit = false
    private var failInventory = false
    private val orders = JSONArray()
    private fun record(): JSONObject {
        val f = TreeMap(mapOf("timestamp" to "1793588880", "nonce" to "123456", "course_id" to "0",
            "uid" to "4242", "card_id" to "12345678", "login_type" to "4", "type" to "1",
            "school_id" to "1", "student_num" to "12345678", "user_type" to "2", "token" to "synthetic"))
        f["sign"] = SportsProtocol.sign(f)
        return JSONObject().put("fields", JSONObject(f))
    }
    private val wire = SchoolSession.Wire { route, _, _, _, _ ->
        val result = SchoolSession.Reply(JSONObject().put("status", 1).put("data", JSONObject()))
        when {
            route.endsWith("checklogin") -> result.session = "synthetic-session"
            route.endsWith("mySubscribe") -> result.json.put("data", orders)
            route.endsWith("getStadiumDetails") -> result.json.put("data", JSONObject("{name:'Synthetic Gym',user_range:0,venue:[{category_id:8,name:'Fitness',list:[{venue_id:14}]}]}"))
            route.endsWith("getInterval") -> {
                if (failInventory) throw java.net.SocketTimeoutException("private query")
                result.json.put("data", JSONObject("{is_can_choose:1,personal_num:10,time_slot_list:[{date:'2026-11-14',week:6,week_name:'Saturday',time_range:'16:30-18:00',slots:[{date:'2026-11-14',status:'available',selected:$selected,price:0,area_name:'A',column_id:67,interval_id:81}]}]}"))
            }
            route.endsWith("getVenueConfig") -> result.json.put("data", JSONObject("{info:{isCanAppoint:true,needRemark:0,verify_phone:0}}"))
            route.endsWith("chooseVerify") -> preparations++
            route.endsWith("captcha") -> result.image = ByteArray(32)
            route.endsWith("addOrder") -> {
                submits++
                if (failSubmit) throw java.net.SocketTimeoutException("private submit")
                orders.put(JSONObject("{uid:'4242',order_id:'synthetic-order',stadium_id:16,venue_id:14,category_id:8,stadium_name:'Synthetic Gym',project_name:'Fitness',audit_status_text:'已预约',detail:[{date:'2026-11-14',interval_time:'16:30-18:00',area_id:67,area_name:'A',status:1}]}"))
            }
            else -> error("UNEXPECTED_ROUTE")
        }
        result
    }
    private fun plan(id: String): JSONObject = JSONObject()
        .put("id", id).put("name", "Synthetic monitor").put("repeat", "ONCE").put("date", "2026-11-14")
        .put("venue", JSONObject("{stadium_id:16,venue_id:14,category_id:8,stadium_name:'Synthetic Gym',project_name:'Fitness'}"))
        .put("weekdays", JSONObject().put("6", JSONArray().put("16:30-18:00")))
        .put("owner_digest", "synthetic-owner").put("execution_mode", "MONITOR").put("poll_seconds", 30)
        .put("monitor_start_at", System.currentTimeMillis() - 1000).put("monitor_end_at", System.currentTimeMillis() + 3600000)
        .put("release_time", "00:00:00").put("execution_time", "00:00:00").put("days_before", 0)
        .put("window_seconds", 120).put("prepare_lead_seconds", 0).put("priority", 1).put("enabled", true).put("rule_confirmed", true)
    @Before fun setup() {
        db = Room.inMemoryDatabaseBuilder(context, LocalDatabase::class.java).allowMainThreadQueries().build()
        engine = LocalEngine(context, db, { SchoolSession(record(), wire) }, { "ABCD" }, "synthetic-owner", prefsName)
        engine.savePlan(plan(UUID.randomUUID().toString()))
    }
    @After fun cleanup() { db.close(); context.deleteSharedPreferences(prefsName) }
    private fun task() = db.store().tasks().single()
    private fun dueNow() { db.openHelper.writableDatabase.execSQL("UPDATE tasks SET prepareAt=0 WHERE state='PENDING'") }

    @Test fun fullSlotStaysPendingAndAReleasedPlaceIsBookedOnlyOnce() {
        engine.execute(task().id)
        assertEquals("PENDING", task().state); assertEquals(0, submits); assertEquals(0, preparations)
        val deferred = task().prepareAt
        engine.generate(); assertEquals(deferred, task().prepareAt)
        selected = 9
        engine.execute(task().id); assertEquals(0, submits) // Rate limit survives a repeated click.
        dueNow(); engine.execute(task().id)
        assertEquals("SUCCEEDED", task().state); assertEquals(1, submits)
        engine.execute(task().id); engine.generate(); assertEquals(1, submits)
        assertFalse(db.store().plans().single().enabled)
    }
    @Test fun monitoringTimeoutBacksOffWithoutClosingThePlan() {
        failInventory = true; engine.execute(task().id)
        assertEquals("PENDING", task().state); assertTrue(task().reason.contains("网络请求超时"))
        assertTrue(db.store().plans().single().enabled); assertEquals(0, submits)
        assertEquals(1, JSONObject(task().receipt).getJSONObject("monitor").getInt("consecutive_errors"))
    }
    @Test fun unknownSubmitNeverReturnsToMonitoringOrAutomaticallyResubmits() {
        selected = 9; failSubmit = true; engine.execute(task().id)
        assertEquals("UNKNOWN", task().state); assertEquals(1, submits)
        engine.generate(); assertEquals("UNKNOWN", task().state); assertEquals(1, submits)
    }
    @Test fun processRecoveryRestoresOnlyUnsentClaimsAndPreservesSentBarrier() {
        db.store().claim(task().id, System.currentTimeMillis(), "claim")
        db.store().recover(System.currentTimeMillis())
        assertEquals("PENDING", task().state)
        db.store().claim(task().id, System.currentTimeMillis(), "claim")
        db.store().markSent(task().id, "{}", System.currentTimeMillis(), "claim")
        db.store().recover(System.currentTimeMillis())
        assertEquals("UNKNOWN", task().state)
    }
    @Test fun pausedMonitoringNeverQueriesOrSubmitsAgain() {
        engine.execute(task().id)
        engine.savePlan(plan(task().planId).put("enabled", false))
        selected = 9; engine.execute(task().id)
        assertEquals("CANCELLED", task().state); assertEquals(0, submits)
    }
    @Test fun monitoringCutoffEndsTheTaskWithoutAnotherQueryOrSubmit() {
        db.openHelper.writableDatabase.execSQL("UPDATE tasks SET deadline=?, prepareAt=0", arrayOf(System.currentTimeMillis()-1))
        engine.execute(task().id)
        assertEquals("EXPIRED", task().state); assertEquals(0, submits); assertEquals(0, preparations)
        assertFalse(db.store().plans().single().enabled)
    }
}
