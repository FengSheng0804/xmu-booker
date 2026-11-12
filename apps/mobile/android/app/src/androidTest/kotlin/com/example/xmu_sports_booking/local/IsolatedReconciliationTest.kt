package com.example.xmu_sports_booking.local

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.xmu_sports_booking.capture.SportsProtocol
import java.util.TreeMap
import java.util.UUID
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.Assert.*
import org.junit.runner.RunWith

/** In-memory DB + isolated preferences + synthetic identity. Never touches TokenVault or HTTP. */
@RunWith(AndroidJUnit4::class)
class IsolatedReconciliationTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val prefsName = "isolated_reconciliation_test_" + UUID.randomUUID()
    private lateinit var db: LocalDatabase
    private lateinit var engine: LocalEngine
    private lateinit var wire: Wire

    private fun target() = JSONObject()
        .put("stadium_id", "16").put("venue_id", "14").put("category_id", "8")
        .put("stadium_name", "Synthetic Gym").put("project_name", "Fitness")
        .put("date", "2026-11-13").put("owner_digest", "isolated-owner")
        .put("ranges", JSONArray().put("12:00-13:30").put("18:30-21:00"))

    private fun record(): JSONObject {
        val fields = TreeMap(mapOf("timestamp" to "1793588880", "nonce" to "123456",
            "course_id" to "0", "uid" to "4242", "card_id" to "12345678",
            "login_type" to "4", "type" to "1", "school_id" to "1",
            "student_num" to "12345678", "user_type" to "2", "token" to "synthetic-test-token"))
        fields["sign"] = SportsProtocol.sign(fields)
        return JSONObject().put("fields", JSONObject(fields))
    }

    private class Wire : SchoolSession.Wire {
        val rows = JSONArray()
        var reads = 0
        var failRead = false
        var afterRead: (() -> Unit)? = null
        override fun call(route: String, values: MutableMap<String, String>, cookie: String,
            referer: String, image: Boolean): SchoolSession.Reply {
            check(!image) { "IMAGE_MUST_NOT_BE_REQUESTED" }
            if (route.endsWith("checklogin")) return SchoolSession.Reply(
                JSONObject().put("status", 1)).also { it.session = "synthetic-session" }
            check(route == "/index/stadium/mySubscribe") { "WRITE_OR_UNEXPECTED_ROUTE_REFUSED" }
            reads++
            if (failRead) error("SIMULATED_READ_FAILURE")
            afterRead?.invoke()
            return SchoolSession.Reply(JSONObject().put("status", 1).put("data", rows))
        }
    }

    private fun order(id: String = "order", payment: Boolean = false) = target()
        .put("uid", "4242").put("order_id", id)
        .put("audit_status_text", if (payment) "待支付" else "已预约")
        .put("detail", JSONArray().put(JSONObject().put("date", "2026-11-13")
            .put("interval_time", "12:00-13:30").put("area_id", "67").put("area_name", "A")
            .put("status_text", if (payment) "待支付" else "已预约")))

    @Before fun setup() {
        db = Room.inMemoryDatabaseBuilder(context, LocalDatabase::class.java)
            .allowMainThreadQueries().build()
        wire = Wire()
        engine = LocalEngine(context, db, { SchoolSession(record(), wire) },
            { error("CAPTCHA_MUST_NOT_BE_USED") }, "isolated-owner", prefsName)
        db.store().savePlan(LocalDatabase.Plan().apply {
            id = "plan"; json = "{\"repeat\":\"ONCE\",\"date\":\"2026-11-13\"}"; enabled = false
        })
        db.store().addTask(LocalDatabase.Task().apply {
            id = "unknown"; planId = "plan"; date = "2026-11-13"; state = "UNKNOWN"
            target = target().toString(); receipt = "{\"claim\":\"old\"}"
        })
    }

    @After fun cleanup() {
        db.close()
        context.deleteSharedPreferences(prefsName)
    }

    @Test fun emptyLookupRetainsBarrierUntilExplicitConfirmationAndFreshSecondRead() {
        assertTrue(engine.inspectTask("unknown").getBoolean("can_release"))
        assertEquals("UNKNOWN", db.store().task("unknown").state)
        assertThrows(Exception::class.java) { engine.releaseTask("unknown", false) }
        assertEquals(1, wire.reads)
        val result = engine.releaseTask("unknown", true)
        assertEquals("FAILED", result.getString("state"))
        assertEquals(2, wire.reads)
        assertEquals(0, db.store().unresolved("2026-11-13").size)
        assertTrue(JSONObject(db.store().task("unknown").receipt).has("manual_resolution"))
    }

    @Test fun directReleaseWithoutLookupIsRejectedBeforeAnyRead() {
        assertThrows(Exception::class.java) { engine.releaseTask("unknown", true) }
        assertEquals(0, wire.reads)
        assertEquals("UNKNOWN", db.store().task("unknown").state)
    }

    @Test fun lateOrderWinsOverEarlierNoMatchResult() {
        engine.inspectTask("unknown")
        wire.rows.put(order())
        val result = engine.releaseTask("unknown", true)
        assertEquals("SUCCEEDED", result.getString("state"))
        assertFalse(result.getBoolean("can_release"))
        assertFalse(JSONObject(db.store().task("unknown").receipt).has("manual_resolution"))
    }

    @Test fun unpaidOrderIsRecognizedWithoutTreatingItAsMissing() {
        wire.rows.put(order(payment = true))
        val result = engine.inspectTask("unknown")
        assertEquals("PAYMENT_REQUIRED", result.getString("state"))
        assertFalse(result.getBoolean("can_release"))
    }

    @Test fun failedSecondReadDoesNotReleaseBarrier() {
        engine.inspectTask("unknown")
        wire.failRead = true
        assertThrows(Exception::class.java) { engine.releaseTask("unknown", true) }
        assertEquals("UNKNOWN", db.store().task("unknown").state)
    }

    @Test fun ambiguousOrdersCannotBeReleased() {
        wire.rows.put(order("one")).put(order("two"))
        val result = engine.inspectTask("unknown")
        assertEquals("UNKNOWN", result.getString("state"))
        assertFalse(result.getBoolean("can_release"))
        assertThrows(Exception::class.java) { engine.releaseTask("unknown", true) }
    }

    @Test fun activeTaskCannotBeReleasedOrInspected() {
        db.store().finish("unknown", "CLAIMED", "{}", "", 1)
        assertThrows(Exception::class.java) { engine.inspectTask("unknown") }
        assertEquals(0, wire.reads)
    }

    @Test fun syncingOrdersAutomaticallyResolvesUnknownDespiteChangedDisplayNames() {
        wire.rows.put(order().put("project_name", "Updated school display name"))
        engine.syncOrders()
        assertEquals("SUCCEEDED", db.store().task("unknown").state)
        assertTrue(db.store().task("unknown").reason.contains("预约成功"))
        assertFalse(db.store().plan("plan").enabled)
    }

    @Test fun realSchoolListShapeWithoutIdsIsAutomaticallyConfirmedFromReceiptArea() {
        val row = order()
        listOf("stadium_id", "venue_id", "category_id").forEach { row.remove(it) }
        row.getJSONArray("detail").getJSONObject(0).remove("area_id")
        db.store().finish("unknown", "UNKNOWN", JSONObject().put("range", "12:00-13:30")
            .put("detail", JSONObject().put("area_id", "67").put("area_name", "A")).toString(), "", 1)
        wire.rows.put(row)
        engine.syncOrders()
        assertEquals("SUCCEEDED", db.store().task("unknown").state)
    }

    @Test fun automaticLookupRecognizesLateOrderWithoutReSubmitting() {
        wire.afterRead = { if (wire.reads == 2) wire.rows.put(order()) }
        engine.reconcile(db.store().task("unknown"))
        assertEquals(2, wire.reads)
        assertEquals("SUCCEEDED", db.store().task("unknown").state)
    }

    @Test fun backgroundLookupLeavesMissingOrUnpaidOrdersDistinctFromSuccess() {
        engine.reconcilePending()
        assertEquals("UNKNOWN", db.store().task("unknown").state)
        wire.rows.put(order(payment = true))
        engine.reconcilePending()
        assertEquals("PAYMENT_REQUIRED", db.store().task("unknown").state)
        val previousReads = wire.reads
        engine.reconcilePending()
        assertEquals(previousReads, wire.reads)
    }

    @Test fun failedAutomaticReadDoesNotInventSuccessOrReleaseTheTask() {
        wire.failRead = true
        assertThrows(Exception::class.java) { engine.reconcilePending() }
        assertEquals("UNKNOWN", db.store().task("unknown").state)
    }

    @Test fun deletingExecutionDisplayPreservesReconciliationAndDuplicateBarrier() {
        assertEquals(1, engine.visibleExecutionTasks().size)
        engine.deleteExecutionRecord("unknown")
        assertTrue(engine.visibleExecutionTasks().isEmpty())
        assertEquals("UNKNOWN", db.store().task("unknown").state)
        assertEquals(1, db.store().unresolved("2026-11-13").size)
        wire.rows.put(order())
        engine.reconcilePending()
        assertEquals("SUCCEEDED", db.store().task("unknown").state)
        assertTrue(engine.visibleExecutionTasks().isEmpty())
    }

    @Test fun activeExecutionCannotBeDeleted() {
        db.store().finish("unknown", "SENT", "{}", "", 1)
        assertThrows(Exception::class.java) { engine.deleteExecutionRecord("unknown") }
        assertEquals(1, engine.visibleExecutionTasks().size)
        assertEquals("SENT", db.store().task("unknown").state)
    }

    @Test fun explicitlyReenabledUnsentTaskAppearsAgainWithItsNewResult() {
        db.store().finish("unknown", "CANCELLED", "{}", "", 1)
        engine.deleteExecutionRecord("unknown")
        assertTrue(engine.visibleExecutionTasks().isEmpty())
        db.store().finish("unknown", "PENDING", "{}", "", 2)
        assertEquals(1, engine.visibleExecutionTasks().size)
        db.store().finish("unknown", "SUCCEEDED", "{}", "", 3)
        assertEquals(1, engine.visibleExecutionTasks().size)
    }

    private fun completePlan(id: String): JSONObject {
        val day = LocalEngine.nowDate().plusDays(2)
        return JSONObject().put("id", id).put("name", "Synthetic edit")
            .put("venue", target()).put("repeat", "ONCE").put("date", day.toString())
            .put("weekdays", JSONObject().put(day.dayOfWeek.value.toString(), JSONArray().put("12:00-13:30")))
            .put("release_time", "07:00:00").put("execution_time", "07:00:00")
            .put("days_before", 1).put("window_seconds", 120).put("prepare_lead_seconds", 15)
            .put("priority", 1).put("rule_confirmed", true).put("owner_digest", "isolated-owner")
    }

    @Test fun savingAnEndedPlanCreatesFreshExecutionWithoutLosingOriginalReceipt() {
        val id = UUID.randomUUID().toString()
        val plan = completePlan(id)
        db.store().savePlan(LocalDatabase.Plan().apply { this.id = id; json = plan.toString(); enabled = false })
        db.store().addTask(LocalDatabase.Task().apply {
            this.id = "old-ended"; planId = id; date = plan.getString("date")
            state = "SUCCEEDED"; target = target().toString(); receipt = "{\"preserved\":true}"
        })
        assertEquals("SUCCEEDED", db.store().latestResult(id).state)
        val newId = engine.savePlan(JSONObject(plan.toString()).put("reset_execution", true))
        assertNotEquals(id, newId)
        assertNull(db.store().plan(id))
        assertTrue(db.store().plan(newId).enabled)
        assertNull(db.store().latestResult(newId))
        assertEquals("PENDING", db.store().taskForDate(newId, plan.getString("date")).state)
        assertEquals("SUCCEEDED", db.store().task("old-ended").state)
        assertTrue(JSONObject(db.store().task("old-ended").receipt).getBoolean("preserved"))
    }

    @Test fun failedEditSaveLeavesTheOriginalPlanAndResultUnchanged() {
        val id = UUID.randomUUID().toString()
        val plan = completePlan(id)
        db.store().savePlan(LocalDatabase.Plan().apply { this.id = id; json = plan.toString(); enabled = false })
        db.store().addTask(LocalDatabase.Task().apply {
            this.id = "original"; planId = id; date = plan.getString("date"); state = "FAILED"
        })
        assertThrows(Exception::class.java) { engine.savePlan(JSONObject(plan.toString())
            .put("reset_execution", true).put("window_seconds", 0)) }
        assertNotNull(db.store().plan(id))
        assertEquals("FAILED", db.store().latestResult(id).state)
        assertFalse(db.store().plan(id).enabled)
    }

    @Test fun missingEnabledFlagDefaultsToAnEnabledPlan() {
        val plan = completePlan(UUID.randomUUID().toString())
        val id = engine.savePlan(plan)
        assertTrue(db.store().plan(id).enabled)
        assertTrue(engine.enabled)
    }
}
