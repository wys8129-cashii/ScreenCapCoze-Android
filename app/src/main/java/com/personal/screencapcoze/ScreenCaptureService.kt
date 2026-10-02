package com.personal.screencapcoze

import android.app.*
import android.content.Context
import android.content.Intent
import android.graphics.*
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.DisplayMetrics
import android.util.Log
import android.view.WindowManager
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.*
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer

/**
 * 截图前台服务
 *
 * 使用 MediaProjection API 截取屏幕，然后将截图上传到 Coze API
 *
 * 流程：
 * 1. 接收 MediaProjection 授权结果
 * 2. 创建 VirtualDisplay + ImageReader 截图
 * 3. 将截图转为 PNG
 * 4. 上传到 Coze API
 * 5. 完成后停止服务
 */
class ScreenCaptureService : Service() {

    companion object {
        private const val TAG = "ScreenCapture"
        const val NOTIFICATION_CHANNEL_ID = "screencap_channel"
        const val NOTIFICATION_ID = 1

        const val ACTION_START_CAPTURE = "com.personal.screencapcoze.START_CAPTURE"
        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_RESULT_DATA = "result_data"

        /** 通知 MainActivity 截图已完成 */
        const val ACTION_CAPTURE_DONE = "com.personal.screencapcoze.CAPTURE_DONE"
        const val EXTRA_SUCCESS = "success"
        const val EXTRA_ERROR_MSG = "error_msg"

        fun startCapture(context: Context, resultCode: Int, resultData: Intent) {
            val intent = Intent(context, ScreenCaptureService::class.java).apply {
                action = ACTION_START_CAPTURE
                putExtra(EXTRA_RESULT_CODE, resultCode)
                putExtra(EXTRA_RESULT_DATA, resultData)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }
    }

    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null

    private lateinit var notificationManager: NotificationManager

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        createNotificationChannel()
        Log.d(TAG, "Service created")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_START_CAPTURE) {
            val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, 0)
            val resultData: Intent? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                intent.getParcelableExtra(EXTRA_RESULT_DATA, Intent::class.java)
            } else {
                @Suppress("DEPRECATION")
                intent.getParcelableExtra(EXTRA_RESULT_DATA)
            }

            if (resultCode == 0 || resultData == null) {
                Log.e(TAG, "Invalid MediaProjection authorization")
                notifyError("截图授权失败")
                stopSelf()
                return START_NOT_STICKY
            }

            // 启动前台通知
            startForeground(NOTIFICATION_ID, buildNotification(getString(R.string.notification_capturing)))

            // 开始截图流程
            serviceScope.launch {
                try {
                    captureAndUpload(resultCode, resultData)
                } catch (e: Exception) {
                    Log.e(TAG, "Capture failed", e)
                    notifyError(e.message ?: "未知错误")
                } finally {
                    cleanup()
                    stopSelf()
                }
            }
        }

        return START_NOT_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        serviceScope.cancel()
        cleanup()
        Log.d(TAG, "Service destroyed")
    }

    /**
     * 截图并上传的核心流程
     */
    private suspend fun captureAndUpload(resultCode: Int, resultData: Intent) {
        // Step 1: 获取 MediaProjection
        val projectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        mediaProjection = projectionManager.getMediaProjection(resultCode, resultData)

        if (mediaProjection == null) {
            notifyError("无法获取 MediaProjection")
            return
        }

        // Android 14+ 强制要求：在 createVirtualDisplay 之前必须注册 Callback
        // 否则抛出 IllegalStateException: Must register a callback before starting capture
        mediaProjection!!.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() {
                Log.d(TAG, "MediaProjection stopped by system")
                cleanup()
                stopSelf()
            }
        }, Handler(Looper.getMainLooper()))

        // Step 2: 设置 ImageReader
        val windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val displayMetrics = DisplayMetrics()
        windowManager.defaultDisplay.getRealMetrics(displayMetrics)
        val screenWidth = displayMetrics.widthPixels
        val screenHeight = displayMetrics.heightPixels
        val screenDensity = displayMetrics.densityDpi

        Log.d(TAG, "Screen: ${screenWidth}x${screenHeight} @ ${screenDensity}dpi")

        imageReader = ImageReader.newInstance(screenWidth, screenHeight, PixelFormat.RGBA_8888, 2)

        // Step 3: 创建 VirtualDisplay
        virtualDisplay = mediaProjection!!.createVirtualDisplay(
            "ScreenCapCoze",
            screenWidth,
            screenHeight,
            screenDensity,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            imageReader!!.surface,
            null,
            null
        )

        // Step 4: 等待截图帧就绪
        Log.d(TAG, "Waiting for screenshot frame...")
        val image = waitForImage(imageReader!!)
        if (image == null) {
            notifyError("截图帧获取失败")
            return
        }

        // Step 5: 转为 Bitmap -> JPEG
        Log.d(TAG, "Converting image to JPEG...")
        val bitmap = imageToBitmap(image, screenWidth, screenHeight)
        image.close()

        val jpegBytes = bitmapToJpeg(bitmap)
        Log.d(TAG, "JPEG size: ${jpegBytes.size} bytes")

        // 更新通知：上传中
        updateNotification(getString(R.string.notification_uploading))

        // Step 6: 上传到 Coze Workflow API
        Log.d(TAG, "Uploading to Coze Workflow API...")
        val prefs = getSharedPreferences("coze_config", Context.MODE_PRIVATE)
        val accessToken = prefs.getString("coze_access_token", "")!!
        val user = prefs.getString("coze_user", "")!!

        if (accessToken.isEmpty()) {
            notifyError("请先在设置中配置 Coze Access Token")
            return
        }

        val uploader = CozeUploader(accessToken, user)
        val result = uploader.uploadImage(jpegBytes)

        if (result.isSuccess) {
            Log.d(TAG, "Upload successful! Response: ${result.responseText?.take(500)}")
            notifyDone()
            broadcastResult(true, null)
        } else {
            Log.e(TAG, "Upload failed: ${result.errorMessage}")
            notifyError(result.errorMessage ?: "上传失败")
            broadcastResult(false, result.errorMessage)
        }
    }

    /**
     * 等待 ImageReader 输出可用帧
     */
    private suspend fun waitForImage(reader: ImageReader): Image? {
        return withTimeoutOrNull(5000L) {
            var image: Image? = null
            while (image == null) {
                image = reader.acquireLatestImage()
                if (image == null) {
                    delay(100) // 等待帧就绪
                }
            }
            image
        }
    }

    /**
     * Image -> Bitmap，处理 RGBA_8888 格式
     */
    private fun imageToBitmap(image: Image, width: Int, height: Int): Bitmap {
        val planes = image.planes
        val buffer: ByteBuffer = planes[0].buffer
        val pixelStride = planes[0].pixelStride
        val rowStride = planes[0].rowStride

        // rowStride 可能大于 width * pixelStride，需要处理 padding
        val rowPadding = rowStride - pixelStride * width

        val bitmap = Bitmap.createBitmap(
            width + rowPadding / pixelStride,
            height,
            Bitmap.Config.ARGB_8888
        )
        bitmap.copyPixelsFromBuffer(buffer)

        // 如果有 rowPadding，裁剪掉多余部分
        return if (rowPadding > 0) {
            Bitmap.createBitmap(bitmap, 0, 0, width, height)
        } else {
            bitmap
        }
    }

    /**
     * Bitmap -> JPEG byte array
     *
     * 使用 JPEG 格式压缩，质量 80%，比 PNG 体积小很多，
     * base64 编码后传输更高效
     */
    private fun bitmapToJpeg(bitmap: Bitmap): ByteArray {
        val stream = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, 80, stream)
        bitmap.recycle()
        return stream.toByteArray()
    }

    private fun cleanup() {
        virtualDisplay?.release()
        virtualDisplay = null
        imageReader?.close()
        imageReader = null
        mediaProjection?.stop()
        mediaProjection = null
    }

    // ---- 通知相关 ----

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                NOTIFICATION_CHANNEL_ID,
                getString(R.string.notification_channel_name),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "截图上传服务通知"
                setShowBadge(false)
            }
            notificationManager.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(text: String): Notification {
        val intent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setContentTitle(getString(R.string.notification_title))
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_screenshot)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()
    }

    private fun updateNotification(text: String) {
        val notification = buildNotification(text)
        notificationManager.notify(NOTIFICATION_ID, notification)
    }

    private fun notifyDone() {
        val notification = NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setContentTitle(getString(R.string.notification_title))
            .setContentText(getString(R.string.notification_done))
            .setSmallIcon(R.drawable.ic_screenshot)
            .setAutoCancel(true)
            .build()
        notificationManager.notify(NOTIFICATION_ID, notification)
    }

    private fun notifyError(msg: String) {
        val notification = NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setContentTitle(getString(R.string.notification_title))
            .setContentText("${getString(R.string.notification_error)}: $msg")
            .setSmallIcon(R.drawable.ic_screenshot)
            .setAutoCancel(true)
            .build()
        notificationManager.notify(NOTIFICATION_ID, notification)
        broadcastResult(false, msg)
    }

    private fun broadcastResult(success: Boolean, errorMsg: String?) {
        val intent = Intent(ACTION_CAPTURE_DONE).apply {
            putExtra(EXTRA_SUCCESS, success)
            putExtra(EXTRA_ERROR_MSG, errorMsg ?: "")
            setPackage(packageName) // 限制为本应用接收
        }
        sendBroadcast(intent)
    }
}
