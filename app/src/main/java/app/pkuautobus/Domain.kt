package app.pkuautobus

import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

val CAMPUS_ZONE: ZoneId = ZoneId.of("Asia/Shanghai")
val TRIP_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("MM月dd日 HH:mm")

enum class Route(val id: Int, val portalName: String, val origin: String, val destination: String) {
    OUTBOUND(7, "燕园校区→新燕园校区", "燕园", "新燕园"),
    INBOUND(4, "新燕园校区→燕园校区", "新燕园", "燕园");
    val title get() = "$origin → $destination"
    companion object { fun fromId(id: Int) = entries.find { it.id == id } }
}

data class Departure(val route: Route, val at: Instant, val periodId: Int, val subId: Int, val remaining: Int) {
    val key get() = TripKey(route, at)
}
data class TripKey(val route: Route, val at: Instant)
data class BoardingCode(val payload: String, val passenger: String)
data class Ticket(
    val id: Long, val detailId: Long, val route: Route, val at: Instant,
    val address: String, val canCancel: Boolean, val code: BoardingCode? = null
) {
    val key get() = TripKey(route, at)
    val expiresAt: Instant get() = at.plusSeconds(600)
    fun unexpired(now: Instant) = now < expiresAt
    val label get() = "${route.title} · ${TRIP_FORMAT.format(at.atZone(CAMPUS_ZONE))}"
}

enum class ActionKind { BOOK, CANCEL }
data class PendingAction(val kind: ActionKind, val key: TripKey, val detailId: Long = 0)
enum class ReservationStatus { ACTIVE, CANCELLED, USED, EXPIRED, UNKNOWN }
data class ReservationRecord(val ticket: Ticket, val status: ReservationStatus)

fun chooseDeparture(departures: List<Departure>, now: Instant): Departure? = departures
    .filter { it.remaining > 0 && it.at > now && it.at.atZone(CAMPUS_ZONE).toLocalDate() == now.atZone(CAMPUS_ZONE).toLocalDate() }
    .minByOrNull { it.at }

fun resolvedPending(pending: PendingAction, records: List<ReservationRecord>): ReservationRecord? = records.firstOrNull {
    if (pending.kind == ActionKind.BOOK) it.ticket.key == pending.key && it.status in setOf(ReservationStatus.ACTIVE, ReservationStatus.USED)
    else it.ticket.detailId == pending.detailId && it.status == ReservationStatus.CANCELLED
}

fun parsePortalTime(value: String): Instant = LocalDateTime.parse(value.trim().replace(' ', 'T'))
    .atZone(CAMPUS_ZONE).toInstant()

class BusClock(
    private val wallTime: () -> Instant = Instant::now,
    private val monotonic: () -> Long = System::nanoTime
) {
    private var server: Instant? = null
    private var anchor = 0L
    @Synchronized fun now(): Instant = server?.plusNanos((monotonic() - anchor).coerceAtLeast(0)) ?: wallTime()
    @Synchronized fun synchronize(time: Instant) { server = time; anchor = monotonic() }
    fun today(): LocalDate = now().atZone(CAMPUS_ZONE).toLocalDate()
}

data class GeoPoint(val latitude: Double, val longitude: Double)
data class District(val route: Route, val boundary: List<GeoPoint>)

object DistrictDetector {
    fun read(data: org.json.JSONArray): List<District> = (0 until data.length()).map { index ->
        val item = data.getJSONObject(index)
        val points = item.getJSONArray("boundary")
        District(requireNotNull(Route.fromId(item.getInt("route"))), (0 until points.length()).map { p ->
            val pair = points.getJSONArray(p); GeoPoint(pair.getDouble(0), pair.getDouble(1))
        })
    }

    fun detect(point: GeoPoint, accuracy: Double, age: Duration, districts: List<District>): Route? {
        if (!point.latitude.isFinite() || !point.longitude.isFinite() || point.latitude !in -90.0..90.0 || point.longitude !in -180.0..180.0 ||
            !accuracy.isFinite() || accuracy !in 0.0..5_000.0 || age.isNegative || age > Duration.ofMinutes(5)) return null
        // Approximate location is sufficient; the campus toggle lets riders correct a district-border estimate.
        return districts.filter { inside(point, it.boundary) }.singleOrNull()?.route
    }

    private fun inside(point: GeoPoint, polygon: List<GeoPoint>): Boolean {
        var inside = false
        var previous = polygon.lastOrNull() ?: return false
        for (current in polygon) {
            if ((current.latitude > point.latitude) != (previous.latitude > point.latitude) &&
                point.longitude < (previous.longitude - current.longitude) * (point.latitude - current.latitude) /
                (previous.latitude - current.latitude) + current.longitude) inside = !inside
            previous = current
        }
        return inside
    }
}
