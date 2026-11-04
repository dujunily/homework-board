package cn.yiweiqiu.homeworkboard

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import cn.yiweiqiu.homework.core.ConfigLoader
import cn.yiweiqiu.homework.core.DayKeys
import cn.yiweiqiu.homework.core.HomeworkItem
import cn.yiweiqiu.homework.core.HomeworkRepository
import cn.yiweiqiu.homework.core.HomeworkRepository.RefreshResult
import cn.yiweiqiu.homework.core.ManifestDay
import cn.yiweiqiu.homework.core.sync.SyncOutcome
import cn.yiweiqiu.homework.core.sync.SyncPhase
import cn.yiweiqiu.homework.core.sync.SyncPingListener
import cn.yiweiqiu.homework.core.sync.SyncScheduler
import cn.yiweiqiu.homework.core.sync.SyncStatusHolder
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

data class UiState(
    val loading: Boolean = true,
    val syncing: Boolean = false,
    val dateLabel: String = "",
    val items: List<HomeworkItem> = emptyList(),
    val notes: String = "",
    val updatedLabel: String = "",
    val offline: Boolean = false,
    val error: String? = null,
    val needConfig: Boolean = false,
    val configPath: String = "",
    val packageName: String = "",
    val latestLabel: String = "",
    /** 非空表示显示的不是今天的作业，而是还没到期的上一次布置（周末、假期） */
    val carryNote: String = "",
    /** 同步状态机的阶段，界面上只用来区分「同步中 / 离线 / 报错」 */
    val phase: SyncPhase = SyncPhase.IDLE,
)

/**
 * 电视端的界面状态。
 *
 * 同步本身交给了 [SyncScheduler] + [SyncStatusHolder]（和课时簿、动账同一套）：
 * 这里不再自己数「5 分钟到了没」「上一轮还在跑吗」，只做两件事——
 * 把拉回来的数据铺到界面上，把状态总线里的阶段翻译成顶栏徽标。
 */
class HomeViewModel(app: Application) : AndroidViewModel(app) {

    private val repository = HomeworkRepository(app)
    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    /** 今天（真实日期），只用来判断该不该沿用 */
    private var today: String = DayKeys.today()

    /**
     * 当前显示的作业日。
     *
     * 平时就是今天；周末和假期是上一次布置、还没到期的那一份（见 [DayRule]）。
     * 界面上所有「是哪天的作业」都以它为准。
     */
    private var activeDate: String = today

    /** 当前挂着的轮询间隔，0 = 关掉了定时轮询 */
    private var pollIntervalMs = 0L

    /** 跨零点自动翻页用的定时器，纯本地计时，不发网络请求 */
    private var dateTickJob: Job? = null

    /** 听手机发来的局域网通知 */
    private val pingListener = SyncPingListener(viewModelScope) {
        // 有人刚写完，立刻去拉。不闪「同步中」，孩子看出来会以为是自动的
        Log.d("HomeViewModel", "收到局域网通知，立即同步")
        syncNow(quiet = true)
    }

    /** 失败退避、手动同步去重都由它管 */
    private val scheduler = SyncScheduler(viewModelScope) { quiet -> runSync(quiet) }

    init {
        today = DayKeys.today()
        // 先只看本地索引：断网、刚开机时也能立刻定下该显示哪一天
        activeDate = repository.resolvedDate(today)
        _state.update {
            it.copy(
                dateLabel = DayKeys.label(activeDate),
                carryNote = carryNoteOf(activeDate),
                configPath = ConfigLoader.primaryPath(app),
                packageName = app.packageName,
            )
        }
        primeFromCache()
        observeStatus()
        // 这里不再主动同步：Activity 的 onResume 紧接着就会调 onEnterForeground()，
        // 两边都发就成了一次冷启动两次请求。
    }

    /* ------------------------------ 界面 ------------------------------ */

    /**
     * 顶栏上那句「沿用周五布置的」。
     *
     * 必须说出来：孩子周末看到的是上周五的单子，如果标题只写「11月1日 周日」，
     * 家长会以为今天新布置了作业，或者以为日期错乱了。
     */
    private fun carryNoteOf(date: String): String =
        if (date == today) "" else "沿用${DayKeys.weekdayOf(date)}布置的"

    /** 先用缓存出画面，再走网络：断网时孩子也能看到作业 */
    private fun primeFromCache() {
        val cached = repository.cachedDay(activeDate)
        _state.update { s ->
            s.copy(
                loading = cached?.items == null,
                items = cached?.items?.sortedBy { it.order } ?: emptyList(),
                notes = cached?.notes.orEmpty(),
                updatedLabel = DayKeys.timeOf(cached?.updatedAt.orEmpty()),
                latestLabel = latestLabel(repository.cachedManifestDays()),
            )
        }
    }

    /** 状态总线 → 顶栏徽标。文案统一来自引擎，界面不再自己编。 */
    private fun observeStatus() {
        viewModelScope.launch {
            SyncStatusHolder.state.collect { st ->
                _state.update { s ->
                    val failureText = when (st.phase) {
                        SyncPhase.AUTH_FAILED,
                        SyncPhase.SERVER_ERROR,
                        SyncPhase.SPACE_FULL,
                        SyncPhase.UPGRADE_REQUIRED,
                        -> st.message.ifBlank { "同步失败" }

                        else -> null
                    }
                    s.copy(
                        phase = st.phase,
                        syncing = st.phase == SyncPhase.SYNCING,
                        needConfig = st.phase == SyncPhase.NOT_CONFIGURED,
                        offline = st.phase != SyncPhase.SYNCED &&
                            st.phase != SyncPhase.IDLE &&
                            st.phase != SyncPhase.SYNCING &&
                            st.phase != SyncPhase.NOT_CONFIGURED,
                        error = failureText,
                    )
                }
            }
        }
    }

    fun onEnterForeground() {
        val want = configuredPollIntervalMs()
        // 0 = 不轮询：全靠「有人写了才通知」这套，只有确实需要时才打开定时器
        if (want > 0L) scheduler.startPolling(want) else scheduler.stopPolling()
        pollIntervalMs = want

        // 开始听局域网通知：手机保存后会喊一嗓子，听见了才去拉
        pingListener.start()
        startDateTicker()

        // 每次打开应用 / 回到前台都刷一次，不做时间去重。
        // 数据没变时服务端只回 304，代价就是一次请求往返；
        // 换来的是「打开就是最新」——比省这一次请求值。连点导致的重叠由 scheduler 自己挡。
        Log.d("HomeViewModel", "进入前台，刷新一次")
        syncNow(quiet = true)
    }

    /**
     * config.json 里的 `refreshIntervalMinutes`：**0（默认）= 关闭定时轮询**。
     *
     * 以前这里是固定 10 分钟，一天上百次请求，而绝大多数时候云端根本没变化。
     * 现在常态下电视不主动问，只在「手机写完 → 局域网喊一声」时才拉。
     * 想恢复定时轮询就把它设成 5~60（家里路由器拦广播时才需要）。
     */
    private fun configuredPollIntervalMs(): Long {
        val minutes = when (val r = ConfigLoader.read(getApplication<Application>())) {
            is ConfigLoader.Result.Ok -> r.config.refreshIntervalMinutes
            else -> 0
        }
        if (minutes <= 0) return 0L
        // 坚果云对频率敏感，小于 5 分钟没意义，直接抬到 5
        return minutes.coerceIn(5, 60) * 60_000L
    }

    /**
     * 跨零点自动切到新的一天。
     *
     * 关掉轮询后没人会碰这个逻辑了，但电视经常通宵开着，
     * 到了 00:00 必须换成新的一天，否则孩子早上看到的是昨天的作业。
     * 这里只在本地计时，到点拉一次，一天一次。
     */
    private fun startDateTicker() {
        if (dateTickJob?.isActive == true) return
        dateTickJob = viewModelScope.launch {
            while (true) {
                delay(millisUntilNextMidnight())
                Log.d("HomeViewModel", "跨零点，重新判断该显示哪一天")
                refreshActiveDate()
                scheduler.syncNow(quiet = true)
            }
        }
    }

    private fun millisUntilNextMidnight(): Long {
        val cal = Calendar.getInstance()
        cal.add(Calendar.DAY_OF_YEAR, 1)
        cal.set(Calendar.HOUR_OF_DAY, 0)
        cal.set(Calendar.MINUTE, 0)
        cal.set(Calendar.SECOND, 5)
        cal.set(Calendar.MILLISECOND, 0)
        return (cal.timeInMillis - System.currentTimeMillis()).coerceAtLeast(1_000L)
    }

    fun onLeaveForeground() {
        scheduler.stopPolling()
        // 页面看不见时不用听：电视这时候本来也不显示作业
        pingListener.stop()
        dateTickJob?.cancel()
        dateTickJob = null
    }

    /** @param manual 遥控器菜单键：手动同步时不静默，让家长看到「正在同步」 */
    fun refresh(manual: Boolean) = syncNow(quiet = !manual)

    private fun syncNow(quiet: Boolean, minIntervalMs: Long = 0L) {
        refreshActiveDate()
        scheduler.syncNow(quiet = quiet, minIntervalMs = minIntervalMs)
    }

    /* ------------------------------ 同步 ------------------------------ */

    private suspend fun runSync(quiet: Boolean): SyncOutcome {
        refreshActiveDate()
        return when (val result = repository.refresh(today, quiet, carryOver = true)) {
            is RefreshResult.Ok -> {
                applyDay(result)
                SyncOutcome.Fresh
            }

            is RefreshResult.Failed -> {
                applyFailure(result)
                val noConfig = result.kind == HomeworkRepository.Kind.NEED_CONFIG ||
                    result.kind == HomeworkRepository.Kind.CONFIG_INVALID
                if (noConfig) {
                    SyncOutcome.NotConfigured
                } else {
                    SyncOutcome.Failure(
                        phase = SyncStatusHolder.state.value.phase,
                        message = result.message,
                        retryable = result.kind != HomeworkRepository.Kind.AUTH,
                    )
                }
            }
        }
    }

    private fun applyDay(result: RefreshResult.Ok) {
        val day = result.day
        // 引擎会把「实际读到的日期」带回来：周末请求周六，拿到的可能是周五那份
        activeDate = result.date.takeIf { it.isNotBlank() } ?: today
        _state.update {
            it.copy(
                loading = false,
                dateLabel = DayKeys.label(activeDate),
                carryNote = carryNoteOf(activeDate),
                items = day?.items?.sortedBy { item -> item.order } ?: emptyList(),
                notes = day?.notes.orEmpty(),
                updatedLabel = DayKeys.timeOf(day?.updatedAt.orEmpty())
                    .ifBlank { clockOf(result.notModified) },
                latestLabel = latestLabel(result.manifestDays),
            )
        }
    }

    private fun applyFailure(result: RefreshResult.Failed) {
        val missing = result.kind == HomeworkRepository.Kind.NEED_CONFIG
        val invalid = result.kind == HomeworkRepository.Kind.CONFIG_INVALID
        _state.update {
            it.copy(
                loading = false,
                // 有缓存就继续展示旧内容，只是把状态标成离线
                offline = !missing && !invalid,
                // 文件不存在时标题已经说清楚了；内容不对时必须把具体原因说出来
                error = when {
                    missing -> null
                    else -> result.message
                },
                needConfig = missing || invalid,
            )
        }
    }

    /**
     * 日期变了：重新算「该显示哪一天」，界面先用缓存顶上。
     *
     * 不能简单地把日期 +1——周五一过，周末要显示的仍然是周五那份；
     * 真到了周一，周五的到期了，这里自然会退回今天。
     */
    private fun refreshActiveDate() {
        val now = DayKeys.today()
        if (now == today) return

        today = now
        activeDate = repository.resolvedDate(today)
        val cached = repository.cachedDay(activeDate)
        _state.update {
            it.copy(
                dateLabel = DayKeys.label(activeDate),
                carryNote = carryNoteOf(activeDate),
                items = cached?.items?.sortedBy { item -> item.order } ?: emptyList(),
                notes = cached?.notes.orEmpty(),
                updatedLabel = DayKeys.timeOf(cached?.updatedAt.orEmpty()),
            )
        }
    }

    private fun clockOf(notModified: Boolean): String {
        val stamp = if (notModified) repository.lastCheckedAt() else System.currentTimeMillis()
        return SimpleDateFormat("HH:mm", Locale.CHINA).format(Date(stamp))
    }

    private fun latestLabel(days: List<ManifestDay>): String {
        val latest = days.map { it.date }.filter { it < today }.maxOrNull() ?: return ""
        return DayKeys.label(latest)
    }
}
