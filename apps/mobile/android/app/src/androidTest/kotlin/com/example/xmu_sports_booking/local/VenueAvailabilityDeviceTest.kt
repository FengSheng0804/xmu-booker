package com.example.xmu_sports_booking.local

import android.os.Bundle
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import org.junit.Assume
import org.junit.Test
import org.junit.runner.RunWith

/** Explicitly opted-in school reads only. No prepare, captcha, submission or cancellation. */
@RunWith(AndroidJUnit4::class)
class VenueAvailabilityDeviceTest {
    @Test fun queryLegacyCatalogAgainstLiveSchoolDetails() {
        Assume.assumeTrue(InstrumentationRegistry.getArguments().getString("authorizeR57VenueReads") == "true")
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        check(context.packageName.endsWith(".debug"))
        val legacy = listOf(
            listOf("6", "6", "8", "思明校区健身房", "健身房"),
            listOf("16", "14", "8", "翔安校区健身房", "健身房"),
            listOf("12", "12", "6", "翔安校区游泳馆", "游泳"),
            listOf("13", "13", "3", "翔安校区羽毛球场", "羽毛球"),
            listOf("14", "14", "9", "翔安校区一期网球场", "网球"),
            listOf("15", "15", "2", "翔安校区乒乓球室", "乒乓球"),
            listOf("8", "8", "9", "思明校区东苑网球场", "网球"),
            listOf("4", "4", "6", "王清明游泳馆一楼泳池", "游泳"),
            listOf("5", "5", "6", "王清明游泳馆顶楼泳池", "游泳"),
            listOf("9", "9", "3", "思明校区风雨球馆", "风雨球馆"))
        SchoolSession.open(context).use { session ->
            for (v in legacy) {
                val target = JSONObject().put("stadium_id", v[0]).put("venue_id", v[1])
                    .put("category_id", v[2]).put("stadium_name", v[3]).put("project_name", v[4])
                val result = Bundle().apply { putString("stadium_id", v[0]) }
                try {
                    val inventory = session.inventoryForQuery(target)
                    result.putInt("time_rows", inventory.optJSONArray("time_slot_list")?.length() ?: 0)
                    result.putString("resolved_venue_id", inventory.optJSONObject("resolved_target")?.optString("venue_id"))
                    result.putString("query_result", "READ_COMPLETE")
                } catch (error: Exception) {
                    result.putString("query_result", error.message?.takeIf { it.matches(Regex("[A-Z_]{3,64}")) } ?: "QUERY_FAILED")
                }
                instrumentation.sendStatus(0, result)
            }
        }
    }
}
