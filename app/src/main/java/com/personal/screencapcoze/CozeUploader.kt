package com.personal.screencapcoze

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import android.util.Base64

/**
 * Coze Workflow API 上传器
 *
 * 两个工作流：
 *  1) 截图上传工作流（WORKFLOW_ID）：把截图 base64 上传，返回 title 变量。
 *  2) 笔记更新工作流（NOTE_WORKFLOW_ID）：在第一个工作流返回 title 之后调用，
 *     把用户输入的备注写入对应记录，输入参数为 title / email / input（备注）。
 *
 * API: POST https://api.coze.cn/v1/workflow/stream_run
 */
class CozeUploader(
    private val accessToken: String,
    private val user: String = ""
) {

    companion object {
        private const val TAG = "CozeUploader"

        // 硬编码的 Coze API 配置
        private const val API_URL = "https://api.coze.cn"
        private const val WORKFLOW_ID = "7656458858995892264"          // 截图上传工作流
        private const val NOTE_WORKFLOW_ID = "7692677928979185699"     // 笔记更新工作流
        private const val APP_ID = "7635532712145076243"

        // 若笔记更新工作流属于另一个 Bot，请把它对应的 app_id 填到这里
        private const val NOTE_WORKFLOW_APP_ID = APP_ID

        private const val ENDPOINT = "/v1/workflow/stream_run"
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
        .writeTimeout(120, java.util.concurrent.TimeUnit.SECONDS)  // base64 图片可能较大
        .readTimeout(120, java.util.concurrent.TimeUnit.SECONDS)   // workflow 执行可能较慢
        .build()

    data class UploadResult(
        val isSuccess: Boolean,
        val responseText: String? = null,
        val title: String? = null,
        val errorMessage: String? = null
    )

    /**
     * 第一个工作流：上传截图。
     *
     * 返回 UploadResult，其中 title 为工作流输出的标题变量，
     * 供后续笔记更新工作流使用。
     */
    suspend fun uploadImage(jpegBytes: ByteArray): UploadResult = withContext(Dispatchers.IO) {
        try {
            // Step 1: 转为 base64
            Log.d(TAG, "Converting ${jpegBytes.size} bytes JPEG to base64...")
            val base64Image = Base64.encodeToString(jpegBytes, Base64.NO_WRAP)
            val screenshotParam = "data:image/jpeg;base64,$base64Image"

            // user 同时放在顶层（API 要求）和 parameters 里（workflow 参数要求）
            val jsonBody = """{"workflow_id":"$WORKFLOW_ID","app_id":"$APP_ID","user":"$user","parameters":{"screenshot":"$screenshotParam","user":"$user"}}"""

            Log.d(TAG, "Calling upload workflow: ${API_URL}$ENDPOINT")
            val requestBody = jsonBody.toRequestBody("application/json".toMediaType())
            val request = Request.Builder()
                .url("${API_URL}$ENDPOINT")
                .addHeader("Authorization", "Bearer $accessToken")
                .addHeader("Content-Type", "application/json")
                .post(requestBody)
                .build()

            val response = client.newCall(request).execute()
            val body = response.body?.string()
            Log.d(TAG, "Response code: ${response.code}")

            if (!response.isSuccessful || body == null) {
                val errorMsg = parseErrorMessage(body) ?: "HTTP ${response.code}"
                return@withContext UploadResult(false, errorMessage = errorMsg)
            }

            val resultText = parseSSEResponse(body)
            val title = extractTitle(body)
            Log.d(TAG, "Upload result; title=$title")

            UploadResult(
                isSuccess = true,
                responseText = resultText ?: body,
                title = title
            )
        } catch (e: Exception) {
            Log.e(TAG, "Upload exception", e)
            UploadResult(false, errorMessage = e.message ?: "上传异常")
        }
    }

    /**
     * 第二个工作流：笔记更新。
     *
     * 在截图上传工作流返回 title 之后调用，将用户输入的备注写入对应记录。
     * 输入参数：title（记录标题）、email（用户邮箱）、input（备注文本）。
     *
     * 注意：若 note 为空，调用方不应调用本方法（即不执行第二个工作流）。
     */
    suspend fun updateNote(title: String, email: String, note: String): UploadResult = withContext(Dispatchers.IO) {
        try {
            val eTitle = jsonEscape(title)
            val eEmail = jsonEscape(email)
            val eNote = jsonEscape(note)
            val jsonBody = """{"workflow_id":"$NOTE_WORKFLOW_ID","app_id":"$NOTE_WORKFLOW_APP_ID","user":"$email","parameters":{"title":"$eTitle","email":"$eEmail","input":"$eNote"}}"""

            Log.d(TAG, "Calling note workflow: ${API_URL}$ENDPOINT (title=$title)")
            val requestBody = jsonBody.toRequestBody("application/json".toMediaType())
            val request = Request.Builder()
                .url("${API_URL}$ENDPOINT")
                .addHeader("Authorization", "Bearer $accessToken")
                .addHeader("Content-Type", "application/json")
                .post(requestBody)
                .build()

            val response = client.newCall(request).execute()
            val body = response.body?.string()
            Log.d(TAG, "Note workflow response code: ${response.code}")

            if (!response.isSuccessful || body == null) {
                val errorMsg = parseErrorMessage(body) ?: "HTTP ${response.code}"
                return@withContext UploadResult(false, errorMessage = errorMsg)
            }

            val resultText = parseSSEResponse(body)
            UploadResult(isSuccess = true, responseText = resultText ?: body)
        } catch (e: Exception) {
            Log.e(TAG, "Update note exception", e)
            UploadResult(false, errorMessage = e.message ?: "备注保存异常")
        }
    }

    /**
     * 解析 SSE (Server-Sent Events) 格式的响应
     *
     * SSE 格式示例：
     * id: 1
     * event: message
     * data: {"data":"...","content":"..."}
     *
     * 提取所有 data: 行的内容，拼接返回
     */
    private fun parseSSEResponse(rawBody: String): String? {
        val lines = rawBody.split("\n")
        val dataLines = mutableListOf<String>()

        for (line in lines) {
            val trimmed = line.trim()
            if (trimmed.startsWith("data:")) {
                val data = trimmed.removePrefix("data:").trim()
                if (data.isNotEmpty() && data != "[DONE]") {
                    dataLines.add(data)
                }
            }
        }

        return if (dataLines.isNotEmpty()) {
            dataLines.joinToString("\n")
        } else {
            null
        }
    }

    /**
     * 从 workflow/stream_run 的 SSE 响应中提取 title 变量。
     *
     * 响应形如：data: {"code":0,"data":"{\"title\":\"...\",\"content\":\"...\"}",...}
     * 先取出外层 data 字段（一个被转义的 JSON 字符串），再从中解析 title。
     */
    private fun extractTitle(rawBody: String): String? {
        val sse = parseSSEResponse(rawBody) ?: return null

        // 优先从外层 data 字段内解析（其内容是被转义的 JSON）
        val dataField = Regex("\"data\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"").find(sse)
        val jsonStr = if (dataField != null) {
            unescapeJsonString(dataField.groupValues[1])
        } else {
            sse
        }

        val titleMatch = Regex("\"title\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"").find(jsonStr)
        return titleMatch?.groupValues?.get(1)?.let { unescapeJsonString(it) }
    }

    /**
     * 把被转义的 JSON 字符串还原（\" -> "，\\n -> 换行 等）。
     * 用 \u0000 暂存真实的反斜杠，避免与转义序列混淆。
     */
    private fun unescapeJsonString(s: String): String {
        return s.replace("\\\\", "\u0000")
            .replace("\\\"", "\"")
            .replace("\\n", "\n")
            .replace("\\r", "\r")
            .replace("\\t", "\t")
            .replace("\\/", "/")
            .replace("\u0000", "\\")
    }

    /**
     * 从错误响应中提取错误消息
     *
     * Coze 错误响应格式通常为: {"code":非零,"msg":"错误信息","data":null}
     */
    private fun parseErrorMessage(body: String?): String? {
        if (body.isNullOrEmpty()) return null
        val msgPattern = Regex("\"msg\"\\s*:\\s*\"([^\"]+)\"")
        val msgMatch = msgPattern.find(body)
        if (msgMatch != null) {
            return msgMatch.groupValues[1]
        }
        return null
    }

    /**
     * 对字符串做 JSON 字符串转义，防止备注中的引号、反斜杠、换行等破坏 JSON 结构。
     */
    private fun jsonEscape(value: String): String {
        val sb = StringBuilder(value.length)
        for (c in value) {
            when (c) {
                '"' -> sb.append("\\\"")
                '\\' -> sb.append("\\\\")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                else -> sb.append(c)
            }
        }
        return sb.toString()
    }
}
