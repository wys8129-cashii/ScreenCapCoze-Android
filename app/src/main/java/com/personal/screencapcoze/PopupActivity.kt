package com.personal.screencapcoze

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.google.android.material.button.MaterialButton

/**
 * 控制中心图标点击后的「弹窗」入口。
 *
 * 与 MainActivity（全屏设置页）不同，这里只提供一个紧凑的小弹窗：
 *   - 一个「截图上传」按钮触发截图流程
 *   - 实时显示上传状态
 *   - 「完整设置」可跳转到全屏 MainActivity
 *
 * 截图授权（MediaProjection）与上传逻辑复用 ScreenCaptureService，
 * 结果通过 ACTION_CAPTURE_DONE 广播回写状态。
 */
class PopupActivity : AppCompatActivity() {

    companion object {
        const val REQUEST_MEDIA_PROJECTION = 2001
    }

    private lateinit var tvStatus: TextView
    private lateinit var btnCapture: MaterialButton
    private lateinit var captureDoneReceiver: BroadcastReceiver

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_popup)

        val prefs = getSharedPreferences("coze_config", Context.MODE_PRIVATE)

        tvStatus = findViewById(R.id.tvPopupStatus)
        btnCapture = findViewById(R.id.btnPopupCapture)

        // 点击背景遮罩（弹窗外部）关闭
        findViewById<View>(R.id.popupScrim).setOnClickListener { finish() }
        // 关闭按钮
        findViewById<View>(R.id.btnPopupClose).setOnClickListener { finish() }
        // 跳转到完整设置页
        findViewById<View>(R.id.tvPopupSettings).setOnClickListener {
            startActivity(Intent(this, MainActivity::class.java))
            finish()
        }

        btnCapture.setOnClickListener {
            val token = prefs.getString("coze_access_token", "") ?: ""
            if (token.isEmpty()) {
                setStatus(getString(R.string.popup_status_token), R.color.status_error)
                // 直接带用户去填 Token
                startActivity(Intent(this, SettingsActivity::class.java))
                return@setOnClickListener
            }
            setStatus(getString(R.string.popup_status_preparing), R.color.text_secondary)
            requestMediaProjection()
        }

        // 监听截图完成广播
        captureDoneReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                val success =
                    intent?.getBooleanExtra(ScreenCaptureService.EXTRA_SUCCESS, false) ?: false
                val errorMsg = intent?.getStringExtra(ScreenCaptureService.EXTRA_ERROR_MSG) ?: ""
                if (success) {
                    setStatus(getString(R.string.popup_status_done), R.color.status_active)
                } else {
                    setStatus("出错：$errorMsg", R.color.status_error)
                }
            }
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(
                captureDoneReceiver,
                IntentFilter(ScreenCaptureService.ACTION_CAPTURE_DONE),
                Context.RECEIVER_NOT_EXPORTED
            )
        } else {
            registerReceiver(
                captureDoneReceiver,
                IntentFilter(ScreenCaptureService.ACTION_CAPTURE_DONE)
            )
        }
    }

    private fun setStatus(text: String, colorRes: Int) {
        tvStatus.text = text
        tvStatus.setTextColor(ContextCompat.getColor(this, colorRes))
    }

    private fun requestMediaProjection() {
        val mpm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        startActivityForResult(mpm.createScreenCaptureIntent(), REQUEST_MEDIA_PROJECTION)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_MEDIA_PROJECTION) {
            if (resultCode == Activity.RESULT_OK && data != null) {
                setStatus(getString(R.string.popup_status_capturing), R.color.text_secondary)
                ScreenCaptureService.startCapture(this, resultCode, data)
            } else {
                setStatus(getString(R.string.popup_status_denied), R.color.status_error)
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
    }
}
