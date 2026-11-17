package com.personal.screencapcoze

import android.app.Activity
import android.net.Uri
import android.view.View
import android.widget.EditText
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import com.google.android.material.button.MaterialButton

/**
 * 备注卡片控制器：弹窗（PopupActivity）与首页（MainActivity）共用。
 *
 * 备注编辑不再单独开页面：截图上传期间卡片直接显示在当前页面，
 * 用户点「完成」把备注交给 NoteCoordinator，待第一个工作流返回 title
 * 后自动触发第二个工作流写回飞书；点「跳过」或留空则不执行第二工作流。
 *
 * 事件由 Activity 的广播接收器转发：
 *   - [onUploadStarted]  上传开始（含预览图 URI）
 *   - [onUploadDone]     上传结束（成功/失败）
 */
class NoteCardController(private val activity: Activity) {

    private val tvUpload: TextView = activity.findViewById(R.id.tvNoteUpload)
    private val ivPreview: ImageView = activity.findViewById(R.id.ivNotePreview)
    private val etNote: EditText = activity.findViewById(R.id.etNoteCard)
    private val btnSubmit: MaterialButton = activity.findViewById(R.id.btnNoteSubmit)
    private val btnSkip: MaterialButton = activity.findViewById(R.id.btnNoteSkip)

    init {
        btnSubmit.setOnClickListener { submit() }
        btnSkip.setOnClickListener { skip() }
    }

    /** 新一轮截图开始：清空输入与预览 */
    fun resetForNewCapture() {
        etNote.text.clear()
        ivPreview.visibility = View.GONE
        tvUpload.visibility = View.GONE
    }

    /** 上传开始：显示预览图和「截图正在上传中」 */
    fun onUploadStarted(previewUri: String?) {
        if (!previewUri.isNullOrEmpty()) {
            try {
                ivPreview.setImageURI(Uri.parse(previewUri))
                ivPreview.visibility = View.VISIBLE
            } catch (e: Exception) {
                ivPreview.visibility = View.GONE
            }
        }
        tvUpload.text = activity.getString(R.string.note_uploading_hint)
        tvUpload.setTextColor(ContextCompat.getColor(activity, R.color.text_secondary))
        tvUpload.visibility = View.VISIBLE
    }

    /** 上传结束 */
    fun onUploadDone(success: Boolean, errorMsg: String?) {
        tvUpload.visibility = View.VISIBLE
        if (success) {
            tvUpload.text = activity.getString(R.string.popup_status_done)
            tvUpload.setTextColor(ContextCompat.getColor(activity, R.color.status_active))
        } else {
            tvUpload.text = "上传失败：${errorMsg ?: ""}"
            tvUpload.setTextColor(ContextCompat.getColor(activity, R.color.status_error))
        }
    }

    private fun submit() {
        val note = etNote.text.toString().trim()
        if (note.isEmpty()) {
            Toast.makeText(activity, "未填写备注，不执行保存", Toast.LENGTH_SHORT).show()
            return
        }
        // title 未返回时协调器会等待，返回后自动触发第二工作流
        NoteCoordinator.onNoteSubmitted(activity, note)
        etNote.text.clear()
        Toast.makeText(activity, "备注已提交，保存结果见通知", Toast.LENGTH_SHORT).show()
    }

    private fun skip() {
        etNote.text.clear()
        Toast.makeText(activity, "已跳过备注", Toast.LENGTH_SHORT).show()
    }
}
