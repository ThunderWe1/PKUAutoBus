package app.pkuautobus

import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate

open class PortalException(message: String) : Exception(message)
class LoginRequired : PortalException("登录已过期，请重新登录北大账户")
class UnsupportedPortal(message: String = "官网规则或数据已变化，请在官网办理") : PortalException(message)

fun interface PortalTransport {
    fun request(path: String, parameters: Map<String, String>, post: Boolean): JSONObject
}

class PortalClient(private val transport: PortalTransport) {
    private fun call(path: String, parameters: Map<String, String> = emptyMap(), post: Boolean = false): JSONObject {
        val result = transport.request("/site/reservation/$path", parameters, post)
        val error = result.opt("e")?.toString()?.toIntOrNull() ?: throw UnsupportedPortal()
        if (error == 10042 || error == 100421) throw LoginRequired()
        if (error != 0) throw PortalException(result.optString("m", "官网请求失败").take(200))
        return result
    }

    fun verifyRoutes() {
        val routes = call("list-name", mapOf("hall_id" to "1")).getJSONArray("d")
        Route.entries.forEach { expected ->
            val found = (0 until routes.length()).map { routes.getJSONObject(it) }.singleOrNull { it.getInt("id") == expected.id }
            if (found?.optString("name") != expected.portalName) throw UnsupportedPortal("班车线路已变化，请在官网确认")
        }
    }

    fun departures(route: Route, date: LocalDate): List<Departure> {
        val rules = call("resource-info", mapOf("resource_id" to route.id.toString())).getJSONObject("d")
        if (rules.getInt("id") != route.id || rules.getString("name") != route.portalName) throw UnsupportedPortal()
        if (!rules.flag("apply_status")) throw PortalException("当前账户没有这条线路的预约权限")
        // shortcut: support the observed free individual bus flow; changed rules use the official portal.
        if (listOf("is_verify", "is_group", "is_notice", "show_reason").any { rules.requiredFlag(it) } ||
            rules.getInt("sub_type") != 0 || rules.getJSONObject("json_advanced_rule").getInt("mode") != 0) throw UnsupportedPortal()
        val response = call("resource-info-margin", mapOf("resource_id" to route.id.toString(), "start_time" to date.toString(), "end_time" to date.toString()))
        return parseDepartures(response.getJSONObject("d"), route, date)
    }

    fun reservations(date: LocalDate, activeOnly: Boolean = true): List<ReservationRecord> {
        val records = mutableListOf<ReservationRecord>()
        var page = 1
        var read = 0
        do {
            val result = call("my-list-time", mapOf("p" to page.toString(), "page_size" to "50", "status" to if (activeOnly) "2" else "0",
                "sort_time" to "true", "sort" to "asc", "date_sta" to date.toString(), "date_end" to date.toString())).getJSONObject("d")
            val data = result.getJSONArray("data")
            for (index in 0 until data.length()) parseReservation(data.getJSONObject(index), activeOnly)?.let(records::add)
            read += data.length()
            val total = result.getInt("total")
            if (read >= total) break
            if (data.length() == 0 || page >= 100) throw UnsupportedPortal("无法完整读取预约列表，请在官网核实")
            page++
        } while (true)
        return records.distinctBy { it.ticket.detailId }
    }

    fun book(departure: Departure) {
        val data = JSONArray().put(JSONObject().put("date", departure.at.atZone(CAMPUS_ZONE).toLocalDate().toString())
            .put("period", departure.periodId).put("sub_resource_id", departure.subId))
        call("launch", mapOf("resource_id" to departure.route.id.toString(), "data" to data.toString(),
            "code" to "", "remarks" to "", "deduct_num" to "", "position_data" to ""), true)
    }

    fun boardingCode(ticket: Ticket): BoardingCode {
        val data = call("get-sign-qrcode", mapOf("id" to ticket.id.toString(), "type" to "0", "hall_appointment_data_id" to ticket.detailId.toString())).getJSONObject("d")
        val payload = data.getString("code")
        if (payload.isBlank() || payload.length > 16_384) throw UnsupportedPortal("官网返回的乘车码无效，请打开官网核实")
        return BoardingCode(payload, data.optString("name").lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.take(2).joinToString(" · "))
    }

    fun codeUsed(code: BoardingCode): Boolean = call("code-is-used", mapOf("code" to code.payload)).getJSONObject("d").requiredFlag("is_used")

    fun cancel(ticket: Ticket) {
        if (!ticket.canCancel) throw PortalException("官网当前不允许取消此预约")
        call("single-time-cancel", mapOf("appointment_id" to ticket.id.toString(), "data_id[0]" to ticket.detailId.toString()), true)
    }

    companion object {
        fun parseDepartures(data: JSONObject, route: Route, date: LocalDate): List<Departure> {
            val slots = data.getJSONArray(route.id.toString())
            return (0 until slots.length()).mapNotNull { index ->
                val slot = slots.getJSONObject(index)
                val row = slot.getJSONObject("row")
                val slotDate = slot.getString("date").trim()
                if (slotDate != date.toString()) throw UnsupportedPortal("官网返回了不同日期的班次")
                if (row.getInt("status") != 1 || row.getDouble("price") != 0.0 || row.getInt("total") <= 0 || row.getInt("margin") <= 0) return@mapNotNull null
                val period = slot.getInt("time_id")
                val sub = slot.getInt("sub_id")
                if (period <= 0 || sub < 0) throw UnsupportedPortal()
                Departure(route, parsePortalTime("$slotDate ${slot.getString("yaxis").trim()}"), period, sub, row.getInt("margin"))
            }
        }

        fun parseReservation(data: JSONObject, activeOnly: Boolean): ReservationRecord? {
            val route = Route.fromId(data.getInt("resource_id")) ?: return null
            val id = data.getLong("id")
            val detail = data.getLong("hall_appointment_data_id")
            if (id <= 0 || detail <= 0) throw UnsupportedPortal()
            val at = parsePortalTime(data.getString("appointment_tim"))
            val status = if (activeOnly) ReservationStatus.ACTIVE else when (data.getString("status_name").trim()) {
                "已预约" -> ReservationStatus.ACTIVE
                "已取消", "已撤销" -> ReservationStatus.CANCELLED
                "已签到" -> ReservationStatus.USED
                "未签到", "已过期" -> ReservationStatus.EXPIRED
                else -> ReservationStatus.UNKNOWN
            }
            return ReservationRecord(Ticket(id, detail, route, at, data.optJSONObject("resource_address")?.optString("text") ?: "",
                data.requiredFlag("is_cancel")), status)
        }
    }
}

internal fun JSONObject.flag(key: String): Boolean = opt(key) == true || opt(key)?.toString() == "1"
internal fun JSONObject.requiredFlag(key: String): Boolean = when (opt(key)?.toString()) {
    "true", "1" -> true
    "false", "0" -> false
    else -> throw UnsupportedPortal("官网状态无法识别，请在官网核实")
}
