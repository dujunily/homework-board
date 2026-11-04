package cn.yiweiqiu.homework.core.sync

import android.util.Log
import cn.yiweiqiu.homework.core.ActiveDay
import cn.yiweiqiu.homework.core.DavSession
import cn.yiweiqiu.homework.core.DayKeys
import cn.yiweiqiu.homework.core.DayRule
import cn.yiweiqiu.homework.core.HomeworkConfig
import cn.yiweiqiu.homework.core.HomeworkDay
import cn.yiweiqiu.homework.core.HomeworkItem
import cn.yiweiqiu.homework.core.HomeworkJson
import cn.yiweiqiu.homework.core.IsoTime
import cn.yiweiqiu.homework.core.LocalCache
import cn.yiweiqiu.homework.core.Manifest
import cn.yiweiqiu.homework.core.ManifestDay
import cn.yiweiqiu.homework.core.WebDavClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerializationException
import java.io.IOException
import kotlin.math.abs

/**
 * 作业板的同步引擎。合并算法、乐观锁、退避重试、备份轮转，整体照课时簿 / 动账那一套。
 *
 * 与那两个项目唯一的差别在数据形态：
 * 课时簿是「一个文件装一本账」，动账是「一个文件装一个人的全部记录」，
 * 一次全量替换即可；作业板是**按天分文件**（`day/YYYY-MM-DD.json`），
 * 所以不能搬「全量快照覆盖」，只能搬**单日文件内的条目级合并**。
 *
 * 保留下来的是真正值钱的那几条：
 * 1. 任何时刻只有一轮同步在跑（[SyncGate]），手动连点不叠请求，否则坚果云按频率回 503；
 * 2. 写之前先读、写完用 `If-Match` 做乐观锁——**这是之前最大的洞**：
 *    手机和电脑同时写同一天时，后写的那份会把先写的整段吞掉，还不报错；
 * 3. 412 分两种处理：真并发就重读重合并，服务端不认 If-Match 就直接写一次；
 * 4. 覆盖前把旧版本存进 `backup/`，每天最多留 [MAX_BACKUPS] 份；
 * 5. 错误分类成异常，每类给人话 + 标出「该不该重试」，交给调度器决定退避；
 * 6. manifest 里记 lastWriteAt，远端没动过且本地没改动时一个字节都不传。
 */
class SyncEngine(
    private val config: HomeworkConfig,
    private val cache: LocalCache,
    private val deviceName: String = "作业板",
) {

    companion object {
        const val TAG = "HomeworkSync"
        const val MAX_BACKUPS = 10
        /** 两端都改过、时间又差不到这个数，才算真冲突（否则只是单方面改动被拉过来） */
        const val CONFLICT_WINDOW_MS = 5_000L
        const val CONFLICT_PREFIX = "【冲突副本】"
        const val MAX_WRITE_ATTEMPTS = 3
        const val JSON = "application/json; charset=utf-8"
        /** 目录建过一次的本地标记，存 30 分钟 */
        const val DIRS_MARKER = "dirs-ensured"

        /** id 建一次就固定，重试不得重新生成，否则会补出重复条目 */
        fun newId(): String = "${System.currentTimeMillis()}-${(1000..9999).random()}"
    }

    private val client: WebDavClient
        get() = WebDavClient(
            baseUrl = config.webdavUrl,
            username = config.username,
            password = config.password,
            userAgent = WebDavClient.USER_AGENT_PHONE,
        )

    val configured: Boolean get() = config.configured

    // ------------------------------------------------------------------ 拉取

    sealed class PullResult {
        data class Ok(
            val day: HomeworkDay?,
            val days: List<ManifestDay>,
            /** 远端没变化，返回的是缓存 */
            val notModified: Boolean,
            /**
             * 实际读到的是哪一天。
             *
             * 和请求的那天不一定相同：周末打开时请求的是「周六」，读到的却是周五那份
             * 还没到期的作业（见 [DayRule]）。调用方拿它当「当前作业日」，
             * 后续的增删改也都落在这天，两端才不会各算各的。
             */
            val date: String = "",
        ) : PullResult()

        data class Failed(
            val phase: SyncPhase,
            val message: String,
            val retryable: Boolean,
        ) : PullResult()
    }

    /**
     * @param carryOver 是否按 [DayRule] 沿用上一次还没到期的作业。
     *   电视端和手机首屏要开；按日期精确读写（比如带 If-Match 改某一天）时不要开。
     */
    suspend fun pull(
        date: String,
        quiet: Boolean = false,
        carryOver: Boolean = false,
    ): PullResult = withContext(Dispatchers.IO) {
        if (!config.configured) return@withContext notConfiguredPull()

        SyncGate.mutex.withLock {
            if (!quiet) setStatus(SyncPhase.SYNCING, "同步中…")
            try {
                DavSession.update(config.webdavUrl, config.username, config.password)
                val dav = client

                // 本地没副本就不带 If-None-Match：否则 304 会拿到「没有内容可读」的死局
                val hasManifest = cache.readText(LocalCache.manifestCacheName()) != null
                val cachedEtag = if (hasManifest) {
                    cache.readText(LocalCache.manifestEtagName())?.trim()?.takeIf { it.isNotEmpty() }
                } else null

                val res = dav.getText("manifest.json", cachedEtag)
                when (res.code) {
                    304 -> {
                        cache.setLastCheckedAt(System.currentTimeMillis())
                        markSynced("已是最新")
                        // 304 没有新索引，用缓存里的那份判断该显示哪一天
                        val days = cachedManifestDays()
                        val target = if (carryOver) DayRule.resolveViewDate(date, days) else date
                        return@withLock PullResult.Ok(
                            day = cachedDay(target),
                            days = days,
                            notModified = true,
                            date = target,
                        )
                    }

                    404 -> {
                        // 目录还没建过：不是错误，只是「一条都还没布置」。
                        // 顺手把目录建出来，这样家长在手机上填完账号密码、电视端
                        // 第一次打开就把云端目录准备好了，不用再去网页版手动建文件夹。
                        runCatching { ensureDirs(dav, date) }
                        cache.setLastCheckedAt(System.currentTimeMillis())
                        markSynced("云端还没有作业板目录")
                        return@withLock PullResult.Ok(null, emptyList(), false, date)
                    }

                    200 -> {
                        val body = res.body.orEmpty()
                        val manifest = HomeworkJson.decodeFromString(Manifest.serializer(), body)
                        if (manifest.schemaVersion > SCHEMA_VERSION) {
                            throw DavUpgradeException(manifest.schemaVersion)
                        }
                        res.etag?.let { cache.writeText(LocalCache.manifestEtagName(), it) }
                        cache.writeText(LocalCache.manifestCacheName(), body)

                        // 先决定「看哪天」，再去下那一天：周末请求的是周六，
                        // 拿回来的可能是周五还没到期的那份
                        val target = if (carryOver) DayRule.resolveViewDate(date, manifest.days) else date
                        val entry = manifest.days.firstOrNull { it.date == target }
                        val day = if (entry == null) null else fetchDayIfChanged(dav, target, entry)

                        cache.setLastCheckedAt(System.currentTimeMillis())
                        val label = if (manifest.lastDeviceName.isNotBlank()) {
                            "已同步（${manifest.lastDeviceName}）"
                        } else {
                            "已同步"
                        }
                        markSynced(label, remote = manifest.lastDeviceName)
                        return@withLock PullResult.Ok(day, manifest.days, false, target)
                    }

                    else -> {
                        throwIfDavError(res.code)
                        throw DavServerException(res.code)
                    }
                }
            } catch (e: Exception) {
                return@withLock fail(e).let {
                    PullResult.Failed(it.phase, it.message, it.retryable)
                }
            }
        }
    }

    private fun fetchDayIfChanged(dav: WebDavClient, date: String, entry: ManifestDay): HomeworkDay? {
        val relPath = entry.file.ifBlank { "day/$date.json" }
        val hasCache = cache.readText(LocalCache.dayCacheName(date)) != null

        // 有缓存、且 manifest 记的 ETag 与缓存时的一致 → 这一天没变，连请求都省了
        val cachedEtag = cache.readText(LocalCache.dayEtagName(date))?.trim()
        if (hasCache && !entry.fileEtag.isNullOrBlank() && entry.fileEtag == cachedEtag) {
            cachedDay(date)?.let { return it }
        }

        val conditionalEtag = if (hasCache) {
            cachedEtag?.takeIf { it.isNotEmpty() } ?: entry.fileEtag.takeIf { it.isNotBlank() }
        } else null

        val res = dav.getText(relPath, conditionalEtag)
        return when (res.code) {
            304 -> cachedDay(date)

            200 -> {
                val body = res.body.orEmpty()
                val day = HomeworkJson.decodeFromString(HomeworkDay.serializer(), body)
                if (day.schemaVersion > SCHEMA_VERSION) throw DavUpgradeException(day.schemaVersion)
                res.etag?.let { cache.writeText(LocalCache.dayEtagName(date), it) }
                cache.writeText(LocalCache.dayCacheName(date), body)
                day
            }

            404 -> null
            else -> {
                throwIfDavError(res.code)
                throw DavServerException(res.code)
            }
        }
    }

    // ------------------------------------------------------------------ 写入

    sealed class PushResult {
        data class Ok(val day: HomeworkDay, val etag: String, val conflicts: Int = 0) : PushResult()

        data class Failed(
            val phase: SyncPhase,
            val message: String,
            /** true = 网络/服务端问题，值得进待发队列重试 */
            val retryable: Boolean,
        ) : PushResult()
    }

    /**
     * 新增或更新一条。
     *
     * 顺序：建目录 → 传图 → **读当天（拿 ETag）** → 按 id 合并 → 备份旧版 →
     * 写 `.tmp` → 写正式文件（带 If-Match）→ 更新 manifest。
     * 第 3 步的读不能省：盲写会把别处刚加的那条吃掉。
     */
    suspend fun push(
        date: String,
        item: HomeworkItem,
        newImages: List<ByteArray>,
        quiet: Boolean = false,
    ): PushResult = withContext(Dispatchers.IO) {
        if (!config.configured) return@withContext notConfiguredPush()

        SyncGate.mutex.withLock {
            if (!quiet) setStatus(SyncPhase.SYNCING, "正在发送…")
            try {
                DavSession.update(config.webdavUrl, config.username, config.password)
                val dav = client
                ensureDirs(dav, date)

                val uploaded = uploadImages(dav, date, item, newImages)
                val payload = item.copy(
                    images = (item.images + uploaded).distinct(),
                    updatedAt = IsoTime.now(),
                )

                var conflicts = 0
                var merged: HomeworkDay? = null
                var newEtag = ""

                for (attempt in 1..MAX_WRITE_ATTEMPTS) {
                    val read = dav.getText("day/$date.json")
                    val existing = when (read.code) {
                        200 -> HomeworkJson.decodeFromString(HomeworkDay.serializer(), read.body.orEmpty())
                        404 -> null
                        else -> {
                            throwIfDavError(read.code)
                            throw DavServerException(read.code)
                        }
                    }
                    if (existing != null && existing.schemaVersion > SCHEMA_VERSION) {
                        throw DavUpgradeException(existing.schemaVersion)
                    }

                    val m = mergeItem(date, existing, payload)
                    conflicts = m.conflicts
                    merged = m.day

                    // 覆盖前留一份：家长手滑删错了还能找回
                    backup(dav, date, read.body)

                    val put = putWithLock(dav, "day/$date.json", encode(m.day), read.etag)
                    if (put.ok) {
                        newEtag = put.etag ?: dav.stat("day/$date.json")?.etag.orEmpty()
                        break
                    }
                    if (put.code == 412 && attempt < MAX_WRITE_ATTEMPTS) {
                        // 真并发：别人刚写过 → 重读、重合并再来一次
                        Log.w(TAG, "day 412，重读合并（第 $attempt 次）")
                        continue
                    }
                    throwIfDavError(put.code)
                    throw DavServerException(put.code)
                }

                val finalDay = merged ?: throw DavServerException(0)
                updateManifest(dav, date, finalDay, newEtag)

                val msg = if (conflicts > 0) "已发送，有 $conflicts 条冲突副本" else "已发送"
                markSynced(msg)
                PushResult.Ok(finalDay, newEtag, conflicts)
            } catch (e: Exception) {
                val f = fail(e)
                PushResult.Failed(f.phase, f.message, f.retryable)
            }
        }
    }

    /** 删除一条：读 → 移除 → 重排 order → 乐观锁写回 */
    suspend fun deleteItem(date: String, itemId: String): PushResult = withContext(Dispatchers.IO) {
        if (!config.configured) return@withContext notConfiguredPush()

        SyncGate.mutex.withLock {
            setStatus(SyncPhase.SYNCING, "正在删除…")
            try {
                DavSession.update(config.webdavUrl, config.username, config.password)
                val dav = client

                for (attempt in 1..MAX_WRITE_ATTEMPTS) {
                    val read = dav.getText("day/$date.json")
                    if (read.code == 404) {
                        markSynced("今天云端还没有数据")
                        return@withLock PushResult.Failed(SyncPhase.SYNCED, "今天云端还没有数据", false)
                    }
                    if (read.code != 200) {
                        throwIfDavError(read.code)
                        throw DavServerException(read.code)
                    }
                    val existing = HomeworkJson.decodeFromString(HomeworkDay.serializer(), read.body.orEmpty())
                    val remaining = existing.items.filterNot { it.id == itemId }
                    if (remaining.isEmpty() && existing.items.isEmpty()) {
                        return@withLock PushResult.Failed(SyncPhase.SYNCED, "这条已经不在云端了", false)
                    }

                    // 目标已经不存在 = 别人先删了，直接当成功，不报错
                    if (remaining.size == existing.items.size) {
                        markSynced("这条已经删过了")
                        return@withLock PushResult.Ok(existing, read.etag.orEmpty())
                    }

                    val updated = existing.copy(
                        items = remaining.mapIndexed { i, it -> it.copy(order = i + 1) },
                        updatedAt = IsoTime.now(),
                        updatedBy = config.childName.ifBlank { existing.updatedBy },
                    )

                    backup(dav, date, read.body)
                    val put = putWithLock(dav, "day/$date.json", encode(updated), read.etag)
                    if (put.ok) {
                        val etag = put.etag ?: dav.stat("day/$date.json")?.etag.orEmpty()
                        updateManifest(dav, date, updated, etag)
                        markSynced("已删除")
                        return@withLock PushResult.Ok(updated, etag)
                    }
                    if (put.code == 412 && attempt < MAX_WRITE_ATTEMPTS) continue
                    throwIfDavError(put.code)
                    throw DavServerException(put.code)
                }
                PushResult.Failed(SyncPhase.CONFLICT, "云端一直在被别人改，稍后再试", true)
            } catch (e: Exception) {
                val f = fail(e)
                PushResult.Failed(f.phase, f.message, f.retryable)
            }
        }
    }

    /**
     * 只改当天的备注（电视端列表下方那条「提醒」），不动 items。
     *
     * 和 push 一样是「读 → 改 → 带 If-Match 写回」：备注是整天共享的字段，
     * 盲写会把别处刚加的作业一起冲掉。当天文件不存在时建一份空的再写。
     */
    suspend fun saveNotes(date: String, notes: String): PushResult = withContext(Dispatchers.IO) {
        if (!config.configured) return@withContext notConfiguredPush()

        SyncGate.mutex.withLock {
            setStatus(SyncPhase.SYNCING, "正在保存提醒…")
            try {
                DavSession.update(config.webdavUrl, config.username, config.password)
                val dav = client
                ensureDirs(dav, date)

                for (attempt in 1..MAX_WRITE_ATTEMPTS) {
                    val read = dav.getText("day/$date.json")
                    val base = when (read.code) {
                        200 -> HomeworkJson.decodeFromString(HomeworkDay.serializer(), read.body.orEmpty())
                        404 -> HomeworkDay(date = date, title = "", updatedBy = config.childName)
                        else -> {
                            throwIfDavError(read.code)
                            throw DavServerException(read.code)
                        }
                    }
                    if (base.schemaVersion > SCHEMA_VERSION) throw DavUpgradeException(base.schemaVersion)

                    val updated = base.copy(
                        notes = notes.trim(),
                        updatedAt = IsoTime.now(),
                        updatedBy = config.childName.ifBlank { base.updatedBy },
                    )

                    backup(dav, date, read.body)
                    val put = putWithLock(dav, "day/$date.json", encode(updated), read.etag)
                    if (put.ok) {
                        val etag = put.etag ?: dav.stat("day/$date.json")?.etag.orEmpty()
                        updateManifest(dav, date, updated, etag)
                        markSynced(if (notes.isBlank()) "提醒已清空" else "提醒已保存")
                        return@withLock PushResult.Ok(updated, etag)
                    }
                    if (put.code == 412 && attempt < MAX_WRITE_ATTEMPTS) continue
                    throwIfDavError(put.code)
                    throw DavServerException(put.code)
                }
                PushResult.Failed(SyncPhase.CONFLICT, "云端一直在被别人改，稍后再试", true)
            } catch (e: Exception) {
                val f = fail(e)
                PushResult.Failed(f.phase, f.message, f.retryable)
            }
        }
    }

    /**
     * 只改「这批作业管到哪天」（[HomeworkDay.validUntil]）。
     *
     * 周末有默认规则兜底，这个方法存在的唯一理由是没有规律的那些日子：
     * 国庆、春节、调休——星期几推不出来，只能由家长说一句「管到 10 月 7 日」。
     *
     * 空串表示「回到默认规则」。传过去的日期会被 [DayRule] 夹在
     * `[当天, 当天 + 14 天]` 之间，填错也不至于把电视钉死在一份陈年作业上。
     */
    suspend fun saveValidUntil(date: String, validUntil: String): PushResult = withContext(Dispatchers.IO) {
        if (!config.configured) return@withContext notConfiguredPush()

        SyncGate.mutex.withLock {
            setStatus(SyncPhase.SYNCING, "正在保存…")
            try {
                DavSession.update(config.webdavUrl, config.username, config.password)
                val dav = client

                for (attempt in 1..MAX_WRITE_ATTEMPTS) {
                    val read = dav.getText("day/$date.json")
                    if (read.code == 404) {
                        // 没有作业就没有「管到哪天」这回事。真建个空文件出来反而糟糕：
                        // 空的一天会占住「今天」，把还没到期的前一天顶掉。
                        return@withLock PushResult.Failed(
                            SyncPhase.SYNCED,
                            "这一天还没有作业，先记一条再说",
                            false,
                        )
                    }
                    if (read.code != 200) {
                        throwIfDavError(read.code)
                        throw DavServerException(read.code)
                    }
                    val base = HomeworkJson.decodeFromString(HomeworkDay.serializer(), read.body.orEmpty())
                    if (base.schemaVersion > SCHEMA_VERSION) throw DavUpgradeException(base.schemaVersion)

                    val updated = base.copy(
                        validUntil = DayRule.normalizeValidUntil(date, validUntil),
                        updatedAt = IsoTime.now(),
                        updatedBy = config.childName.ifBlank { base.updatedBy },
                    )

                    backup(dav, date, read.body)
                    val put = putWithLock(dav, "day/$date.json", encode(updated), read.etag)
                    if (put.ok) {
                        val etag = put.etag ?: dav.stat("day/$date.json")?.etag.orEmpty()
                        updateManifest(dav, date, updated, etag)
                        markSynced("已保存")
                        return@withLock PushResult.Ok(updated, etag)
                    }
                    if (put.code == 412 && attempt < MAX_WRITE_ATTEMPTS) continue
                    throwIfDavError(put.code)
                    throw DavServerException(put.code)
                }
                PushResult.Failed(SyncPhase.CONFLICT, "云端一直在被别人改，稍后再试", true)
            } catch (e: Exception) {
                val f = fail(e)
                PushResult.Failed(f.phase, f.message, f.retryable)
            }
        }
    }

    /** 设置页「测试连接」 */
    suspend fun testConnection(): String = withContext(Dispatchers.IO) {
        try {
            val code = client.propfind("", 0)
            when (code) {
                in 200..299 -> "连接正常，目录可读"
                else -> davErrorMessage(code)
            }
        } catch (e: IOException) {
            Log.w(TAG, "probe", e)
            "连不上：检查网络或地址"
        }
    }

    /** 直接取当天（手机首屏用，走的是同一套缓存 + 条件请求） */
    suspend fun fetchDay(date: String): HomeworkDay? = when (val r = pull(date, quiet = true)) {
        is PullResult.Ok -> r.day
        is PullResult.Failed -> throw IOException(r.message)
    }

    /**
     * 手机首屏：拉一次，返回「当前该看的那一天」。
     *
     * 和 [fetchDay] 的差别在于会按 [DayRule] 判断该不该沿用上一次布置的：
     * 周六打开手机，返回的是周五那份还没到期的作业和它的日期。
     */
    suspend fun loadActiveDay(today: String): ActiveDay =
        when (val r = pull(today, quiet = true, carryOver = true)) {
            is PullResult.Ok -> ActiveDay(r.date.ifBlank { today }, r.day)
            is PullResult.Failed -> throw IOException(r.message)
        }

    // ------------------------------------------------------------------ 内部：写

    /**
     * PUT + 乐观锁。坚果云有两个必须单独兜的怪脾气（课时簿那里撞出来的）：
     *
     * 1. 文件不存在时带 `If-Match` 也回 412 —— 先 stat，真没有就无条件写；
     * 2. 文件在、ETag 也没变却回 412，说明它不认我们带的 If-Match —— 无条件写一次。
     *    真并发时 ETag 必然变了，第 2 条不会误伤。
     *
     * 另外固定先写 `.tmp`：中途断网时正式文件仍是完整旧版，不会变成半截 json。
     */
    private fun putWithLock(dav: WebDavClient, relPath: String, body: String, baseEtag: String?): PutOutcome {
        val bytes = body.toByteArray(Charsets.UTF_8)
        val tmpPath = "$relPath.tmp"

        runCatching { dav.putBytes(tmpPath, bytes, JSON) }

        var res = dav.putBytes(relPath, bytes, JSON, ifMatch = baseEtag)

        if (res.code == 412 || res.code == 409) {
            val latest = runCatching { dav.stat(relPath) }.getOrNull()?.etag
            when {
                latest == null -> res = dav.putBytes(relPath, bytes, JSON)
                !baseEtag.isNullOrBlank() && latest == baseEtag -> res = dav.putBytes(relPath, bytes, JSON)
            }
        }

        runCatching { dav.delete(tmpPath) }
        return PutOutcome(res.code, res.etag)
    }

    private class PutOutcome(val code: Int, val etag: String?) {
        val ok: Boolean get() = code in 200..299
    }

    private fun backup(dav: WebDavClient, date: String, oldBody: String?) {
        if (oldBody.isNullOrBlank()) return
        runCatching { dav.mkcol("backup/") }
        runCatching {
            val stamp = System.currentTimeMillis()
            dav.putBytes("backup/$date-$stamp.json", oldBody.toByteArray(Charsets.UTF_8), JSON)

            // 只留最近 MAX_BACKUPS 份，按文件名（前缀日期+时间戳）倒序裁掉
            val olds = dav.list("backup/")
                .filter { !it.isCollection && it.name.startsWith("$date-") }
                .sortedByDescending { it.name }
                .drop(MAX_BACKUPS)
            olds.forEach { dav.delete("backup/${it.name}") }
        }
    }

    private fun uploadImages(
        dav: WebDavClient,
        date: String,
        item: HomeworkItem,
        images: List<ByteArray>,
    ): List<String> {
        if (images.isEmpty()) return emptyList()
        return images.mapIndexed { index, bytes ->
            // 文件名 = 本条 id 的短哈希 + 序号 + 时间戳后缀，
            // 同一条重发时不会覆盖已有图片（重发沿用原 id）
            val name = "img/$date/${shortName(item.id)}-$index-${System.currentTimeMillis() % 100000}.jpg"
            val res = dav.putBytes(name, bytes, "image/jpeg")
            if (res.code !in 200..299) {
                throwIfDavError(res.code)
                throw DavServerException(res.code)
            }
            name
        }
    }

    /**
     * 建目录。405 = 已存在。
     *
     * 除了 `day/ img/ backup/` 这些子目录，**base 目录本身也要建**：
     * 手机端现在只往电视推邮箱和密码，云端那个 `作业板/` 文件夹是没人手动建的，
     * base 不在的话子目录 MKCOL 和 PUT 全是 409。
     *
     * 每次保存都发 5 个 MKCOL 太浪费（坚果云对请求频率敏感），
     * 同一天建过一次就记 30 分钟。标记存本地缓存而不是成员变量——
     * 引擎每次操作都新建一个实例，放成员变量等于没缓存。
     */
    private fun ensureDirs(dav: WebDavClient, date: String) {
        val now = System.currentTimeMillis()
        val marker = cache.readText(DIRS_MARKER)
        if (marker != null) {
            val parts = marker.split(':')
            if (parts.size == 2 && parts[0] == date) {
                val at = parts[1].toLongOrNull() ?: 0L
                if (now - at in 0 until 30 * 60 * 1000L) return
            }
        }

        // 先保证 base 目录在；失败就没必要往下建子目录了
        val baseCode = dav.ensureBaseDir()
        if (baseCode != -1 && baseCode !in 200..299 && baseCode != 405) {
            throwIfDavError(baseCode)
            throw DavServerException(baseCode)
        }

        listOf("day/", "img/", "img/$date/", "backup/").forEach { dir ->
            val code = dav.mkcol(dir)
            if (code !in 200..299 && code != 405) {
                throwIfDavError(code)
                throw DavServerException(code)
            }
        }
        cache.writeText(DIRS_MARKER, "$date:$now")
    }

    // ------------------------------------------------------------------ 内部：合并

    private class MergeOutcome(val day: HomeworkDay, val conflicts: Int)

    /**
     * 条目级合并：
     * - 新 id → 追加，order 取当前最大值 +1；
     * - 已有 id → 比 updatedAt，新的赢；时间相同/缺失一律以本次提交为准
     * - 两边都改过、时间差 <5 秒、内容又不一样 → 输的那份留成 `【冲突副本】`
     *
     * 「两边都改过」这个判断不能省：只看时间窗的话，另一端单方面的改动被拉过来时
     * 也会凭空多出一条冲突副本垃圾数据。
     */
    private fun mergeItem(date: String, existing: HomeworkDay?, incoming: HomeworkItem): MergeOutcome {
        val base = existing ?: HomeworkDay(
            date = date,
            title = "今天的作业",
            updatedBy = config.childName,
        )

        val idx = base.items.indexOfFirst { it.id == incoming.id }
        if (idx < 0) {
            val nextOrder = (base.items.maxOfOrNull { it.order } ?: 0) + 1
            val items = (base.items + incoming.copy(order = nextOrder)).sortedBy { it.order }
            return MergeOutcome(
                base.copy(
                    date = date,
                    items = reindex(items),
                    updatedAt = IsoTime.now(),
                    updatedBy = config.childName.ifBlank { base.updatedBy },
                ),
                0,
            )
        }

        val old = base.items[idx]
        val oldMs = IsoTime.toMillis(old.updatedAt)
        val newMs = IsoTime.toMillis(incoming.updatedAt)

        val incomingWins = newMs >= oldMs
        val winner = if (incomingWins) incoming else old
        val loser = if (incomingWins) old else incoming

        val sameContent = winner.title == loser.title &&
            winner.detail == loser.detail &&
            winner.subject == loser.subject &&
            winner.images == loser.images

        // 判成真冲突的条件：内容不同 + 时间差在窗口内 + 受伤的那份确实有自己改动过的时间戳
        val concurrent = oldMs > 0L && newMs > 0L && abs(newMs - oldMs) < CONFLICT_WINDOW_MS
        val conflict = !sameContent && concurrent && IsoTime.toMillis(loser.updatedAt) > 0L

        val items = base.items.toMutableList()
        items[idx] = winner.copy(order = old.order)

        if (conflict) {
            items += loser.copy(
                id = newId(),
                title = CONFLICT_PREFIX + loser.title,
                order = (base.items.maxOfOrNull { it.order } ?: 0) + 1,
            )
        }

        return MergeOutcome(
            base.copy(
                date = date,
                items = reindex(items.sortedBy { it.order }),
                updatedAt = IsoTime.now(),
                updatedBy = config.childName.ifBlank { base.updatedBy },
            ),
            if (conflict) 1 else 0,
        )
    }

    private fun reindex(items: List<HomeworkItem>): List<HomeworkItem> =
        items.mapIndexed { i, it -> it.copy(order = i + 1) }

    private fun updateManifest(dav: WebDavClient, date: String, day: HomeworkDay, dayEtag: String) {
        for (attempt in 1..MAX_WRITE_ATTEMPTS) {
            val read = dav.getText("manifest.json")
            var manifest = Manifest()
            if (read.code == 200) {
                manifest = runCatching {
                    HomeworkJson.decodeFromString(Manifest.serializer(), read.body.orEmpty())
                }.getOrElse { Manifest() }
                if (manifest.schemaVersion > SCHEMA_VERSION) throw DavUpgradeException(manifest.schemaVersion)
            } else if (read.code != 404) {
                throwIfDavError(read.code)
                throw DavServerException(read.code)
            }

            val entry = ManifestDay(
                date = date,
                weekday = DayKeys.weekdayOf(date),
                file = "day/$date.json",
                // 服务端没给 ETag 就写空串，读的那端会降级为每次都下载当天 json
                fileEtag = dayEtag,
                itemCount = day.items.size,
                updatedAt = day.updatedAt,
                validUntil = day.validUntil,
            )

            val updated = manifest.copy(
                updatedAt = IsoTime.now(),
                days = (manifest.days.filterNot { it.date == date } + entry)
                    .sortedByDescending { it.date }
                    .take(60),
                lastWriteAt = System.currentTimeMillis(),
                lastDeviceName = deviceName,
            )

            val body = HomeworkJson.encodeToString(Manifest.serializer(), updated)
            val res = dav.putBytes("manifest.json", body.toByteArray(Charsets.UTF_8), JSON, ifMatch = read.etag)
            if (res.code in 200..299) return
            if ((res.code == 412 || res.code == 409) && attempt < MAX_WRITE_ATTEMPTS) {
                Log.w(TAG, "manifest 412，重读再来（第 $attempt 次）")
                continue
            }
            // 412 到顶了也不该让「已经写进去的作业」被判定失败：
            // 只影响别处的索引，下一次同步会自愈。
            if (res.code == 412) {
                Log.w(TAG, "manifest 连续 412，本轮放弃更新索引")
                return
            }
            throwIfDavError(res.code)
            throw DavServerException(res.code)
        }
    }

    // ------------------------------------------------------------------ 内部：状态与错误

    private fun setStatus(phase: SyncPhase, message: String) {
        SyncStatusHolder.update { it.copy(phase = phase, message = message) }
    }

    private fun markSynced(message: String, remote: String? = null) {
        SyncStatusHolder.update {
            it.copy(
                phase = if (it.conflictCount > 0) SyncPhase.CONFLICT else SyncPhase.SYNCED,
                message = message,
                lastSuccessAt = System.currentTimeMillis(),
                remoteDevice = remote ?: it.remoteDevice,
            )
        }
    }

    /**
     * 错误 → (阶段, 人话, 是否值得重试)。三段信息缺一不可：
     * 界面要显示人话，调度器要知道该不该退避。
     */
    private fun fail(e: Throwable): SyncOutcome.Failure {
        val f = when (e) {
            is DavAuthException -> SyncOutcome.Failure(
                SyncPhase.AUTH_FAILED,
                "认证失败，请检查坚果云「应用密码」",
                false,
            )

            // 坚果云凭据不对时返回 400，不是 401。归到「重试没用」这一类，别让调度器白退避一小时
            is DavBadRequestException -> SyncOutcome.Failure(
                SyncPhase.AUTH_FAILED,
                "账号、应用密码或云端地址不对（HTTP 400）",
                false,
            )

            is DavUpgradeException -> SyncOutcome.Failure(
                SyncPhase.UPGRADE_REQUIRED,
                "云端数据由更新版本写入，请升级 App",
                false,
            )

            is DavSpaceException -> SyncOutcome.Failure(
                SyncPhase.SPACE_FULL,
                "坚果云空间不足，请清理",
                false,
            )

            is DavPreconditionException -> SyncOutcome.Failure(
                SyncPhase.CONFLICT,
                "云端被其他设备抢先改写，稍后自动重试",
                true,
            )

            is DavServerException -> SyncOutcome.Failure(
                SyncPhase.SERVER_ERROR,
                when (e.code) {
                    503 -> "坚果云临时限流，几分钟后自动重试"
                    502, 504 -> "坚果云网关超时，稍后自动重试"
                    else -> "坚果云服务暂时不可用（${e.code}）"
                },
                true,
            )

            is SerializationException -> SyncOutcome.Failure(
                SyncPhase.SERVER_ERROR,
                "云端数据格式异常，已保留本机数据",
                true,
            )

            is IOException -> SyncOutcome.Failure(
                SyncPhase.OFFLINE,
                "网络不可用，稍后自动重试",
                true,
            )

            else -> SyncOutcome.Failure(
                SyncPhase.SERVER_ERROR,
                "同步失败，稍后自动重试",
                true,
            )
        }
        Log.w(TAG, "sync failed: ${e.javaClass.simpleName} / ${e.message}")
        SyncStatusHolder.update { it.copy(phase = f.phase, message = f.message) }
        return f
    }

    private fun notConfiguredPull(): PullResult {
        setStatus(SyncPhase.NOT_CONFIGURED, "还没填坚果云账号")
        return PullResult.Failed(SyncPhase.NOT_CONFIGURED, "还没填坚果云账号", false)
    }

    private fun notConfiguredPush(): PushResult {
        setStatus(SyncPhase.NOT_CONFIGURED, "还没填坚果云账号")
        return PushResult.Failed(SyncPhase.NOT_CONFIGURED, "还没填坚果云账号", false)
    }

    // ------------------------------------------------------------------ 缓存

    fun cachedDay(date: String): HomeworkDay? {
        val raw = cache.readText(LocalCache.dayCacheName(date)) ?: return null
        return runCatching { HomeworkJson.decodeFromString(HomeworkDay.serializer(), raw) }.getOrNull()
    }

    fun cachedManifestDays(): List<ManifestDay> {
        val raw = cache.readText(LocalCache.manifestCacheName()) ?: return emptyList()
        return runCatching { HomeworkJson.decodeFromString(Manifest.serializer(), raw).days }
            .getOrDefault(emptyList())
    }

    private fun encode(day: HomeworkDay): String =
        HomeworkJson.encodeToString(HomeworkDay.serializer(), day)

    private fun shortName(id: String): String {
        val digits = id.filter { it.isLetterOrDigit() }.lowercase()
        return digits.takeLast(8).ifBlank { "img" }
    }
}

/**
 * 进程内唯一的同步闸门。
 *
 * 电视端只有一个界面，但手机端「保存后自动重发 + 前台轮询 + 手动刷新」三条路会撞，
 * 而坚果云对同一账号的并发请求很敏感（503）。这里串起来最省事。
 */
internal object SyncGate {
    val mutex = Mutex()
}
