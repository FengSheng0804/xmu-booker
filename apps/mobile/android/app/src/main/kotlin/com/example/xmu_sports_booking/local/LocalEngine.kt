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
    private val testOwnerDigest: String? = null,
    private val testPrefsName: String? = null,
) {
    init {
        check((testOwnerDigest == null && testPrefsName == null) ||
            (com.example.xmu_sports_booking.BuildConfig.DEBUG && testOwnerDigest != null &&
                testPrefsName?.startsWith("isolated_reconciliation_test_") == true)) { "TEST_ONLY" }
    }
    private val dao
        get() = db.store()

    private val prefs = context.getSharedPreferences(testPrefsName ?: "local_booking", 0)
    private var initialized = false
    private var reconciliationRunning = false
    private var lastReconciliationAttempt = 0L
    private val reconciliationWorker by lazy {
        java.util.concurrent.Executors.newSingleThreadExecutor()
    }

    private fun ownerDigest(): String {
        testOwnerDigest?.let { return it }
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
        get() = prefs.getBoolean("real_execution", true)
    internal val isolated get() = testOwnerDigest != null
    private fun readSession(): SchoolSession = if (isolated) sessions() else SchoolSession.openReadOnly(context)

    private fun schedule() {
        if (testOwnerDigest == null) LocalScheduler.schedule(context)
    }

    companion object {
        val zone: ZoneId = ZoneId.of("Asia/Shanghai")
        private const val MIN_CONFIRM_TO_SUBMIT_MS = 6_000L
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

        fun singlePlanFinished(plan: JSONObject, state: String): Boolean =
            plan.optString("repeat") == "ONCE" &&
                state in setOf("SUCCEEDED", "FAILED", "EXPIRED", "UNKNOWN", "PAYMENT_REQUIRED")

        fun sameBookingScope(first: JSONObject, second: JSONObject): Boolean {
            val venue = first.optString("venue_id")
            if (venue.isEmpty() || venue != second.optString("venue_id")) return false
            return listOf("stadium_id", "category_id", "owner_digest").all {
                val a = first.optString(it)
                val b = second.optString(it)
                a.isEmpty() || b.isEmpty() || a == b
            }
        }

        fun executionWindow(p: JSONObject, day: LocalDate): Pair<Long, Long> {
            if (MonitorRules.enabled(p)) return MonitorRules.window(p).let { Pair(it[0], it[1]) }
            val run =
                day.minusDays(p.getLong("days_before"))
                    .atTime(LocalTime.parse(p.getString("execution_time")))
                    .atZone(zone)
            val seconds = p.getLong("window_seconds")
            require(seconds in 15..3600) { "EXECUTION_WINDOW_INVALID" }
            val end = run.plusSeconds(seconds)
            return Pair(run.toInstant().toEpochMilli(), end.toInstant().toEpochMilli())
        }

        /** Live stadiums replace their fallback mapping, whose child venue IDs can be stale. */
        fun mergeCatalog(live: JSONArray, fallback: JSONArray): JSONArray {
            val result = JSONArray()
            val stadiums = mutableSetOf<String>()
            val keys = mutableSetOf<String>()
            fun add(row: JSONObject) {
                val key = listOf("stadium_id", "venue_id", "category_id")
                    .joinToString("_") { row.optString(it) }
                if (keys.add(key)) result.put(row)
            }
            for (i in 0 until live.length()) {
                val row = live.getJSONObject(i)
                stadiums.add(row.optString("stadium_id"))
                add(row)
            }
            for (i in 0 until fallback.length()) {
                val row = fallback.getJSONObject(i)
                if (row.optString("stadium_id") !in stadiums) add(row)
            }
            return result
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
                .put("next_check_at", t.prepareAt)
                .put("deadline", t.deadline)
    }

    @Synchronized
    fun initialize() {
        if (initialized) return
        initialized = true
        if (testOwnerDigest == null) LocalStorage.clean(context)
        if (!prefs.getBoolean("migrated", false)) {
            // Old project login/server data is obsolete. Keep own_sports_token and its Keystore
            // alias.
            context.getSharedPreferences("FlutterSecureStorage", 0).edit().clear().commit()
            context.getSharedPreferences("FlutterSharedPreferences", 0).edit().clear().commit()
            prefs.edit().putBoolean("migrated", true).commit()
        }
        if (!prefs.getBoolean("automatic_execution_default", false)) {
            val authorized = runCatching { ownerDigest() }.isSuccess
            prefs.edit().putBoolean("automatic_execution_default", true)
                .putBoolean("real_execution", authorized).commit()
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
        if (owner != null) prefs.edit().putString("active_owner", owner)
            .putBoolean("real_execution", true).commit()
        generate()
        schedule()
        val metadata =
            runCatching { TokenVault(context).metadata() }
                .getOrElse { JSONObject().put("has_token", false) }
        return JSONObject()
            .put("authorization", metadata)
            .put("execution_enabled", enabled)
            .put("plans", JSONArray(dao.plans().map {
                jsonPlan(it).also { output ->
                    dao.latestResult(it.id)?.let { task -> output.put("last_result", jsonTask(task)) }
                }
            }))
            .put("tasks", JSONArray(visibleExecutionTasks().map { task ->
                jsonTask(task).also { output ->
                    if (task.reason.contains("PENDING_RECONCILIATION")) {
                        output.put("blocking_tasks", JSONArray(blockingTasks(task).map { jsonTask(it) }))
                    }
                }
            }))
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
            .put(
                "venues",
                dao.cached("venues")?.json?.let { cached ->
                    if (cached.isBlank() || cached == "[]") defaultVenuesArray() else JSONArray(cached)
                } ?: defaultVenuesArray()
            )
            .put("orders_updated_at", dao.cached("orders")?.updatedAt ?: 0)
            .put("venues_updated_at", dao.cached("venues")?.updatedAt ?: 0)
            .put("permissions", LocalScheduler.permissions(context))
    }

    private fun defaultCategory(name: String): String = when {
        name.contains("健身") -> "8"
        name.contains("羽毛球") -> "3"
        name.contains("乒乓球") -> "2"
        name.contains("网球") -> "9"
        name.contains("游泳") || name.contains("泳池") -> "6"
        name.contains("单车") -> "7"
        name.contains("瑜伽") -> "11"
        name.contains("匹克球") -> "12"
        name.contains("篮球") -> "1"
        name.contains("排球") -> "5"
        name.contains("足球") -> "4"
        else -> "1"
    }

    private fun defaultProjectName(name: String): String = when {
        name.contains("健身") -> "健身房"
        name.contains("羽毛球") -> "羽毛球"
        name.contains("乒乓球") -> "乒乓球"
        name.contains("网球") -> "网球"
        name.contains("游泳") || name.contains("泳池") -> "游泳"
        name.contains("单车") -> "动感单车"
        name.contains("瑜伽") -> "瑜伽"
        name.contains("匹克球") -> "匹克球"
        name.contains("风雨球馆") -> "风雨球馆"
        else -> name
    }

    private fun knownVenues(): List<JSONObject> = listOf(
        JSONObject().put("stadium_id", "6").put("venue_id", "6").put("category_id", "8").put("stadium_name", "思明校区健身房").put("project_name", "健身房"),
        JSONObject().put("stadium_id", "16").put("venue_id", "14").put("category_id", "8").put("stadium_name", "翔安校区健身房").put("project_name", "健身房"),
        JSONObject().put("stadium_id", "9").put("venue_id", "9").put("category_id", "3").put("stadium_name", "思明校区风雨球馆").put("project_name", "风雨球馆"),
        JSONObject().put("stadium_id", "4").put("venue_id", "4").put("category_id", "6").put("stadium_name", "王清明游泳馆一楼泳池").put("project_name", "游泳"),
        JSONObject().put("stadium_id", "5").put("venue_id", "5").put("category_id", "6").put("stadium_name", "王清明游泳馆顶楼泳池").put("project_name", "游泳"),
        JSONObject().put("stadium_id", "8").put("venue_id", "8").put("category_id", "9").put("stadium_name", "思明校区东苑网球场").put("project_name", "网球"),
        JSONObject().put("stadium_id", "2").put("venue_id", "2").put("category_id", "7").put("stadium_name", "动感单车室（动感单车朋辈课堂）").put("project_name", "动感单车"),
        JSONObject().put("stadium_id", "17").put("venue_id", "17").put("category_id", "12").put("stadium_name", "演武室外匹克球场").put("project_name", "匹克球"),
        JSONObject().put("stadium_id", "11").put("venue_id", "11").put("category_id", "11").put("stadium_name", "B馆教室（瑜伽朋辈课堂）").put("project_name", "瑜伽"),
        JSONObject().put("stadium_id", "12").put("venue_id", "12").put("category_id", "6").put("stadium_name", "翔安校区游泳馆").put("project_name", "游泳"),
        JSONObject().put("stadium_id", "13").put("venue_id", "13").put("category_id", "3").put("stadium_name", "翔安校区羽毛球场").put("project_name", "羽毛球"),
        JSONObject().put("stadium_id", "15").put("venue_id", "15").put("category_id", "2").put("stadium_name", "翔安校区乒乓球室").put("project_name", "乒乓球"),
        JSONObject().put("stadium_id", "14").put("venue_id", "14").put("category_id", "9").put("stadium_name", "翔安校区一期网球场").put("project_name", "网球")
    )

    private fun defaultVenuesArray(): JSONArray {
        val arr = JSONArray()
        for (v in knownVenues()) {
            arr.put(JSONObject(v.toString()))
        }
        return arr
    }

    @Synchronized
    fun setExecution(value: Boolean) {
        if (value) check(TokenVault(context).metadata().optBoolean("has_token")) { "AUTH_REQUIRED" }
        check(prefs.edit().putBoolean("real_execution", value).commit())
        generate()
        schedule()
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
        val reconcile = dao.pendingReconciliation().isNotEmpty()
        return readSession().use { session ->
            session.orders(reconcile).also {
                cacheCurrent("orders", it, owner, epoch)
                if (reconcile) reconcileOrders(session, it, owner, epoch)
            }
        }
    }

    @Synchronized
    internal fun visibleExecutionTasks(): List<LocalDatabase.Task> {
        val hidden = (prefs.getStringSet("hidden_execution_records", emptySet()) ?: emptySet()).toMutableSet()
        val tasks = dao.tasks()
        // A paused, unsent task may be explicitly enabled again with the same durable ID.
        val reopened = tasks.filter { it.id in hidden && it.state in setOf("PENDING", "CLAIMED", "SENT") }
        if (reopened.isNotEmpty()) {
            hidden.removeAll(reopened.map { it.id }.toSet())
            check(prefs.edit().putStringSet("hidden_execution_records", hidden).commit())
        }
        return tasks.filter { it.id !in hidden }
    }

    @Synchronized
    fun deleteExecutionRecord(id: String) {
        val task = dao.task(id) ?: error("INVALID_TASK")
        check(task.state !in setOf("PENDING", "CLAIMED", "SENT")) { "TASK_IN_PROGRESS" }
        val hidden = (prefs.getStringSet("hidden_execution_records", emptySet()) ?: emptySet()).toMutableSet()
        hidden.add(id)
        check(prefs.edit().putStringSet("hidden_execution_records", hidden).commit())
        // Keep the durable execution receipt for duplicate prevention and unresolved-order checks.
    }

    fun entryPass(id: String): JSONObject {
        val task = synchronized(this) { dao.task(id) ?: error("INVALID_TASK") }
        check(task.state == "SUCCEEDED") { "ENTRY_PASS_UNAVAILABLE" }
        val target = JSONObject(task.target)
        check(ownerDigest() == target.optString("owner_digest")) { "OWNER_MISMATCH" }
        val currentEpoch = epoch()
        val result = sessions().use { it.entryPass(target, JSONObject(task.receipt)) }
        check(epoch() == currentEpoch && ownerDigest() == target.optString("owner_digest")) { "AUTHORIZATION_CHANGED" }
        return result
    }

    fun orderEntryPass(id: String): JSONObject {
        val owner = ownerDigest()
        val currentEpoch = epoch()
        val result = readSession().use { it.orderEntryPass(id) }
        check(epoch() == currentEpoch && ownerDigest() == owner) { "AUTHORIZATION_CHANGED" }
        return result
    }

    /** Called on UI refresh; a single background read at most every 30 seconds, never a submit. */
    @Synchronized
    fun requestReconciliation() {
        val now = android.os.SystemClock.elapsedRealtime()
        if (reconciliationRunning ||
            (lastReconciliationAttempt != 0L && now - lastReconciliationAttempt < 30_000L) ||
            dao.pendingReconciliation().isEmpty()) return
        reconciliationRunning = true
        lastReconciliationAttempt = now
        reconciliationWorker.execute {
            try { reconcilePending() } catch (_: Exception) {
                // Keep unresolved states intact; later refresh or updated authorization retries reads.
            } finally { synchronized(this) { reconciliationRunning = false } }
        }
    }

    fun reconcilePending() {
        if (dao.pendingReconciliation().isEmpty()) return
        val owner = ownerDigest()
        val currentEpoch = epoch()
        sessions().use { school ->
            val orders = school.orders(true)
            cacheCurrent("orders", orders, owner, currentEpoch)
            reconcileOrders(school, orders, owner, currentEpoch)
        }
    }

    private fun reconcileOrders(school: SchoolSession, orders: JSONArray, owner: String, currentEpoch: Long) {
        for (task in dao.pendingReconciliation()) {
            if (JSONObject(task.target).optString("owner_digest") == owner)
                applyLookup(task, school, orders, owner, currentEpoch, false)
        }
    }

    fun syncCatalog(): JSONArray {
        val owner = ownerDigest()
        val epoch = epoch()
        return readSession().use { session ->
            val stadiumsData = try {
                session.stadiumList()
            } catch (e: Exception) {
                null
            }
            val stadiumList = stadiumsData?.optJSONArray("stadium")
            val targets = mutableListOf<JSONObject>()
            val seenKeys = mutableSetOf<String>()

            fun addTarget(stadiumId: String, venueId: String, categoryId: String, stadiumName: String, projectName: String) {
                val key = "${stadiumId}_${venueId}_${categoryId}"
                if (!seenKeys.add(key)) return
                targets.add(
                    JSONObject()
                        .put("stadium_id", stadiumId)
                        .put("venue_id", venueId)
                        .put("category_id", categoryId)
                        .put("stadium_name", stadiumName)
                        .put("project_name", projectName)
                        .put("date", nowDate().toString())
                )
            }

            fun addFallback(stadiumId: String, stadiumName: String) {
                val known = knownVenues().firstOrNull { it.getString("stadium_id") == stadiumId }
                addTarget(stadiumId, known?.getString("venue_id") ?: stadiumId,
                    known?.getString("category_id") ?: defaultCategory(stadiumName),
                    stadiumName, known?.getString("project_name") ?: defaultProjectName(stadiumName))
            }

            if (stadiumList != null && stadiumList.length() > 0) {
                val detailsByIndex = ReadBatch.run((0 until stadiumList.length()).map { index ->
                    java.util.concurrent.Callable {
                        try {
                            (if (isolated) readSession() else session.forkReadOnly()).use {
                                it.stadiumDetails(stadiumList.getJSONObject(index).getString("id"))
                            }
                        } catch (_: Exception) { null }
                    }
                })
                for (i in 0 until stadiumList.length()) {
                    val st = stadiumList.getJSONObject(i)
                    val sId = st.optString("id")
                    val sName = st.optString("name")
                    try {
                        val details = detailsByIndex[i] ?: error("CATALOG_DETAILS_UNAVAILABLE")
                        val venues = details.optJSONArray("venue")
                        if (venues != null && venues.length() > 0) {
                            for (j in 0 until venues.length()) {
                                val v = venues.getJSONObject(j)
                                val pName = v.optString("name").ifBlank { defaultProjectName(sName) }
                                val cId = v.optString("category_id").ifBlank { defaultCategory(sName) }
                                val list = v.optJSONArray("list")
                                if (list != null && list.length() > 0) {
                                    val vIds = mutableSetOf<String>()
                                    for (k in 0 until list.length()) {
                                        val vid = list.getJSONObject(k).optString("venue_id")
                                        if (vid.isNotBlank()) vIds.add(vid)
                                    }
                                    if (vIds.isEmpty()) vIds.add(sId)
                                    for (vid in vIds) {
                                        addTarget(sId, vid, cId, sName, pName)
                                    }
                                } else {
                                    addTarget(sId, sId, cId, sName, pName)
                                }
                            }
                        } else {
                            addFallback(sId, sName)
                        }
                    } catch (e: Exception) {
                        addFallback(sId, sName)
                    }
                }
            }

            val result = JSONArray()
            val merged = mergeCatalog(JSONArray(targets), defaultVenuesArray())
            val inventories = ReadBatch.run((0 until merged.length()).map { index ->
                java.util.concurrent.Callable {
                val target = merged.getJSONObject(index)
                val inventory = try {
                    (if (isolated) readSession() else session.forkReadOnly()).use { it.inventoryForQuery(target) }
                } catch (e: Exception) {
                    JSONObject()
                        .put("is_can_choose", 0)
                        .put("is_can_choose_tips", "暂不支持预约或未开放")
                        .put("time_slot_list", JSONArray())
                }
                val resolved = inventory.optJSONObject("resolved_target") ?: target
                JSONObject(resolved.toString()).put("inventory", inventory)
                }
            })
            for (inventory in inventories) result.put(inventory)
            cacheCurrent("venues", result, owner, epoch)
            result
        }
    }

    fun availability(target: JSONObject): JSONObject = readSession().use { session ->
        session.inventoryForQuery(target)
    }

    fun validate(plan: JSONObject) {
        require(plan.optString("execution_mode", "TIMED") in setOf("TIMED", "MONITOR")) { "INVALID_PLAN" }
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
        if (MonitorRules.enabled(plan)) {
            val span = MonitorRules.window(plan)
            require(plan.getString("repeat") == "ONCE" &&
                span[1] <= LocalDate.parse(plan.getString("date")).plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli() &&
                (!plan.optBoolean("enabled") || span[1] > System.currentTimeMillis())) { "MONITOR_WINDOW_INVALID" }
        }
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
            require(ranges.length() >= 1) { "INVALID_PLAN" }
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
        if (!plan.has("enabled")) plan.put("enabled", true)
        validate(plan)
        val sourceId = plan.optString("id").ifEmpty { UUID.randomUUID().toString() }
        UUID.fromString(sourceId)
        val latest = dao.latestResult(sourceId)
        val reset = plan.optBoolean("reset_execution") && dao.plan(sourceId) != null && latest != null &&
            latest.state in setOf("SUCCEEDED", "FAILED", "EXPIRED", "UNKNOWN", "PAYMENT_REQUIRED", "CANCELLED")
        if (reset) check(dao.inFlightForPlan(sourceId) == 0) {
            "TASK_IN_PROGRESS"
        }
        val id = if (reset) UUID.randomUUID().toString() else sourceId
        val existing = dao.taskForDate(id, plan.optString("date"))
        require(!plan.optBoolean("enabled") || existing == null ||
            !singlePlanFinished(plan, existing.state)) { "SINGLE_PLAN_FINISHED" }
        val saved =
            LocalDatabase.Plan().apply {
                this.id = id
                json =
                    JSONObject(plan.toString())
                        .also { it.remove("last_result"); it.remove("reset_execution") }
                        .put("id", id)
                        .put("owner_digest", ownerDigest())
                        .toString()
                enabled = plan.optBoolean("enabled")
                updatedAt = System.currentTimeMillis()
            }
        db.runInTransaction {
            dao.cancelPending(sourceId, System.currentTimeMillis())
            dao.savePlan(saved)
            if (reset) dao.deletePlan(sourceId)
        }
        generate()
        schedule()
        return id
    }

    @Synchronized
    fun deletePlan(id: String) {
        db.runInTransaction {
            dao.cancelPending(id, System.currentTimeMillis())
            dao.deletePlan(id)
        }
        schedule()
    }

    @Synchronized
    fun generate() {
        // Repair enabled flags of completed single plans created by older versions as well.
        for (plan in dao.plans().filter { it.enabled }) {
            val p = JSONObject(plan.json)
            if (p.optString("repeat") == "ONCE") {
                dao.taskForDate(plan.id, p.optString("date"))?.let { retireSingle(plan, it) }
            }
        }
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
                        .put("execution_mode", p.optString("execution_mode", "TIMED"))
                        .put("poll_seconds", if (MonitorRules.enabled(p)) MonitorRules.interval(p) else 0)
                val task =
                    LocalDatabase.Task().apply {
                        id = UUID.nameUUIDFromBytes((plan.id + ":" + day).toByteArray()).toString()
                        planId = plan.id
                        date = day.toString()
                        this.target = target.toString()
                        runAt = window.first
                        deadline = window.second
                        prepareAt = if (MonitorRules.enabled(p)) maxOf(runAt, System.currentTimeMillis())
                            else runAt - p.getLong("prepare_lead_seconds") * 1000
                        updatedAt = System.currentTimeMillis()
                    }
                if (MonitorRules.enabled(p) && dao.task(task.id)?.state == "PENDING") continue
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
        if (testOwnerDigest == null) LocalScheduler.notifyResult(context, text, task.id.hashCode())
    }

    private fun finish(
        task: LocalDatabase.Task,
        state: String,
        reason: String,
        receipt: JSONObject = JSONObject(task.receipt),
    ) {
        val previous = dao.task(task.id) ?: return
        task.state = state
        task.receipt = receipt.toString()
        db.runInTransaction {
            dao.finish(task.id, state, receipt.toString(), reason, System.currentTimeMillis())
            dao.plan(task.planId)?.let { retireSingle(it, task) }
        }
        if (previous.state != state || previous.reason != reason) message(task, reason)
    }

    private fun retireSingle(plan: LocalDatabase.Plan, task: LocalDatabase.Task) {
        val p = JSONObject(plan.json)
        if (plan.enabled && p.optString("date") == task.date && singlePlanFinished(p, task.state)) {
            plan.enabled = false
            plan.json = p.put("enabled", false).toString()
            plan.updatedAt = System.currentTimeMillis()
            dao.savePlan(plan)
        }
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

    private fun blockingTasks(task: LocalDatabase.Task): List<LocalDatabase.Task> =
        dao.unresolved(task.date).filter {
            it.id != task.id && sameBookingScope(JSONObject(it.target), JSONObject(task.target))
        }

    @Synchronized
    internal fun activeMonitor(): Boolean = enabled && dao.activeUnsent().any {
        it.state in setOf("PENDING", "CLAIMED") && MonitorRules.enabled(JSONObject(it.target)) &&
            System.currentTimeMillis() >= it.runAt && System.currentTimeMillis() < it.deadline &&
            dao.plan(it.planId)?.enabled == true
    }

    @Synchronized
    private fun deferMonitor(task: LocalDatabase.Task, claim: String, cause: String, transientError: Boolean = false): Boolean {
        val current = dao.task(task.id) ?: return false
        val now = System.currentTimeMillis()
        if (!enabled || dao.plan(task.planId)?.enabled != true || now >= task.deadline ||
            current.state != "CLAIMED" || current.receipt != claim) return false
        val before = JSONObject(claim).optJSONObject("monitor") ?: JSONObject()
        val errors = if (transientError) before.optInt("consecutive_errors") + 1 else 0
        val monitor = JSONObject().put("polls", before.optInt("polls") + 1)
            .put("last_checked_at", now).put("consecutive_errors", errors)
        val next = MonitorRules.nextPoll(now, task.deadline, MonitorRules.interval(JSONObject(task.target)), errors)
        monitor.put("next_check_at", next)
        val receipt = JSONObject().put("monitor", monitor)
        val deferred = dao.deferMonitor(task.id, claim, next, receipt.toString(),
            if (transientError) "监控暂缓：$cause，稍后继续检查" else "监控中：暂无可预约名额，等待下一次检查", now) == 1
        if (deferred) schedule()
        return deferred
    }

    /** Run on one worker thread. Waiting does not hold the mutation lock so the user can pause. */
    fun execute(id: String) {
        val task = synchronized(this) { dao.task(id) ?: return }
        if (task.state == "UNKNOWN") {
            reconcile(task)
            return
        }
        if (task.state != "PENDING") return
        if (task.prepareAt > System.currentTimeMillis() + 1000) return
        val monitoring = MonitorRules.enabled(JSONObject(task.target))
        val claim = JSONObject().put("claim", UUID.randomUUID().toString()).also {
            if (monitoring) JSONObject(task.receipt).optJSONObject("monitor")?.let { previous -> it.put("monitor", previous) }
        }.toString()
        synchronized(this) {
            if (!enabled) return
            if (System.currentTimeMillis() >= task.deadline) {
                finish(task, "EXPIRED", if (monitoring) "监控已结束，未预约到名额" else "已超过执行窗口")
                return
            }
            if (dao.claim(id, System.currentTimeMillis(), claim) != 1) return
            task.receipt = claim
        }
        var sent = false
        var phase = "CHECK_PLAN"
        try {
            stillAllowed(task)
            schedule() // Arm recovery before any slow school request, even after a cold alarm wake.
            if (monitoring) {
                check(blockingTasks(task).isEmpty()) { "PENDING_RECONCILIATION" }
                phase = "READ_INVENTORY"
                val available = readSession().use { school ->
                    val target = JSONObject(task.target)
                    val inventory = school.inventory(target)
                    val ranges = target.getJSONArray("ranges")
                    (0 until ranges.length()).any {
                        try { SchoolSession.select(inventory, target, ranges.getString(it)); true }
                        catch (error: Exception) {
                            if (error.message in setOf("NO_AVAILABLE_TARGET", "VENUE_UNAVAILABLE")) false else throw error
                        }
                    }
                }
                if (!available && deferMonitor(task, claim, "NO_AVAILABLE_TARGET")) return
                stillAllowed(task)
            }
            phase = "VERIFY_AUTHORIZATION"
            sessions().use { school ->
                val target = JSONObject(task.target)
                val ranges = target.getJSONArray("ranges")
                phase = "READ_BASELINE_ORDERS"
                val baseline = school.orders(true)
                // Unknown same-venue/day receipts are a barrier even for a newly copied plan.
                check(
                    blockingTasks(task).isEmpty()
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
                                if (SchoolSession.needsPayment(matches.getJSONObject(0))) "PAYMENT_REQUIRED" else "SUCCEEDED",
                                if (SchoolSession.needsPayment(matches.getJSONObject(0)))
                                    "学校已有对应订单，待支付；请到学校入口处理" else "学校已有对应预约",
                                JSONObject().put("order", matches.getJSONObject(0)),
                            )
                        }
                        return
                    }
                }
                phase = "WAIT_EXECUTION"
                while (System.currentTimeMillis() < task.runAt) {
                    stillAllowed(task)
                    Thread.sleep(
                        minOf(250L, task.runAt - System.currentTimeMillis()).coerceAtLeast(1)
                    )
                }
                stillAllowed(task)
                phase = "READ_INVENTORY"
                val inventory = school.inventory(target)
                phase = "SELECT_SLOT"
                var detail: JSONObject? = null
                var range = ""
                for (i in 0 until ranges.length()) try {
                    range = ranges.getString(i)
                    detail = SchoolSession.select(inventory, target, range)
                    break
                } catch (e: Exception) {
                    if (e.message != "NO_AVAILABLE_TARGET") throw e
                }
                if (detail == null && monitoring && deferMonitor(task, claim, "NO_AVAILABLE_TARGET")) return
                check(detail != null) { "NO_AVAILABLE_TARGET" }
                val selected = detail
                val prepareStarted = System.currentTimeMillis()
                phase = "PREPARE_BOOKING"
                val image = school.prepare(target, selected)
                phase = "RECOGNIZE_CAPTCHA"
                val prediction = predictor(image)
                phase = "WAIT_SUBMISSION"
                // Official manual bookings took 5.7-7.1 s between the confirm page and addOrder;
                // every machine-speed submit was refused. Keep a human-scale gap before sending.
                while (System.currentTimeMillis() - prepareStarted < MIN_CONFIRM_TO_SUBMIT_MS) {
                    stillAllowed(task)
                    Thread.sleep(
                        minOf(
                            250L,
                            MIN_CONFIRM_TO_SUBMIT_MS - (System.currentTimeMillis() - prepareStarted),
                        ).coerceAtLeast(1)
                    )
                }
                stillAllowed(task)
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
                phase = "SUBMIT_BOOKING"
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
            phase = "CHECK_RESULT"
            reconcile(task)
        } catch (e: Exception) {
            if (monitoring && !sent && System.currentTimeMillis() >= task.deadline) {
                synchronized(this) {
                    val current = dao.task(id)
                    if (current?.state == "CLAIMED" && current.receipt == claim)
                        finish(task, "EXPIRED", "监控已结束，未预约到名额")
                }
                return
            }
            if (monitoring && !sent && ExecutionFailure.code(e) in setOf(
                "NETWORK_TIMEOUT", "NETWORK_UNAVAILABLE", "SCHOOL_HTTP_FAILED", "READ_CHECK_HTTP_FAILED", "NO_AVAILABLE_TARGET", "VENUE_UNAVAILABLE")) {
                val transient = ExecutionFailure.code(e) !in setOf("NO_AVAILABLE_TARGET", "VENUE_UNAVAILABLE")
                if (deferMonitor(task, claim, ExecutionFailure.description(e, phase), transient)) return
            }
            synchronized(this) {
                val current = dao.task(id) ?: return
                if (!sent && (current.state != "CLAIMED" || current.receipt != claim)) return
                val rejection = (e as? SchoolSession.RejectedException)?.description()
                    ?: (e as? com.example.xmu_sports_booking.capture.SportsProtocol.ReadRejectedException)?.description()
                val failure = ExecutionFailure.diagnostic(e, phase).put("at", System.currentTimeMillis())
                val receipt = JSONObject(current.receipt).put("failure", failure)
                if (sent) {
                    finish(task, "UNKNOWN", "提交结果待核对，禁止自动重发" +
                        (rejection?.let { "。$it" } ?: "。${ExecutionFailure.description(e, phase)}"), receipt)
                } else {
                    val stopped = dao.plan(task.planId)?.enabled != true || !enabled
                    finish(
                        task,
                        if (stopped) "CANCELLED" else "FAILED",
                        if (stopped) "计划已暂停" else "预约未完成：${rejection ?: ExecutionFailure.description(e, phase)}",
                        receipt,
                    )
                }
            }
            if (sent) reconcile(task)
        }
    }

    fun inspectTask(id: String): JSONObject = lookupTask(id, false, false)

    fun releaseTask(id: String, confirmedNoOrder: Boolean): JSONObject {
        require(confirmedNoOrder) { "RECONCILIATION_CONFIRMATION_REQUIRED" }
        val task = synchronized(this) { dao.task(id) ?: error("INVALID_TASK") }
        require(JSONObject(task.receipt).optJSONObject("last_lookup")?.optString("result") == "NO_MATCH") {
            "RECONCILIATION_CHECK_REQUIRED"
        }
        // Read school orders again; a newly visible order must win over the earlier empty result.
        return lookupTask(id, true, false)
    }

    private fun lookupTask(id: String, confirmedNoOrder: Boolean, allowSent: Boolean): JSONObject {
        val task = synchronized(this) { dao.task(id) ?: error("INVALID_TASK") }
        check(task.state == "UNKNOWN" || (allowSent && task.state == "SENT")) { "TASK_IN_PROGRESS" }
        val target = JSONObject(task.target)
        val owner = ownerDigest()
        val currentEpoch = epoch()
        check(owner == target.getString("owner_digest")) { "OWNER_MISMATCH" }
        return sessions().use { school ->
            val orders = school.orders(true)
            cacheCurrent("orders", orders, owner, currentEpoch)
            applyLookup(task, school, orders, owner, currentEpoch, confirmedNoOrder)
        }
    }

    private fun applyLookup(task: LocalDatabase.Task, school: SchoolSession, orders: JSONArray,
        owner: String, currentEpoch: Long, confirmedNoOrder: Boolean): JSONObject {
        val matches = school.matchesTask(orders, JSONObject(task.target), JSONObject(task.receipt))
        return synchronized(this) {
                check(epoch() == currentEpoch && ownerDigest() == owner) { "AUTHORIZATION_CHANGED" }
                val current = dao.task(task.id) ?: error("INVALID_TASK")
                if (current.state != task.state || current.receipt != task.receipt) {
                    return@synchronized jsonTask(current).put("can_release", false)
                }
                val receipt = JSONObject(current.receipt)
                val checkedAt = System.currentTimeMillis()
                receipt.put("last_lookup", JSONObject().put("checked_at", checkedAt)
                    .put("result", if (matches.length() == 0) "NO_MATCH" else "MATCHED"))
                when {
                    matches.length() == 1 -> {
                        val order = matches.getJSONObject(0)
                        val payment = SchoolSession.needsPayment(order)
                        finish(current, if (payment) "PAYMENT_REQUIRED" else "SUCCEEDED",
                            if (payment) "查单确认学校已有对应订单，待支付；请到学校入口处理"
                            else "预约成功，已核对学校订单", receipt.put("order", order))
                    }
                    matches.length() > 1 -> finish(current, "UNKNOWN",
                        "学校有多条对应订单，请到学校入口核对；保留阻塞，不会重新提交", receipt)
                    confirmedNoOrder -> finish(current, "FAILED",
                        "已查单且用户确认学校无对应订单，旧任务阻塞已解除；未重新提交",
                        receipt.put("manual_resolution", JSONObject().put("confirmed_no_order_at", checkedAt)))
                    else -> finish(current, "UNKNOWN",
                        "已完整读取学校订单，未找到对应预约；请核对学校原入口后确认解除阻塞，不会自动重发", receipt)
                }
                jsonTask(dao.task(task.id)).put("can_release", matches.length() == 0 && !confirmedNoOrder)
        }
    }

    fun reconcile(task: LocalDatabase.Task) {
        try {
            for (attempt in 0..2) {
                if (attempt > 0) Thread.sleep(if (attempt == 1) 1500L else 3000L)
                val current = synchronized(this) { dao.task(task.id) } ?: return
                if (current.state !in setOf("SENT", "UNKNOWN")) return
                val result = lookupTask(task.id, false, true)
                if (result.getString("state") != "UNKNOWN") return
            }
        } catch (_: Exception) {
            synchronized(this) {
                val current = dao.task(task.id) ?: return
                if (current.state in setOf("SENT", "UNKNOWN"))
                    finish(current, "UNKNOWN", "查单未完成，请联网或更新授权后重试；不会重新提交")
            }
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
        prefs.edit().remove("hidden_execution_records").commit()
    }

    @Synchronized
    fun forgetAuthorization() {
        setExecution(false)
        prefs.edit().putLong("data_epoch", epoch() + 1).commit()
        TokenVault(context).clear()
        dao.clearCache()
    }
}
