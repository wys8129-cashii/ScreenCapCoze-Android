package com.personal.screencapcoze

import java.security.MessageDigest

/**
 * 全局上传去重器（单例）
 *
 * 背景：
 * 同一张截图可能被多条路径重复上传到 Coze，导致数据库出现重复记录：
 *   1. ScreenshotObserver —— 监听 MediaStore，系统截图后自动上传
 *   2. MainActivity.handleSharedImage —— 系统「分享」到本应用时上传
 *   3. ScreenCaptureService —— Tile / 测试按钮经 MediaProjection 上传
 *
 * 触发重复的常见场景：
 *   - 用户截图后「分享」给本应用：分享拉起 MainActivity 时注册了截图监听，
 *     而截图此时在 MediaStore 中可能仍在收尾写入，触发一次回调 → 自动上传一次；
 *     分享逻辑本身又上传一次 → 出现 2 条记录。
 *   - ContentObserver 对同一次媒资变更可能回调多次，并发执行导致重复上传。
 *
 * 解决：
 *   以「图片原始字节的 MD5 指纹」作为去重键，在时间窗口内只允许上传一次。
 *   所有上传路径共用本单例，acquire() 为原子操作，天然规避并发竞态。
 *
 * 说明：指纹基于**读取到的原始图片字节**（而非重新编码后的 JPEG），
 * 因此无论图片是 PNG 还是 JPEG、是否经过二次压缩，只要来源是同一张图，
 * 各路径算出的指纹都一致，去重才能生效。
 */
object UploadDedup {

    /** 去重时间窗口：窗口内出现相同内容的图片只上传一次 */
    private const val DEDUP_WINDOW_MS = 120_000L // 2 分钟

    private val HEX = "0123456789abcdef".toCharArray()
    private val lock = Any()

    /** 最近上传过的内容指纹 -> 上传时间戳(ms)，仅保留窗口内的记录 */
    private val recent = HashMap<String, Long>()

    /**
     * 根据图片原始字节计算内容指纹（MD5）。
     */
    fun contentKey(bytes: ByteArray): String {
        val digest = MessageDigest.getInstance("MD5").digest(bytes)
        val sb = StringBuilder(digest.size * 2 + 4)
        sb.append("img:")
        for (b in digest) {
            val v = b.toInt()
            sb.append(HEX[(v shr 4) and 0x0F])
            sb.append(HEX[v and 0x0F])
        }
        return sb.toString()
    }

    /**
     * 尝试占用一个上传名额（原子操作）。
     *
     * @return true  —— 该内容在时间窗口内首次出现，可以上传；
     *         false —— 属于重复内容，应跳过上传。
     */
    fun acquire(key: String): Boolean {
        val now = System.currentTimeMillis()
        synchronized(lock) {
            // 清理已过期的记录
            val it = recent.entries.iterator()
            while (it.hasNext()) {
                if (now - it.next().value > DEDUP_WINDOW_MS) {
                    it.remove()
                }
            }
            val last = recent[key]
            if (last != null && now - last < DEDUP_WINDOW_MS) {
                return false
            }
            recent[key] = now
            return true
        }
    }
}
