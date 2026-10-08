package com.personal.screencapcoze

import android.content.Context
import android.net.Uri
import androidx.core.content.FileProvider
import java.io.File

/**
 * 截图预览图缓存：把即将上传的图片字节保存到应用私有缓存目录，
 * 并生成一个 FileProvider content URI，供 NoteActivity 展示预览。
 *
 * 使用 FileProvider 的好处：
 * 1. 跨 Activity 不需要临时授予 content URI 读取权限；
 * 2. 控制中心 Tile、系统截图监听、系统分享三条路径都能统一传预览。
 */
object PreviewCache {

    private const val PREVIEW_DIR = "previews"
    private const val PREVIEW_FILE = "note_preview.jpg"

    /**
     * 将图片字节写入缓存并返回 content:// URI。
     * @return 成功时返回 FileProvider URI，失败返回 null
     */
    fun save(context: Context, bytes: ByteArray): Uri? {
        return try {
            val dir = File(context.cacheDir, PREVIEW_DIR).apply { mkdirs() }
            val file = File(dir, PREVIEW_FILE)
            file.writeBytes(bytes)
            FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                file
            )
        } catch (e: Exception) {
            null
        }
    }
}
