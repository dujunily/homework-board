package cn.yiweiqiu.homework.core.sync

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * 同步触发调度，节奏照课时簿 / 动账：
 *
 * - 写操作后 **debounce 8 秒**：连记三条只发一次请求（坚果云对频率敏感，密了直接 503）
 * - 回到前台立即尝试，但带 **60 秒去重**：刚同步过、本地又没改动就不发请求
 * - 前台每 **10 分钟**静默轮询一次，只把别的设备写的拉过来，不闪「同步中…」
 * - 失败按 **30s → 2min → 10min → 1h → 1h** 退避，最多 5 次
 * - WorkManager 每 6 小时兜底（App 好几天没打开也不会掉队），由各端自己注册
 *
 * 这里只管「什么时候跑」，跑什么由构造时传进来的 [runner] 决定——
 * 电视端跑 pull，手机端先补发待发队列再 pull。
 */
class SyncScheduler(
    private val scope: CoroutineScope,
    private val runner: suspend (quiet: Boolean) -> SyncOutcome,
) {

    private var debounceJob: Job? = null
    private var retryJob: Job? = null
    private var pollJob: Job? = null

    /** 正在跑的那一轮；手动连点「同步」不再叠请求 */
    private var activeJob: Job? = null
    private var retryAttempt = 0

    private val retryDelays = listOf(30_000L, 120_000L, 600_000L, 3_600_000L, 3_600_000L)

    /** 写操作后调用：攒够 [delayMs] 没新改动才真正发请求 */
    fun requestSync(delayMs: Long = 8_000L) {
        debounceJob?.cancel()
        debounceJob = scope.launch {
            delay(delayMs)
            run(quiet = false)
        }
    }

    /**
     * @param minIntervalMs 大于 0 时，若「上次成功同步距今不到这个时间」且本地没有待发内容，直接跳过。
     *                      用来吃掉「启动 + 回前台」这类一进 App 就触发的重复同步。
     */
    fun syncNow(quiet: Boolean = false, minIntervalMs: Long = 0L) {
        debounceJob?.cancel()
        if (activeJob?.isActive == true) return
        activeJob = scope.launch {
            if (minIntervalMs > 0L && recentlySynced(minIntervalMs)) return@launch
            run(quiet)
        }
    }

    private fun recentlySynced(minIntervalMs: Long): Boolean {
        val last = SyncStatusHolder.state.value.lastSuccessAt
        if (last <= 0L) return false
        if (System.currentTimeMillis() - last >= minIntervalMs) return false
        // 有待发送的东西就一定得跑，不能因为「时间还早」压在本机
        return SyncStatusHolder.state.value.pendingCount == 0
    }

    private suspend fun run(quiet: Boolean) {
        when (val outcome = runner(quiet)) {
            is SyncOutcome.Success, SyncOutcome.Fresh -> {
                retryAttempt = 0
            }
            is SyncOutcome.Failure -> if (outcome.retryable) scheduleRetry()
            SyncOutcome.NotConfigured -> Unit
        }
    }

    private fun scheduleRetry() {
        if (retryAttempt >= retryDelays.size) return
        val wait = retryDelays[retryAttempt]
        retryAttempt++
        retryJob?.cancel()
        retryJob = scope.launch {
            delay(wait)
            run(quiet = false)
        }
    }

    /** 前台静默轮询：不闪状态、不叠请求，只为把别的设备的改动拉过来 */
    fun startPolling(intervalMs: Long = 10 * 60 * 1000L) {
        if (pollJob?.isActive == true) return
        pollJob = scope.launch {
            while (true) {
                delay(intervalMs)
                syncNow(quiet = true)
            }
        }
    }

    fun stopPolling() {
        pollJob?.cancel()
        pollJob = null
    }

    fun cancelAll() {
        debounceJob?.cancel()
        retryJob?.cancel()
        pollJob?.cancel()
        activeJob?.cancel()
        retryAttempt = 0
    }
}

/** 把引擎结果翻译成调度器认识的样子 */
fun SyncEngine.PullResult.toOutcome(): SyncOutcome = when (this) {
    is SyncEngine.PullResult.Ok -> SyncOutcome.Fresh
    is SyncEngine.PullResult.Failed -> when (phase) {
        SyncPhase.NOT_CONFIGURED -> SyncOutcome.NotConfigured
        else -> SyncOutcome.Failure(phase, message, retryable)
    }
}

fun SyncEngine.PushResult.toOutcome(): SyncOutcome = when (this) {
    is SyncEngine.PushResult.Ok -> SyncOutcome.Success
    is SyncEngine.PushResult.Failed -> when (phase) {
        SyncPhase.NOT_CONFIGURED -> SyncOutcome.NotConfigured
        else -> SyncOutcome.Failure(phase, message, retryable)
    }
}
