package cn.yiweiqiu.homework.core

import cn.yiweiqiu.homework.core.sync.SyncEngine
import java.io.File

/**
 * 手机端的写入门面。同样只是薄薄一层，实际逻辑在 [SyncEngine]：
 * 建目录 → 传图 → 读当天（带 ETag）→ 合并 → 备份 → 带 If-Match 写回 → 更新 manifest。
 *
 * 这层存在的理由：录入界面只想知道「发成功了吗、要不要进待发队列」，
 * 不该关心 412、备份轮转、冲突副本这些。
 */
class HomeworkWriter(
    private val config: HomeworkConfig,
    cacheDir: File? = null,
) {
    private val cache = LocalCache(cacheDir ?: File(System.getProperty("java.io.tmpdir") ?: "/tmp", "hw-cache"))

    private fun engine() = SyncEngine(config, cache, deviceName = "手机")

    sealed class SaveResult {
        data class Ok(val day: HomeworkDay, val etag: String) : SaveResult()

        /** retryable=true 表示值得进待发队列重试（网络类），false 表示配置/认证问题，重试无用 */
        data class Failed(val message: String, val retryable: Boolean) : SaveResult()
    }

    suspend fun upsert(
        date: String,
        item: HomeworkItem,
        imageBytes: List<ByteArray>,
    ): SaveResult = when (val r = engine().push(date, item, imageBytes)) {
        is SyncEngine.PushResult.Ok -> SaveResult.Ok(r.day, r.etag)
        is SyncEngine.PushResult.Failed -> SaveResult.Failed(r.message, r.retryable)
    }

    suspend fun delete(date: String, itemId: String): SaveResult =
        when (val r = engine().deleteItem(date, itemId)) {
            is SyncEngine.PushResult.Ok -> SaveResult.Ok(r.day, r.etag)
            is SyncEngine.PushResult.Failed -> SaveResult.Failed(r.message, r.retryable)
        }

    /** 只改当天备注，不动条目：电视端把它显示在列表下方的「提醒」条里 */
    suspend fun saveNotes(date: String, notes: String): SaveResult =
        when (val r = engine().saveNotes(date, notes)) {
            is SyncEngine.PushResult.Ok -> SaveResult.Ok(r.day, r.etag)
            is SyncEngine.PushResult.Failed -> SaveResult.Failed(r.message, r.retryable)
        }

    /** 只改「这批作业管到哪天」：假期和调休用，平时靠默认规则就够了 */
    suspend fun saveValidUntil(date: String, validUntil: String): SaveResult =
        when (val r = engine().saveValidUntil(date, validUntil)) {
            is SyncEngine.PushResult.Ok -> SaveResult.Ok(r.day, r.etag)
            is SyncEngine.PushResult.Failed -> SaveResult.Failed(r.message, r.retryable)
        }

    /** 手机首屏：走同一套缓存 + 条件请求，避免每次都下整份 json */
    suspend fun fetchDay(date: String): HomeworkDay? = engine().fetchDay(date)

    /**
     * 手机首屏真正的入口：返回「当前作业日」而不是「今天」。
     *
     * 周六打开手机，拿回来的是周五那份还没到期的作业和它的日期；
     * 之后的新增、删除、备注都落在那一天，和电视端看到的完全一致。
     */
    suspend fun loadActiveDay(today: String): ActiveDay = engine().loadActiveDay(today)

    /** 不联网，只看本地索引：给界面第一帧用 */
    fun resolvedDate(today: String): String = DayRule.resolveViewDate(today, engine().cachedManifestDays())

    suspend fun testConnection(): String = engine().testConnection()

    companion object {
        /** id 在创建时生成一次就固定，重试不得重新生成，否则会产生重复条目 */
        fun newId(): String = SyncEngine.newId()
    }
}
