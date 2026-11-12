package app.pkuautobus

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate

class PortalClientTest {
    private val date = LocalDate.of(2026, 10, 15)
    private fun slot(time: String, status: Int = 1, margin: Int = 5, price: Int = 0) = JSONObject()
        .put("date", "$date").put("yaxis", time).put("time_id", 40).put("sub_id", 0)
        .put("row", JSONObject().put("status", status).put("total", "900").put("margin", "$margin").put("price", price))
    private fun ok(data: Any) = JSONObject().put("e", 0).put("d", data)
    private fun record(id: Int = 2) = JSONObject().put("id", 1).put("hall_appointment_data_id", id)
        .put("resource_id", 7).put("appointment_tim", " 2026-10-15 10:10\r\n").put("status", 4)
        .put("status_name", "已签到").put("is_cancel", 0).put("resource_address", JSONObject().put("text", "测试站点"))

    @Test fun availabilityUsesLiveRowsAndRejectsPaidClosedFullOrDisabledSlots() {
        val rows = JSONArray().put(slot("10:10")).put(slot("11:00", 0)).put(slot("12:00", 3)).put(slot("13:00", 1, 0))
            .put(slot("14:00", 2)).put(slot("15:00", 1, 5, 10)).put(slot("16:00", 5))
        val parsed = PortalClient.parseDepartures(JSONObject().put("7", rows), Route.OUTBOUND, date)
        assertEquals(1, parsed.size)
        assertEquals(parsePortalTime("2026-10-15 10:10"), parsed.single().at)
        assertEquals(5, parsed.single().remaining)
    }
    @Test(expected = UnsupportedPortal::class) fun differentDateIsNotSilentlyBooked() {
        PortalClient.parseDepartures(JSONObject().put("7", JSONArray().put(slot("10:10").put("date", "2026-10-16"))), Route.OUTBOUND, date)
    }
    @Test fun recordTimeIsDepartureNotCreationTimeAndStatusFilterIsNotRecordStatus() {
        val data = record().put("create_time", "2026-10-14 09:00")
        assertEquals(ReservationStatus.USED, PortalClient.parseReservation(data, false)!!.status)
        val active = PortalClient.parseReservation(data, true)!!
        assertEquals(ReservationStatus.ACTIVE, active.status)
        assertEquals(parsePortalTime("2026-10-15 10:10"), active.ticket.at)
    }
    @Test fun cancelTargetsOnlyTheDisplayedDeparture() {
        var called = false
        val client = PortalClient { path, parameters, post ->
            assertEquals("/site/reservation/single-time-cancel", path)
            assertTrue(post)
            assertEquals(mapOf("appointment_id" to "51", "data_id[0]" to "73"), parameters)
            called = true; ok(JSONObject())
        }
        client.cancel(Ticket(51, 73, Route.INBOUND, parsePortalTime("2026-10-15 11:20"), "", true))
        assertTrue(called)
    }
    @Test fun bookingPreservesServerIdentifiersAndSingleSlot() {
        val client = PortalClient { path, params, post ->
            assertTrue(post); assertEquals("/site/reservation/launch", path)
            assertEquals("7", params["resource_id"])
            val data = JSONArray(params.getValue("data"))
            assertEquals(1, data.length()); assertEquals(40, data.getJSONObject(0).getInt("period"))
            assertEquals(0, data.getJSONObject(0).getInt("sub_resource_id"))
            assertEquals("2026-10-15", data.getJSONObject(0).getString("date"))
            ok(JSONObject())
        }
        client.book(Departure(Route.OUTBOUND, parsePortalTime("2026-10-15 10:10"), 40, 0, 5))
    }
    @Test fun qrUsesTheReservationIdAndExactServerPayload() {
        val client = PortalClient { path, params, post ->
            assertFalse(post); assertEquals("/site/reservation/get-sign-qrcode", path)
            assertEquals(mapOf("id" to "1", "type" to "0", "hall_appointment_data_id" to "2"), params)
            ok(JSONObject().put("code", "opaque-code\n").put("name", "测试乘客\r\nTEST-ID\r\n测试学院"))
        }
        assertEquals(BoardingCode("opaque-code\n", "测试乘客 · TEST-ID"), client.boardingCode(PortalClient.parseReservation(record(), true)!!.ticket))
    }
    @Test fun reservationPaginationIsComplete() {
        var calls = 0
        val client = PortalClient { _, params, _ ->
            calls++
            assertEquals("2", params["status"]); assertEquals("asc", params["sort"])
            assertEquals(date.toString(), params["date_sta"])
            ok(JSONObject().put("total", 2).put("data", JSONArray().put(record(params.getValue("p").toInt()))))
        }
        assertEquals(2, client.reservations(date).size)
        assertEquals(2, calls)
    }
    @Test(expected = LoginRequired::class) fun expiredSessionStopsBookingFlow() {
        PortalClient { _, _, _ -> JSONObject().put("e", 10042) }.verifyRoutes()
    }
    @Test(expected = UnsupportedPortal::class) fun changedRouteIdsAreNotGuessed() {
        PortalClient { _, _, _ -> ok(JSONArray().put(JSONObject().put("id", 7).put("name", "另一线路"))) }.verifyRoutes()
    }
    @Test(expected = UnsupportedPortal::class) fun unknownQrUsageStateDoesNotBecomeUnused() {
        PortalClient { _, _, _ -> ok(JSONObject().put("is_used", "unknown")) }.codeUsed(BoardingCode("x", ""))
    }
    @Test(expected = UnsupportedPortal::class) fun missingBookingRulesRequireOfficialPortal() {
        PortalClient { path, _, post ->
            assertFalse(post)
            assertEquals("/site/reservation/resource-info", path)
            ok(JSONObject().put("id", 7).put("name", Route.OUTBOUND.portalName).put("apply_status", true)
                .put("is_verify", 0).put("is_group", 0).put("show_reason", 0)
                .put("sub_type", 0).put("json_advanced_rule", JSONObject().put("mode", 0)))
        }.departures(Route.OUTBOUND, date)
    }
}
