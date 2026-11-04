package cn.yiweiqiu.homework.core

import android.content.Context
import cn.yiweiqiu.homework.core.sync.SyncEngine
import cn.yiweiqiu.homework.core.sync.SyncPhase
import java.io.File

/**
 * 电视端的读取门面。真正的活都在 [SyncEngine] 里，这里只做两件事：
 * 把配置读出来、把引擎结果翻译成界面认识的形状。
 *
 * 保留这层是因为电视端的调用点只关心「今天有没有作业 / 有没有变化」，
 * 不该被迫理解 ETag、412、manifest 这些东西。
 */
class HomeworkRepository(context: Context) {

    private val appContext: Context = context.applicationContext
    private val cache = LocalCache(File(appContext.filesDir, "cache"))

    /**
     * NEED_CONFIG 是文件根本不存在，CONFIG_INVALID 是文件在、内容不对（模板没改、账号为空、格式错）。
     * 分开是因为两者的修法完全不同：前者要放文件，后者要改文件里的字。
     */
    enum class Kind { NONE, NEED_CONFIG, CONFIG_INVALID, AUTH, NOT_FOUND, NETWORK, PARSE, OTHER }

    sealed class RefreshResult {
        data class Ok(
            val day: HomeworkDay?,
            val notModified: Boolean,
            val manifestDays: List<ManifestDay>,
            /** 实际读到的是哪一天：周末会是沿用中那份的日期，不是请求的今天 */
            val date: String = "",
        ) : RefreshResult()

        data class Failed(
            val kind: Kind,
            val message: String,
        ) : RefreshResult()
    }

    /** 不联网，只看本地索引：用于界面第一帧就定下「当前作业日」 */
    fun resolvedDate(today: String): String = DayRule.resolveViewDate(today, cachedManifestDays())

    fun cachedDay(date: String): HomeworkDay? {
        val raw = cache.readText(LocalCache.dayCacheName(date)) ?: return null
        return runCatching { HomeworkJson.decodeFromString(HomeworkDay.serializer(), raw) }.getOrNull()
    }

    fun cachedManifestDays(): List<ManifestDay> {
        val raw = cache.readText(LocalCache.manifestCacheName()) ?: return emptyList()
        return runCatching { HomeworkJson.decodeFromString(Manifest.serializer(), raw).days }
            .getOrDefault(emptyList())
    }

    fun lastCheckedAt(): Long = cache.lastCheckedAt()

    /**
     * @param carryOver 今天没有作业时，是否沿用上一次还没到期的那份（见 [DayRule]）。
     *   电视端固定开——周末和假期本来就没有「今天的作业」。
     */
    suspend fun refresh(date: String, quiet: Boolean = false, carryOver: Boolean = true): RefreshResult {
        val config = when (val r = ConfigLoader.read(appContext)) {
            is ConfigLoader.Result.Ok -> r.config
            ConfigLoader.Result.Missing -> return RefreshResult.Failed(Kind.NEED_CONFIG, "尚未配置坚果云账号")
            is ConfigLoader.Result.Bad -> return RefreshResult.Failed(Kind.CONFIG_INVALID, r.reason)
        }

        val engine = SyncEngine(config, cache, deviceName = "电视")
        return when (val r = engine.pull(date, quiet, carryOver)) {
            is SyncEngine.PullResult.Ok ->
                RefreshResult.Ok(r.day, r.notModified, r.days, r.date)

            is SyncEngine.PullResult.Failed -> RefreshResult.Failed(kindOf(r.phase), r.message)
        }
    }

    private fun kindOf(phase: SyncPhase): Kind = when (phase) {
        SyncPhase.NOT_CONFIGURED -> Kind.NEED_CONFIG
        SyncPhase.AUTH_FAILED -> Kind.AUTH
        SyncPhase.OFFLINE -> Kind.NETWORK
        SyncPhase.UPGRADE_REQUIRED -> Kind.OTHER
        else -> Kind.OTHER
    }
}
