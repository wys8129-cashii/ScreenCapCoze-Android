package com.personal.screencapcoze

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.EditText
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.button.MaterialButton

/**
 * 备注页：截图后立刻弹出，用户在后台上传期间即可填备注。
 * 点「完成」把备注交给 NoteCoordinator，待第一个工作流返回 title 后自动写回飞书。
 * 本页不依赖 title（上传可能还没完成），也不直接调用第二个工作流。
 */
class NoteActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_IMAGE_URI = "image_uri"

        /** 统一的启动入口，供各上传路径调用（服务/监听等需在非 Activity 上下文加 NEW_TASK）。 */
        fun start(context: Context, imageUri: String? = null) {
            val intent = Intent(context, NoteActivity::class.java).apply {
                if (!imageUri.isNullOrEmpty()) {
                    putExtra(EXTRA_IMAGE_URI, imageUri)
                    // 授予 NoteActivity 读取预览图 URI 的临时权限
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        }
    }

    private lateinit var etNote: EditText

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_note)

        etNote = findViewById(R.id.etNote)
        val btnDone = findViewById<MaterialButton>(R.id.btnDone)
        val btnSkip = findViewById<MaterialButton>(R.id.btnSkip)
        val tvTitle = findViewById<TextView>(R.id.tvTitle)
        val ivPreview = findViewById<ImageView>(R.id.ivPreview)

        tvTitle.text = "为本次截图添加备注"

        val imageUri = intent.getStringExtra(EXTRA_IMAGE_URI)
        if (!imageUri.isNullOrEmpty()) {
            try {
                ivPreview.setImageURI(Uri.parse(imageUri))
                ivPreview.visibility = View.VISIBLE
            } catch (e: Exception) {
                ivPreview.visibility = View.GONE
            }
        }

        btnDone.setOnClickListener { submitNote() }
        btnSkip.setOnClickListener { finish() }
    }

    private fun submitNote() {
        val note = etNote.text.toString().trim()
        // 没有输入备注：直接关闭，不登记
        if (note.isEmpty()) {
            finish()
            return
        }
        // 把备注交给协调器：若 title 已就绪会立刻触发第二工作流，否则等上传完成
        NoteCoordinator.onNoteSubmitted(this, note)
        finish()
    }
}
