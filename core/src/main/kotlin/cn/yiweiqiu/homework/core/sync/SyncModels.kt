package cn.yiweiqiu.homework.core.sync

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * 云端格式版本。
 *
 * 本机读到比它大的，直接报错让用户升级——猜格式解出来的往往是半截数据，
 * 比明确报错危险得多。这条是从课时簿里原样搬过来的。
 */
const val SCHEMA_VERSION = 1

/**
 * 同步状态机的阶段。两端共用一套，只是展示方式不同：
 * 电视端只在顶栏亮一个小徽标，手机端会把 message 直接给家长看。
 */
enum class SyncPhase {
    /** 还没填账号 */
    NOT_CONFIGURED,

    /** 空闲（上一轮成功过或还没跑过） */
    IDLE,

    SYNCING,
    SYNCED,

    /** 网络不通 / 超时：有缓存就继续显示旧内容 */
    OFFLINE,

    CONFLICT,

    AUTH_FAILED,
    SERVER_ERROR,
    SPACE_FULL,
    UPGRADE_REQUIRED,
}

data class SyncStatus(
    val phase: SyncPhase = SyncPhase.NOT_CONFIGURED,
    val message: String = "",
    /** 还没发出去的条数（手机端待发队列） */
    val pendingCount: Int = 0,
    val lastSuccessAt: Long = 0L,
    val conflictCount: Int = 0,
    /** 云端最后是谁写的：「另一台设备」这类提示用 */
    val remoteDevice: String = "",
) {
    val isBusy: Boolean get() = phase == SyncPhase.SYNCING || phase == SyncPhase.IDLE
}

sealed interface SyncOutcome {
    /** 真的传了东西 */
    data object Success : SyncOutcome

    /** 远端没变、本地也没改动，一个字节都没传 */
    data object Fresh : SyncOutcome

    data object NotConfigured : SyncOutcome

    data class Failure(
        val phase: SyncPhase,
        val message: String,
        /** true = 值得退避后重试；false = 重试无用，得人去改配置 */
        val retryable: Boolean,
    ) : SyncOutcome
}

/**
 * 同步状态总线。引擎写、界面读；两端各一个进程，所以用进程内单例就够。
 *
 * 之前电视端和手机端各在自己的 UiState 里维护 offline/error，文案和判定逻辑各写一遍，
 * 同一个错误在两个端上显示得不一样。现在统一到这里。
 */
object SyncStatusHolder {

    private val _state = MutableStateFlow(SyncStatus())
    val state: StateFlow<SyncStatus> = _state.asStateFlow()

    fun update(block: (SyncStatus) -> SyncStatus) = _state.update(block)

    fun reset() = _state.update { SyncStatus() }
}
