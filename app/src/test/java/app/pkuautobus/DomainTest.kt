package app.pkuautobus

import org.junit.Assert.*
import org.junit.Test
import java.time.Duration

class DomainTest {
    private val now = parsePortalTime("2026-10-15 10:00:00")
    private fun departure(seconds: Long, remaining: Int = 2) = Departure(Route.OUTBOUND, now.plusSeconds(seconds), 40, 0, remaining)

    @Test fun nextBusHasNoExtraBufferButMustBeFutureAndAvailable() {
        val next = departure(1)
        assertEquals(next, chooseDeparture(listOf(departure(-1), departure(0), departure(20), next, departure(2, 0)), now))
    }
    @Test fun nextDayNeverSelected() {
        assertNull(chooseDeparture(listOf(departure(86_400)), now))
        val midnight = parsePortalTime("2026-10-16 00:00")
        assertNull(chooseDeparture(listOf(Departure(Route.INBOUND, midnight, 1, 0, 10)), midnight.minusSeconds(1)))
    }
    @Test fun qrRemainsValidAfterDepartureButExpiresAtTenMinutes() {
        val ticket = Ticket(1, 2, Route.OUTBOUND, now, "站点", true)
        assertTrue(ticket.unexpired(now.minusSeconds(1)))
        assertTrue(ticket.unexpired(now.plusSeconds(599)))
        assertFalse(ticket.unexpired(now.plusSeconds(600)))
        assertFalse(ticket.unexpired(now.plusSeconds(601)))
    }
    @Test fun serverClockIgnoresWallClockChanges() {
        var nano = 0L
        var wall = now
        val clock = BusClock({ wall }, { nano })
        clock.synchronize(now)
        wall = now.plusSeconds(86_400)
        nano = 5_000_000_000L
        assertEquals(now.plusSeconds(5), clock.now())
    }
    @Test fun districtDetectionAcceptsCoarseFixesAndRejectsStaleOrInvalidLocations() {
        val polygon = listOf(GeoPoint(39.98, 116.29), GeoPoint(40.0, 116.29), GeoPoint(40.0, 116.32), GeoPoint(39.98, 116.32))
        val districts = listOf(District(Route.OUTBOUND, polygon))
        val inside = GeoPoint(39.99, 116.305)
        assertEquals(Route.OUTBOUND, DistrictDetector.detect(inside, 2500.0, Duration.ofMinutes(4), districts))
        assertNull(DistrictDetector.detect(inside, 5001.0, Duration.ZERO, districts))
        assertNull(DistrictDetector.detect(inside, 20.0, Duration.ofSeconds(301), districts))
        assertNull(DistrictDetector.detect(inside, 20.0, Duration.ofSeconds(-1), districts))
        assertEquals(Route.OUTBOUND, DistrictDetector.detect(GeoPoint(39.9801, 116.305), 2500.0, Duration.ZERO, districts))
        assertNull(DistrictDetector.detect(GeoPoint(41.0, 116.305), 20.0, Duration.ZERO, districts))
        assertNull(DistrictDetector.detect(GeoPoint(Double.NaN, 116.305), 20.0, Duration.ZERO, districts))
        assertNull(DistrictDetector.detect(inside, 20.0, Duration.ZERO, districts + District(Route.INBOUND, polygon)))
    }
    @Test fun bundledDistrictsChooseBothCampusesAndNearbyAreasWithoutAReverseGeocoder() {
        val json = requireNotNull(javaClass.classLoader!!.getResourceAsStream("districts.json")).bufferedReader().use { it.readText() }
        val districts = DistrictDetector.read(org.json.JSONArray(json))
        fun route(lat: Double, lon: Double) = DistrictDetector.detect(GeoPoint(lat, lon), 2500.0, Duration.ZERO, districts)
        assertEquals(Route.OUTBOUND, route(39.992, 116.306))
        assertEquals(Route.OUTBOUND, route(39.979, 116.309))
        assertEquals(Route.INBOUND, route(40.175, 116.157))
        assertEquals(Route.INBOUND, route(40.219, 116.225))
        assertNull(route(39.91, 116.42))
    }
    @Test fun uncertainActionsRequireEvidenceForTheExactTrip() {
        val ticket = Ticket(1, 2, Route.INBOUND, now, "", true)
        val book = PendingAction(ActionKind.BOOK, ticket.key)
        assertNotNull(resolvedPending(book, listOf(ReservationRecord(ticket, ReservationStatus.USED))))
        assertNull(resolvedPending(book, listOf(ReservationRecord(ticket.copy(at = now.plusSeconds(60)), ReservationStatus.ACTIVE))))
        val cancel = PendingAction(ActionKind.CANCEL, ticket.key, 2)
        assertNull(resolvedPending(cancel, listOf(ReservationRecord(ticket, ReservationStatus.ACTIVE))))
        assertNull(resolvedPending(cancel, listOf(ReservationRecord(ticket.copy(detailId = 3), ReservationStatus.CANCELLED))))
        assertNotNull(resolvedPending(cancel, listOf(ReservationRecord(ticket, ReservationStatus.CANCELLED))))
    }
    @Test fun encryptedStorePayloadRoundTripPreservesPendingAndQr() {
        val ticket = Ticket(1, 2, Route.OUTBOUND, now, "测试站点", true, BoardingCode("exact payload\n", "测试乘客"))
        val saved = SavedState(ticket, PendingAction(ActionKind.CANCEL, ticket.key, ticket.detailId))
        assertEquals(saved, TicketStore.decode(TicketStore.encode(saved)))
        assertEquals(SavedState(), TicketStore.decode(TicketStore.encode(SavedState())))
    }
}
