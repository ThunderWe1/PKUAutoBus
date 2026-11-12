package app.pkuautobus

import android.app.Application
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import java.io.IOException
import java.util.concurrent.Executors

class BusApplication : Application() {
    lateinit var model: BusModel
    override fun onCreate() {
        super.onCreate()
        val clock = BusClock()
        model = BusModel(PortalClient(PortalHttp(clock)), clock, TicketStore(this))
    }
}

class BusModel(val client: PortalClient, val clock: BusClock, private val store: TicketStore) {
    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()
    private var foreground = false
    private var generation = 0
    private var nextStatusCheck = 0L
    private var nextCodeCheck = 0L
    private var backoff = 2_000L
    private var storageFailed = false
    private val usedIds = mutableSetOf<Long>()
    var onChange: (() -> Unit)? = null
    var route: Route? = null; private set
    var manualRoute = false; private set
    var tickets: List<Ticket> = emptyList(); private set
    var selected: Ticket? = null; private set
    var preview: Departure? = null; private set
    var pending: PendingAction? = null; private set
    var busy = false; private set
    var loginRequired = false; private set
    var status = "正在连接北大预约系统…"; private set
    var stale = true; private set

    init {
        try {
            val saved = store.load()
            selected = saved.ticket?.takeIf { it.unexpired(clock.now()) }
            tickets = listOfNotNull(selected)
            pending = saved.pending
            status = if (pending != null) "上次操作结果待核实，正在检查官网…" else if (selected != null) "正在核实乘车码状态…" else status
        } catch (_: Exception) {
            storageFailed = true
            status = "本地预约记录无法读取，请先在官网核实，再重新登录"
        }
    }

    private val tick = object : Runnable {
        override fun run() {
            if (!foreground) return
            expire()
            val now = SystemClock.elapsedRealtime()
            if (!busy && !loginRequired && !storageFailed) {
                when {
                    now >= nextStatusCheck -> refresh()
                    selected?.code != null && now >= nextCodeCheck -> checkCode()
                }
            }
            main.postDelayed(this, 1_000)
        }
    }

    fun resume() {
        foreground = true
        main.removeCallbacks(tick)
        nextStatusCheck = 0
        main.post(tick)
        changed()
    }
    fun pause() { foreground = false; main.removeCallbacks(tick) }

    fun selectRoute(value: Route, manual: Boolean) {
        if (selected?.unexpired(clock.now()) == true || pending != null || (!manual && manualRoute)) return
        route = value
        manualRoute = manual
        preview = null
        nextStatusCheck = 0
        changed()
        refresh()
    }
    fun resetOrigin() {
        route = null; preview = null; manualRoute = false
        nextStatusCheck = 0
        changed()
    }

    fun selectTicket(detailId: Long) {
        if (busy || pending != null) return
        selected = tickets.firstOrNull { it.detailId == detailId && it.unexpired(clock.now()) } ?: return
        persist()
        nextCodeCheck = 0
        refresh()
    }

    private data class RefreshResult(val tickets: List<Ticket>, val preview: Departure?, val resolved: ReservationRecord?, val pendingExpired: Boolean)

    fun refresh() {
        if (busy || storageFailed) return
        val saved = selected
        val action = pending
        val direction = route
        work({
            client.verifyRoutes()
            var active = loadActive().filter { it.detailId !in usedIds }
            val resolved = action?.let { resolvedPending(it, client.reservations(it.key.at.atZone(CAMPUS_ZONE).toLocalDate(), false)) }
            val pendingExpired = action != null && clock.now() >= action.key.at.plusSeconds(600)
            val target = active.firstOrNull { it.detailId == saved?.detailId } ?: active.minByOrNull { it.at }
            if (target != null) {
                val code = saved?.takeIf { it.detailId == target.detailId }?.code ?: client.boardingCode(target)
                active = active.map { if (it.detailId == target.detailId) it.copy(code = code) else it }
            }
            val departure = if (active.isEmpty() && direction != null && (action == null || resolved != null || pendingExpired))
                chooseDeparture(client.departures(direction, clock.today()), clock.now()) else null
            RefreshResult(active, departure, resolved, pendingExpired)
        }, { result ->
            loginRequired = false
            stale = false
            tickets = result.tickets.filter { it.unexpired(clock.now()) }
            selected = tickets.firstOrNull { it.detailId == saved?.detailId } ?: tickets.minByOrNull { it.at }
            preview = result.preview?.takeIf { direction == route }
            if (result.resolved != null || result.pendingExpired) pending = null
            status = when {
                pending != null -> "操作结果仍待核实，请勿重复提交；可打开官网查看"
                result.resolved?.status == ReservationStatus.CANCELLED -> "预约已取消"
                result.resolved?.status == ReservationStatus.USED -> "已签到，祝旅途顺利"
                result.pendingExpired -> "该班次已结束；历史操作结果请到官网核实"
                selected != null -> "出示此码乘车"
                saved != null -> "此预约已不在待乘车列表，请到官网查看状态"
                route == null -> "请选择出发校区"
                direction != route -> "正在查询出发校区的班车…"
                preview == null -> "今日暂无可预约班车"
                else -> "已为你选好下一班"
            }
            backoff = 2_000
            nextStatusCheck = if (direction != route) 0 else SystemClock.elapsedRealtime() + 30_000
            nextCodeCheck = SystemClock.elapsedRealtime() + 2_000
            persist()
        })
    }

    private fun loadActive(): List<Ticket> {
        val today = clock.today()
        val dates = mutableListOf(today)
        if (clock.now().atZone(CAMPUS_ZONE).toLocalTime() < java.time.LocalTime.of(0, 10)) dates += today.minusDays(1)
        return dates.flatMap { client.reservations(it).map { r -> r.ticket } }.filter { it.unexpired(clock.now()) }.distinctBy { it.detailId }
    }

    private sealed interface BookResult {
        data class Existing(val tickets: List<Ticket>) : BookResult
        data class Changed(val departure: Departure?) : BookResult
        data class Sent(val ticket: Ticket?) : BookResult
    }

    fun book() {
        val departure = preview ?: return
        if (busy || pending != null || selected != null || loginRequired || storageFailed || stale) return
        var writeStarted = false
        var accepted = false
        val action = PendingAction(ActionKind.BOOK, departure.key)
        work({
            val existing = loadActive()
            if (existing.isNotEmpty()) return@work BookResult.Existing(existing)
            val fresh = chooseDeparture(client.departures(departure.route, clock.today()), clock.now())
            if (fresh == null || fresh.key != departure.key || fresh.periodId != departure.periodId || fresh.subId != departure.subId)
                return@work BookResult.Changed(fresh)
            store.save(SavedState(null, action))
            writeStarted = true
            client.book(fresh)
            accepted = true
            val ticket = loadActive().firstOrNull { it.key == fresh.key }
            BookResult.Sent(ticket)
        }, { result ->
            when (result) {
                is BookResult.Existing -> {
                    tickets = result.tickets; selected = tickets.minByOrNull { it.at }; preview = null
                    status = "已有待乘车预约，正在加载乘车码…"
                }
                is BookResult.Changed -> { preview = result.departure; status = if (preview == null) "今日暂无可预约班车" else "班次已变化，请确认后重新点击预约" }
                is BookResult.Sent -> {
                    pending = if (result.ticket == null) action else null
                    selected = result.ticket
                    tickets = listOfNotNull(selected)
                    preview = null
                    status = if (selected == null) "预约结果待核实，请勿重复预约" else "预约成功，正在加载乘车码…"
                }
            }
            persist()
            nextStatusCheck = if (result is BookResult.Changed) SystemClock.elapsedRealtime() + 30_000 else 0
        }, { error ->
            pending = if (writeStarted && (accepted || error !is PortalException)) action else null
            // A successful write followed by failed readback is also uncertain, even for a login/schema error.
            if (writeStarted && error is UnsupportedPortal) pending = action
            persist()
            failed(error)
            if (pending != null) status = "预约结果待核实，请勿重复预约；点击刷新或打开官网"
        })
    }

    fun cancelConfirmed(detailId: Long) {
        val ticket = selected?.takeIf { it.detailId == detailId } ?: return
        if (busy || pending != null || !ticket.canCancel || loginRequired || storageFailed || stale) return
        var writeStarted = false
        var accepted = false
        val action = PendingAction(ActionKind.CANCEL, ticket.key, ticket.detailId)
        work({
            val fresh = loadActive().firstOrNull { it.detailId == ticket.detailId } ?: throw PortalException("此预约已不可取消，请刷新状态")
            if (!fresh.canCancel) throw PortalException("官网当前不允许取消此预约")
            store.save(SavedState(ticket, action))
            writeStarted = true
            client.cancel(fresh)
            accepted = true
            client.reservations(ticket.at.atZone(CAMPUS_ZONE).toLocalDate(), false)
        }, { records ->
            val cancelled = resolvedPending(action, records) != null
            if (cancelled) {
                tickets = tickets.filterNot { it.detailId == ticket.detailId }
                selected = tickets.minByOrNull { it.at }
                pending = null
                status = "预约已取消"
            } else {
                pending = action; stale = true
                status = "取消请求已提交，正在核实结果…"
            }
            persist()
            nextStatusCheck = 0
        }, { error ->
            pending = if (writeStarted && (accepted || error !is PortalException || error is UnsupportedPortal)) action else null
            persist(); failed(error)
            if (pending != null) status = "取消结果待核实，乘车码状态未更新；请刷新或打开官网"
        })
    }

    private fun checkCode() {
        val ticket = selected ?: return
        val code = ticket.code ?: return
        work({ client.codeUsed(code) }, { used ->
            if (selected?.detailId == ticket.detailId && used) {
                usedIds += ticket.detailId
                tickets = tickets.filterNot { it.detailId == ticket.detailId }
                selected = tickets.minByOrNull { it.at }
                status = "已签到，祝旅途顺利"
                persist()
                nextStatusCheck = 0
            }
            nextCodeCheck = SystemClock.elapsedRealtime() + 2_000
        }, { error ->
            stale = true
            backoff = (backoff * 2).coerceAtMost(30_000)
            nextCodeCheck = SystemClock.elapsedRealtime() + backoff
            if (error is LoginRequired) loginRequired = true
            status = "状态未更新 · ${friendly(error)}"
        }, silent = true)
    }

    private fun expire() {
        val ticket = selected ?: return
        if (ticket.unexpired(clock.now())) return
        tickets = tickets.filter { it.unexpired(clock.now()) }
        selected = tickets.minByOrNull { it.at }
        status = "乘车码已过期"
        persist()
        nextStatusCheck = 0
        changed()
    }

    fun clearForLogin() {
        generation++
        selected = null; tickets = emptyList(); preview = null
        loginRequired = false; stale = true; usedIds.clear()
        // Reauthentication must not erase an uncertain write and permit a duplicate submission.
        try {
            if (pending != null) store.save(SavedState(pending = pending)) else store.clear()
            storageFailed = false
        }
        catch (_: Exception) { storageFailed = true }
        status = "请在北大官网完成登录"
        nextStatusCheck = 0
        changed()
    }

    fun afterPortal() {
        loginRequired = false
        nextStatusCheck = 0
        refresh()
    }

    private fun persist() {
        try { store.save(SavedState(selected, pending)) } catch (_: Exception) {
            storageFailed = true; status = "无法安全保存预约记录，请到官网核实后重新登录"
        }
    }

    private fun failed(error: Exception) {
        stale = true
        if (error is LoginRequired) loginRequired = true
        status = if (selected != null) "状态未更新 · ${friendly(error)}" else friendly(error)
        backoff = (backoff * 2).coerceAtMost(30_000)
        nextStatusCheck = SystemClock.elapsedRealtime() + backoff
        nextCodeCheck = nextStatusCheck
    }

    private fun friendly(error: Exception): String = when (error) {
        is PortalException -> error.message ?: "官网请求失败"
        is IOException -> "网络连接失败，请刷新或打开官网"
        else -> "官网数据无法识别，请打开官网核实"
    }

    private fun <T> work(task: () -> T, success: (T) -> Unit, failure: (Exception) -> Unit = ::failed, silent: Boolean = false) {
        if (busy) return
        busy = true
        val version = generation
        if (!silent) changed()
        worker.execute {
            val result = runCatching(task)
            main.post {
                busy = false
                if (version == generation) result.fold(success) { failure(it as? Exception ?: Exception("操作失败")) }
                changed()
            }
        }
    }
    private fun changed() { onChange?.invoke() }
    fun dispose() { pause(); worker.shutdownNow(); onChange = null }
}
