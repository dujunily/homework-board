package cn.yiweiqiu.homework.core

import okhttp3.Credentials

/**
 * 图片加载需要同一套 WebDAV 凭据。仓库在解析配置成功时把会话写进来，
 * Coil 的 OkHttp 拦截器按需读取，避免把密码到处传。
 */
object DavSession {

    @Volatile
    private var root: String? = null

    @Volatile
    private var authorization: String? = null

    fun update(baseUrl: String, username: String, password: String) {
        root = if (baseUrl.endsWith("/")) baseUrl else "$baseUrl/"
        authorization = Credentials.basic(username, password)
    }

    fun clear() {
        root = null
        authorization = null
    }

    val authHeader: String? get() = authorization

    /** 把契约里的相对路径转成完整 URL；未配置时返回 null */
    fun absoluteUrl(relPath: String): String? {
        val base = root ?: return null
        return base + relPath.trimStart('/')
    }
}
