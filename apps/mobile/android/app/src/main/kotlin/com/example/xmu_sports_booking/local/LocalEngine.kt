package com.example.xmu_sports_booking.local

import android.content.Context
import com.example.xmu_sports_booking.capture.TokenVault
import java.time.*
import java.util.UUID
import org.json.JSONArray
import org.json.JSONObject

/** All UI and service writes share this process-wide lock; Room gates survive process death. */
class LocalEngine
internal constructor(
    private val context: Context,
    val db: LocalDatabase = LocalDatabase.get(context),
    private val sessions: () -> SchoolSession = { SchoolSession.open(context) },
    private val predictor: (ByteArray) -> String = { image ->
        Captcha(context).use { it.predict(image) }
    },
) {
    private val dao
        get() = db.store()

    private val prefs = context.getSharedPreferences("local_booking", 0)
    private var initialized = false

    private fun ownerDigest(): String {
        val raw = TokenVault(context).loadSecret("sealed") ?: error("AUTH_REQUIRED")
        val f = JSONObject(raw).getJSONObject("fields")
        return SchoolSession.hex(
            java.security.MessageDigest.getInstance("SHA-256")
                .digest(
                    (f.getString("school_id") +
                            ":" +
                            f.getString("uid") +
                            ":" +
                            f.getString("student_num"))
                        .toByteArray()
                )
        )
    }

    val enabled
        get() = prefs.getBoolean("real_execution", false)

    companion object {
        val zone: ZoneId = ZoneId.of("Asia/Shanghai")
        @Volatile private var instance: LocalEngine? = null

        internal fun installTestInstance(value: LocalEngine?) {
            check(com.example.xmu_sports_booking.BuildConfig.DEBUG) { "TEST_ONLY" }
            instance = value
        }

        fun get(context: Context): LocalEngine =
            instance
                ?: synchronized(this) {
                    instance ?: LocalEngine(context.applicationContext).also { instance = it }
                }

        fun nowDate(): LocalDate = LocalDate.now(zone)

        fun executionWindow(p: JSONObject, day: LocalDate): Pair<Long, Long> {
            val opened =
                day.minusDays(p.getLong("days_before"))
                    .atTime(LocalTime.parse(p.getString("release_time")))
                    .atZone(zone)
            var run =
                opened
                    .toLocalDate()
                    .atTime(LocalTime.parse(p.getString("execution_time")))
                    .atZone(zone)
            val end = opened.plusSeconds(p.getLong("window_seconds"))
            if (run < opened && end.toLocalDate() > opened.toLocalDate()) run = run.plusDays(1)
            require(run >= opened && run < end) { "EXECUTION_WINDOW_INVALID" }
            return Pair(run.toInstant().toEpochMilli(), end.toInstant().toEpochMilli())
        }

        fun jsonPlan(p: LocalDatabase.Plan) =
            JSONObject(p.json).put("id", p.id).put("enabled", p.enabled)

        fun jsonTask(t: LocalDatabase.Task) =
            JSONObject()
                .put("id", t.id)
                .put("plan_id", t.planId)
                .put("date", t.date)
                .put("target", JSONObject(t.target))
                .put("state", t.state)
                .put("reason", t.reason)
                .put("run_at", t.runAt)
                .put("deadline", t.deadline)
    }

    @Synchronized
    fun initialize() {
        if (initialized) return
        initialized = true
        if (!prefs.getBoolean("migrated", false)) {
            // Old project login/server data is obsolete. Keep own_sports_token and its Keystore
            // alias.
            context.getSharedPreferences("FlutterSecureStorage", 0).edit().clear().commit()
            context.getSharedPreferences("FlutterSharedPreferences", 0).edit().clear().commit()
            prefs.edit().putBoolean("migrated", true).putBoolean("real_execution", false).commit()
        }
        dao.recover(System.currentTimeMillis())
        generate()
    }

    @Synchronized
    fun home(): JSONObject {
        val owner = runCatching { ownerDigest() }.getOrNull()
        val previous = prefs.getString("active_owner", null)
        if (owner == null || (previous != null && owner != previous)) {
            prefs.edit().putBoolean("real_execution", false).commit()
            if (owner != null && previous != null && owner != previous) {
                for (plan in dao.plans()) {
                    plan.enabled = false
                    dao.savePlan(plan)
                    dao.cancelPending(plan.id, System.currentTimeMillis())
                }
                dao.clearCache()
            }
        }
        if (owner != null) prefs.edit().putString("active_owner", owner).commit()
        generate()
        LocalScheduler.schedule(context)
        val metadata =
            runCatching { TokenVault(context).metadata() }
                .getOrElse { JSONObject().put("has_token", false) }
        return JSONObject()
            .put("authorization", metadata)
            .put("execution_enabled", enabled)
            .put("plans", JSONArray(dao.plans().map { jsonPlan(it) }))
            .put("tasks", JSONArray(dao.tasks().map { jsonTask(it) }))
            .put(
                "messages",
                JSONArray(
                    dao.messages().map {
                        JSONObject()
                            .put("id", it.id)
                            .put("text", it.text)
                            .put("created_at", it.createdAt)
                    }
                ),
            )
            .put("orders", JSONArray(dao.cached("orders")?.json ?: "[]"))
            .put("venues", JSONArray(dao.cached("venues")?.json ?: "[]"))
            .put("orders_updated_at", dao.cached("orders")?.updatedAt ?: 0)
            .put("venues_updated_at", dao.cached("venues")?.updatedAt ?: 0)
            .put("permissions", LocalScheduler.permissions(context))
    }

    @Synchronized
    fun setExecution(value: Boolean) {
        if (value) check(TokenVault(context).metadata().optBoolean("has_token")) { "AUTH_REQUIRED" }
        check(prefs.edit().putBoolean("real_execution", value).commit())
        generate()
        LocalScheduler.schedule(context)
    }

    private fun cache(key: String, value: JSONArray) {
        dao.cache(
            LocalDatabase.Cache().apply {
                this.key = key
                json = value.toString()
                updatedAt = System.currentTimeMillis()
            }
        )
    }

    private fun epoch() = prefs.getLong("data_epoch", 0)

    private fun cacheCurrent(key: String, value: JSONArray, owner: String, epoch: Long) =
        synchronized(this) {
            check(epoch() == epoch && ownerDigest() == owner) { "AUTHORIZATION_CHANGED" }
            cache(key, value)
        }

    fun syncOrders(): JSONArray {
        val owner = ownerDigest()
        val epoch = epoch()
        return sessions().use { session ->
            session.orders(true).also { cacheCurrent("orders", it, owner, epoch) }
        }
    }

    fun syncCatalog(): JSONArray {
        val owner = ownerDigest()
        val epoch = epoch()
        return sessions().use { session ->
            session.initializeHome() // Home initialization has no assumed stadium-list wire shape.
            val result = JSONArray()
            for ((stadium, venue, name) in
                listOf(Triple("16", "14", "翔安校区健身房"), Triple("6", "6", "思明校区健身房"))) {
                val target =
                    JSONObject()
                        .put("stadium_id", stadium)
                        .put("venue_id", venue)
                        .put("category_id", "8")
                        .put("stadium_name", name)
                        .put("project_name", "健身房")
                        .put("date", nowDate().toString())
                val inventory = session.inventory(target)
                result.put(JSONObject(target.toString()).put("inventory", inventory))
            }
            cacheCurrent("venues", result, owner, epoch)
            result
        }
    }

    fun availability(target: JSONObject): JSONObject = sessions().use { it.inventory(target) }

    fun validate(plan: JSONObject) {
        require(plan.getString("name").length in 1..80) { "INVALID_PLAN" }
        SchoolSession.targetFields(plan.getJSONObject("venue"))
        require(
            plan.getJSONObject("venue").getString("stadium_name").length in 1..100 &&
                plan.getJSONObject("venue").getString("project_name").length in 1..100
        ) {
            "INVALID_PLAN"
        }
        require(plan.getString("repeat") in setOf("ONCE", "WEEKLY")) { "INVALID_PLAN" }
        LocalDate.parse(plan.getString("date"))
        LocalTime.parse(plan.getString("release_time"))
        LocalTime.parse(plan.getString("execution_time"))
        require(
            plan.getInt("days_before") in 0..14 &&
                plan.getInt("window_seconds") in 15..3600 &&
                plan.getInt("prepare_lead_seconds") in 0..120
        ) {
            "INVALID_PLAN"
        }
        executionWindow(plan, nowDate())
        require(plan.getInt("priority") in 1..99) { "INVALID_PLAN" }
        val weekly = plan.getJSONObject("weekdays")
        require(weekly.length() in 1..7) { "INVALID_PLAN" }
        weekly.keys().forEach { day ->
            require(day.toInt() in 1..7) { "INVALID_PLAN" }
            val ranges = weekly.getJSONArray(day)
            require(ranges.length() in 1..3) { "INVALID_PLAN" }
            val unique = mutableSetOf<String>()
            for (i in 0 until ranges.length()) {
                val range = ranges.getString(i)
                require(
                    range.matches(
                        Regex("(?:[01][0-9]|2[0-3]):[0-5][0-9]-(?:[01][0-9]|2[0-3]):[0-5][0-9]")
                    )
                ) {
                    "INVALID_PLAN"
                }
                val (start, end) = range.split('-')
                require(LocalTime.parse(start) < LocalTime.parse(end) && unique.add(range)) {
                    "INVALID_PLAN"
                }
            }
        }
        if (plan.getString("repeat") == "ONCE")
            require(
                weekly.has(LocalDate.parse(plan.getString("date")).dayOfWeek.value.toString())
            ) {
                "INVALID_PLAN"
            }
        require(!plan.optBoolean("enabled") || plan.optBoolean("rule_confirmed")) {
            "RULE_CONFIRMATION_REQUIRED"
        }
    }

    @Synchronized
    fun savePlan(plan: JSONObject): String {
        validate(plan)
        val id = plan.optString("id").ifEmpty { UUID.randomUUID().toString() }
        UUID.fromString(id)
        val saved =
            LocalDatabase.Plan().apply {
                this.id = id
                json =
                    JSONObject(plan.toString())
                        .put("id", id)
                        .put("owner_digest", ownerDigest())
                        .toString()
                enabled = plan.optBoolean("enabled")
                updatedAt = System.currentTimeMillis()
            }
        db.runInTransaction {
            dao.cancelPending(id, System.currentTimeMillis())
            dao.savePlan(saved)
        }
        generate()
        LocalScheduler.schedule(context)
        return id
    }

    @Synchronized
    fun deletePlan(id: String) {
        db.runInTransaction {
            dao.cancelPending(id, System.currentTimeMillis())
            dao.deletePlan(id)
        }
        LocalScheduler.schedule(context)
    }

    @Synchronized
    fun generate() {
        for (plan in dao.plans().filter { it.enabled }) {
            val p = JSONObject(plan.json)
            val weekly = p.getJSONObject("weekdays")
            val days =
                if (p.getString("repeat") == "ONCE") listOf(LocalDate.parse(p.getString("date")))
                else (0L..14L).map { nowDate().plusDays(it) }
            for (day in days) {
                val ranges = weekly.optJSONArray(day.dayOfWeek.value.toString()) ?: continue
                val window = executionWindow(p, day)
                if (window.second <= System.currentTimeMillis()) continue
                val target =
                    JSONObject(p.getJSONObject("venue").toString())
                        .put("date", day.toString())
                        .put("ranges", ranges)
                        .put("priority", p.getInt("priority"))
                        .put("owner_digest", p.getString("owner_digest"))
                val task =
                    LocalDatabase.Task().apply {
                        id = UUID.nameUUIDFromBytes((plan.id + ":" + day).toByteArray()).toString()
                        planId = plan.id
                        date = day.toString()
                        this.target = target.toString()
                        runAt = window.first
                        deadline = window.second
                        prepareAt = runAt - p.getLong("prepare_lead_seconds") * 1000
                        updatedAt = System.currentTimeMillis()
                    }
                if (dao.addTask(task) == -1L)
                    dao.refreshUnsent(
                        plan.id,
                        task.date,
                        task.target,
                        task.prepareAt,
                        task.runAt,
                        task.deadline,
                        task.updatedAt,
                    )
            }
        }
    }

    fun message(task: LocalDatabase.Task, text: String) {
        dao.message(
            LocalDatabase.Message().apply {
                id = task.id + ":" + task.state
                text.let { this.text = it }
                createdAt = System.currentTimeMillis()
            }
        )
        LocalScheduler.notifyResult(context, text, task.id.hashCode())
    }

    private fun finish(
        task: LocalDatabase.Task,
        state: String,
        reason: String,
        receipt: JSONObject = JSONObject(task.receipt),
    ) {
        if (dao.task(task.id) == null) return
        dao.finish(task.id, state, receipt.toString(), reason, System.currentTimeMillis())
        task.state = state
        task.receipt = receipt.toString()
        message(task, reason)
    }

    private fun stillAllowed(task: LocalDatabase.Task) {
        check(
            enabled &&
                dao.plan(task.planId)?.enabled == true &&
                System.currentTimeMillis() < task.deadline &&
                ownerDigest() == JSONObject(task.target).getString("owner_digest")
        ) {
            "EXECUTION_STOPPED"
        }
    }

    /** Run on one worker thread. Waiting does not hold the mutation lock so the user can pause. */
    fun execute(id: String) {
        val task = synchronized(this) { dao.task(id) ?: return }
        if (task.state in setOf("SENT", "UNKNOWN", "CLAIMED")) {
            reconcile(task)
            return
        }
        val claim = JSONObject().put("claim", UUID.randomUUID().toString()).toString()
        synchronized(this) {
            if (!enabled) return
            if (System.currentTimeMillis() >= task.deadline) {
                finish(task, "EXPIRED", "已超过执行窗口")
                return
            }
            if (dao.claim(id, System.currentTimeMillis(), claim) != 1) return
            task.receipt = claim
        }
        var sent = false
        try {
            stillAllowed(task)
            sessions().use { school ->
                val target = JSONObject(task.target)
                val ranges = target.getJSONArray("ranges")
                val baseline = school.orders(true)
                // Unknown same-venue/day receipts are a barrier even for a newly copied plan.
                check(
                    dao.unresolved(task.date).none {
                        it.id != id &&
                            JSONObject(it.target).optString("venue_id") ==
                                target.getString("venue_id")
                    }
                ) {
                    "PENDING_RECONCILIATION"
                }
                for (i in 0 until ranges.length()) {
                    val matches = school.matches(baseline, target, ranges.getString(i), null)
                    check(matches.length() <= 1) { "AMBIGUOUS_ORDERS" }
                    if (matches.length() == 1) {
                        synchronized(this) {
                            finish(
                                task,
                                "SUCCEEDED",
                                "学校已有对应预约",
                                JSONObject().put("order", matches.getJSONObject(0)),
                            )
                        }
                        return
                    }
                }
                while (System.currentTimeMillis() < task.runAt) {
                    stillAllowed(task)
                    Thread.sleep(
                        minOf(250L, task.runAt - System.currentTimeMillis()).coerceAtLeast(1)
                    )
                }
                stillAllowed(task)
                val inventory = school.inventory(target)
                var detail: JSONObject? = null
                var range = ""
                for (i in 0 until ranges.length()) try {
                    range = ranges.getString(i)
                    detail = SchoolSession.select(inventory, target, range)
                    break
                } catch (e: Exception) {
                    if (e.message != "NO_AVAILABLE_TARGET") throw e
                }
                check(detail != null) { "NO_AVAILABLE_TARGET" }
                val selected = detail
                val image = school.prepare(target, selected)
                val prediction = predictor(image)
                val receipt =
                    JSONObject()
                        .put("detail", selected)
                        .put("range", range)
                        .put(
                            "baseline",
                            JSONArray(
                                (0 until baseline.length()).map {
                                    baseline.getJSONObject(it).getString("order_id")
                                }
                            ),
                        )
                school.submit(target, selected, prediction) {
                    synchronized(this) {
                        stillAllowed(task)
                        check(
                            dao.markSent(
                                id,
                                receipt.toString(),
                                System.currentTimeMillis(),
                                claim,
                            ) == 1
                        ) {
                            "SUBMIT_ALREADY_CLAIMED"
                        }
                        task.receipt = receipt.toString()
                        sent = true
                    }
                }
                task.state = "SENT"
            }
            reconcile(task)
        } catch (e: Exception) {
            synchronized(this) {
                val current = dao.task(id) ?: return
                if (!sent && (current.state != "CLAIMED" || current.receipt != claim)) return
                if (sent) {
                    finish(task, "UNKNOWN", "提交结果待核对，禁止自动重发")
                } else {
                    val code =
                        e.message?.takeIf { it.matches(Regex("[A-Z_]{3,64}")) }
                            ?: "LOCAL_OPERATION_FAILED"
                    val stopped = dao.plan(task.planId)?.enabled != true || !enabled
                    finish(
                        task,
                        if (stopped) "CANCELLED" else "FAILED",
                        if (stopped) "计划已暂停" else "预约未完成：$code",
                    )
                }
            }
        }
    }

    fun reconcile(task: LocalDatabase.Task) {
        try {
            check(ownerDigest() == JSONObject(task.target).getString("owner_digest")) {
                "OWNER_MISMATCH"
            }
            sessions().use { school ->
                val currentEpoch = epoch()
                val orders = school.orders(true)
                cacheCurrent(
                    "orders",
                    orders,
                    JSONObject(task.target).getString("owner_digest"),
                    currentEpoch,
                )
                val target = JSONObject(task.target)
                val receipt = JSONObject(task.receipt)
                if (!receipt.has("range")) {
                    synchronized(this) { finish(task, "UNKNOWN", "执行曾中断，请核对学校订单；不会重新提交") }
                    return
                }
                val matches =
                    school.matches(
                        orders,
                        target,
                        receipt.getString("range"),
                        receipt.getJSONObject("detail").getString("area_id"),
                    )
                val baseline = receipt.getJSONArray("baseline")
                val old = (0 until baseline.length()).map { baseline.getString(it) }.toSet()
                if (
                    matches.length() == 1 && matches.getJSONObject(0).getString("order_id") !in old
                ) {
                    synchronized(this) {
                        finish(
                            task,
                            "SUCCEEDED",
                            "预约成功，学校订单已确认",
                            receipt.put("order", matches.getJSONObject(0)),
                        )
                    }
                } else synchronized(this) { finish(task, "UNKNOWN", "未取得唯一学校订单，保留查单状态；不会重新提交") }
            }
        } catch (_: Exception) {
            synchronized(this) { finish(task, "UNKNOWN", "查单未完成，请联网或更新授权后重试；不会重新提交") }
        }
    }

    @Synchronized
    fun clear() {
        forgetAuthorization()
        db.runInTransaction {
            dao.clearPlans()
            dao.clearTasks()
            dao.clearCache()
            dao.clearMessages()
        }
    }

    @Synchronized
    fun forgetAuthorization() {
        setExecution(false)
        prefs.edit().putLong("data_epoch", epoch() + 1).commit()
        TokenVault(context).clear()
        dao.clearCache()
    }
}
