package app.pkuautobus

import android.Manifest
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.View
import android.view.WindowManager
import android.webkit.CookieManager
import android.webkit.WebStorage
import android.widget.*
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import org.json.JSONArray
import java.time.Duration
import java.time.format.DateTimeFormatter

class MainActivity : ComponentActivity() {
    private val model get() = (application as BusApplication).model
    private val handler = Handler(Looper.getMainLooper())
    private var locationListener: LocationListener? = null
    private var renderedCode: String? = null
    private var qrBitmap: Bitmap? = null
    private var openingPortal = false
    private val timeFormat = DateTimeFormatter.ofPattern("HH:mm")
    private val dateFormat = DateTimeFormatter.ofPattern("MM月dd日 E", java.util.Locale.SIMPLIFIED_CHINESE)
    private val permission = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { locate(false) }
    private val portal = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { openingPortal = false; model.afterPortal() }
    private val locationTimeout = Runnable { stopLocation(); locationUnavailable() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        setContentView(R.layout.activity_main)
        val root = findViewById<View>(R.id.root)
        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            view.setPadding(dp(20) + bars.left, dp(12) + bars.top, dp(20) + bars.right, dp(4) + bars.bottom)
            insets
        }
        val qrSize = minOf(280, resources.configuration.screenWidthDp - 80).coerceAtLeast(160)
        findViewById<View>(R.id.qr_frame).layoutParams.apply { width = dp(qrSize); height = dp(qrSize) }
        button(R.id.origin).setOnClickListener {
            stopLocation()
            model.selectRoute(if (model.route == Route.OUTBOUND) Route.INBOUND else Route.OUTBOUND, true)
        }
        icon(R.id.refresh).setOnClickListener {
            model.refresh()
            if (model.selected?.unexpired(model.clock.now()) != true && !model.manualRoute) locate(false)
        }
        button(R.id.reserve).setOnClickListener { if (model.loginRequired) openPortal(true) else model.book() }
        icon(R.id.official_portal).setOnClickListener { openPortal(model.loginRequired) }
        icon(R.id.account).setOnClickListener {
            if (model.loginRequired) openPortal(true) else showAccount()
        }
        button(R.id.cancel_reservation).setOnClickListener { confirmCancellation() }
        button(R.id.ticket_selector).setOnClickListener {
            val tickets = model.tickets
            AlertDialog.Builder(this).setTitle("选择乘车预约").setItems(tickets.map { it.label }.toTypedArray()) { _, index ->
                model.selectTicket(tickets[index].detailId)
            }.show()
        }
        findViewById<View>(R.id.about).setOnClickListener {
            val notices = listOf("THIRD_PARTY_NOTICES.txt", "LICENSE-APACHE-2.0.txt", "LICENSE-LUCIDE.txt").joinToString("\n\n") { name ->
                assets.open(name).bufferedReader().use { it.readText() }
            }
            val content = TextView(this).apply { text = notices; setTextIsSelectable(true); setPadding(dp(20), dp(12), dp(20), dp(12)) }
            AlertDialog.Builder(this).setTitle("开源许可").setView(ScrollView(this).apply { addView(content) })
                .setPositiveButton("关闭", null).show()
        }
        render()
    }

    override fun onResume() {
        super.onResume()
        model.onChange = ::render
        model.resume()
        if (model.selected?.unexpired(model.clock.now()) != true && !model.manualRoute) locate(true)
    }
    override fun onPause() {
        model.onChange = null
        model.pause()
        stopLocation()
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        super.onPause()
    }

    override fun onStop() {
        super.onStop()
        if (!isChangingConfigurations && !openingPortal) model.resetOrigin()
    }

    private fun render() {
        val ticket = model.selected?.takeIf { it.unexpired(model.clock.now()) }
        findViewById<View>(R.id.ticket).visibility = if (ticket == null) View.GONE else View.VISIBLE
        findViewById<View>(R.id.booking).visibility = if (ticket == null) View.VISIBLE else View.GONE
        text(R.id.status).text = model.status
        text(R.id.status).visibility = if (model.pending != null || (!model.busy && (!model.loginRequired || ticket != null) &&
            (model.stale || (ticket == null && model.route != null && model.preview == null)))) View.VISIBLE else View.GONE
        findViewById<View>(R.id.loading).visibility = if (model.busy) View.VISIBLE else View.INVISIBLE
        icon(R.id.refresh).isEnabled = !model.busy
        icon(R.id.official_portal).isEnabled = !model.busy
        icon(R.id.account).isEnabled = !model.busy
        icon(R.id.account).contentDescription = if (model.loginRequired) "登录北大账户" else getString(R.string.account)
        icon(R.id.account).tooltipText = icon(R.id.account).contentDescription
        if (ticket != null) {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            text(R.id.ticket_route).text = ticket.route.title
            text(R.id.ticket_time).text = getString(R.string.departure_at, TRIP_FORMAT.format(ticket.at.atZone(CAMPUS_ZONE)))
            text(R.id.ticket_address).text = ticket.address
            text(R.id.ticket_address).visibility = if (ticket.address.isBlank()) View.GONE else View.VISIBLE
            button(R.id.cancel_reservation).isEnabled = ticket.canCancel && !model.busy && !model.stale && model.pending == null && !model.loginRequired
            button(R.id.cancel_reservation).text = if (ticket.canCancel) "取消预约" else "当前不可取消"
            button(R.id.ticket_selector).visibility = if (model.tickets.size > 1) View.VISIBLE else View.GONE
            button(R.id.ticket_selector).isEnabled = !model.busy && model.pending == null
            renderQr(ticket)
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            renderQr(null)
            text(R.id.departure_time).text = model.preview?.let { timeFormat.format(it.at.atZone(CAMPUS_ZONE)) } ?: "— : —"
            text(R.id.departure_detail).text = model.preview?.let { dateFormat.format(it.at.atZone(CAMPUS_ZONE)) } ?: ""
            button(R.id.origin).text = model.route?.origin ?: getString(R.string.choose_campus)
            button(R.id.origin).contentDescription = model.route?.let { "从${it.origin}出发，点按切换至${it.destination}" } ?: "选择出发校区"
            button(R.id.origin).isEnabled = !model.busy && model.pending == null
            button(R.id.reserve).text = when {
                model.loginRequired -> "登录"
                model.pending != null -> "待确认"
                else -> getString(R.string.reserve)
            }
            button(R.id.reserve).isEnabled = !model.busy && (model.loginRequired || (model.preview != null && !model.stale && model.pending == null))
        }
    }

    private fun renderQr(ticket: Ticket?) {
        val payload = ticket?.code?.payload
        val image = findViewById<ImageView>(R.id.qr)
        val placeholder = text(R.id.qr_placeholder)
        if (payload != renderedCode) {
            image.setImageDrawable(null)
            qrBitmap?.recycle(); qrBitmap = null
            renderedCode = null
            if (payload != null) {
                try {
                    qrBitmap = qrBitmap(payload, 840)
                    image.setImageBitmap(qrBitmap)
                    renderedCode = payload
                } catch (_: Exception) { placeholder.text = "乘车码无法显示，请打开官网" }
            }
        }
        placeholder.visibility = if (renderedCode == null) View.VISIBLE else View.GONE
        if (payload == null) placeholder.text = if (model.busy) "正在加载乘车码…" else "乘车码未加载\n请刷新或打开官网"
        image.contentDescription = ticket?.let { "${it.label}，预约乘车二维码" } ?: "预约乘车二维码"
    }

    private fun confirmCancellation() {
        val ticket = model.selected ?: return
        if (!ticket.canCancel || model.pending != null || model.busy) return
        model.pause()
        val dialog = AlertDialog.Builder(this).setTitle("取消这班预约？")
            .setMessage("${ticket.route.title}\n${TRIP_FORMAT.format(ticket.at.atZone(CAMPUS_ZONE))} 发车\n\n确认后将释放此班次的预约。")
            .setNegativeButton("保留预约", null)
            .setPositiveButton("确认取消") { _, _ -> model.cancelConfirmed(ticket.detailId) }.create()
        dialog.setOnDismissListener {
            if (lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED)) model.resume()
        }
        dialog.show()
        dialog.getButton(AlertDialog.BUTTON_NEGATIVE).requestFocus()
    }

    private fun showAccount() {
        AlertDialog.Builder(this).setTitle("北大账户")
            .setItems(arrayOf("在官网查看账户", "退出登录并清除本机乘车码")) { _, index ->
                if (index == 0) openPortal(false) else {
                    if (model.pending != null) {
                        AlertDialog.Builder(this).setMessage("上次操作结果尚未确认，请先刷新或在官网核实，再退出登录。")
                            .setPositiveButton("知道了", null).show()
                        return@setItems
                    }
                    model.pause()
                    CookieManager.getInstance().removeAllCookies {
                        CookieManager.getInstance().flush()
                        WebStorage.getInstance().deleteAllData()
                        model.clearForLogin()
                        if (lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED)) model.resume()
                    }
                }
            }.show()
    }

    private fun openPortal(login: Boolean) {
        if (model.busy) return
        openingPortal = true
        portal.launch(Intent(this, PortalActivity::class.java).putExtra("login", login))
    }

    private fun locate(askPermission: Boolean) {
        if (model.selected?.unexpired(model.clock.now()) == true || model.manualRoute || locationListener != null) return
        val fine = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        val coarse = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
        if (!fine && !coarse) {
            val preferences = getSharedPreferences("location_permission", MODE_PRIVATE)
            if (askPermission && !preferences.getBoolean("asked", false)) {
                preferences.edit().putBoolean("asked", true).apply()
                permission.launch(arrayOf(Manifest.permission.ACCESS_COARSE_LOCATION))
            } else locationUnavailable()
            return
        }
        val districts = try {
            DistrictDetector.read(JSONArray(assets.open("districts.json").bufferedReader().use { it.readText() }))
        } catch (_: Exception) { locationUnavailable(); return }
        val manager = getSystemService(LOCATION_SERVICE) as LocationManager
        fun accept(location: Location): Boolean {
            if (!location.hasAccuracy()) return false
            val age = Duration.ofNanos(SystemClock.elapsedRealtimeNanos() - location.elapsedRealtimeNanos)
            val route = DistrictDetector.detect(GeoPoint(location.latitude, location.longitude), location.accuracy.toDouble(), age, districts) ?: return false
            stopLocation(); model.selectRoute(route, false)
            return true
        }
        val providers = listOfNotNull(if (fine) LocationManager.GPS_PROVIDER else null, LocationManager.NETWORK_PROVIDER)
            .filter { runCatching { manager.isProviderEnabled(it) }.getOrDefault(false) }
        if (providers.isEmpty()) { locationUnavailable(); return }
        try {
            val cached = providers.mapNotNull { manager.getLastKnownLocation(it) }.sortedByDescending { it.elapsedRealtimeNanos }
            if (cached.any(::accept)) return
            locationListener = object : LocationListener {
                override fun onLocationChanged(location: Location) { accept(location) }
                override fun onProviderEnabled(provider: String) {}
                override fun onProviderDisabled(provider: String) {}
                @Deprecated("Deprecated in platform") override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
            }
            providers.forEach { manager.requestLocationUpdates(it, 1000, 0f, locationListener!!, Looper.getMainLooper()) }
            handler.postDelayed(locationTimeout, 10_000)
        } catch (_: SecurityException) { stopLocation(); locationUnavailable() }
        catch (_: IllegalArgumentException) { stopLocation(); locationUnavailable() }
    }

    private fun locationUnavailable() { button(R.id.origin).tooltipText = "定位不可用，点按选择出发校区" }

    private fun stopLocation() {
        handler.removeCallbacks(locationTimeout)
        locationListener?.let { (getSystemService(LOCATION_SERVICE) as LocationManager).removeUpdates(it) }
        locationListener = null
    }
    private fun text(id: Int) = findViewById<TextView>(id)
    private fun button(id: Int) = findViewById<Button>(id)
    private fun icon(id: Int) = findViewById<ImageButton>(id)
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    companion object {
        fun qrBitmap(payload: String, size: Int): Bitmap {
            val matrix = QRCodeWriter().encode(payload, BarcodeFormat.QR_CODE, size, size,
                mapOf(EncodeHintType.CHARACTER_SET to "UTF-8", EncodeHintType.MARGIN to 4, EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M))
            val pixels = IntArray(size * size) { index -> if (matrix[index % size, index / size]) android.graphics.Color.BLACK else android.graphics.Color.WHITE }
            return Bitmap.createBitmap(pixels, size, size, Bitmap.Config.ARGB_8888)
        }
    }
}
