package cn.yiweiqiu.homework.core.sync

import java.io.IOException

/**
 * WebDAV 异常分类，照着课时簿 / 动账那一套搬。
 *
 * 分门别类的唯一理由：给家长看的话必须说人话，而且「该不该重试」得让调度器一眼看出来。
 * 401 是密码错（重试一万次也没用），507 是空间满，412 是别人抢先写了（重试才有意义），
 * 糊成一句「同步失败」等于什么都没说。
 */
class DavAuthException :
    IOException("账号或应用密码错误（坚果云要用「应用专用密码」，不是登录密码）")

/** 412：远端在本次读写之间被别的设备改过，需要重新拉取合并 */
class DavPreconditionException :
    IOException("云端被其他设备抢先改写过")

class DavSpaceException :
    IOException("坚果云空间不足，请清理")

class DavServerException(val code: Int) :
    IOException("坚果云服务异常（$code）")

/**
 * 400：坚果云对**凭据不对**的请求不返回 401，而是直接 400。
 *
 * 这是踩过的坑：把 username/password 写成模板占位符（"你的坚果云登录邮箱"）时，
 * Basic Auth 里是中文，坚果云一律 400。以前 400 落到兜底分支，
 * 界面上显示成「服务暂时不可用」，看着像坚果云挂了，实际是账号没填对。
 * 顺带一提，webdavUrl 写错也会 400，所以文案要把地址也带上。
 */
class DavBadRequestException :
    IOException("账号、应用密码或云端地址不对（HTTP 400）")

/** 云端 schemaVersion 比本机支持的高：说明是更新版本写的，猜着解析只会解出半截数据 */
class DavUpgradeException(val remoteVersion: Int) :
    IOException("云端数据由更新版本的 App 写入（v$remoteVersion），请升级")

/** 把 HTTP 码翻译成异常。调用方只需 catch 这一处。 */
internal fun throwIfDavError(code: Int) {
    when (code) {
        400 -> throw DavBadRequestException()
        401, 403 -> throw DavAuthException()
        507 -> throw DavSpaceException()
        in 500..599 -> throw DavServerException(code)
    }
}

/** 状态码转人话，给「测试连接」这类需要直接展示结果的场景用 */
fun davErrorMessage(code: Int): String = when (code) {
    400 -> "账号、应用密码或云端地址不对"
    401, 403 -> "账号或应用密码错误"
    404 -> "云端没有 /作业板/ 目录，请先在坚果云里建一个"
    409, 412 -> "云端数据刚被别的设备改过，稍后自动重试"
    423 -> "云端文件被锁定，稍后再试"
    507 -> "坚果云空间不足，请清理"
    in 500..599 -> "坚果云服务暂时不可用（$code）"
    else -> "请求失败（HTTP $code）"
}
