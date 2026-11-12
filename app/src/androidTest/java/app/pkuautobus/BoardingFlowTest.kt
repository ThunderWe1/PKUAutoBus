package app.pkuautobus

import android.graphics.Bitmap
import android.os.SystemClock
import android.widget.ImageButton
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.assertion.ViewAssertions.doesNotExist
import androidx.test.espresso.matcher.ViewMatchers.*
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.zxing.BinaryBitmap
import com.google.zxing.MultiFormatReader
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.HybridBinarizer
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class BoardingFlowTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private lateinit var app: BusApplication
    private lateinit var store: TicketStore
    private lateinit var fake: FakePortal
    private var scenario: ActivityScenario<MainActivity>? = null
    private val now = parsePortalTime("2026-10-15 17:30:00")
    private val departure = parsePortalTime("2026-10-15 18:30:00")
    private val code = BoardingCode("PKUAUTOBUS-TEST-ONLY-DO-NOT-BOARD", "测试乘客 · TEST-ONLY")
    private val ticket get() = Ticket(101, 202, Route.OUTBOUND, departure, "测试站点 · 非真实预约", true, code)

    @Before fun setup() {
        app = instrumentation.targetContext.applicationContext as BusApplication
        store = TicketStore(app)
        fake = FakePortal()
        instrumentation.runOnMainSync {
            app.model.dispose()
            store.clear()
            store.save(SavedState(ticket))
            app.model = BusModel(PortalClient(fake), BusClock({ now }), store)
        }
    }
    @After fun cleanup() {
        scenario?.close()
        instrumentation.runOnMainSync { app.model.dispose(); store.clear() }
    }
    private fun launch() {
        scenario = ActivityScenario.launch(MainActivity::class.java)
        await { !app.model.busy && !app.model.stale }
    }
    private fun await(predicate: () -> Boolean) {
        val end = SystemClock.elapsedRealtime() + 10_000
        while (SystemClock.elapsedRealtime() < end) {
            var done = false
            instrumentation.runOnMainSync { done = predicate() }
            if (done) { instrumentation.waitForIdleSync(); return }
            SystemClock.sleep(50)
        }
        fail("Timed out waiting for app state")
    }
    private fun screenshot(name: String) {
        instrumentation.waitForIdleSync()
        val file = File(app.getExternalFilesDir(null), name)
        instrumentation.uiAutomation.takeScreenshot().let { bitmap ->
            file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
        }
    }

    @Test fun qrSurvivesRecreationAndLocationChangesAndDecodesExactly() {
        launch()
        onView(withId(R.id.qr)).check(matches(isDisplayed()))
        onView(withId(R.id.ticket_route)).check(matches(withText("燕园 → 新燕园")))
        screenshot("boarding-demo.png")
        val bitmap = MainActivity.qrBitmap(code.payload, 840)
        val pixels = IntArray(bitmap.width * bitmap.height)
        bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        val decoded = MultiFormatReader().decode(BinaryBitmap(HybridBinarizer(RGBLuminanceSource(bitmap.width, bitmap.height, pixels))))
        assertEquals(code.payload, decoded.text)
        bitmap.recycle()
        instrumentation.runOnMainSync { app.model.selectRoute(Route.INBOUND, false) }
        instrumentation.runOnMainSync { app.model.resetOrigin() }
        assertEquals(Route.OUTBOUND, app.model.selected!!.route)
        scenario!!.recreate()
        await { !app.model.busy }
        onView(withId(R.id.qr)).check(matches(isDisplayed()))
        assertEquals(code.payload, store.load().ticket!!.code!!.payload)
        val privateXml = File(app.applicationInfo.dataDir, "shared_prefs/boarding.xml").readText()
        assertFalse(privateXml.contains(code.payload))
        assertFalse(privateXml.contains("TEST-ONLY"))
    }

    @Test fun cancellationRequiresSecondTapAndOnlyCancelsThisDeparture() {
        launch()
        onView(withId(R.id.cancel_reservation)).perform(click())
        onView(withText("保留预约")).perform(click())
        assertEquals(0, fake.cancellations.get())
        onView(withId(R.id.qr)).check(matches(isDisplayed()))
        onView(withId(R.id.cancel_reservation)).perform(click())
        screenshot("cancellation-demo.png")
        onView(withText("确认取消")).perform(click())
        await { fake.cancellations.get() == 1 && app.model.selected == null && app.model.pending == null }
        assertEquals("101", fake.cancelParameters["appointment_id"])
        assertEquals("202", fake.cancelParameters["data_id[0]"])
        assertNull(store.load().ticket)
        assertEquals(0, fake.bookings.get())
    }

    @Test fun cachedQrRemainsVisibleOfflineAndPollingPausesInBackground() {
        launch()
        fake.offline = true
        instrumentation.runOnMainSync { app.model.refresh() }
        await { !app.model.busy && app.model.stale }
        onView(withId(R.id.qr)).check(matches(isDisplayed()))
        assertTrue(app.model.status.contains("状态未更新"))
        onView(withId(R.id.status)).check(matches(isDisplayed()))
        scenario!!.moveToState(androidx.lifecycle.Lifecycle.State.CREATED)
        val count = fake.calls.get()
        SystemClock.sleep(2500)
        assertEquals(count, fake.calls.get())
    }

    @Test fun usedCodeDisappearsWithoutMakingAnotherBooking() {
        launch()
        fake.used = true
        await { app.model.selected == null }
        assertEquals(0, fake.bookings.get())
        assertNull(store.load().ticket)
    }

    @Test fun uncertainCancellationPersistsAndDoesNotRepeatAfterRestart() {
        launch()
        fake.cancelTimeout = true
        onView(withId(R.id.cancel_reservation)).perform(click())
        onView(withText("确认取消")).perform(click())
        await { !app.model.busy && app.model.pending != null }
        assertEquals(1, fake.cancellations.get())
        assertNotNull(store.load().pending)
        scenario!!.close(); scenario = null
        instrumentation.runOnMainSync {
            app.model.dispose()
            app.model = BusModel(PortalClient(fake), BusClock({ now }), store)
        }
        scenario = ActivityScenario.launch(MainActivity::class.java)
        await { !app.model.busy }
        assertNotNull(app.model.pending)
        assertEquals(1, fake.cancellations.get())
        onView(withId(R.id.qr)).check(matches(isDisplayed()))
    }

    @Test fun bookingReadbackFailureRemainsPendingAndDoubleTapDoesNotResubmit() {
        instrumentation.runOnMainSync {
            app.model.dispose(); store.clear()
            fake.active = false
            app.model = BusModel(PortalClient(fake), BusClock({ now }), store)
            app.model.selectRoute(Route.OUTBOUND, true)
        }
        launch()
        screenshot("booking-demo.png")
        fake.failBookingReadback = true
        onView(withId(R.id.reserve)).perform(click())
        await { !app.model.busy && app.model.pending != null }
        assertEquals(1, fake.bookings.get())
        assertNotNull(store.load().pending)
        instrumentation.runOnMainSync { app.model.clearForLogin() }
        assertNotNull(store.load().pending)
        assertNull(store.load().ticket)
        instrumentation.runOnMainSync { app.model.book(); app.model.book() }
        assertEquals(1, fake.bookings.get())
        fake.failBookingReadback = false
        instrumentation.runOnMainSync { app.model.refresh() }
        await { !app.model.busy && app.model.selected != null && app.model.pending == null }
        assertEquals(1, fake.bookings.get())
    }

    @Test fun expiryClearsTheCachedQrAtExactlyTenMinutes() {
        launch()
        instrumentation.runOnMainSync { app.model.clock.synchronize(departure.plusSeconds(599)) }
        assertTrue(app.model.selected!!.unexpired(app.model.clock.now()))
        instrumentation.runOnMainSync { app.model.clock.synchronize(departure.plusSeconds(600)) }
        await { app.model.selected == null }
        assertNull(store.load().ticket)
    }

    @Test fun locationArrivingDuringInitialRefreshLoadsTheCorrectPreview() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val calls = AtomicInteger()
        val transport = PortalTransport { path, parameters, post ->
            if (calls.incrementAndGet() == 1) {
                entered.countDown()
                check(release.await(5, TimeUnit.SECONDS))
            }
            fake.request(path, parameters, post)
        }
        instrumentation.runOnMainSync {
            app.model.dispose(); store.clear(); fake.active = false
            app.model = BusModel(PortalClient(transport), BusClock({ now }), store)
            app.model.resume()
        }
        assertTrue(entered.await(5, TimeUnit.SECONDS))
        instrumentation.runOnMainSync { app.model.selectRoute(Route.OUTBOUND, false) }
        release.countDown()
        await { !app.model.busy && app.model.preview != null }
        assertEquals(Route.OUTBOUND, app.model.route)
        assertEquals(Route.OUTBOUND, app.model.preview!!.route)
        assertEquals(0, fake.bookings.get())
    }

    @Test fun minimalControlsToggleCampusAndResetToLocationOnReopen() {
        instrumentation.runOnMainSync {
            app.model.dispose(); store.clear(); fake.active = false
            app.getSharedPreferences("location_permission", 0).edit().putBoolean("asked", true).commit()
            app.model = BusModel(PortalClient(fake), BusClock({ now }), store)
            app.model.selectRoute(Route.OUTBOUND, false)
        }
        launch()
        onView(withId(R.id.origin)).check(matches(withText("燕园")))
        onView(withId(R.id.reserve)).check(matches(withText("预约")))
        onView(withText("PKU / AUTO BUS")).check(doesNotExist())
        onView(withText("校区班车")).check(doesNotExist())
        onView(withId(R.id.departure_detail)).check(matches(withText("10月15日 周四")))
        onView(withId(R.id.status)).check(matches(withEffectiveVisibility(Visibility.GONE)))
        listOf(R.id.account, R.id.refresh, R.id.official_portal, R.id.about).forEach { id ->
            onView(withId(id)).check(matches(isAssignableFrom(ImageButton::class.java)))
        }
        onView(withId(R.id.about)).check(matches(withContentDescription("开源许可"))).perform(click())
        onView(withText("开源许可")).check(matches(isDisplayed()))
        onView(withText("知道了")).check(doesNotExist())
        screenshot("licenses-demo.png")
        onView(withText("关闭")).perform(click())
        onView(withId(R.id.origin)).perform(click())
        await { !app.model.busy && app.model.preview?.route == Route.INBOUND }
        onView(withId(R.id.origin)).check(matches(withText("新燕园")))
        onView(withId(R.id.origin)).perform(click())
        await { !app.model.busy && app.model.preview?.route == Route.OUTBOUND }
        screenshot("booking-demo.png")
        assertEquals(0, fake.bookings.get())
        scenario!!.moveToState(androidx.lifecycle.Lifecycle.State.CREATED)
        assertNull(app.model.route)
        assertFalse(app.model.manualRoute)
        scenario!!.moveToState(androidx.lifecycle.Lifecycle.State.RESUMED)
        instrumentation.runOnMainSync { app.model.selectRoute(Route.INBOUND, false) }
        await { !app.model.busy && app.model.preview?.route == Route.INBOUND }
        onView(withId(R.id.origin)).check(matches(withText("新燕园")))
        assertEquals(0, fake.bookings.get())
    }

    private inner class FakePortal : PortalTransport {
        @Volatile var active = true
        @Volatile var used = false
        @Volatile var offline = false
        @Volatile var cancelTimeout = false
        @Volatile var failBookingReadback = false
        val calls = AtomicInteger()
        val cancellations = AtomicInteger()
        val bookings = AtomicInteger()
        var cancelParameters: Map<String, String> = emptyMap()
        private fun ok(data: Any) = JSONObject().put("e", 0).put("d", data)
        private fun record() = JSONObject().put("id", 101).put("hall_appointment_data_id", 202).put("resource_id", 7)
            .put("appointment_tim", "2026-10-15 18:30").put("status_name", if (used) "已签到" else if (active) "已预约" else "已撤销")
            .put("is_cancel", if (active && !used) 1 else 0).put("resource_address", JSONObject().put("text", ticket.address))
        override fun request(path: String, parameters: Map<String, String>, post: Boolean): JSONObject {
            calls.incrementAndGet()
            if (offline) throw IOException("simulated offline")
            return when (path.substringAfterLast('/')) {
                "list-name" -> ok(JSONArray().apply { Route.entries.forEach { put(JSONObject().put("id", it.id).put("name", it.portalName)) } })
                "my-list-time" -> {
                    if (failBookingReadback && bookings.get() > 0) throw LoginRequired()
                    val visible = parameters["status"] == "0" || (active && !used)
                    ok(JSONObject().put("total", if (visible) 1 else 0).put("data", JSONArray().apply { if (visible) put(record()) }))
                }
                "get-sign-qrcode" -> ok(JSONObject().put("code", code.payload).put("name", code.passenger))
                "code-is-used" -> ok(JSONObject().put("is_used", used))
                "single-time-cancel" -> {
                    assertTrue(post); cancellations.incrementAndGet(); cancelParameters = parameters
                    if (cancelTimeout) throw IOException("simulated lost response")
                    active = false; ok(JSONObject())
                }
                "resource-info" -> ok(JSONObject().put("id", parameters.getValue("resource_id").toInt())
                    .put("name", Route.fromId(parameters.getValue("resource_id").toInt())!!.portalName).put("apply_status", true)
                    .put("is_verify", 0).put("is_group", 0).put("is_notice", 0).put("show_reason", 0)
                    .put("sub_type", 0).put("json_advanced_rule", JSONObject().put("mode", 0)))
                "resource-info-margin" -> ok(JSONObject().put(parameters.getValue("resource_id"), JSONArray().put(JSONObject().put("date", "2026-10-15").put("yaxis", "18:30")
                    .put("time_id", 44).put("sub_id", 0).put("row", JSONObject().put("status", 1).put("price", 0).put("total", 900).put("margin", 890)))))
                "launch" -> { assertTrue(post); bookings.incrementAndGet(); active = true; ok(JSONObject()) }
                else -> error("Unexpected endpoint $path")
            }
        }
    }
}
