package cn.yiweiqiu.homeworkboard.phone

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import cn.yiweiqiu.homework.core.DayKeys
import cn.yiweiqiu.homework.core.DayRule
import cn.yiweiqiu.homework.core.HomeworkItem
import cn.yiweiqiu.homework.core.HomeworkJson
import cn.yiweiqiu.homework.core.HomeworkWriter
import cn.yiweiqiu.homework.core.sync.SyncOutcome
import cn.yiweiqiu.homework.core.sync.SyncPhase
import cn.yiweiqiu.homework.core.sync.SyncPing
import cn.yiweiqiu.homework.core.sync.SyncScheduler
import cn.yiweiqiu.homework.core.sync.SyncStatusHolder
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File
import java.io.IOException

/** 编辑页的状态：新建或编辑一条都用它 */
@kotlinx.serialization.Serializable
data class EditorState(
    val id: String = "",
    val subject: String = "语文",
    val title: String = "",
    val detail: String = "",
    /** 已在云端的图片相对路径（编辑老条目时保留） */
    val keptImages: List<String> = emptyList(),
    /** 新挑的、还没上传的图片字节 */
    val newImages: List<ByteArray> = emptyList(),
    /**
     * 草稿落盘时间（毫秒）。0 表示这是「还没有这个字段」的老版本留下的草稿。
     * 只用于判断一条草稿值不值得再拿出来，不参与界面。
     */
    val savedAt: Long = 0L,
) {
    val isEditing: Boolean get() = id.isNotBlank()
    val hasContent: Boolean get() = title.isNotBlank() || detail.isNotBlank() || newImages.isNotEmpty()
    val imageCount: Int get() = keptImages.size + newImages.size

    /** 草稿真正能恢复的只有文字——图片字节不进盘，只剩图等于存了个空壳 */
    val hasText: Boolean get() = title.isNotBlank() || detail.isNotBlank()

    /**
     * 和「刚进编辑页时的原样」比，用户到底动没动过。
     *
     * 不用 data class 自带的 ==：newImages 是 ByteArray，== 比的是数组引用，
     * 同一批图换个 List 实例就判成「改过了」。这里只比会写进草稿的字段。
     * newImages 故意不比：图不进草稿，只挑几张图谈不上「写了没保存」。
     */
    fun sameAs(other: EditorState?): Boolean =
        other != null &&
            id == other.id &&
            subject == other.subject &&
            title == other.title &&
            detail == other.detail &&
            keptImages == other.keptImages
}

data class PhoneState(
    /** 今天的日期，如「11月1日 周日」 */
    val dateLabel: String = "",
    /**
     * 当前作业日：平时就是今天，周末/假期是上一次还没到期的那天。
     *
     * 新增、删除、改提醒都落在这天上，和电视端显示的是同一份。
     */
    val activeDate: String = "",
    /** 非空表示现在看的不是今天布置的，例如「沿用周五的」 */
    val carryNote: String = "",
    /** 这天显式设置的有效期；空串 = 按星期几自动推（周五、周六管到周日） */
    val validUntil: String = "",
    val items: List<HomeworkItem> = emptyList(),
    /** 当天的备注，和网页端的 notes 是同一个字段，电视端显示成「提醒」 */
    val notes: String = "",
    val pending: Int = 0,
    val loading: Boolean = false,
    val sending: Boolean = false,
    val configured: Boolean = false,
    val error: String? = null,
    /** 一次性提示，显示完由 UI 调 clearToast 清掉 */
    val toast: String? = null,
    /**
     * 这条提示是不是「恢复了草稿」——是的话 Snackbar 要带一个「丢弃」按钮。
     *
     * 没有这个出口的话，草稿一旦写进盘里就只能靠清 App 数据去掉，
     * 而清数据会把坚果云账号一起清掉。
     */
    val draftRestored: Boolean = false,
    /** 同步阶段，给「离线 / 认证失败」这类状态留个入口 */
    val phase: SyncPhase = SyncPhase.IDLE,
    /** 上一次同步成功的时间戳；设置页要拿它说「上次成功 3 分钟前」 */
    val lastSuccessAt: Long = 0L,
    /** 给电视用的 config.json 已经落到哪个路径了；没配置时为 null */
    val tvConfigPath: String? = null,
) {
    /** 电视端会一直显示到哪一天 */
    val untilLabel: String
        get() = if (activeDate.isBlank()) "" else DayRule.validUntilOf(activeDate, validUntil)
}

/** 「测试连接」的结果。loading 期间按钮显示「测试中…」，两种情况都不弹 Snackbar。 */
data class TestResult(
    val loading: Boolean = false,
    val ok: Boolean = false,
    val message: String = "",
)

class PhoneViewModel(app: Application) : AndroidViewModel(app) {

    private val appContext: Application = app
    private val store = PhoneStore(app)
    private val queue = PendingQueue(app)
    private val _state = MutableStateFlow(PhoneState(configured = store.isConfigured))
    val state: StateFlow<PhoneState> = _state.asStateFlow()

    private val _editor = MutableStateFlow(EditorState(subject = store.lastSubject))
    val editor: StateFlow<EditorState> = _editor.asStateFlow()

    private val _test = MutableStateFlow<TestResult?>(null)
    val test: StateFlow<TestResult?> = _test.asStateFlow()

    /**
     * 同步节奏交给共享调度器：
     * 失败不再每 30 秒硬撞一次（会被坚果云限流），改成 30s → 2min → 10min → 1h 退避。
     */
    private val scheduler = SyncScheduler(viewModelScope) { quiet -> runSync(quiet) }

    /** 今天（真实日期） */
    private var today: String = DayKeys.today()

    /**
     * 当前作业日。
     *
     * 平时就是今天；周末和假期是上一次布置、还没到期的那一天（见 [DayRule]）。
     * 新增、删除、改提醒都写在它上面——否则周六补一条会建出「周六的作业」，
     * 把周五那份还没到期的顶掉，电视上就突然少了一半内容。
     */
    private var activeDate: String = today

    /**
     * 进编辑页那一刻的原样（新建是空模板，编辑是那条作业的原文）。
     *
     * 只为了回答一个问题：用户离开时到底有没有写东西。
     * 没有它的话，「点开一条作业看一眼再返回」会被当成「写了一半没发」，
     * 下次开 App 就弹「已恢复上次没发出去的草稿」——这是实际报上来的问题。
     */
    private var editorBaseline: EditorState? = null

    init {
        today = DayKeys.today()
        activeDate = writer().resolvedDate(today)
        _state.update {
            it.copy(
                dateLabel = DayKeys.label(today),
                activeDate = activeDate,
                carryNote = carryNoteOf(activeDate),
                pending = queue.count(),
                configured = store.isConfigured,
            )
        }
        observeStatus()
        loadDraft()
        // 顺手把配置落到外部私有目录：电视端靠它过河（电脑上 adb pull 后 push 到电视）
        val exported = store.exportForTv()
        _state.update { it.copy(tvConfigPath = exported?.absolutePath) }
        scheduler.syncNow(quiet = true)
    }

    /** 「沿用周五的」这种说明；是自己这天的作业时返回空串 */
    private fun carryNoteOf(date: String): String =
        if (date.isBlank() || date == today) "" else "沿用${DayKeys.weekdayOf(date)}的"

    private fun observeStatus() {
        viewModelScope.launch {
            SyncStatusHolder.state.collect { st ->
                _state.update { s ->
                    s.copy(
                        phase = st.phase,
                        pending = st.pendingCount,
                        lastSuccessAt = st.lastSuccessAt,
                        error = when (st.phase) {
                            SyncPhase.AUTH_FAILED,
                            SyncPhase.SERVER_ERROR,
                            SyncPhase.SPACE_FULL,
                            SyncPhase.UPGRADE_REQUIRED,
                            -> st.message.ifBlank { "同步失败" }

                            else -> if (st.phase == SyncPhase.SYNCED) null else s.error
                        },
                    )
                }
            }
        }
    }

    /* ------------------------------ 首屏 ------------------------------ */

    fun refresh() = scheduler.syncNow(quiet = false)

    fun onEnterForeground() {
        scheduler.startPolling()
        // 回前台 60 秒去重：刚同步过、又没有待发内容就不发请求
        scheduler.syncNow(quiet = true, minIntervalMs = 60_000L)
    }

    fun onLeaveForeground() {
        scheduler.stopPolling()
    }

    /**
     * 一轮同步 = 先补发待发队列，再把当天拉回来。
     *
     * 顺序不能反：先拉后发的话，界面上会短暂出现「刚发的那条不见了」。
     */
    private suspend fun runSync(quiet: Boolean): SyncOutcome {
        if (!store.isConfigured) {
            SyncStatusHolder.update { it.copy(phase = SyncPhase.NOT_CONFIGURED, message = "还没填坚果云账号") }
            return SyncOutcome.NotConfigured
        }
        if (!quiet) _state.update { it.copy(loading = true) }

        var failedRetryable = false
        var pushed = 0

        for ((_, entry) in queue.pendingEntries()) {
            when (val r = writer().upsert(entry.date, entry.item, queue.imagesOf(entry))) {
                is HomeworkWriter.SaveResult.Ok -> {
                    queue.remove(entry)
                    pushed++
                }

                is HomeworkWriter.SaveResult.Failed -> {
                    // 认证/格式问题重试无用，留着等家长改设置；网络问题让它留在队列里
                    if (r.retryable) failedRetryable = true
                    Log.w(TAG, "补发失败：${r.message}")
                }
            }
        }
        if (pushed > 0) _state.update { it.copy(toast = "补发了 $pushed 条") }
        syncPendingCount()

        // 拉「当前作业日」而不是「今天」：周六打开手机，看到的应该还是周五布置的那批
        val loaded = runCatching { writer().loadActiveDay(today) }
        loaded.fold(
            onSuccess = { active ->
                today = DayKeys.today()
                activeDate = active.date.ifBlank { today }
                val d = active.day
                _state.update {
                    it.copy(
                        loading = false,
                        dateLabel = DayKeys.label(today),
                        activeDate = activeDate,
                        carryNote = carryNoteOf(activeDate),
                        validUntil = d?.validUntil.orEmpty(),
                        items = d?.items?.sortedBy { item -> item.order } ?: emptyList(),
                        notes = d?.notes.orEmpty(),
                        error = null,
                    )
                }
            },
            onFailure = { e ->
                Log.w(TAG, "refresh", e)
                _state.update { it.copy(loading = false, error = readable(e)) }
            },
        )

        return when {
            failedRetryable -> SyncOutcome.Failure(SyncPhase.OFFLINE, "网络不可用，稍后自动重试", true)
            loaded.isFailure ->
                SyncOutcome.Failure(SyncStatusHolder.state.value.phase, readable(loaded.exceptionOrNull()), true)

            else -> SyncOutcome.Fresh
        }
    }

    private fun syncPendingCount() {
        val n = queue.count()
        _state.update { it.copy(pending = n) }
        SyncStatusHolder.update { it.copy(pendingCount = n) }
    }

    /* ------------------------------ 编辑 ------------------------------ */

    fun startNew() {
        _editor.value = EditorState(subject = store.lastSubject)
        editorBaseline = _editor.value
    }

    /** 点列表行进编辑页：把该条的正文拆回标题/详情 */
    fun startEdit(item: HomeworkItem) {
        _editor.value = EditorState(
            id = item.id,
            subject = item.subject.ifBlank { "其他" },
            title = item.title,
            detail = item.detail,
            keptImages = item.images,
        )
        // 记下「刚进来的样子」。这条作业本来就有标题和详情，
        // 没有这行的话 hasContent 天然为真，看一眼就走也会被存成草稿。
        editorBaseline = _editor.value
    }

    fun setSubject(subject: String) {
        _editor.update { it.copy(subject = subject) }
        store.lastSubject = subject
    }

    fun setTitle(text: String) = _editor.update { it.copy(title = text) }

    fun setDetail(text: String) = _editor.update { it.copy(detail = text) }

    fun addImages(list: List<ByteArray>) {
        _editor.update { current ->
            val room = (MAX_IMAGES - current.imageCount).coerceAtLeast(0)
            current.copy(newImages = current.newImages + list.take(room))
        }
    }

    fun removeKeptImage(path: String) = _editor.update { it.copy(keptImages = it.keptImages - path) }

    fun removeNewImage(index: Int) = _editor.update {
        it.copy(newImages = it.newImages.filterIndexed { i, _ -> i != index })
    }

    /**
     * 离开编辑页时决定要不要留草稿。
     *
     * 三道闸，缺一条就会出现「进来就提示恢复了草稿」：
     * 1. 和进门时的原样一样 → 什么都没写，什么都不存（也不能顺手清掉旧的）；
     * 2. 只有图、没有字 → 图不进草稿，存下来恢复出来是空的，等于白存；
     * 3. 走到这里才真的写盘，并盖上时间戳。
     */
    fun saveDraftIfNeeded() {
        val e = _editor.value
        if (e.sameAs(editorBaseline)) return
        if (!e.hasText) {
            store.draft = ""
            return
        }
        // 图片字节不进草稿（可能好几 MB），只留文字，够防止手滑丢失
        val draft = e.copy(newImages = emptyList(), savedAt = System.currentTimeMillis())
        store.draft = HomeworkJson.encodeToString(EditorState.serializer(), draft)
    }

    fun clearDraft() {
        store.draft = ""
    }

    /**
     * 启动时捞草稿。
     *
     * 会丢两种情况，都是为了不再「每次开 App 弹一次」：
     * - savedAt = 0：老版本写的草稿（那时还不记时间）。这类恰恰是「点开作业看一眼」
     *   留下的假草稿，直接作废；
     * - 超过 [DRAFT_TTL_MS]：一周前写了半句就再没管，不该突然冒出来。
     */
    private fun loadDraft() {
        val raw = store.draft
        if (raw.isBlank()) return
        val draft = runCatching {
            HomeworkJson.decodeFromString(EditorState.serializer(), raw)
        }.getOrNull()
        if (draft == null) {
            store.draft = ""
            return
        }
        val stale = draft.savedAt <= 0L ||
            System.currentTimeMillis() - draft.savedAt > DRAFT_TTL_MS
        if (stale || !draft.hasText) {
            store.draft = ""
            return
        }
        _editor.value = draft
        // 恢复出来的草稿算「进门时的原样」：用户不动它再退出，不会再重复存一遍
        editorBaseline = draft
        _state.update { it.copy(toast = "已恢复上次没发出去的草稿", draftRestored = true) }
    }

    /* ------------------------------ 发送 ------------------------------ */

    fun send(onDone: () -> Unit) {
        val e = _editor.value
        if (!e.hasContent) {
            _state.update { it.copy(toast = "写点什么再发吧") }
            return
        }
        if (!store.isConfigured) {
            _state.update { it.copy(toast = "先去设置里填坚果云账号") }
            return
        }

        val item = HomeworkItem(
            id = e.id.ifBlank { HomeworkWriter.newId() },
            subject = e.subject,
            title = e.title.ifBlank { "作业" },
            detail = e.detail,
            images = e.keptImages,
        )

        _state.update { it.copy(sending = true) }

        viewModelScope.launch {
            // 写在「当前作业日」上：周末补一条，是补进周五那批里，
            // 不是另起一天把周五的顶掉
            today = DayKeys.today()
            val date = activeDate.takeIf { it.isNotBlank() } ?: today
            val result = writer().upsert(date, item, e.newImages)
            _state.update { it.copy(sending = false) }

            when (result) {
                is HomeworkWriter.SaveResult.Ok -> {
                    clearDraft()
                    // 乐观更新：立刻把这条塞进首屏，不等服务器二次回包
                    _state.update { s ->
                        val others = s.items.filterNot { it.id == item.id }
                        val fresh = result.day.items.firstOrNull { it.id == item.id } ?: item
                        s.copy(
                            items = (others + fresh).sortedBy { it.order },
                            error = null,
                            toast = if (e.isEditing) "已更新" else "已发到电视",
                        )
                    }
                    onDone()
                    // 喊一嗓子：同 WiFi 下的电视听见了就立刻去拉，不用等轮询
                    SyncPing.announce(viewModelScope)
                    // 3 秒后静默对一次账：别的设备这会儿可能也动过
                    scheduler.requestSync(delayMs = 3_000)
                }

                is HomeworkWriter.SaveResult.Failed -> {
                    if (result.retryable) {
                        val queued = queue.enqueue(
                            PendingQueue.Entry(date = date, item = item),
                            e.newImages,
                        )
                        syncPendingCount()
                        _state.update {
                            it.copy(toast = if (queued) "没发出去，已存进待发送" else result.message)
                        }
                        if (queued) {
                            clearDraft()
                            onDone()
                            // 先等 8 秒再试，失败就交给退避
                            scheduler.requestSync(delayMs = 8_000)
                        }
                    } else {
                        _state.update { it.copy(error = result.message, toast = result.message) }
                    }
                }
            }
        }
    }

    fun delete(item: HomeworkItem) {
        if (!store.isConfigured) return
        _state.update { it.copy(sending = true) }
        viewModelScope.launch {
            val result = writer().delete(currentDate(), item.id)
            _state.update { it.copy(sending = false) }
            when (result) {
                is HomeworkWriter.SaveResult.Ok -> {
                    _state.update { s ->
                        s.copy(items = result.day.items.sortedBy { it.order }, toast = "已删除", error = null)
                    }
                    SyncPing.announce(viewModelScope)
                    scheduler.requestSync(delayMs = 3_000)
                }

                is HomeworkWriter.SaveResult.Failed -> _state.update {
                    it.copy(toast = result.message, error = result.message)
                }
            }
        }
    }

    /* ------------------------------ 待发队列 ------------------------------ */

    /**
     * 保存当天的备注。和网页端写的是同一个 `notes` 字段，电视端会显示成「提醒」。
     * 先乐观更新界面，失败就把真实值拉回来，不让家长以为存上了。
     */
    fun saveNotes(text: String) {
        val clean = text.trim()
        _state.update { it.copy(notes = clean) }
        if (!store.isConfigured) {
            _state.update { it.copy(toast = "先去设置里填坚果云账号") }
            return
        }
        viewModelScope.launch {
            when (val r = writer().saveNotes(currentDate(), clean)) {
                is HomeworkWriter.SaveResult.Ok -> {
                    _state.update { it.copy(notes = r.day.notes, toast = if (clean.isBlank()) "提醒已清空" else "提醒已保存") }
                    SyncPing.announce(viewModelScope)
                    scheduler.requestSync(delayMs = 3_000)
                }

                is HomeworkWriter.SaveResult.Failed -> {
                    _state.update { it.copy(toast = "提醒没存上：${r.message}") }
                    // 回滚成云端真实值，避免界面显示一条其实没发出去的话
                    runSync(quiet = true)
                }
            }
        }
    }

    /* ------------------------------ 有效期 ------------------------------ */

    /**
     * 改「这批作业管到哪天」。
     *
     * 周末有默认规则（周五、周六布置的自动管到周日），这里只处理没有规律的情况：
     * 国庆、春节、调休。空串 = 回到默认规则。
     */
    fun setValidUntil(until: String) {
        val date = currentDate()
        if (!store.isConfigured) {
            _state.update { it.copy(toast = "先去设置里填坚果云账号") }
            return
        }
        viewModelScope.launch {
            _state.update { it.copy(sending = true) }
            when (val r = writer().saveValidUntil(date, until)) {
                is HomeworkWriter.SaveResult.Ok -> {
                    _state.update {
                        it.copy(
                            sending = false,
                            validUntil = r.day.validUntil,
                            toast = "电视端会显示到 ${DayKeys.label(DayRule.validUntilOf(date, r.day.validUntil))}",
                        )
                    }
                    SyncPing.announce(viewModelScope)
                    scheduler.requestSync(delayMs = 3_000)
                }

                is HomeworkWriter.SaveResult.Failed -> {
                    _state.update { it.copy(sending = false, toast = "没存上：${r.message}") }
                }
            }
        }
    }

    /** 当前作业日；还没算出来时兜到「今天」 */
    private fun currentDate(): String = activeDate.takeIf { it.isNotBlank() } ?: DayKeys.today()

    /** 家长点「待发送 N 条，点这里重试」 */
    fun retryPending() = scheduler.syncNow(quiet = false)

    /* ------------------------------ 设置 ------------------------------ */

    /**
     * 保存账号。刻意不直接回到首页：保存完还要能接着「测试连接」和「立即同步」，
     * 一保存就跳走的话家长得重新点回设置才知道到底通没通。
     */
    fun saveAccount(url: String, user: String, pass: String) {
        // 地址留空就落默认值，别把空串写进 SharedPreferences——
        // 之后拼出来会变成 "/manifest.json" 这种无效请求
        store.webdavUrl = url.trim().ifBlank { PhoneStore.DEFAULT_URL }
        store.username = user
        store.password = pass
        val ok = store.isConfigured
        _state.update {
            it.copy(
                configured = ok,
                error = null,
                toast = if (ok) "账号已保存" else "账号或密码还空着，暂时连不上",
            )
        }
        val exported = store.exportForTv()
        _state.update { it.copy(tvConfigPath = exported?.absolutePath) }
        // 配置换了要重新测：上一个结果对应的是旧账号，留着会误导
        _test.value = null
        if (ok) refresh()
    }

    /**
     * 测速走的是一份临时配置，不落盘。
     *
     * 这样「填了新密码 → 点测试 → 没通过 → 改回旧的」不会把半截配置写进 SharedPreferences，
     * 也不会因为测试失败就让首屏跟着变成未配置。
     */
    fun testConnection(url: String, user: String, pass: String) {
        // 地址允许空着：空就等于用默认目录，和电视端的规则保持一致
        val target = url.trim().ifBlank { PhoneStore.DEFAULT_URL }
        if (user.isBlank() || pass.isBlank()) {
            _test.value = TestResult(loading = false, ok = false, message = "账号和应用密码都要填")
            return
        }
        _test.value = TestResult(loading = true, ok = false, message = "正在连接…")
        viewModelScope.launch {
            // 单独的缓存目录：探针不该污染真实同步用的 etag 缓存
            val probe = HomeworkWriter(
                store.toConfig().copy(
                    webdavUrl = target,
                    username = user.trim(),
                    password = pass.trim(),
                ),
                cacheDir = File(appContext.filesDir, "cache/probe"),
            )
            val message = probe.testConnection()
            _test.value = TestResult(
                loading = false,
                ok = message.startsWith("连接正常"),
                message = message,
            )
        }
    }

    fun saveChild(name: String) {
        store.childName = name
        val exported = store.exportForTv()
        _state.update {
            it.copy(
                tvConfigPath = exported?.absolutePath,
                toast = if (name.isBlank()) "孩子名字已清空" else "已保存：$name",
            )
        }
    }

    /** 设置页的「立即同步」 */
    fun syncNow() = scheduler.syncNow(quiet = false)

    fun configJson(): String = store.configJson()

    fun settingsSnapshot(): List<String> =
        listOf(store.webdavUrl, store.username, store.password, store.childName)

    fun clearToast() = _state.update { it.copy(toast = null, draftRestored = false) }

    /**
     * 用户点了「丢弃」：草稿删掉，编辑页回到空白新建态。
     *
     * baseline 一起重置成空白，否则下次离开编辑页时会被判成「改过」，
     * 又把刚丢掉的空内容存回去。
     */
    fun discardDraft() {
        store.draft = ""
        _editor.value = EditorState(subject = store.lastSubject)
        editorBaseline = _editor.value
    }

    private fun writer() = HomeworkWriter(
        store.toConfig(),
        cacheDir = File(appContext.filesDir, "cache"),
    )

    private fun readable(e: Throwable?): String = when (e) {
        null -> "出错了"
        is IOException -> "网络不可用"
        else -> e.message ?: "出错了"
    }

    companion object {
        private const val TAG = "PhoneVM"
        private const val MAX_IMAGES = 9

        /**
         * 草稿的保质期：7 天。
         *
         * 草稿是给「写到一半被打断」兜底的，不是长期暂存。放久了会变成
         * 「上周随手写的半句话，今天突然盖在编辑页上」——比丢了更让人困惑。
         */
        private const val DRAFT_TTL_MS = 7L * 24 * 60 * 60 * 1000
    }
}
