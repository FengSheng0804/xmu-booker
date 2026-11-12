package com.example.xmu_sports_booking

import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import com.example.xmu_sports_booking.capture.CaptureKeepAliveService
import com.example.xmu_sports_booking.capture.PhoneAdb
import com.example.xmu_sports_booking.capture.SportsCaptureEngine
import com.example.xmu_sports_booking.capture.SportsProtocol
import com.example.xmu_sports_booking.capture.TokenVault
import io.flutter.embedding.android.FlutterActivity
import io.flutter.plugin.common.MethodChannel
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong

/**
 * Shared owner-authorized bridge. No plaintext token, signed URL, private ADB key or arbitrary
 * command API.
 */
class CaptureBridge(private val activity: FlutterActivity, channel: MethodChannel) : AutoCloseable {
    private val main = Handler(Looper.getMainLooper())
    private val executor = Executors.newSingleThreadExecutor()
    private val generation = AtomicLong()
    private val vault = TokenVault(activity)
    private val preferences = activity.getSharedPreferences("capture_setup", 0)
    @Volatile private var phoneAdb: PhoneAdb? = null
    @Volatile private var engine: SportsCaptureEngine? = null
    @Volatile private var connected = false
    @Volatile private var stage = "READY"
    private var pending: MethodChannel.Result? = null

    init {
        channel.setMethodCallHandler { call, reply ->
            if (
                !BuildConfig.OWN_SPORTS_AUTH_ENABLED ||
                    activity.packageName != BuildConfig.APPLICATION_ID
            ) {
                reply.notImplemented()
                return@setMethodCallHandler
            }
            try {
                when (call.method) {
                    "status" -> reply.success(metadata())
                    "settings" -> {
                        val direct = Intent("android.settings.WIRELESS_DEBUGGING_SETTINGS")
                        val intent =
                            if (direct.resolveActivity(activity.packageManager) != null) direct
                            else Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS)
                        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        activity.startActivity(intent)
                        reply.success(null)
                    }
                    "pair" -> {
                        val port = call.argument<Int>("pairing_port") ?: 0
                        val code = call.argument<String>("code") ?: ""
                        val connectPort = call.argument<Int>("connection_port") ?: 0
                        if (port !in 1024..65535 || !code.matches(Regex("[0-9]{6}"))) {
                            reply.error("PAIRING_INPUT_REQUIRED", "请填写系统显示的配对端口和六位配对码", null)
                        } else
                            runOperation(reply) { current ->
                                stage = "PAIRING_LOCAL_ADB"
                                val adb = getAdb()
                                if (!adb.pairLocal(port, code)) throw Exception("PAIRING_FAILED")
                                if (generation.get() != current) return@runOperation
                                preferences.edit().putBoolean("paired", true).apply()
                                if (connectPort in 1024..65535) {
                                    adb.connectLocal(connectPort)
                                    connected = true
                                    preferences
                                        .edit()
                                        .putInt("connection_port", connectPort)
                                        .apply()
                                }
                                stage = "READY"
                            }
                    }
                    "connect" -> {
                        val port = call.argument<Int>("connection_port") ?: 0
                        runOperation(reply) { _ ->
                            stage = "CONNECTING_LOCAL_ADB"
                            getAdb().connectLocal(port)
                            connected = true
                            preferences.edit().putInt("connection_port", port).apply()
                            stage = "READY"
                        }
                    }
                    "check_debug" -> {
                        val port =
                            call.argument<Int>("connection_port")
                                ?: preferences.getInt("connection_port", 0)
                        runOperation(reply) { _ ->
                            val adb = getAdb()
                            adb.connectLocal(port)
                            connected = true
                            val visible = SportsCaptureEngine().debugVisible(adb)
                            preferences
                                .edit()
                                .putBoolean("debug_visible", visible)
                                .putInt("connection_port", port)
                                .apply()
                            if (!visible) throw Exception("WECHAT_DEBUG_NOT_ENABLED")
                            stage = "WECHAT_DEBUG_READY"
                        }
                    }
                    "capture" -> {
                        val account = call.argument<String>("student") ?: ""
                        val port =
                            call.argument<Int>("connection_port")
                                ?: preferences.getInt("connection_port", 0)
                        if (!account.matches(Regex("[0-9]{6,24}")))
                            reply.error("ACCOUNT_REQUIRED", "请填写本人的学号", null)
                        else {
                            activity.startForegroundService(
                                Intent(activity, CaptureKeepAliveService::class.java)
                            )
                            runOperation(reply) { current ->
                                stage = "CONNECTING_LOCAL_ADB"
                                val adb = getAdb()
                                adb.connectLocal(port)
                                connected = true
                                preferences.edit().putInt("connection_port", port).apply()
                                vault.saveSecret("expected_student", account)
                                val capture = SportsCaptureEngine()
                                engine = capture
                                main.post {
                                    if (generation.get() == current)
                                        activity.packageManager
                                            .getLaunchIntentForPackage("com.tencent.mm")
                                            ?.let { activity.startActivity(it) }
                                }
                                val query = capture.capture(adb, account)
                                if (generation.get() != current) return@runOperation
                                engine = null
                                stage = "CHECKING_OWN_IDENTITY_AND_ORDERS"
                                val record =
                                    SportsProtocol.verifyAndRecord(query, account) { phase ->
                                        stage = phase
                                    }
                                synchronized(generation) {
                                    if (generation.get() != current) return@runOperation
                                    vault.save(record)
                                }
                                stage = "SAVED"
                            }
                        }
                    }
                    "cancel" -> {
                        cancel()
                        reply.success(null)
                    }
                    "forget" -> {
                        cancel()
                        runOperation(reply) { _ ->
                            synchronized(generation) {
                                com.example.xmu_sports_booking.local.LocalEngine.get(activity)
                                    .forgetAuthorization()
                            }
                        }
                    }
                    else -> reply.notImplemented()
                }
            } catch (_: Exception) {
                reply.error("CAPTURE_FAILED", "操作未完成，请重新检查授权状态", null)
            }
        }
    }

    private fun getAdb(): PhoneAdb = phoneAdb ?: PhoneAdb(activity).also { phoneAdb = it }

    private fun runOperation(
        reply: MethodChannel.Result,
        resultProvider: () -> Any = { metadata() },
        operation: (Long) -> Unit,
    ) {
        if (pending != null) {
            reply.error("BUSY", "已有操作正在运行", null)
            return
        }
        val current = generation.incrementAndGet()
        pending = reply
        executor.execute {
            try {
                operation(current)
                if (generation.get() != current) return@execute
                val result = resultProvider()
                main.post {
                    if (generation.get() == current) {
                        pending?.success(result)
                        pending = null
                        engine = null
                        activity.stopService(Intent(activity, CaptureKeepAliveService::class.java))
                    }
                }
            } catch (error: Exception) {
                // Stage/type only: no exception message, account, URL, cookie or token.
                preferences
                    .edit()
                    .putString("last_failure_stage", engine?.stage ?: stage)
                    .putString("last_error_class", error.javaClass.simpleName)
                    .putString(
                        "last_error_site",
                        error.stackTrace.firstOrNull()?.let {
                            "${it.className}.${it.methodName}:${it.lineNumber}"
                        } ?: "unknown",
                    )
                    .putLong("last_failure_at", System.currentTimeMillis())
                    .commit()
                val code = safeCode(error.message)
                stage = code
                main.post {
                    if (generation.get() == current) {
                        pending?.error(code, code, null)
                        pending = null
                        engine = null
                        activity.stopService(Intent(activity, CaptureKeepAliveService::class.java))
                    }
                }
            }
        }
        main.postDelayed({ if (generation.get() == current && pending != null) cancel() }, 155000)
    }

    private fun metadata(): Map<String, Any> {
        val result =
            mutableMapOf<String, Any>(
                "adb_connected" to connected,
                "adb_paired" to preferences.getBoolean("paired", false),
                "connection_port" to preferences.getInt("connection_port", 0),
                "capture_stage" to (engine?.stage ?: stage),
            )
        val data = vault.metadata()
        result["last_failure_type"] = preferences.getString("last_error_class", "") ?: ""
        result["last_failure_stage"] = preferences.getString("last_failure_stage", "") ?: ""
        result["debug_visible"] = preferences.getBoolean("debug_visible", false)
        data.keys().forEach { key -> result[key] = data.get(key) }
        if (!data.optBoolean("has_token"))
            vault.loadSecret("expected_student")?.let { result["expected_student"] = it }
        return result
    }

    private fun safeCode(code: String?): String =
        if (
            code in
                setOf(
                    "CANCELLED",
                    "ACCOUNT_REQUIRED",
                    "OWNER_MISMATCH",
                    "WECHAT_DEBUG_NOT_ENABLED",
                    "SPORTS_PAGE_NOT_FOUND",
                    "READ_CHECK_HTTP_FAILED",
                    "READ_CHECK_REJECTED",
                    "SCOPED_SESSION_MISSING",
                    "IDENTITY_CONTEXT_MISMATCH",
                    "ORDERS_OWNER_MISMATCH",
                    "ADB_PORT_REQUIRED",
                    "PAIRING_INPUT_REQUIRED",
                    "PAIRING_FAILED",
                    "PAIRING_PORT_CLOSED",
                    "PAIRING_TIMEOUT",
                    "PAIRING_TLS_FAILED",
                    "PAIRING_CODE_REJECTED",
                    "PAIRING_CRYPTO_FAILED",
                    "ADB_NOT_CONNECTED",
                )
        )
            code!!
        else "CAPTURE_FAILED"

    private fun cancel() {
        synchronized(generation) { generation.incrementAndGet() }
        engine?.cancel()
        engine = null
        val previous = phoneAdb
        phoneAdb = null
        connected = false
        stage = "CANCELLED"
        pending?.error("CANCELLED", "已停止本次操作", null)
        pending = null
        activity.stopService(Intent(activity, CaptureKeepAliveService::class.java))
        Thread { runCatching { previous?.disconnect() } }.start()
    }

    override fun close() {
        cancel()
        executor.shutdownNow()
    }
}
