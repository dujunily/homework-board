package cn.yiweiqiu.homework.core

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * 数据契约（v1）。字段只增不改名，未知字段一律忽略。
 */
val HomeworkJson: Json = Json {
    ignoreUnknownKeys = true
    isLenient = true
    coerceInputValues = true
    encodeDefaults = true
    prettyPrint = true
}

@Serializable
data class Manifest(
    val schemaVersion: Int = 1,
    val updatedAt: String = "",
    val days: List<ManifestDay> = emptyList(),
    /**
     * 最后一次写入的时间戳（epoch ms）与写入方。
     *
     * 这两个字段是从课时簿的 manifest 学来的：有了它，「这一轮要不要拉全量」不用下载
     * 当天 json 就能回答——远端没动过、本地也没改动，直接跳过。
     * 老版本与网页端不写这两个字段，读到 0 就退化成「每次都拉」，不影响正确性。
     */
    val lastWriteAt: Long = 0L,
    val lastDeviceName: String = "",
)

@Serializable
data class ManifestDay(
    val date: String,
    val weekday: String = "",
    val file: String = "",
    /** 服务端返回的 ETag 原文，含 W/ 前缀与引号，必须原样透传 */
    val fileEtag: String = "",
    @SerialName("itemCount") val itemCount: Int = 0,
    val updatedAt: String = "",
    /**
     * 这一天布置的作业管到哪天（含当天）。空串 = 按星期几推：
     * 周五、周六布置的管到周日，其余管到当天。见 [DayRule]。
     *
     * 索引里也带一份，是因为电视端要回答「今天该显示哪一天」，
     * 不带上就得把每个候选日的 json 都下一遍才能判断。
     */
    val validUntil: String = "",
)

@Serializable
data class HomeworkDay(
    val schemaVersion: Int = 1,
    val date: String = "",
    val title: String = "",
    val updatedBy: String = "",
    val updatedAt: String = "",
    val items: List<HomeworkItem> = emptyList(),
    val notes: String = "",
    /**
     * 这批作业管到哪天（含当天），空串 = 按星期几推（见 [DayRule]）。
     *
     * 为什么需要它：作业按天存，但看的动作不按天发生——周五布置的作业周六周日都在做，
     * 国庆布置的作业要管七天。没有这个字段，电视端只能认「今天」，周末一打开就是空白。
     */
    val validUntil: String = "",
)

@Serializable
data class HomeworkItem(
    val id: String = "",
    val subject: String = "",
    val title: String = "",
    val detail: String = "",
    val order: Int = 0,
    val images: List<String> = emptyList(),
    /**
     * 这一条最后一次被改动的时间（ISO8601 带时区）。
     *
     * 用于条目级合并：两端都改过同一条时，谁的时间新谁赢；
     * 时间几乎相同（<5 秒）而又都改过，才算真冲突，留一份 `[冲突副本]`。
     * 老数据和网页端写的是空串，空串一律当作「比任何时间都旧」。
     */
    val updatedAt: String = "",
) {
    val subjectOrDefault: String get() = subject.ifBlank { "其他" }
}

/**
 * 「当前作业日」：解出来的日期 + 那天的内容。
 *
 * 不直接用「今天」，是因为周末/假期要看的是上一次布置的还没到期的那份（见 [DayRule]）。
 * 增删改都必须落在这个日期上，两端才是同一套算法。
 */
data class ActiveDay(
    val date: String,
    val day: HomeworkDay?,
)

/** 科目配色，两端共用一套 */
object SubjectPalette {
    private val map = mapOf(
        "语文" to 0xFFC05A4A,
        "数学" to 0xFF3B7D9E,
        "英语" to 0xFF7A5EA8,
        "其他" to 0xFF6B8F5E,
    )

    fun colorOf(subject: String): Long = map[subject] ?: 0xFF6B8F5E
}
