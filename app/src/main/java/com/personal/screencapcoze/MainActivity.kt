package com.personal.screencapcoze

import android.Manifest
import android.app.Activity
import android.app.StatusBarManager
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.drawable.Icon
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.util.Log
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.google.android.material.button.MaterialButton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream

/**
 * 主界面 Activity
 *
 * 功能：
 * 1. 首次授权 MediaProjection（截图权限）
 * 2. 打开配置页面
 * 3. 接收来自 Tile 的点击意图
 * 4. 显示截图/上传结果
 */
class MainActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "MainActivity"
        const val REQUEST_MEDIA_PROJECTION = 1001
        const val REQUEST_READ_MEDIA_IMAGES = 1002
    }

    private var isFromTile = false
    private lateinit var tvStatus: TextView
    private lateinit var tvResult: TextView
    private lateinit var btnTestCapture: MaterialButton
    private lateinit var mediaProjectionManager: MediaProjectionManager

    // 监听系统截图
    private val observerScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private lateinit var screenshotObserver: ScreenshotObserver

    /** 接收截图完成广播 */
    private val captureDoneReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val success = intent?.getBooleanExtra(ScreenCaptureService.EXTRA_SUCCESS, false) ?: false
            val errorMsg = intent?.getStringExtra(ScreenCaptureService.EXTRA_ERROR_MSG) ?: ""

            if (success) {
                tvStatus.text = getString(R.string.status_done)
                tvResult.text = "截图已成功上传到 Coze!"
            } else {
                tvStatus.text = getString(R.string.status_error)
                tvResult.text = "错误: $errorMsg"
            }

            // 如果是从 Tile 触发的，完成后关闭 Activity
            if (isFromTile) {
                finish()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        mediaProjectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager

        tvStatus = findViewById(R.id.tvStatus)
        tvResult = findViewById(R.id.tvResult)
        btnTestCapture = findViewById(R.id.btnTestCapture)

        val btnAuthorize = findViewById<MaterialButton>(R.id.btnAuthorize)
        val btnSettings = findViewById<MaterialButton>(R.id.btnSettings)
        val btnAddTile = findViewById<MaterialButton>(R.id.btnAddTile)

        // 授权按钮 -> 弹出 MediaProjection 授权对话框
        btnAuthorize.setOnClickListener {
            requestMediaProjection()
        }

        // 设置按钮 -> 打开配置页
        btnSettings.setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }

        // 添加 Tile 到控制中心 -> 调用系统 API 弹出添加对话框
        btnAddTile.setOnClickListener {
            requestAddTile()
        }

        // 测试截图按钮 -> 手动触发截图（需先授权）
        btnTestCapture.setOnClickListener {
            requestMediaProjection()
        }

        // 检查更新入口（手动）
        val btnCheckUpdate = findViewById<TextView>(R.id.btnCheckUpdate)
        btnCheckUpdate.text = "检查更新 · 当前 v${Updater.currentVersionName(this)}"
        btnCheckUpdate.setOnClickListener { checkForUpdate(manual = true) }

        // 注册截图完成广播
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(captureDoneReceiver,
                IntentFilter(ScreenCaptureService.ACTION_CAPTURE_DONE),
                Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(captureDoneReceiver,
                IntentFilter(ScreenCaptureService.ACTION_CAPTURE_DONE))
        }

        // 注册系统截图监听
        screenshotObserver = ScreenshotObserver(this, observerScope)
        requestScreenshotObserverPermission()

        // 检查是否从 Tile 触发
        handleIntent(intent)
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        // 更新 Activity 的 intent，使 getIntent() 拿到最新 intent
        setIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        when {
            intent?.action == ScreenCapTileService.ACTION_FROM_TILE -> {
                isFromTile = true
                Log.d(TAG, "Launched from Quick Settings Tile")
                tvStatus.text = "来自控制中心，准备请求截图权限"
                Toast.makeText(this, "请确认截图权限", Toast.LENGTH_SHORT).show()
                // 从 Tile 触发时，立即请求授权然后截图
                requestMediaProjection()
            }
            intent?.action == Intent.ACTION_SEND && intent.type?.startsWith("image/") == true -> {
                isFromTile = false
                Log.d(TAG, "Received shared image")
                handleSharedImage(intent)
            }
            else -> {
                isFromTile = false
                // 显示测试按钮（仅在配置完成后）
                val prefs = getSharedPreferences("coze_config", Context.MODE_PRIVATE)
                val hasConfig = prefs.getString("coze_access_token", "")?.isNotEmpty() == true
                btnTestCapture.visibility = if (hasConfig) android.view.View.VISIBLE else android.view.View.GONE
                // 正常启动时静默检查一次更新
                checkForUpdate(manual = false)
            }
        }
    }

    /**
     * 处理从系统分享接收到的图片
     *
     * 用户截屏后点击「分享」选择本应用时触发。
     * 系统会通过 Intent 授予临时的 URI 读取权限。
     */
    private fun handleSharedImage(intent: Intent) {
        val imageUri = intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)

        if (imageUri == null) {
            tvStatus.text = getString(R.string.status_error)
            tvResult.text = "未收到图片"
            return
        }

        val prefs = getSharedPreferences("coze_config", Context.MODE_PRIVATE)
        val token = prefs.getString("coze_access_token", "")
        val user = prefs.getString("coze_user", "") ?: ""

        if (token.isNullOrEmpty()) {
            tvStatus.text = "请先配置 Coze Access Token"
            tvResult.text = "点击「打开设置」按钮进行配置"
            startActivity(Intent(this, SettingsActivity::class.java))
            return
        }

        tvStatus.text = getString(R.string.status_uploading)
        tvResult.text = ""
        Toast.makeText(this, "正在上传分享的图片…", Toast.LENGTH_SHORT).show()

        observerScope.launch {
            try {
                // 先读取原始字节（用于去重指纹 + 解码），
                // 与 ScreenshotObserver 使用同一份原始字节计算指纹，保证去重键一致
                val rawBytes = withContext(Dispatchers.IO) {
                    contentResolver.openInputStream(imageUri)?.use { it.readBytes() }
                }

                if (rawBytes == null || rawBytes.isEmpty()) {
                    tvStatus.text = getString(R.string.status_error)
                    tvResult.text = "无法读取图片"
                    return@launch
                }

                // —— 去重：若该图片已被「系统截图监听」自动上传过，则跳过，避免重复 ——
                val dedupKey = UploadDedup.contentKey(rawBytes)
                if (!UploadDedup.acquire(dedupKey)) {
                    Log.d(TAG, "Duplicate shared image content, skip upload")
                    tvStatus.text = getString(R.string.status_done)
                    tvResult.text = "该截图已上传过，已跳过重复上传"
                    Toast.makeText(this@MainActivity, "该截图已上传过，跳过重复", Toast.LENGTH_SHORT).show()
                    return@launch
                }

                val bitmap = withContext(Dispatchers.IO) {
                    BitmapFactory.decodeByteArray(rawBytes, 0, rawBytes.size)
                }

                if (bitmap == null) {
                    tvStatus.text = getString(R.string.status_error)
                    tvResult.text = "无法读取图片"
                    return@launch
                }

                val jpegBytes = withContext(Dispatchers.IO) {
                    ByteArrayOutputStream().use { output ->
                        bitmap.compress(Bitmap.CompressFormat.JPEG, 80, output)
                        output.toByteArray()
                    }
                }

                val result = CozeUploader(token, user).uploadImage(jpegBytes)

                if (result.isSuccess) {
                    tvStatus.text = getString(R.string.status_done)
                    tvResult.text = result.responseText ?: "图片已成功上传到 Coze!"
                    Toast.makeText(this@MainActivity, "分享图片上传成功", Toast.LENGTH_SHORT).show()
                } else {
                    tvStatus.text = getString(R.string.status_error)
                    tvResult.text = result.errorMessage ?: "上传失败"
                    Toast.makeText(this@MainActivity, "上传失败: ${result.errorMessage}", Toast.LENGTH_LONG).show()
                }
            } catch (e: Exception) {
                Log.e(TAG, "Upload shared image failed", e)
                tvStatus.text = getString(R.string.status_error)
                tvResult.text = "上传异常: ${e.message}"
                Toast.makeText(this@MainActivity, "上传异常", Toast.LENGTH_SHORT).show()
            }
        }
    }

    /**
     * 请求 MediaProjection 授权
     *
     * 系统会弹出一个确认对话框，用户确认后回调 onActivityResult
     */
    private fun requestMediaProjection() {
        // 检查是否已配置 Coze 参数
        val prefs = getSharedPreferences("coze_config", Context.MODE_PRIVATE)
        val token = prefs.getString("coze_access_token", "")

        if (token.isNullOrEmpty()) {
            tvStatus.text = "请先配置 Coze Access Token"
            tvResult.text = "点击「打开设置」按钮进行配置"
            startActivity(Intent(this, SettingsActivity::class.java))
            return
        }

        tvStatus.text = getString(R.string.status_capturing)
        Toast.makeText(this, "请允许截图权限", Toast.LENGTH_SHORT).show()
        val intent = mediaProjectionManager.createScreenCaptureIntent()
        startActivityForResult(intent, REQUEST_MEDIA_PROJECTION)
    }

    /**
     * 请求读取媒体图片权限，用于监听系统截图
     */
    private fun requestScreenshotObserverPermission() {
        val permission = when {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU -> Manifest.permission.READ_MEDIA_IMAGES
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.M -> Manifest.permission.READ_EXTERNAL_STORAGE
            else -> null
        }

        if (permission == null) {
            registerScreenshotObserver()
            return
        }

        if (ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED) {
            registerScreenshotObserver()
        } else {
            ActivityCompat.requestPermissions(this, arrayOf(permission), REQUEST_READ_MEDIA_IMAGES)
        }
    }

    private fun registerScreenshotObserver() {
        contentResolver.registerContentObserver(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            true,
            screenshotObserver
        )
        Log.d(TAG, "Screenshot observer registered")
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_READ_MEDIA_IMAGES) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                registerScreenshotObserver()
            } else {
                Toast.makeText(this, "未授予读取图片权限，无法监听系统截图", Toast.LENGTH_LONG).show()
            }
        }
    }

    /**
     * 请求添加 Tile 到 Quick Settings 控制中心
     *
     * 调用 StatusBarManager.requestAddTileService()（API 33+）
     * 系统会弹出对话框让用户确认添加
     */
    private fun requestAddTile() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            Toast.makeText(this, "需要 Android 13 以上版本", Toast.LENGTH_SHORT).show()
            return
        }

        val statusBarManager = getSystemService(StatusBarManager::class.java)
        val componentName = ComponentName(this, ScreenCapTileService::class.java)
        val tileLabel = getString(R.string.tile_label)
        val icon = Icon.createWithResource(this, R.drawable.ic_braces)

        statusBarManager.requestAddTileService(
            componentName,
            tileLabel,
            icon,
            mainExecutor
        ) { result ->
            when (result) {
                StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_ADDED -> {
                    Toast.makeText(this, "已添加到控制中心", Toast.LENGTH_SHORT).show()
                    Log.d(TAG, "Tile added to Quick Settings")
                }
                StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_ALREADY_ADDED -> {
                    Toast.makeText(this, "已经在控制中心了", Toast.LENGTH_SHORT).show()
                    Log.d(TAG, "Tile already added")
                }
                else -> {
                    Toast.makeText(this, "添加结果: $result", Toast.LENGTH_SHORT).show()
                    Log.w(TAG, "Tile add result: $result")
                }
            }
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)

        if (requestCode == REQUEST_MEDIA_PROJECTION) {
            if (resultCode == Activity.RESULT_OK && data != null) {
                Log.d(TAG, "MediaProjection authorized")
                tvStatus.text = getString(R.string.status_capturing)

                // 启动截图服务
                ScreenCaptureService.startCapture(this, resultCode, data)
            } else {
                Log.d(TAG, "MediaProjection denied")
                tvStatus.text = getString(R.string.status_error)
                tvResult.text = "截图权限被拒绝"

                if (isFromTile) {
                    finish()
                }
            }
        }
    }

    // ---- 应用内更新 ----

    /**
     * 检查更新。
     * @param manual 是否为用户手动点击触发（true 时无新版本会给出提示）
     */
    private fun checkForUpdate(manual: Boolean) {
        observerScope.launch {
            val info = Updater.checkForUpdate(this@MainActivity)
            if (info == null) {
                if (manual) {
                    Toast.makeText(
                        this@MainActivity,
                        "已是最新版本 v${Updater.currentVersionName(this@MainActivity)}",
                        Toast.LENGTH_SHORT
                    ).show()
                }
                return@launch
            }
            showUpdateDialog(info)
        }
    }

    private fun showUpdateDialog(info: Updater.UpdateInfo) {
        val msg = buildString {
            append("发现新版本 v${info.versionName}\n")
            append("当前版本 v${Updater.currentVersionName(this@MainActivity)}\n\n")
            if (info.notes.isNotBlank()) {
                append(info.notes.take(600))
            }
        }
        AlertDialog.Builder(this)
            .setTitle("发现新版本")
            .setMessage(msg)
            .setPositiveButton("立即更新") { _, _ -> startUpdateDownload(info) }
            .setNegativeButton("稍后", null)
            .show()
    }

    private fun startUpdateDownload(info: Updater.UpdateInfo) {
        val density = resources.displayMetrics.density
        val pad = (24 * density).toInt()

        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
        }
        val tv = TextView(this).apply { text = "正在下载 0%" }
        val pb = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            progress = 0
        }
        container.addView(tv)
        container.addView(pb)

        val dialog = AlertDialog.Builder(this)
            .setTitle("正在下载更新")
            .setView(container)
            .setCancelable(false)
            .show()

        observerScope.launch {
            val file = Updater.downloadApk(this@MainActivity, info) { p ->
                runOnUiThread {
                    pb.progress = p
                    tv.text = "正在下载 $p%"
                }
            }
            dialog.dismiss()

            if (file == null) {
                Toast.makeText(this@MainActivity, "下载失败，请稍后重试", Toast.LENGTH_SHORT).show()
                return@launch
            }

            Toast.makeText(this@MainActivity, "下载完成，即将安装", Toast.LENGTH_SHORT).show()
            val ok = Updater.installApk(this@MainActivity, file)
            if (!ok) {
                Toast.makeText(this@MainActivity, "无法启动安装", Toast.LENGTH_SHORT).show()
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        try {
            unregisterReceiver(captureDoneReceiver)
        } catch (e: Exception) {
            // 忽略
        }
        try {
            contentResolver.unregisterContentObserver(screenshotObserver)
        } catch (e: Exception) {
            // 忽略
        }
        observerScope.cancel()
    }
}
