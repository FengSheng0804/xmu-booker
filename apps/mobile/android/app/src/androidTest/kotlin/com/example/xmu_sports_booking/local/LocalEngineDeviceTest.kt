package com.example.xmu_sports_booking.local

import android.graphics.Bitmap
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.xmu_sports_booking.capture.SportsProtocol
import com.example.xmu_sports_booking.capture.TokenVault
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import java.util.TreeMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.json.JSONArray
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith

/** Runs in the separate .debug application; no real school HTTP is permitted in these tests. */
@RunWith(AndroidJUnit4::class)
class LocalEngineDeviceTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var db: LocalDatabase
    private lateinit var record: JSONObject
    private lateinit var digest: String
    private val day
        get() = LocalEngine.nowDate().toString()

    @Before
    fun setup() {
        Assume.assumeTrue(
            "Synthetic tests must use the separate debug package",
            context.packageName.endsWith(".debug"),
        )
        db =
            Room.inMemoryDatabaseBuilder(context, LocalDatabase::class.java)
                .allowMainThreadQueries()
                .build()
        val f =
            TreeMap(
                mapOf(
                    "timestamp" to "1793588880",
                    "nonce" to "123456",
                    "course_id" to "0",
                    "uid" to "4242",
                    "card_id" to "12345678",
                    "login_type" to "4",
                    "type" to "1",
                    "school_id" to "1",
                    "student_num" to "12345678",
                    "user_type" to "2",
                    "token" to "synthetic-test-token",
                )
            )
        f["sign"] = SportsProtocol.sign(f)
        record =
            JSONObject()
                .put("fields", JSONObject(f))
                .put("verified_at", System.currentTimeMillis())
                .put("orders_count", 0)
        TokenVault(context).save(record)
        digest =
            SchoolSession.hex(
                MessageDigest.getInstance("SHA-256").digest("1:4242:12345678".toByteArray())
            )
    }

    @After
    fun cleanup() {
        context
            .getSharedPreferences("local_booking", 0)
            .edit()
            .putBoolean("real_execution", false)
            .commit()
        TokenVault(context).clear()
        db.close()
    }

    private fun target() =
        JSONObject()
            .put("stadium_id", "16")
            .put("venue_id", "14")
            .put("category_id", "8")
            .put("stadium_name", "Synthetic Gym")
            .put("project_name", "Fitness")
            .put("date", day)
            .put("ranges", JSONArray().put("12:00-13:30"))
            .put("owner_digest", digest)

    private fun seed(): LocalDatabase.Task {
        db.store()
            .savePlan(
                LocalDatabase.Plan().apply {
                    id = "plan"
                    enabled = true
                    json =
                        JSONObject()
                            .put("weekdays", JSONObject())
                            .put("repeat", "ONCE")
                            .put("date", "2000-01-01")
                            .toString()
                }
            )
        return LocalDatabase.Task().apply {
            id = "task"
            planId = "plan"
            date = day
            target = target().toString()
            runAt = System.currentTimeMillis() - 1000
            prepareAt = runAt - 1000
            deadline = runAt + 60000
            updatedAt = runAt
            db.store().addTask(this)
        }
    }

    private inner class Wire(val loseResponse: Boolean = false, val foreign: Boolean = false) :
        SchoolSession.Wire {
        var submits = 0
        private var created = false

        override fun call(
            route: String,
            values: MutableMap<String, String>,
            cookie: String,
            referer: String,
            image: Boolean,
        ): SchoolSession.Reply {
            val data = JSONObject()
            val reply = SchoolSession.Reply(JSONObject().put("status", 1).put("data", data))
            when {
                route.endsWith("checklogin") -> reply.session = "synthetic-session"
                route.endsWith("mySubscribe") ->
                    reply.json.put(
                        "data",
                        JSONArray().apply {
                            if (created)
                                put(
                                    JSONObject()
                                        .put("order_id", "synthetic-order")
                                        .put("uid", if (foreign) "other" else "4242")
                                        .put("stadium_id", "16")
                                        .put("venue_id", "14")
                                        .put("category_id", "8")
                                        .put("stadium_name", "Synthetic Gym")
                                        .put("project_name", "Fitness")
                                        .put(
                                            "detail",
                                            JSONArray()
                                                .put(
                                                    JSONObject()
                                                        .put("date", day)
                                                        .put("interval_time", "12:00-13:30")
                                                        .put("area_name", "A")
                                                        .put("area_id", "67")
                                                        .put("status_text", "已预约")
                                                ),
                                        )
                                )
                        },
                    )
                route.endsWith("getStadiumDetails") ->
                    data
                        .put("name", "Synthetic Gym")
                        .put("user_range", 0)
                        .put(
                            "venue",
                            JSONArray("[{category_id:8,name:'Fitness',list:[{venue_id:14}]}]"),
                        )
                route.endsWith("getInterval") ->
                    reply.json.put(
                        "data",
                        JSONObject()
                            .put("is_can_choose", 1)
                            .put("personal_num", 10)
                            .put("venue_appointment_min_num", 0)
                            .put(
                                "time_slot_list",
                                JSONArray()
                                    .put(
                                        JSONObject()
                                            .put("date", day)
                                            .put("time_range", "12:00-13:30")
                                            .put("week", "5")
                                            .put("week_name", "周五")
                                            .put(
                                                "slots",
                                                JSONArray()
                                                    .put(
                                                        JSONObject()
                                                            .put("date", day)
                                                            .put("status", "available")
                                                            .put("selected", 1)
                                                            .put("price", 0)
                                                            .put("column_id", "67")
                                                            .put("interval_id", "81")
                                                            .put("area_name", "A")
                                                    ),
                                            )
                                    ),
                            ),
                    )
                route.endsWith("getVenueConfig") ->
                    data.put("info", JSONObject("{isCanAppoint:true,needRemark:0,verify_phone:0}"))
                route.endsWith("captcha") -> reply.image = ByteArray(32)
                route.endsWith("addOrder") -> {
                    submits++
                    created = true
                    if (loseResponse) error("SIMULATED_RESPONSE_LOSS")
                }
            }
            return reply
        }
    }

    private fun engine(wire: Wire) =
        LocalEngine(context, db, { SchoolSession(record, wire) }, { "AB12" })

    @Test
    fun unresolvedReceiptOutsideRecentUiHistoryStillBlocksSubmission() {
        val task = seed()
        db.store()
            .addTask(
                LocalDatabase.Task().apply {
                    id = "old-unknown"
                    planId = "old-plan"
                    date = day
                    target = target().toString()
                    state = "UNKNOWN"
                    runAt = task.runAt - 10000
                }
            )
        db.runInTransaction {
            repeat(501) { index ->
                db.store()
                    .addTask(
                        LocalDatabase.Task().apply {
                            id = "history-$index"
                            planId = "history-$index"
                            date = day
                            state = "SUCCEEDED"
                            runAt = task.runAt + 10000
                        }
                    )
            }
        }
        assertFalse(db.store().tasks().any { it.id == "old-unknown" })
        assertTrue(db.store().due(System.currentTimeMillis()).any { it.id == task.id })
        val wire = Wire()
        val engine = engine(wire)
        engine.setExecution(true)
        engine.execute(task.id)
        assertEquals(0, wire.submits)
        assertEquals("FAILED", db.store().task(task.id).state)
        assertTrue(db.store().task(task.id).reason.contains("PENDING_RECONCILIATION"))
    }

    @Test
    fun singleSubmissionConfirmsSchoolOrderAndRetryDoesNotSend() {
        val t = seed()
        val w = Wire()
        val e = engine(w)
        e.setExecution(true)
        e.execute(t.id)
        e.execute(t.id)
        assertEquals(1, w.submits)
        assertEquals("SUCCEEDED", db.store().task(t.id).state)
    }

    @Test
    fun lostResponseIsLookupOnlyAcrossEngineRestart() {
        val t = seed()
        val w = Wire(loseResponse = true)
        val e = engine(w)
        e.setExecution(true)
        e.execute(t.id)
        assertEquals("UNKNOWN", db.store().task(t.id).state)
        val resumed = engine(w)
        resumed.execute(t.id)
        assertEquals(1, w.submits)
        assertEquals("SUCCEEDED", db.store().task(t.id).state)
    }

    @Test
    fun wrongOwnerCannotConfirmAndDoesNotResubmit() {
        val t = seed()
        val w = Wire(foreign = true)
        val e = engine(w)
        e.setExecution(true)
        e.execute(t.id)
        e.execute(t.id)
        assertEquals(1, w.submits)
        assertEquals("UNKNOWN", db.store().task(t.id).state)
    }

    @Test
    fun expiredTaskDoesNotConnectOrSubmit() {
        val t = seed()
        db.store().clearTasks()
        t.deadline = System.currentTimeMillis() - 1
        db.store().addTask(t)
        val w = Wire()
        val e = engine(w)
        e.setExecution(true)
        e.execute(t.id)
        assertEquals(0, w.submits)
        assertEquals("EXPIRED", db.store().task(t.id).state)
    }

    @Test
    fun modelFailureNeverConsumesSendReceipt() {
        val t = seed()
        val w = Wire()
        val e =
            LocalEngine(
                context,
                db,
                { SchoolSession(record, w) },
                { error("MODEL_RUNTIME_UNAVAILABLE") },
            )
        e.setExecution(true)
        e.execute(t.id)
        assertEquals(0, w.submits)
        assertEquals("FAILED", db.store().task(t.id).state)
    }

    @Test
    fun concurrentClaimsAndStaleClaimCannotSend() {
        val t = seed()
        val ready = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(2)
        val one =
            pool.submit<Int> {
                ready.await()
                db.store().claim(t.id, 1, "claim-one")
            }
        val two =
            pool.submit<Int> {
                ready.await()
                db.store().claim(t.id, 1, "claim-two")
            }
        ready.countDown()
        assertEquals(1, one.get(5, TimeUnit.SECONDS) + two.get(5, TimeUnit.SECONDS))
        val winner = db.store().task(t.id).receipt
        assertEquals(0, db.store().markSent(t.id, "{}", 2, "stale-claim"))
        assertEquals(1, db.store().markSent(t.id, "{}", 2, winner))
        assertEquals(0, db.store().markSent(t.id, "{}", 2, winner))
        pool.shutdownNow()
    }

    @Test
    fun crashBeforeSendFailsClosedAndTransactionRollbackPreservesPending() {
        val t = seed()
        try {
            db.runInTransaction {
                db.store().claim(t.id, 1, "claim")
                error("crash")
            }
        } catch (_: Exception) {}
        assertEquals("PENDING", db.store().task(t.id).state)
        db.store().claim(t.id, 2, "claim")
        db.store().recover(3)
        assertEquals("UNKNOWN", db.store().task(t.id).state)
        db.store().refreshUnsent("plan", day, "{}", 0, 0, 0, 4)
        assertEquals("UNKNOWN", db.store().task(t.id).state)
    }

    @Test
    fun actualOnnxModelMatchesBlackImageReference() {
        val bitmap = Bitmap.createBitmap(160, 64, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(android.graphics.Color.BLACK)
        val stream = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream)
        bitmap.recycle()
        assertEquals("WDWW", Captcha(context).use { it.predict(stream.toByteArray()) })
    }

    @Test
    fun pairingCryptoLoadsAndInvalidPortsCannotConnect() {
        val provider =
            Class.forName("org.conscrypt.OpenSSLProvider").getDeclaredConstructor().newInstance()
                as java.security.Provider
        assertNotNull(javax.net.ssl.SSLContext.getInstance("TLSv1.3", provider))
        assertNotNull(
            Class.forName("org.conscrypt.Conscrypt")
                .getMethod(
                    "exportKeyingMaterial",
                    javax.net.ssl.SSLSocket::class.java,
                    String::class.java,
                    ByteArray::class.java,
                    Int::class.javaPrimitiveType,
                )
        )
        val adb = com.example.xmu_sports_booking.capture.PhoneAdb(context)
        assertThrows(Exception::class.java) { adb.pairLocal(0, "123456") }
        assertThrows(Exception::class.java) { adb.pairLocal(65536, "123456") }
        assertThrows(Exception::class.java) { adb.pairLocal(39000, "bad-code") }
        adb.disconnect()
    }

    @Test
    fun privateModelRegressionOnlyWhenExplicitlySupplied() {
        Assume.assumeTrue(
            InstrumentationRegistry.getArguments().getString("privateModelTest") == "true"
        )
        val assets = InstrumentationRegistry.getInstrumentation().context.assets
        val cases =
            JSONArray(assets.open("captcha-cases/manifest.json").use { String(it.readBytes()) })
        Captcha(context).use { model ->
            for (i in 0 until cases.length()) {
                val sample = cases.getJSONObject(i)
                val image =
                    assets.open("captcha-cases/" + sample.getString("name")).use { it.readBytes() }
                assertEquals(
                    "Native/reference prediction mismatch at case $i",
                    sample.getString("expected"),
                    model.predict(image),
                )
            }
        }
        assertTrue(cases.length() > 0)
    }

    @Test
    fun roomFileReopenPreservesPlansAndReceipts() {
        val name = "test-reopen.db"
        context.deleteDatabase(name)
        val first =
            Room.databaseBuilder(context, LocalDatabase::class.java, name)
                .allowMainThreadQueries()
                .build()
        first
            .store()
            .savePlan(
                LocalDatabase.Plan().apply {
                    id = "persisted"
                    json = "{\"name\":\"test\"}"
                    enabled = false
                }
            )
        first.close()
        val second =
            Room.databaseBuilder(context, LocalDatabase::class.java, name)
                .allowMainThreadQueries()
                .build()
        assertEquals("{\"name\":\"test\"}", second.store().plan("persisted").json)
        second.close()
        context.deleteDatabase(name)
    }

    @Test
    fun planSavePauseResumeAndDeleteRetainHistoryWithoutSending() {
        val date = LocalEngine.nowDate().plusDays(2)
        val p =
            JSONObject()
                .put("name", "Synthetic local plan")
                .put("venue", target())
                .put("repeat", "ONCE")
                .put("date", date.toString())
                .put(
                    "weekdays",
                    JSONObject()
                        .put(date.dayOfWeek.value.toString(), JSONArray().put("12:00-13:30")),
                )
                .put("release_time", "07:00:00")
                .put("execution_time", "07:00:00")
                .put("days_before", 1)
                .put("window_seconds", 120)
                .put("prepare_lead_seconds", 15)
                .put("priority", 1)
                .put("rule_confirmed", true)
                .put("enabled", true)
        val w = Wire()
        val e = engine(w)
        val id = e.savePlan(p)
        p.put("id", id)
        assertEquals(1, db.store().tasks().size)
        assertEquals("PENDING", db.store().tasks().first().state)
        e.savePlan(p.put("enabled", false))
        assertEquals("CANCELLED", db.store().tasks().first().state)
        e.savePlan(p.put("enabled", true))
        assertEquals("PENDING", db.store().tasks().first().state)
        e.deletePlan(id)
        assertNull(db.store().plan(id))
        assertEquals("CANCELLED", db.store().tasks().first().state)
        assertEquals(0, w.submits)
    }

    @Test
    fun weeklyCandidatesAreDifferentForEachVisitWeekday() {
        val today = LocalEngine.nowDate()
        val first = today.plusDays(2).dayOfWeek.value
        val second = today.plusDays(3).dayOfWeek.value
        val p =
            JSONObject()
                .put("name", "Weekly synthetic")
                .put("venue", target())
                .put("repeat", "WEEKLY")
                .put("date", today.plusDays(2).toString())
                .put(
                    "weekdays",
                    JSONObject()
                        .put(first.toString(), JSONArray().put("08:00-09:00"))
                        .put(second.toString(), JSONArray().put("09:00-10:00").put("10:00-11:00")),
                )
                .put("release_time", "07:00:00")
                .put("execution_time", "07:00:15")
                .put("days_before", 1)
                .put("window_seconds", 120)
                .put("prepare_lead_seconds", 15)
                .put("priority", 1)
                .put("rule_confirmed", true)
                .put("enabled", true)
        engine(Wire()).savePlan(p)
        assertTrue(db.store().tasks().size >= 2)
        for (task in db.store().tasks()) {
            val ranges = JSONObject(task.target).getJSONArray("ranges")
            if (java.time.LocalDate.parse(task.date).dayOfWeek.value == first)
                assertEquals("08:00-09:00", ranges.getString(0))
            else {
                assertEquals("09:00-10:00", ranges.getString(0))
                assertEquals(2, ranges.length())
            }
        }
    }

    @Test
    fun changedOwnerStopsPendingTaskBeforeAnySession() {
        val t = seed()
        val w = Wire()
        val e = engine(w)
        e.setExecution(true)
        val changed = JSONObject(record.toString())
        changed.getJSONObject("fields").put("uid", "different-owner")
        TokenVault(context).save(changed)
        e.execute(t.id)
        assertEquals(0, w.submits)
        assertEquals("FAILED", db.store().task(t.id).state)
    }

    @Test
    fun delayedVerificationCannotRestoreDeletedAuthorization() {
        val vault = TokenVault(context)
        val before = vault.loadSecret("sealed")
        vault.clear()
        assertFalse(vault.replaceRecordIfCurrent(before, record))
        assertFalse(vault.metadata().optBoolean("has_token"))
    }

    @Test
    fun benignConcurrentVerificationDoesNotInvalidateOtherReader() {
        val vault = TokenVault(context)
        val before = vault.loadSecret("sealed")
        assertTrue(vault.replaceRecordIfCurrent(before, record))
        assertTrue(vault.replaceRecordIfCurrent(before, record))
        vault.save(record) // an explicit new capture increments the authorization generation
        assertFalse(vault.replaceRecordIfCurrent(before, record))
    }

    @Test
    fun clearingDataDuringReadDoesNotRestoreOrderCache() {
        val entered = CountDownLatch(1)
        val resume = CountDownLatch(1)
        val wire =
            object : SchoolSession.Wire {
                override fun call(
                    route: String,
                    values: MutableMap<String, String>,
                    cookie: String,
                    referer: String,
                    image: Boolean,
                ): SchoolSession.Reply {
                    val reply =
                        SchoolSession.Reply(JSONObject().put("status", 1).put("data", JSONArray()))
                    if (route.endsWith("checklogin")) reply.session = "synthetic-session"
                    if (route.endsWith("mySubscribe")) {
                        entered.countDown()
                        resume.await(5, TimeUnit.SECONDS)
                    }
                    return reply
                }
            }
        val e = LocalEngine(context, db, { SchoolSession(record, wire) }, { "AB12" })
        val pool = Executors.newSingleThreadExecutor()
        val reading =
            pool.submit<Boolean> {
                try {
                    e.syncOrders()
                    false
                } catch (_: Exception) {
                    true
                }
            }
        assertTrue(entered.await(5, TimeUnit.SECONDS))
        e.clear()
        resume.countDown()
        assertTrue(reading.get(5, TimeUnit.SECONDS))
        assertNull(db.store().cached("orders"))
        assertFalse(TokenVault(context).metadata().optBoolean("has_token"))
        pool.shutdownNow()
    }

    @Test
    fun shortForegroundServiceExecutesSyntheticTaskWithScreenOff() {
        val wire = Wire()
        val e = engine(wire)
        e.initialize()
        val t = seed()
        db.store().clearTasks()
        t.runAt = System.currentTimeMillis() + 3000
        t.prepareAt = System.currentTimeMillis()
        t.deadline = t.runAt + 60000
        db.store().addTask(t)
        e.setExecution(true)
        LocalEngine.installTestInstance(e)
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        automation.adoptShellPermissionIdentity()
        try {
            LocalScheduler.start(context, t.id)
            for (i in 0 until 50) {
                if (LocalBookingService.active) break
                Thread.sleep(100)
            }
            assertTrue(LocalBookingService.active)
            automation.executeShellCommand("input keyevent 223").close()
            for (i in 0 until 100) {
                if (db.store().task(t.id).state == "SUCCEEDED" && !LocalBookingService.active) break
                Thread.sleep(100)
            }
            assertEquals("SUCCEEDED", db.store().task(t.id).state)
            assertEquals(1, wire.submits)
            assertFalse(LocalBookingService.active)
        } finally {
            context.stopService(android.content.Intent(context, LocalBookingService::class.java))
            automation.executeShellCommand("input keyevent 224").close()
            automation.dropShellPermissionIdentity()
            LocalEngine.installTestInstance(null)
        }
    }
}
