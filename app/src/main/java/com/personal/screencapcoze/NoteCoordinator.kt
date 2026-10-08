package com.personal.screencapcoze

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 备注提交协调器：解耦「用户填备注」与「第一个工作流返回 title」。
 *
 * 流程：
 *  - 截图后立刻弹出 NoteActivity，用户在后台上传期间即可填备注、点完成；
 *  - 用户点完成时把备注交给 [onNoteSubmitted]（此时 title 可能还没回来）；
 *  - 第一个工作流成功返回 title 时调用 [onUploadSuccess]；
 *  - 当 title 与备注都就绪，自动调用第二个工作流（笔记更新）写回飞书；
 *  - 若上传失败，已登记的备注作废并提示。
 *
 * 两个事件到达顺序无关：先填备注后回 title、或先回 title 后填备注，都能正确触发。
 */
object NoteCoordinator {

    @Volatile private var title: String? = null
    @Volatile private var note: String? = null
    @Volatile private var token: String? = null
    @Volatile private var email: String? = null
    @Volatile private var fired = false

    /** 第一个工作流成功返回 title */
    @Synchronized
    fun onUploadSuccess(context: Context, title: String) {
        this.title = title
        tryFire(context)
    }

    /** 第一个工作流失败：若已登记备注则作废并提示 */
    @Synchronized
    fun onUploadFailed(context: Context) {
        if (!note.isNullOrBlank()) {
            toast(context, "截图上传失败，备注未保存")
        }
        reset()
    }

    /** 备注页点完成：登记备注并尝试提交（title 可能尚未返回） */
    @Synchronized
    fun onNoteSubmitted(context: Context, note: String) {
        if (note.isBlank()) return
        this.note = note
        val prefs = context.getSharedPreferences("coze_config", Context.MODE_PRIVATE)
        this.token = prefs.getString("coze_access_token", "")
        this.email = prefs.getString("coze_user", "")
        tryFire(context)
    }

    @Synchronized
    private fun tryFire(context: Context) {
        if (fired) return
        val t = title ?: return
        val n = note ?: return
        if (n.isBlank()) return
        val tk = token
        val em = email
        if (tk.isNullOrEmpty() || em.isNullOrEmpty()) return

        fired = true
        val appCtx = context.applicationContext
        // 参数已捕获，重置状态不影响下方协程
        val capturedTitle = t
        val capturedNote = n
        val capturedToken = tk
        val capturedEmail = em
        reset()

        CoroutineScope(Dispatchers.IO).launch {
            val result = CozeUploader(capturedToken, capturedEmail).updateNote(
                capturedTitle, capturedEmail, capturedNote
            )
            withContext(Dispatchers.Main) {
                if (result.isSuccess) toast(appCtx, "备注已保存")
                else toast(appCtx, "备注保存失败：${result.errorMessage ?: ""}")
            }
        }
    }

    @Synchronized
    private fun reset() {
        title = null
        note = null
        token = null
        email = null
        fired = false
    }

    private fun toast(context: Context, msg: String) {
        Handler(Looper.getMainLooper()).post {
            Toast.makeText(context.applicationContext, msg, Toast.LENGTH_SHORT).show()
        }
    }
}
