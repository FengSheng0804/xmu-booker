package com.example.xmu_sports_booking.local

import android.os.Bundle
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assume
import org.junit.Test
import org.junit.runner.RunWith

/** Explicitly opted-in read benchmark. No prepare, submit, cancel or payment path. */
@RunWith(AndroidJUnit4::class)
class SynchronizationPerformanceDeviceTest {
    @Test fun measureOwnSynchronizationWithoutSubmitting() {
        Assume.assumeTrue(InstrumentationRegistry.getArguments().getString("authorizeR62ReadBenchmark") == "true")
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val engine = LocalEngine.get(instrumentation.targetContext)
        fun measure(name: String, operation: () -> Int): Int {
            val start = SystemClock.elapsedRealtime()
            val count = operation()
            instrumentation.sendStatus(0, Bundle().apply {
                putString("operation", name); putLong("elapsed_ms", SystemClock.elapsedRealtime() - start)
                putInt("count", count)
            })
            return count
        }
        measure("catalog_cold") { engine.syncCatalog().length() }
        measure("orders_after_catalog") { engine.syncOrders().length() }
        val orders = engine.home().getJSONArray("orders")
        val id = orders.getJSONObject(0).getString("order_id")
        repeat(2) { round ->
            measure("entry_${round + 1}") {
                try { engine.orderEntryPass(id).getJSONArray("passes").length() }
                catch (error: Exception) { check(error.message == "ENTRY_PASS_UNAVAILABLE"); 0 }
            }
        }
        measure("orders_warm") { engine.syncOrders().length() }
    }
}
