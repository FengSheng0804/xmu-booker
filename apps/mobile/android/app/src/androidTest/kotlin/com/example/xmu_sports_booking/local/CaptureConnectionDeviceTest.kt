package com.example.xmu_sports_booking.local

import android.os.Bundle
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.xmu_sports_booking.capture.PhoneAdb
import com.example.xmu_sports_booking.capture.TokenVault
import org.junit.Assume
import org.junit.Test
import org.junit.runner.RunWith

/** Uses the existing owner-paired identity only; no pairing, school request or credential writes. */
@RunWith(AndroidJUnit4::class)
class CaptureConnectionDeviceTest {
    @Test fun inspectExistingLocalConnection() {
        Assume.assumeTrue(InstrumentationRegistry.getArguments().getString("authorizeR56ConnectionCheck") == "true")
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        check(context.packageName.endsWith(".debug"))
        check(TokenVault(context).loadSecret("adb_identity") != null) { "PAIRING_REQUIRED" }
        val port = context.getSharedPreferences("capture_setup", 0).getInt("connection_port", 0)
        val adb = PhoneAdb(context)
        var result = "CONNECTED"
        var discovered = false
        var actualPort = port
        val causes = mutableListOf<String>()
        try {
            actualPort = adb.connectLocal(context, port) { discovered = true }
        } catch (error: Exception) {
            result = "CONNECTION_FAILED"
            var cause: Throwable? = error
            repeat(8) {
                cause?.let { causes.add(it.javaClass.simpleName); cause = it.cause }
            }
        } finally {
            runCatching { adb.disconnect() }
        }
        instrumentation.sendStatus(0, Bundle().apply {
            putString("connection_result", result)
            putString("cause_types", causes.joinToString(" > "))
            putBoolean("discovery_attempted", discovered)
            putBoolean("port_updated", actualPort != port)
        })
    }
}
