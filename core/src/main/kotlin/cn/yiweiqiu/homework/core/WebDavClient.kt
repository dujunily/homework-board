package cn.yiweiqiu.homework.core

import android.util.Log
import cn.yiweiqiu.homework.core.sync.throwIfDavError
import okhttp3.Credentials
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.ByteArrayInputStream
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * 极简 WebDAV 客户端。只跟坚果云一家打交道，所以不引整套 dav 库。
 *
 * 踩坑约定（都是真机上一头撞出来的，改回去会出事）：
 * - **ETag 必须原样透传**：坚果云返回的是弱校验器 `W/"..."`，去掉 `W/` 或引号再拼回去，
 *   会导致每次条件请求都 412，界面上表现为「每次都冲突」；
 * - MKCOL 回 405 是「目录已存在」，必须当成功；
 * - PROPFIND 必须带请求体才能要到 getetag，`Depth: 0` 用来单文件取 ETag（等价于 stat）。
 */
class WebDavClient(
    baseUrl: String,
    username: String,
    password: String,
    private val userAgent: String = USER_AGENT_TV,
) {
    private val root: String = if (baseUrl.endsWith("/")) baseUrl else "$baseUrl/"

    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(45, TimeUnit.SECONDS)
        .writeTimeout(45, TimeUnit.SECONDS)
        .callTimeout(120, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .addInterceptor(::errorLogInterceptor)
        .build()

    /**
     * 把服务端报错原样落到日志里：方法 + 路径 + 状态码。
     *
     * 401/403 时顺带抓响应体——坚果云的「限流」和「密码错」都长成 401，
     * 只有响应体能分清（限流时正文会写 quota/too many，密码错是空body或 XML fault）。
     * 没有这段日志，间歇性 401 只能靠猜。
     */
    private fun errorLogInterceptor(chain: Interceptor.Chain): okhttp3.Response {
        val req = chain.request()
        val resp = chain.proceed(req)
        if (resp.code >= 400) {
            Log.w(TAG, "DAV ${req.method} ${req.url.encodedPath} -> ${resp.code}")
            if (resp.code == 401 || resp.code == 403) {
                runCatching { resp.peekBody(512).string() }
                    .onSuccess { Log.w(TAG, "DAV 401 body: ${it.replace('\n', ' ')}") }
            }
        }
        return resp
    }

    private val authorization: String = Credentials.basic(username, password, Charsets.UTF_8)

    fun urlOf(relPath: String): String = root + relPath.trimStart('/')

    /** 带条件请求的 GET。code==304 时 body 为 null。 */
    @Throws(IOException::class)
    fun getText(relPath: String, ifNoneMatch: String? = null): DavResponse {
        val builder = Request.Builder()
            .url(urlOf(relPath))
            .header("Authorization", authorization)
            .header("User-Agent", userAgent)
            .get()

        if (!ifNoneMatch.isNullOrBlank()) {
            // 原样透传，连 W/ 和引号一起
            builder.header("If-None-Match", ifNoneMatch)
        }

        client.newCall(builder.build()).execute().use { response ->
            val body = if (response.code == 200) response.body?.string() else null
            return DavResponse(
                code = response.code,
                etag = response.header("ETag"),
                body = body,
            )
        }
    }

    /** PUT 一段 JSON 文本。 */
    @Throws(IOException::class)
    fun putText(
        relPath: String,
        body: String,
        contentType: String = "application/json; charset=utf-8",
        ifMatch: String? = null,
    ): DavResponse = putBytes(relPath, body.toByteArray(Charsets.UTF_8), contentType, ifMatch)

    /**
     * PUT 二进制（图片 / json 都走这里）。
     * @param ifMatch 非空时带上 `If-Match`，做乐观锁；412 表示别人抢先写过。
     */
    @Throws(IOException::class)
    fun putBytes(
        relPath: String,
        bytes: ByteArray,
        contentType: String,
        ifMatch: String? = null,
    ): DavResponse {
        val builder = Request.Builder()
            .url(urlOf(relPath))
            .header("Authorization", authorization)
            .header("User-Agent", userAgent)
            .put(bytes.toRequestBody(contentType.toMediaType()))

        // 别去引号：坚果云给的是弱校验器
        if (!ifMatch.isNullOrBlank()) builder.header("If-Match", ifMatch)

        client.newCall(builder.build()).execute().use { response ->
            return DavResponse(
                code = response.code,
                etag = response.header("ETag"),
                body = null,
            )
        }
    }

    /** 建目录。已存在时服务器回 405，视为成功。 */
    @Throws(IOException::class)
    fun mkcol(relPath: String): Int {
        val request = Request.Builder()
            .url(urlOf(relPath))
            .header("Authorization", authorization)
            .header("User-Agent", userAgent)
            .method("MKCOL", null)
            .build()

        client.newCall(request).execute().use { return it.code }
    }

    /**
     * 逐级创建 base 目录本身（例如 `.../dav/作业板/`）。
     *
     * [mkcol] 只能建 base 目录**里面**的子目录。base 自己不存在时，
     * 子目录的 MKCOL 和后续 PUT 都会回 409，界面上表现为「建目录失败」，
     * 而家长看到的其实是「云端还没有这个文件夹」。
     *
     * 坚果云允许 MKCOL 建目录，但要求父目录已存在，所以这里从业务目录那一层开始
     * 逐段往下建。**服务器的固定 WebDAV 根段（坚果云的 `/dav`）直接跳过，不发请求**——
     * 对它执行 MKCOL 回 403 OperationNotAllowed，曾被当成「认证失败」，
     * 导致每次发作业都误报；课时簿 / 动账从来只对业务目录 MKCOL，不碰这一层。
     *
     * 405（已存在）和 403（服务端管理的段）按「跳过继续」处理；
     * 其他非成功码才中断——真缺目录时后续 PUT 会以 409 露出来。
     *
     * @return 最末一层的状态码；地址解析失败返回 -1。
     */
    @Throws(IOException::class)
    fun ensureBaseDir(): Int {
        val parsed = root.toHttpUrlOrNull() ?: return -1
        val builder = parsed.newBuilder().encodedPath("/").query(null)
        var last = 0
        for (seg in parsed.pathSegments) {
            if (seg.isEmpty()) continue
            builder.addPathSegment(seg)
            val current = builder.build()
            // 固定根段（/dav）：课时簿 / 动账的口径——只建业务目录，不碰服务器路径
            if (current.encodedPath == "/dav") continue
            last = rawMkcol(current.toString())
            if (last in 200..299 || last == 405 || last == 403) continue
            return last
        }
        return last
    }

    private fun rawMkcol(url: String): Int {
        val request = Request.Builder()
            .url(url)
            .header("Authorization", authorization)
            .header("User-Agent", userAgent)
            .method("MKCOL", null)
            .build()

        client.newCall(request).execute().use { return it.code }
    }

    /** 目录探测，用于给出「云端没有 /作业板/ 目录」这类可读错误。 */    @Throws(IOException::class)
    fun propfind(relPath: String = "", depth: Int = 0): Int {
        val request = Request.Builder()
            .url(urlOf(relPath))
            .header("Authorization", authorization)
            .header("User-Agent", userAgent)
            .header("Depth", depth.toString())
            .method("PROPFIND", null)
            .build()

        client.newCall(request).execute().use { return it.code }
    }

    /**
     * 取单个文件的元信息（ETag / 大小）——等价于 `stat`。
     *
     * 为什么需要它：412 有两种成因，一种是真并发（重新拉取合并即可），
     * 另一种是服务端不认我们带的 If-Match（死磕重试永远失败）。
     * 只有拿回当前 ETag 比一比，才能把这两种分开。
     *
     * 文件不存在返回 null（404 或 207 里没有条目）。
     */
    @Throws(IOException::class)
    fun stat(relPath: String): DavEntry? {
        val request = Request.Builder()
            .url(urlOf(relPath))
            .header("Authorization", authorization)
            .header("User-Agent", userAgent)
            .header("Depth", "0")
            .method("PROPFIND", PROPFIND_BODY.toRequestBody(XML_TYPE))
            .build()

        client.newCall(request).execute().use { response ->
            if (response.code == 404) return null
            if (response.code != 207) {
                throwIfDavError(response.code)
                return null
            }
            val bytes = response.body?.bytes() ?: return null
            return parsePropfind(bytes).firstOrNull()
        }
    }

    /** 删除文件。删除不存在的文件返回 404，调用方自己决定要不要当成功。 */
    @Throws(IOException::class)
    fun delete(relPath: String): Int {
        val request = Request.Builder()
            .url(urlOf(relPath))
            .header("Authorization", authorization)
            .header("User-Agent", userAgent)
            .delete()
            .build()

        client.newCall(request).execute().use { return it.code }
    }

    /** 列一层目录（PROPFIND depth 1）。目录不存在返回空列表，不抛错。 */
    @Throws(IOException::class)
    fun list(relPath: String): List<DavEntry> {
        val request = Request.Builder()
            .url(urlOf(relPath))
            .header("Authorization", authorization)
            .header("User-Agent", userAgent)
            .header("Depth", "1")
            .method("PROPFIND", PROPFIND_BODY.toRequestBody(XML_TYPE))
            .build()

        client.newCall(request).execute().use { response ->
            if (response.code == 404) return emptyList()
            if (response.code != 207) {
                throwIfDavError(response.code)
                return emptyList()
            }
            val bytes = response.body?.bytes() ?: return emptyList()
            return parsePropfind(bytes)
        }
    }

    // ------------------------------------------------------------------ 解析

    private fun parsePropfind(bytes: ByteArray): List<DavEntry> {
        val factory = XmlPullParserFactory.newInstance().apply { isNamespaceAware = true }
        val parser = factory.newPullParser()
        parser.setInput(ByteArrayInputStream(bytes), null)

        val entries = mutableListOf<DavEntry>()
        var inResponse = false
        var inPropstat = true
        var current = ""
        var href: String? = null
        var isCollection = false
        var etag: String? = null
        var status = 0
        var size = 0L

        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            when (event) {
                XmlPullParser.START_TAG -> when (parser.name) {
                    "response" -> {
                        inResponse = true
                        href = null; isCollection = false; etag = null; size = 0L
                    }
                    // 404 的 propstat 里也会出现空的 getetag，不能直接采信
                    "propstat" -> inPropstat = true
                    "status" -> current = "status"
                    "collection" -> isCollection = true
                    "href" -> current = "href"
                    "getetag" -> current = "etag"
                    "getcontentlength" -> current = "size"
                }

                XmlPullParser.TEXT -> {
                    val text = parser.text?.trim().orEmpty()
                    if (text.isNotEmpty()) {
                        when (current) {
                            "href" -> if (inResponse && href == null) href = text
                            "etag" -> if (inPropstat) etag = text
                            "size" -> size = text.toLongOrNull() ?: 0L
                            "status" -> status = text.substringAfter(' ', "").trim().toIntOrNull() ?: 0
                        }
                        current = ""
                    }
                }

                XmlPullParser.END_TAG -> {
                    when (parser.name) {
                        "propstat" -> {
                            // 这一段 propstat 不是 200，说明这个属性不存在，丢掉
                            if (status != 0 && status != 200) etag = null
                            inPropstat = false
                            status = 0
                        }
                        "response" -> if (inResponse) {
                            val raw = href
                            if (raw != null) {
                                val name = raw.trimEnd('/').substringAfterLast('/')
                                entries += DavEntry(name, isCollection, etag, size)
                            }
                            inResponse = false
                        }
                    }
                    current = ""
                }
            }
            event = parser.next()
        }
        return entries
    }

    companion object {
        private const val TAG = "WebDav"

        const val USER_AGENT_TV = "HomeworkBoard-TV/1.0"
        const val USER_AGENT_PHONE = "HomeworkBoard-Phone/1.0"

        private val XML_TYPE = "application/xml; charset=utf-8".toMediaType()

        private const val PROPFIND_BODY = """<?xml version="1.0" encoding="utf-8"?>
<d:propfind xmlns:d="DAV:">
  <d:prop>
    <d:getetag/>
    <d:getcontentlength/>
    <d:resourcetype/>
  </d:prop>
</d:propfind>"""
    }
}

data class DavResponse(
    val code: Int,
    val etag: String?,
    val body: String?,
)

data class DavEntry(
    val name: String,
    val isCollection: Boolean,
    /** 服务端原文，含 W/ 与引号 */
    val etag: String?,
    val size: Long,
)
