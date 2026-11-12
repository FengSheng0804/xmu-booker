package com.example.xmu_sports_booking.local

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import io.flutter.embedding.android.FlutterActivity
import io.flutter.plugin.common.MethodChannel
import java.util.concurrent.Executors
import org.json.JSONArray
import org.json.JSONObject

/** A small method surface, no endpoint URLs or credentials accepted from Dart. */
class LocalBridge(private val activity: FlutterActivity, private val channel: MethodChannel) :
    AutoCloseable {
    private val worker = Executors.newSingleThreadExecutor()
    private val reads = Executors.newFixedThreadPool(2)
    private val main = Handler(Looper.getMainLooper())
    @Volatile private var closed = false

    init {
        channel.setMethodCallHandler { call, result ->
            when (call.method) {
                "settings" -> {
                    when (call.argument<String>("kind")) {
                        "notifications" ->
                            if (Build.VERSION.SDK_INT >= 33)
                                activity.requestPermissions(
                                    arrayOf(Manifest.permission.POST_NOTIFICATIONS),
                                    41,
                                )
                        "alarm" ->
                            if (Build.VERSION.SDK_INT >= 31)
                                activity.startActivity(
                                    Intent(
                                        Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM,
                                        Uri.parse("package:${activity.packageName}"),
                                    )
                                )
                        "battery" ->
                            activity.startActivity(
                                Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
                            )
                    }
                    result.success(null)
                }
                else ->
                    (if (call.method in setOf("syncOrders", "syncCatalog", "availability", "orderEntryPass")) reads else worker).execute {
                        try {
                            val engine = LocalEngine.get(activity)
                            engine.initialize()
                            val output: Any? =
                                when (call.method) {
                                    "home" -> engine.home().also { engine.requestReconciliation() }
                                    "syncOrders" -> engine.syncOrders()
                                    "syncCatalog" -> engine.syncCatalog()
                                    "availability" ->
                                        engine.availability(
                                            JSONObject(call.argument<String>("target") ?: "{}")
                                        )
                                    "savePlan" ->
                                        engine.savePlan(
                                            JSONObject(call.argument<String>("plan") ?: "{}")
                                        )
                                    "deletePlan" -> {
                                        engine.deletePlan(call.argument<String>("id") ?: "")
                                        null
                                    }
                                    "deleteExecutionRecord" -> {
                                        engine.deleteExecutionRecord(call.argument<String>("id") ?: "")
                                        null
                                    }
                                    "entryPass" -> engine.entryPass(call.argument<String>("id") ?: "")
                                    "orderEntryPass" -> engine.orderEntryPass(call.argument<String>("id") ?: "")
                                    "cleanStorage" -> LocalStorage.clean(activity)
                                    "setExecution" -> {
                                        engine.setExecution(
                                            call.argument<Boolean>("enabled") == true
                                        )
                                        null
                                    }
                                    "runTask" -> {
                                        val id =
                                            call.argument<String>("id") ?: error("INVALID_TASK")
                                        val task =
                                            engine.db.store().task(id) ?: error("INVALID_TASK")
                                        check(
                                            task.state in setOf("SENT", "UNKNOWN", "CLAIMED") ||
                                                task.prepareAt <= System.currentTimeMillis() + 1000
                                        ) {
                                            "NOT_IN_EXECUTION_WINDOW"
                                        }
                                        LocalScheduler.start(activity, id)
                                        null
                                    }
                                    "inspectTask" -> engine.inspectTask(call.argument<String>("id") ?: "")
                                    "releaseTask" -> engine.releaseTask(call.argument<String>("id") ?: "",
                                        call.argument<Boolean>("confirmed_no_order") == true)
                                    "clear" -> {
                                        engine.clear()
                                        null
                                    }
                                    else -> error("UNKNOWN_METHOD")
                                }
                            if (!closed)
                                main.post {
                                    if (!closed)
                                        result.success(
                                            when (output) {
                                                is JSONObject,
                                                is JSONArray -> output.toString()
                                                else -> output
                                            }
                                        )
                                }
                        } catch (error: Exception) {
                            val code =
                                error.message?.takeIf { it.matches(Regex("[A-Z_]{3,64}")) }
                                    ?: "LOCAL_OPERATION_FAILED"
                            val site =
                                error.stackTrace.firstOrNull {
                                    it.className.startsWith("com.example.xmu_sports_booking.local.")
                                }
                            android.util.Log.w(
                                "XmuLocal",
                                "operation=${call.method},type=${error.javaClass.simpleName},site=${site?.methodName}:${site?.lineNumber}",
                            )
                            if (!closed)
                                main.post { if (!closed) result.error(code,
                                    (error as? SchoolSession.RejectedException)?.description()
                                        ?: (error as? com.example.xmu_sports_booking.capture.SportsProtocol.ReadRejectedException)?.description()
                                        ?: "操作未完成：$code", null) }
                        }
                    }
            }
        }
    }

    override fun close() {
        closed = true
        channel.setMethodCallHandler(null)
        worker.shutdownNow()
        reads.shutdownNow()
    }
}
