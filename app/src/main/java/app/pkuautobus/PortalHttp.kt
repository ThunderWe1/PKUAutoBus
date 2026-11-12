package app.pkuautobus

import android.webkit.CookieManager
import org.json.JSONObject
import java.io.IOException
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URI
import java.net.URLEncoder
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

const val PORTAL_ORIGIN = "https://wproc.pku.edu.cn"
const val PORTAL_HALL = "$PORTAL_ORIGIN/v2/reserve/hallView?id=1"
const val PORTAL_TICKETS = "$PORTAL_ORIGIN/v2/matter/reserveTime"

class PortalHttp(private val clock: BusClock) : PortalTransport {
    override fun request(path: String, parameters: Map<String, String>, post: Boolean): JSONObject {
        require(path.startsWith("/site/reservation/") && !path.contains('?') && !path.contains('#'))
        val encoded = parameters.entries.joinToString("&") { "${encode(it.key)}=${encode(it.value)}" }
        val url = URI.create(PORTAL_ORIGIN + path + if (!post && encoded.isNotEmpty()) "?$encoded" else "").toURL()
        val connection = url.openConnection() as HttpURLConnection
        try {
            connection.instanceFollowRedirects = false
            connection.connectTimeout = 15_000
            connection.readTimeout = 20_000
            connection.requestMethod = if (post) "POST" else "GET"
            connection.setRequestProperty("Accept", "application/json")
            connection.setRequestProperty("X-Requested-With", "XMLHttpRequest")
            connection.setRequestProperty("Referer", PORTAL_HALL)
            connection.setRequestProperty("Origin", PORTAL_ORIGIN)
            CookieManager.getInstance().getCookie(url.toString())?.let { connection.setRequestProperty("Cookie", it) }
            if (post) {
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8")
                val bytes = encoded.toByteArray(Charsets.UTF_8)
                connection.setFixedLengthStreamingMode(bytes.size)
                connection.outputStream.use { it.write(bytes) }
            }
            val status = connection.responseCode
            connection.headerFields.filterKeys { it?.equals("Set-Cookie", true) == true }.values.flatten().forEach {
                CookieManager.getInstance().setCookie(url.toString(), it)
            }
            connection.getHeaderField("Date")?.let { header ->
                runCatching { ZonedDateTime.parse(header, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant() }.getOrNull()?.let(clock::synchronize)
            }
            if (status in 300..399 || status == 401) throw LoginRequired()
            if (status != 200) throw IOException("官网暂时不可用（HTTP $status）")
            val bytes = connection.inputStream.use { input ->
                val output = ByteArrayOutputStream()
                val buffer = ByteArray(8192)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    if (output.size() + count > 2_000_000) throw IOException("官网响应过大，请打开官网查看")
                    output.write(buffer, 0, count)
                }
                output.toByteArray()
            }
            val body = bytes.toString(Charsets.UTF_8)
            if (!body.trimStart().startsWith('{')) throw UnsupportedPortal("官网返回了非预期页面，请重新登录或打开官网")
            return try { JSONObject(body) } catch (_: Exception) { throw UnsupportedPortal() }
        } finally { connection.disconnect() }
    }

    private fun encode(value: String) = URLEncoder.encode(value, "UTF-8")
}
