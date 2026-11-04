package cn.yiweiqiu.homework.core.sync

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface

/**
 * 局域网「有新作业」通知。
 *
 * 为什么需要它：手机和电视之间没有推送通道（没有服务器、没有 FCM），
 * 电视想知道「有没有新内容」只能自己去问坚果云。以前是每 5 分钟问一次，
 * 一天下来上百次请求，绝大多数都白跑。
 *
 * 改成：手机写完 → 在局域网里喊一嗓子 → 电视听见了才去拉。
 * 没听见就什么都不做，靠手动刷新键和 6 小时兜底保平安。
 *
 * 走 UDP 广播而不是 HTTP，是因为不需要知道对方 IP、不需要电视跑服务；
 * 代价是 UDP 可能丢包，所以连发 3 次，并且它只是「加速器」，
 * 丢了也不影响正确性——最坏情况退回手动刷新。
 */
object SyncPing {

    /** 约定的端口，两端写死同一个值 */
    const val PORT = 47823

    /** 带版本号的暗号，防止同端口上别的应用乱入 */
    private const val MAGIC = "HOMEWORK-BOARD/SYNC/1"

    private const val TAG = "SyncPing"

    /** 连发几次。UDP 不保证送达，多喊两遍覆盖偶发丢包 */
    private const val REPEAT = 3
    private const val REPEAT_GAP_MS = 120L

    /**
     * 喊一句「有新作业」。失败就算了，绝不因为喊不出去影响保存。
     *
     * @param scope 用调用方的 scope（一般是 viewModelScope），页面销毁时自动取消
     */
    fun announce(scope: CoroutineScope) {
        scope.launch(Dispatchers.IO) {
            val payload = MAGIC.toByteArray(Charsets.UTF_8)
            val targets = broadcastAddresses() + limitedBroadcast()
            for (i in 0 until REPEAT) {
                for (addr in targets) {
                    runCatching {
                        DatagramSocket().use { socket ->
                            socket.broadcast = true
                            socket.send(DatagramPacket(payload, payload.size, addr, PORT))
                        }
                    }.onFailure { Log.d(TAG, "广播失败 ${addr.hostAddress}: ${it.message}") }
                }
                if (i < REPEAT - 1) delay(REPEAT_GAP_MS)
            }
            Log.d(TAG, "已广播 ${targets.size} 个地址 × $REPEAT 次")
        }
    }

    /** 各网卡自己的子网广播地址（192.168.31.255 这种），比 255.255.255.255 更容易被路由器转发 */
    private fun broadcastAddresses(): List<InetAddress> = runCatching {
        NetworkInterface.getNetworkInterfaces()
            ?.toList()
            ?.filter { nif -> nif.isUp && !nif.isLoopback && !nif.isPointToPoint }
            ?.flatMap { nif -> nif.interfaceAddresses }
            ?.mapNotNull { ia -> ia.broadcast?.takeIf { it is Inet4Address } }
            ?: emptyList()
    }.getOrElse { emptyList() }

    /** 受限广播地址，兜底用 */
    private fun limitedBroadcast(): List<InetAddress> = runCatching {
        listOf(InetAddress.getByName("255.255.255.255"))
    }.getOrElse { emptyList() }
}

/**
 * 电视端开着听。收到 [SyncPing] 的广播就回调一次。
 *
 * 只在页面可见时开着（[start] / [stop] 由 Activity 生命周期驱动），
 * 页面不可见时电视本来也不需要立刻更新。
 */
class SyncPingListener(
    private val scope: CoroutineScope,
    private val onPing: () -> Unit,
) {

    private var job: Job? = null
    private var socket: DatagramSocket? = null

    /** 上一次触发的时间，用来吃掉「同一次保存的 3 个包」造成的重复拉取 */
    private var lastFireAt = 0L

    fun start() {
        if (job?.isActive == true) return
        job = scope.launch(Dispatchers.IO) {
            val sock = runCatching {
                DatagramSocket(null).apply {
                    // 电视端可能重进页面，端口还压在 TIME_WAIT 里，必须允许复用
                    reuseAddress = true
                    broadcast = true
                    bind(java.net.InetSocketAddress(SyncPing.PORT))
                }
            }.getOrElse {
                Log.w("SyncPing", "监听 ${SyncPing.PORT} 失败：${it.message}")
                return@launch
            }
            socket = sock
            Log.d("SyncPing", "开始监听 ${SyncPing.PORT}")
            val buf = ByteArray(128)

            try {
                while (isActive) {
                    val packet = DatagramPacket(buf, buf.size)
                    runCatching { sock.receive(packet) }
                        .onFailure { if (!isActive) return@launch else throw it }
                        .onSuccess {
                            val text = String(packet.data, 0, packet.length, Charsets.UTF_8)
                            if (text.startsWith("HOMEWORK-BOARD/SYNC")) {
                                val now = System.currentTimeMillis()
                                // 同一次保存会连发 3 个包，1.5 秒内只认一次
                                if (now - lastFireAt > 1_500L) {
                                    lastFireAt = now
                                    Log.d("SyncPing", "收到通知，来自 ${packet.address?.hostAddress}")
                                    onPing()
                                }
                            }
                        }
                }
            } finally {
                runCatching { sock.close() }
            }
        }
    }

    fun stop() {
        job?.cancel()
        job = null
        runCatching { socket?.close() }
        socket = null
    }
}
