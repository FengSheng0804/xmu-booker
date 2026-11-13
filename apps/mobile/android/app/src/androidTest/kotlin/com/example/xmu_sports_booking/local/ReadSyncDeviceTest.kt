package com.example.xmu_sports_booking.local

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.xmu_sports_booking.capture.SportsProtocol
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.TreeMap
import java.util.concurrent.atomic.AtomicInteger

/** Synthetic protocol tests; the explicit opt-in probe performs reads only. */
@RunWith(AndroidJUnit4::class)
class ReadSyncDeviceTest {
    private fun reader(wire: SchoolSession.Wire): SchoolSession {
        val fields = TreeMap(mapOf("timestamp" to "1793588880", "nonce" to "123456",
            "course_id" to "0", "uid" to "4242", "card_id" to "12345678", "login_type" to "4",
            "type" to "1", "school_id" to "1", "student_num" to "12345678", "user_type" to "2", "token" to "synthetic-test-token"))
        fields["sign"] = SportsProtocol.sign(fields)
        val constructor = SchoolSession::class.java.getDeclaredConstructor(JSONObject::class.java,
            SchoolSession.Wire::class.java, String::class.java, JSONArray::class.java, Boolean::class.javaPrimitiveType)
        constructor.isAccessible = true
        val session = constructor.newInstance(JSONObject().put("fields", JSONObject(fields)), wire, "synthetic-session", null, true)
        SchoolSession::class.java.getDeclaredField("readOnly").apply { isAccessible = true }.setBoolean(session, true)
        return session
    }
    private fun reply(data: Any) = SchoolSession.Reply(JSONObject().put("status", 1).put("data", data))
    private fun order(id: String) = JSONObject().put("uid", "4242").put("order_id", id)
        .put("stadium_name", "Synthetic Pool").put("project_name", "Swimming")

    @Test fun parallelPagesKeepEveryOrderAndIgnoreAnUnneededPageFailure() {
        reader { route, values, _, _, _ ->
            check(route.endsWith("mySubscribe"))
            val page = values["p"]!!.toInt()
            if (page == 3) throw java.io.IOException("synthetic unused page")
            val rows = JSONArray()
            repeat(if (page == 1) 10 else 1) { rows.put(order("${page}_$it")) }
            reply(rows)
        }.use { session ->
            val orders = session.orders(false)
            assertEquals(11, orders.length())
            assertEquals("2_0", orders.getJSONObject(10).getString("order_id"))
        }
    }
    @Test fun cachedOwnershipAvoidsAnotherListScanButCancelledDetailCannotShowQr() {
        val lists = AtomicInteger()
        reader { route, _, _, _, _ ->
            if (route.endsWith("mySubscribe")) { lists.incrementAndGet(); reply(JSONArray().put(order("owned"))) }
            else {
                check(route.endsWith("orderDetails"))
                reply(order("owned").put("qrcode", "synthetic-code").put("details", JSONArray().put(
                    JSONObject().put("status", 3).put("date", "2026-11-13").put("interval_time", "12:00-14:00"))))
            }
        }.use { session ->
            session.orders(false)
            val before = lists.get()
            try { session.orderEntryPass("owned"); fail("cancelled code must be refused") }
            catch (e: Exception) { assertEquals("ENTRY_PASS_UNAVAILABLE", e.message) }
            assertEquals(before, lists.get())
            session.forkReadOnly().use { fork ->
                try { fork.read("/index/Stadium/addOrder", emptyMap()); fail("write refused") }
                catch (e: Exception) { assertEquals("READ_ROUTE_REFUSED", e.message) }
            }
        }
    }
    @Test fun metadataIsSharedButEachInventoryReadRemainsLive() {
        val details = AtomicInteger(); val intervals = AtomicInteger()
        val target = JSONObject().put("stadium_id", "12").put("venue_id", "10").put("category_id", "6")
            .put("stadium_name", "Synthetic Pool").put("project_name", "Swimming").put("date", "2026-11-13")
        reader { route, _, _, _, _ ->
            if (route.endsWith("getStadiumDetails")) {
                details.incrementAndGet()
                reply(JSONObject().put("name", "Synthetic Pool").put("venue", JSONArray().put(JSONObject()
                    .put("name", "Swimming").put("category_id", "6").put("list", JSONArray().put(JSONObject().put("venue_id", "10"))))))
            } else { check(route.endsWith("getInterval")); reply(JSONObject().put("revision", intervals.incrementAndGet())) }
        }.use { session ->
            assertEquals(1, session.inventoryForQuery(target).getInt("revision"))
            session.forkReadOnly().use { assertEquals(2, it.inventoryForQuery(target).getInt("revision")) }
            assertEquals(1, details.get()); assertEquals(2, intervals.get())
        }
    }
    @Test fun optInReadTimingProbe() {
        if (InstrumentationRegistry.getArguments().getString("R67ReadProbe") != "true") return
        val engine = LocalEngine.get(InstrumentationRegistry.getInstrumentation().targetContext)
        engine.initialize()
        fun <T> timed(label: String, block: () -> T): T {
            val start = android.os.SystemClock.elapsedRealtime()
            return block().also {
                InstrumentationRegistry.getInstrumentation().sendStatus(0, android.os.Bundle().apply {
                    putString("operation", label); putLong("elapsed_ms", android.os.SystemClock.elapsedRealtime() - start)
                })
            }
        }
        val venues = timed("catalog") { engine.syncCatalog() }
        if (venues.length() > 0) timed("availability") { engine.availability(venues.getJSONObject(0)) }
        val orders = timed("orders") { engine.syncOrders() }
        // Never create, pay for or cancel any order. Read just one existing entry credential if available.
        for (i in 0 until orders.length()) {
            val row = orders.getJSONObject(i)
            if (row.optString("audit_status_text") == "已预约") {
                try { timed("entry_pass") { engine.orderEntryPass(row.getString("order_id")) } }
                catch (e: Exception) { println("R67_TIMING entry_pass_unavailable ${e.javaClass.simpleName}") }
                break
            }
        }
    }
}
