package com.personal.screencapcoze

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 系统分享中转页
 *
 * 用户截屏后通过系统「分享」选择本应用时触发。
 * 本页不直接显示上传 UI：收到图片后
 *  1) 立即弹出备注页（NoteActivity，带预览），用户可马上填写备注；
 *  2) 立即在后台自动执行第一个工作流（上传截图），无需用户点任何按钮；
 * 上传返回 title 后，由 NoteCoordinator 在用户提交备注时自动调用第二个工作流写回飞书。
 */
class ShareComposeActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "ShareCompose"
    }

    private var imageUri: Uri? = null

    // 独立后台协程作用域：不随 Activity 销毁取消，保证上传能跑完
    private val uploadScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 透明主题，不在屏幕闪现上传界面；用户看到的是前台弹出的备注页
        setContentView(R.layout.activity_share_compose)
        handleIntent(intent)
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        if (intent?.action == Intent.ACTION_SEND && intent.type?.startsWith("image/") == true) {
            val uri = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
            } else {
                @Suppress("DEPRECATION")
                intent.getParcelableExtra(Intent.EXTRA_STREAM)
            }
            if (uri == null) {
                Toast.makeText(this, "未收到图片", Toast.LENGTH_SHORT).show()
                finish()
                return
            }
            imageUri = uri
            // 后台自动读图、压缩、保存预览、上传；在拿到预览图后立刻弹备注页
            startAutoUpload(uri)
        } else {
            finish()
        }
    }

    private fun startAutoUpload(uri: Uri) {
        val prefs = getSharedPreferences("coze_config", MODE_PRIVATE)
        val token = prefs.getString("coze_access_token", "")
        val user = prefs.getString("coze_user", "") ?: ""

        if (token.isNullOrEmpty()) {
            Toast.makeText(this, "请先在「截图上传」App 中配置 Coze Access Token", Toast.LENGTH_LONG).show()
            NoteCoordinator.onUploadFailed(applicationContext)
            finish()
            return
        }

        val appCtx = applicationContext
        uploadScope.launch {
            try {
                val rawBytes = withContext(Dispatchers.IO) {
                    appCtx.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                }
                if (rawBytes == null || rawBytes.isEmpty()) {
                    withContext(Dispatchers.Main) {
                        Toast.makeText(appCtx, "无法读取图片", Toast.LENGTH_SHORT).show()
                        NoteCoordinator.onUploadFailed(appCtx)
                        this@ShareComposeActivity.finish()
                    }
                    return@launch
                }

                // 去重：若该图已被「系统截图监听」自动上传过，则跳过，避免重复
                val dedupKey = UploadDedup.contentKey(rawBytes)
                if (!UploadDedup.acquire(dedupKey)) {
                    withContext(Dispatchers.Main) {
                        Toast.makeText(appCtx, "该截图已上传过，已跳过重复", Toast.LENGTH_SHORT).show()
                        this@ShareComposeActivity.finish()
                    }
                    return@launch
                }

                val bitmap = withContext(Dispatchers.IO) {
                    BitmapFactory.decodeByteArray(rawBytes, 0, rawBytes.size)
                }
                if (bitmap == null) {
                    withContext(Dispatchers.Main) {
                        Toast.makeText(appCtx, "无法解码图片", Toast.LENGTH_SHORT).show()
                        NoteCoordinator.onUploadFailed(appCtx)
                        this@ShareComposeActivity.finish()
                    }
                    return@launch
                }

                val jpegBytes = withContext(Dispatchers.IO) {
                    val j = ImageCompressor.compress(bitmap)
                    bitmap.recycle()
                    j
                }

                // 保存预览图到私有缓存并弹出备注页（用户可立即填写备注）
                val previewUri = PreviewCache.save(appCtx, jpegBytes)
                withContext(Dispatchers.Main) {
                    NoteActivity.start(this@ShareComposeActivity, previewUri?.toString())
                }

                val result = CozeUploader(token, user).uploadImage(jpegBytes)

                withContext(Dispatchers.Main) {
                    if (result.isSuccess) {
                        // 第一个工作流完成：通知协调器（用户已填备注则自动触发第二个工作流）
                        NoteCoordinator.onUploadSuccess(appCtx, result.title ?: "")
                    } else {
                        NoteCoordinator.onUploadFailed(appCtx)
                        Toast.makeText(appCtx, "上传失败: ${result.errorMessage}", Toast.LENGTH_LONG).show()
                    }
                    this@ShareComposeActivity.finish()
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(appCtx, "上传异常: ${e.message}", Toast.LENGTH_SHORT).show()
                    NoteCoordinator.onUploadFailed(appCtx)
                    this@ShareComposeActivity.finish()
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        // 注意：不上传 uploadScope.cancel()，让上传协程跑完（协程内部已自行 finish）
    }
}
