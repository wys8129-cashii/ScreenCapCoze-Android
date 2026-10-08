package com.personal.screencapcoze

import android.content.Context
import android.database.ContentObserver
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * 系统截图监听器
 *
 * 通过 ContentObserver 监听 MediaStore.Images.Media.EXTERNAL_CONTENT_URI 变化，
 * 当检测到新的截图（文件名包含 screenshot/截屏/截图 等关键字）时，自动读取并上传到 Coze API。
 *
 * 注意：需要 READ_MEDIA_IMAGES 权限（Android 13+）或 READ_EXTERNAL_STORAGE（Android 12 及以下）。
 */
class ScreenshotObserver(
    private val context: Context,
    private val scope: CoroutineScope
) : ContentObserver(Handler(Looper.getMainLooper())) {

    companion object {
        private const val TAG = "ScreenshotObserver"

        /** 只处理「刚刚生成」的截图（秒）。避免触发无关媒体变更时，
         *  把早已存在的旧截图又查出来重复上传。 */
        private const val FRESH_WINDOW_SEC = 60L

        private val SCREENSHOT_KEYWORDS = listOf(
            "screenshot", "screen_shot", "screencap", "screenshots",
            "截屏", "截图"
        )

        /** 串行化处理：一次截图 MediaStore 常回调多次，
         *  用互斥锁保证同一时刻只有一个 processLatestScreenshot 在执行，
         *  避免并发重复上传。 */
        private val processMutex = Mutex()
    }

    override fun onChange(selfChange: Boolean, uri: Uri?) {
        super.onChange(selfChange, uri)
        Log.d(TAG, "MediaStore changed: $uri")
        scope.launch(Dispatchers.IO) {
            processMutex.withLock {
                processLatestScreenshot()
            }
        }
    }

    /**
     * 查询 MediaStore 中最新添加的图片，判断是否为截图
     */
    private suspend fun processLatestScreenshot() {
        val projection = arrayOf(
            MediaStore.Images.Media._ID,
            MediaStore.Images.Media.DISPLAY_NAME,
            MediaStore.Images.Media.DATE_ADDED
        )

        val sortOrder = "${MediaStore.Images.Media.DATE_ADDED} DESC"
        // 排除还在写入中的图片（Android 10+）
        val selection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            "${MediaStore.Images.Media.IS_PENDING} = 0"
        } else null

        context.contentResolver.query(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            projection,
            selection,
            null,
            sortOrder
        )?.use { cursor ->
            if (!cursor.moveToFirst()) return

            val idIndex = cursor.getColumnIndex(MediaStore.Images.Media._ID)
            val nameIndex = cursor.getColumnIndex(MediaStore.Images.Media.DISPLAY_NAME)
            val dateAddedIndex = cursor.getColumnIndex(MediaStore.Images.Media.DATE_ADDED)

            val id = if (idIndex >= 0) cursor.getLong(idIndex) else -1L
            val name = if (nameIndex >= 0) cursor.getString(nameIndex) ?: "" else ""
            val dateAddedSec = if (dateAddedIndex >= 0) cursor.getLong(dateAddedIndex) else 0L

            if (id == -1L) return

            // 只处理「刚生成」的截图，避免无关媒体变更把旧截图查出来重复上传
            val nowSec = System.currentTimeMillis() / 1000
            if (nowSec - dateAddedSec > FRESH_WINDOW_SEC) {
                Log.d(TAG, "Skip non-fresh image: $name (age=${nowSec - dateAddedSec}s)")
                return
            }

            // 根据文件名判断是否为系统截图
            val lowerName = name.lowercase()
            val isScreenshot = SCREENSHOT_KEYWORDS.any { keyword ->
                lowerName.contains(keyword)
            }

            if (!isScreenshot) {
                Log.d(TAG, "Ignore non-screenshot image: $name")
                return
            }

            val uri = MediaStore.Images.Media.EXTERNAL_CONTENT_URI.buildUpon()
                .appendPath(id.toString())
                .build()

            Log.d(TAG, "Detected screenshot: $uri ($name)")
            uploadScreenshot(uri)
        }
    }

    /**
     * 读取截图并上传
     */
    private suspend fun uploadScreenshot(uri: Uri) {
        try {
            val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
            if (bytes == null || bytes.isEmpty()) {
                Log.w(TAG, "Failed to read screenshot bytes")
                return
            }

            // —— 去重：以原始图片字节的指纹为准，与分享路径共用同一去重键 ——
            val dedupKey = UploadDedup.contentKey(bytes)
            if (!UploadDedup.acquire(dedupKey)) {
                Log.d(TAG, "Duplicate screenshot content, skip upload: $uri")
                return
            }

            // 统一解码后交给 ImageCompressor 压缩到 1MB 以下：
            // PNG 转 JPEG、JPEG 原图也都走压缩，避免高分屏原图体积过大。
            val bmp = ImageCompressor.decode(bytes)
            val jpegBytes = if (bmp != null) {
                val j = ImageCompressor.compress(bmp)
                bmp.recycle()
                j
            } else {
                bytes
            }

            Log.d(TAG, "Screenshot size: ${bytes.size} bytes, JPEG size: ${jpegBytes.size} bytes (<= ${ImageCompressor.MAX_BYTES})")

            // 保存预览图到私有缓存，供备注页展示
            val previewUri = PreviewCache.save(context, jpegBytes)

            // 截图后立刻弹出备注页（后台上传期间可填备注）
            NoteActivity.start(context, previewUri?.toString())

            val prefs = context.getSharedPreferences("coze_config", Context.MODE_PRIVATE)
            val token = prefs.getString("coze_access_token", "") ?: ""
            val user = prefs.getString("coze_user", "") ?: ""
            if (token.isEmpty()) {
                Log.w(TAG, "No Coze Access Token configured, skip upload")
                return
            }

            val uploader = CozeUploader(token, user)
            val result = uploader.uploadImage(jpegBytes)

            if (result.isSuccess) {
                Log.d(TAG, "Screenshot uploaded successfully")
                NoteCoordinator.onUploadSuccess(context, result.title ?: "")
            } else {
                Log.e(TAG, "Upload failed: ${result.errorMessage}")
                NoteCoordinator.onUploadFailed(context)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Upload screenshot exception", e)
        }
    }
}
