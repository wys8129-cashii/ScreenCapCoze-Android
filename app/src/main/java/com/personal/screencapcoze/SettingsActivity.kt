package com.personal.screencapcoze

import android.content.Context
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.textfield.TextInputEditText

/**
 * Coze API 配置页面
 *
 * 用户需填写邮箱和 Access Token (PAT)，
 * workflow_id、app_id、API 地址都已硬编码在代码中。
 */
class SettingsActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        // 返回按钮
        findViewById<android.view.View>(R.id.btnBack).setOnClickListener {
            finish()
        }

        val etUserName = findViewById<TextInputEditText>(R.id.etUserName)
        val etAccessToken = findViewById<TextInputEditText>(R.id.etAccessToken)
        val btnSave = findViewById<com.google.android.material.button.MaterialButton>(R.id.btnSave)

        // 读取已有配置
        val prefs = getSharedPreferences("coze_config", Context.MODE_PRIVATE)
        etUserName.setText(prefs.getString("coze_user", ""))
        etAccessToken.setText(prefs.getString("coze_access_token", ""))

        // 保存按钮
        btnSave.setOnClickListener {
            val userName = etUserName.text?.toString()?.trim() ?: ""
            val accessToken = etAccessToken.text?.toString()?.trim() ?: ""

            if (userName.isEmpty()) {
                Toast.makeText(this, "用户名称不能为空", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            if (accessToken.isEmpty()) {
                Toast.makeText(this, "Access Token 不能为空", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            // 保存到 SharedPreferences
            prefs.edit()
                .putString("coze_user", userName)
                .putString("coze_access_token", accessToken)
                .apply()

            Toast.makeText(this, "配置已保存", Toast.LENGTH_SHORT).show()
            finish()
        }
    }
}
