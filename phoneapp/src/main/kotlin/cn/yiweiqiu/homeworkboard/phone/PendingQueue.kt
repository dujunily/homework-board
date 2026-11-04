package cn.yiweiqiu.homeworkboard.phone

import android.content.Context
import cn.yiweiqiu.homework.core.HomeworkItem
import cn.yiweiqiu.homework.core.HomeworkJson
import kotlinx.serialization.Serializable
import java.io.File

/**
 * 待发队列：网络不通或上传失败时，把"这一条 + 它的图片"落到本地，联网后自动补发。
 *
 * 用文件而不是 Room：一条待发记录就是一个 json + 一个图片目录，肉眼可查、坏了能手工删。
 * 关键是 id 在入队时就定了，重发沿用同一个 id，所以补发不会产生重复条目。
 */
class PendingQueue(context: Context) {

    private val root = File(context.applicationContext.filesDir, "pending").apply { mkdirs() }

    @Serializable
    data class Entry(
        val date: String,
        val item: HomeworkItem,
        /** 该条已压缩图片在本地的文件名，按顺序 */
        val imageFiles: List<String> = emptyList(),
    )

    fun count(): Int = runCatching {
        root.listFiles { f -> f.name.endsWith(".json") }?.size ?: 0
    }.getOrDefault(0)

    /** 入队（覆盖同 id 的旧记录，避免反复失败时堆积） */
    fun enqueue(entry: Entry, images: List<ByteArray>): Boolean = runCatching {
        val id = entry.item.id.ifBlank { return false }
        val dir = File(root, id).apply { mkdirs() }
        // 清掉上一轮的图，防止同一 id 多次失败越积越多
        dir.listFiles()?.forEach { it.delete() }

        val names = images.mapIndexed { index, bytes ->
            val name = "img-$index.jpg"
            File(dir, name).writeBytes(bytes)
            name
        }

        val stored = entry.copy(imageFiles = names)
        File(root, "$id.json").writeText(HomeworkJson.encodeToString(Entry.serializer(), stored))
        true
    }.getOrDefault(false)

    fun pendingEntries(): List<Pair<File, Entry>> = runCatching {
        root.listFiles { f -> f.name.endsWith(".json") }
            ?.sortedBy { it.lastModified() }
            ?.mapNotNull { file ->
                val entry = runCatching {
                    HomeworkJson.decodeFromString(Entry.serializer(), file.readText())
                }.getOrNull() ?: return@mapNotNull null
                file to entry
            }
            .orEmpty()
    }.getOrDefault(emptyList())

    fun imagesOf(entry: Entry): List<ByteArray> = entry.imageFiles.mapNotNull { name ->
        runCatching { File(File(root, entry.item.id), name).readBytes() }.getOrNull()
    }

    fun remove(entry: Entry) {
        runCatching {
            File(root, "${entry.item.id}.json").delete()
            File(root, entry.item.id).deleteRecursively()
        }
    }
}
