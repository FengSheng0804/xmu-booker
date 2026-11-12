package com.example.xmu_sports_booking.local

import android.os.Bundle
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.xmu_sports_booking.capture.TokenVault
import com.example.xmu_sports_booking.capture.SportsProtocol
import java.io.File
import java.math.BigDecimal
import java.security.MessageDigest
import java.util.UUID
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assume
import org.junit.Test
import org.junit.runner.RunWith

/** Explicitly authorized, one-shot R54 diagnostic. Default test runs always skip this class. */
@RunWith(AndroidJUnit4::class)
class AuthorizedBookingDiagnosticTest {
    @Test
    fun inspectSavedAuthorizationWithoutSubmitting() {
        Assume.assumeTrue(InstrumentationRegistry.getArguments()
            .getString("authorizeR55ReadOnlyCheck") == "true")
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        check(context.packageName.endsWith(".debug")) { "DEBUG_ONLY" }
        var phase = "LOADING_SAVED_AUTHORIZATION"
        var result = "VERIFIED"
        var explanation: String? = null
        try {
            val record = JSONObject(TokenVault(context).loadSecret("sealed") ?: error("AUTH_REQUIRED"))
            val fields = record.getJSONObject("fields")
            SportsProtocol.verifyAndRecord(SchoolSession.encode(SchoolSession.map(fields)),
                fields.getString("student_num")) { phase = it }
        } catch (error: Exception) {
            result = error.message?.takeIf { it.matches(Regex("[A-Z_]{3,64}")) } ?: "READ_CHECK_FAILED"
            explanation = (error as? SportsProtocol.ReadRejectedException)?.description()
        }
        instrumentation.sendStatus(0, Bundle().apply {
            putString("authorization_phase", phase)
            putString("authorization_result", result)
            explanation?.let { putString("authorization_explanation", it) }
        })
    }

    @Test
    fun lookupOwnDiagnosticWithoutSubmitting() {
        val mode = InstrumentationRegistry.getArguments().getString("authorizeR54OwnLookup")
        Assume.assumeTrue(mode in setOf("inspect", "release"))
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val profile = InstrumentationRegistry.getArguments().getString("diagnosticProfile") ?: "default"
        check(profile in setOf("default", "wechat", "wechat2", "home", "original")) { "INVALID_DIAGNOSTIC_PROFILE" }
        val folder = File(context.filesDir, if (profile == "default") "private-diagnostics/R54"
            else "private-diagnostics/R54/$profile")
        val report = JSONObject(File(folder, "report.json").readText())
        val id = report.getString("task_id")
        val engine = LocalEngine.get(context)
        val task = engine.db.store().task(id)
        check(task != null && task.planId == id) { "NOT_OWN_DIAGNOSTIC" }
        val result = if (mode == "release") engine.releaseTask(id, true) else engine.inspectTask(id)
        File(folder, "lookup.json").writeText(result.toString(2))
        instrumentation.sendStatus(0, Bundle().apply {
            putString("diagnostic_state", result.getString("state"))
            putBoolean("can_release", result.optBoolean("can_release"))
        })
    }

    @Test
    fun oneAuthorizedSwimmingSubmissionWithoutPaymentOrRetry() {
        Assume.assumeTrue(InstrumentationRegistry.getArguments()
            .getString("authorizeR54OneBookingMax2Cny") == "true")
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val profile = InstrumentationRegistry.getArguments().getString("diagnosticProfile") ?: "default"
        check(profile in setOf("default", "wechat", "wechat2", "home", "original")) { "INVALID_DIAGNOSTIC_PROFILE" }
        check(context.packageName.endsWith(".debug")) { "DEBUG_ONLY" }
        val store = LocalDatabase.get(context).store()
        check(store.plans().none { it.enabled }) { "ACTIVE_PLAN_PRESENT" }
        check(store.tasks().none { it.state in setOf("PENDING", "CLAIMED", "SENT") }) { "ACTIVE_TASK_PRESENT" }
        // Recover old process state before creating this diagnostic's durable claim.
        LocalEngine.get(context).initialize()
        val prior = store.tasks().first { JSONObject(it.target).optString("stadium_id") == "12" }
        val target = JSONObject(prior.target)
        check(target.getString("date") == "2026-11-13" && target.getString("venue_id") == "10" &&
            target.getString("category_id") == "6") { "DIAGNOSTIC_TARGET_CHANGED" }
        val range = "18:30-21:00"
        check(store.unresolved("2026-11-13").none {
            LocalEngine.sameBookingScope(JSONObject(it.target), target)
        }) { "PENDING_RECONCILIATION" }
        // Credentials stay inside their owning app and are never included in diagnostic output.
        val saved = JSONObject(TokenVault(context).loadSecret("sealed") ?: error("AUTH_REQUIRED"))
        val original = if (profile == "original") JSONObject(File(context.filesDir,
            "private-diagnostics/R54/live-session.json").readText()) else null
        val record = if (original != null) JSONObject().put("fields", JSONObject(SportsProtocol.parse(
            original.getString("query"), saved.getJSONObject("fields").getString("student_num")) as Map<*, *>))
            else SportsProtocol.verifyAndRecord(
            SchoolSession.encode(SchoolSession.map(saved.getJSONObject("fields"))),
            saved.getJSONObject("fields").getString("student_num"), {})
        val identity = record.getJSONObject("fields")
        val owner = SchoolSession.hex(MessageDigest.getInstance("SHA-256").digest(
            "${identity.getString("school_id")}:${identity.getString("uid")}:${identity.getString("student_num")}".toByteArray()))
        check(owner == target.getString("owner_digest")) { "OWNER_MISMATCH" }
        val runName = if (profile == "default") "R54" else "R54_$profile"
        val id = UUID.nameUUIDFromBytes("$runName:$owner:2026-11-13:12:10:18:30-21:00".toByteArray()).toString()
        val claim = JSONObject().put("claim", UUID.randomUUID().toString())
        val task = LocalDatabase.Task().apply {
            this.id = id; planId = id; date = "2026-11-13"; this.target = target.toString()
            state = "CLAIMED"; receipt = claim.toString(); runAt = System.currentTimeMillis()
            prepareAt = runAt; deadline = runAt + 120000; updatedAt = runAt
        }
        check(store.addTask(task) != -1L) { "R54_ALREADY_ATTEMPTED" }
        val folder = File(context.filesDir, if (profile == "default") "private-diagnostics/R54"
            else "private-diagnostics/R54/$profile").apply { mkdirs() }
        val events = JSONArray()
        val report = JSONObject().put("task_id", id).put("events", events).put("profile", profile)
        val http = if (profile.startsWith("wechat") || profile in setOf("home", "original")) SchoolSession.HttpWire(
            "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 Chrome/120.0 Mobile Safari/537.36 MicroMessenger/8.0.79")
            else SchoolSession.HttpWire()
        var captchaCookie: String? = null
        var submits = 0
        var sent = false
        var receipt = claim
        val wire = SchoolSession.Wire { route, values, cookie, referer, image ->
            check(route in setOf("/index/index/checklogin", "/index/stadium/mySubscribe",
                "/index/stadium/orderDetails", "/index/Stadium/getStadiumDetails", "/index/Stadium/getStadiumList",
                "/stadium/interval/getInterval", "/index/Stadium/getVenueConfig",
                "/index/stadium/chooseVerify", "/index/index/captcha", "/index/Stadium/addOrder")) {
                "DIAGNOSTIC_ROUTE_REFUSED"
            }
            if (route.endsWith("addOrder")) {
                check(++submits == 1) { "ONE_SUBMISSION_ONLY" }
                check(cookie == captchaCookie) { "CAPTCHA_SESSION_CHANGED" }
                check(values["pay_type"] == "1" && values["is_vip"] == "0") { "AUTOMATIC_PAYMENT_REFUSED" }
                report.put("captcha_cookie_matches_submit", true)
            }
            val event = JSONObject().put("route", route)
                .put("fields", JSONArray(values.keys.sorted()))
                .put("confirm_referer", referer.contains("/stadium/confirm.html?"))
            events.put(event)
            val wireCookie = if (profile == "home" && cookie.isNotEmpty())
                "$cookie; COOKIE_IS_APP_UI=0" else cookie
            val reply = http.call(route, values, wireCookie, referer, image)
            if (route.endsWith("captcha")) captchaCookie = cookie
            reply.json?.let { response ->
                event.put("status", response.optInt("status", -1))
                    .put("response_keys", JSONArray(response.keys().asSequence().toList().sorted()))
                for (key in listOf("code", "errno", "error_code")) {
                    val value = response.opt(key)
                    if (value is Number) event.put(key, value)
                }
            }
            reply
        }
        try {
            SchoolSession(record, wire, original?.getString("session")).use { school ->
                if (profile == "home") school.initializeHome()
                val baseline = school.orders(true)
                check(school.matchesTask(baseline, target, JSONObject()).length() == 0) { "EXISTING_ORDER_PRESENT" }
                val detail = SchoolSession.select(school.inventory(target), target, range)
                check(BigDecimal(detail.getString("price")) <= BigDecimal("2")) { "PRICE_EXCEEDS_AUTHORIZED_LIMIT" }
                val image = school.prepare(target, detail)
                File(folder, "captcha.png").writeBytes(image)
                val prediction = Captcha(context).use { it.predict(image) }
                report.put("prediction", prediction).put("submitted_captcha", prediction.lowercase())
                receipt = JSONObject().put("detail", detail).put("range", range)
                    .put("baseline", JSONArray((0 until baseline.length()).map {
                        baseline.getJSONObject(it).getString("order_id") }))
                school.submit(target, detail, prediction) {
                    check(System.currentTimeMillis() < task.deadline) { "EXECUTION_STOPPED" }
                    check(store.markSent(id, receipt.toString(), System.currentTimeMillis(), claim.toString()) == 1) {
                        "SUBMIT_ALREADY_CLAIMED"
                    }
                    sent = true
                }
                val orders = school.orders(true)
                val matches = school.matchesTask(orders, target, receipt)
                val state = if (matches.length() == 1) {
                    if (SchoolSession.needsPayment(matches.getJSONObject(0))) "PAYMENT_REQUIRED" else "SUCCEEDED"
                } else "UNKNOWN"
                store.finish(id, state, receipt.toString(),
                    if (state == "UNKNOWN") "诊断已提交，结果待核对；不会重发" else "诊断查单已确认学校订单；不自动支付",
                    System.currentTimeMillis())
            }
        } catch (error: Exception) {
            val description = (error as? SchoolSession.RejectedException)?.description()
                ?: error.message?.takeIf { it.matches(Regex("[A-Z_]{3,64}")) } ?: "DIAGNOSTIC_FAILED"
            report.put("error", description)
            store.finish(id, if (sent) "UNKNOWN" else "FAILED", receipt.toString(),
                "诊断未完成：$description；不会自动重发", System.currentTimeMillis())
        } finally {
            report.put("submit_calls", submits).put("state", store.task(id).state)
            File(folder, "report.json").writeText(report.toString(2))
            instrumentation.sendStatus(0, Bundle().apply {
                putString("diagnostic_state", store.task(id).state)
                putInt("submit_calls", submits)
                putBoolean("captcha_captured", File(folder, "captcha.png").exists())
            })
        }
    }
}
