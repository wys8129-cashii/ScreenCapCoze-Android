package com.personal.screencapcoze

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.widget.EditText
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.button.MaterialButton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream

/**
 * 系统分享撰写页
 *
 * 用户截屏后点击「分享」选择本应用时触发。
 * 在此页面可预览截图、填写可选备注，再手动点「上传截图」，
 * 将截图与备注一并上传到 Coze Workflow。
 */
class ShareComposeActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "ShareCompose"
    }

    private var imageUri: Uri? = null
    private lateinit var ivPreview: ImageView
    private lateinit var etNotes: EditText
    private lateinit var tvStatus: TextView
    private lateinit var btnUpload: MaterialButton

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_share_compose)

        ivPreview = findViewById(R.id.ivPreview)
        etNotes = findViewById(R.id.etNotes)
        tvStatus = findViewById(R.id.tvStatus)
        btnUpload = findViewById(R.id.btnUpload)
        val btnCancel = findViewById<MaterialButton>(R.id.btnCancel)

        // 恢复上次填写的备注（跨分享会话保留，方便连续上传）
        val prefs = getSharedPreferences("coze_config", MODE_PRIVATE)
        etNotes.setText(prefs.getString("last_notes", ""))

        btnUpload.setOnClickListener { startUpload() }
        btnCancel.setOnClickListener { finish() }

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
            ivPreview.setImageURI(uri)
            tvStatus.text = ""
        } else {
            finish()
        }
    }

    private fun startUpload() {
        val uri = imageUri
        if (uri == null) {
            finish()
            return
        }

        val prefs = getSharedPreferences("coze_config", MODE_PRIVATE)
        val token = prefs.getString("coze_access_token", "")
        val user = prefs.getString("coze_user", "") ?: ""

        if (token.isNullOrEmpty()) {
            tvStatus.text = "请先在「截图上传」App 中配置 Coze Access Token"
            Toast.makeText(this, "请先配置 Token", Toast.LENGTH_LONG).show()
            return
        }

        val notes = etNotes.text.toString().trim()
        // 记住备注，方便下次分享预填（可选）
        prefs.edit().putString("last_notes", notes).apply()

        btnUpload.isEnabled = false
        tvStatus.text = "正在上传…"

        scope.launch {
            try {
                // 读取原始字节：用于去重指纹 + 解码（与 ScreenshotObserver 同源，保证去重键一致）
                val rawBytes = withContext(Dispatchers.IO) {
                    contentResolver.openInputStream(uri)?.use { it.readBytes() }
                }

                if (rawBytes == null || rawBytes.isEmpty()) {
                    tvStatus.text = "无法读取图片"
                    btnUpload.isEnabled = true
                    return@launch
                }

                // 去重：若该图已被「系统截图监听」自动上传过，则跳过，避免重复
                val dedupKey = UploadDedup.contentKey(rawBytes)
                if (!UploadDedup.acquire(dedupKey)) {
                    tvStatus.text = "该截图已上传过，已跳过重复上传"
                    Toast.makeText(this@ShareComposeActivity, "已上传过，跳过重复", Toast.LENGTH_SHORT).show()
                    btnUpload.isEnabled = true
                    return@launch
                }

                val bitmap = withContext(Dispatchers.IO) {
                    BitmapFactory.decodeByteArray(rawBytes, 0, rawBytes.size)
                }
                if (bitmap == null) {
                    tvStatus.text = "无法解码图片"
                    btnUpload.isEnabled = true
                    return@launch
                }

                val jpegBytes = withContext(Dispatchers.IO) {
                    ByteArrayOutputStream().use { out ->
                        bitmap.compress(Bitmap.CompressFormat.JPEG, 80, out)
                        out.toByteArray()
                    }
                }

                val result = CozeUploader(token, user).uploadImage(jpegBytes, notes)

                if (result.isSuccess) {
                    tvStatus.text = result.responseText ?: "已成功上传到 Coze!"
                    Toast.makeText(this@ShareComposeActivity, "上传成功", Toast.LENGTH_SHORT).show()
                    // 成功后 1.2 秒自动关闭，回到分享前的界面
                    delay(1200)
                    finish()
                } else {
                    tvStatus.text = result.errorMessage ?: "上传失败"
                    Toast.makeText(this@ShareComposeActivity, "上传失败: ${result.errorMessage}", Toast.LENGTH_LONG).show()
                    btnUpload.isEnabled = true
                }
            } catch (e: Exception) {
                tvStatus.text = "上传异常: ${e.message}"
                Toast.makeText(this@ShareComposeActivity, "上传异常", Toast.LENGTH_SHORT).show()
                btnUpload.isEnabled = true
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        scope.cancel()
    }
}
