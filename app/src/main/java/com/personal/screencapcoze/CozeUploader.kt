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
 * 调用 Coze 的 workflow/stream_run 接口，
 * 将截图以 base64 编码的 JPEG 图片作为参数发送。
 *
 * API: POST https://api.coze.cn/v1/workflow/stream_run
 *
 * workflow_id 和 app_id 已硬编码，用户只需填写 Access Token。
 */
class CozeUploader(
    private val accessToken: String,
    private val user: String = ""
) {

    companion object {
        private const val TAG = "CozeUploader"

        // 硬编码的 Coze API 配置
        private const val API_URL = "https://api.coze.cn"
        private const val WORKFLOW_ID = "7656458858995892264"
        private const val APP_ID = "7635532712145076243"

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
        val errorMessage: String? = null
    )

    /**
     * 将截图 JPEG 数据通过 Coze Workflow API 上传
     *
     * 步骤：
     * 1. 将 JPEG bytes 转为 base64 字符串，加上 data:image/jpeg;base64, 前缀
     * 2. 调用 POST /v1/workflow/stream_run
     * 3. 读取流式响应（SSE 格式）
     */
    suspend fun uploadImage(jpegBytes: ByteArray, notes: String = ""): UploadResult = withContext(Dispatchers.IO) {
        try {
            // Step 1: 转为 base64
            Log.d(TAG, "Converting ${jpegBytes.size} bytes JPEG to base64...")
            val base64Image = Base64.encodeToString(jpegBytes, Base64.NO_WRAP)
            val screenshotParam = "data:image/jpeg;base64,$base64Image"
            Log.d(TAG, "Base64 string length: ${screenshotParam.length}")

            // Step 2: 构建 JSON 请求体
            // 手动构建 JSON，避免引入额外 JSON 库
            // 注意：base64 字符串中不含特殊字符，可以直接拼接到 JSON 字符串中
            // user 同时放在顶层（API 要求）和 parameters 里（workflow 参数要求）
            // notes 是用户自由输入的备注，需先转义避免破坏 JSON 结构（引号/换行/反斜杠）
            val escapedNotes = jsonEscape(notes)
            val jsonBody = """{"workflow_id":"$WORKFLOW_ID","app_id":"$APP_ID","user":"$user","parameters":{"screenshot":"$screenshotParam","user":"$user","notes":"$escapedNotes"}}"""

            Log.d(TAG, "Calling workflow API: ${API_URL}$ENDPOINT")
            Log.d(TAG, "Request body size: ${jsonBody.length} chars")

            val requestBody = jsonBody.toRequestBody("application/json".toMediaType())

            val request = Request.Builder()
                .url("${API_URL}$ENDPOINT")
                .addHeader("Authorization", "Bearer $accessToken")
                .addHeader("Content-Type", "application/json")
                .post(requestBody)
                .build()

            // Step 3: 执行请求
            val response = client.newCall(request).execute()
            val body = response.body?.string()
            Log.d(TAG, "Response code: ${response.code}")
            Log.d(TAG, "Response body: ${body?.take(2000)}")

            if (!response.isSuccessful || body == null) {
                val errorMsg = parseErrorMessage(body) ?: "HTTP ${response.code}"
                return@withContext UploadResult(false, errorMessage = errorMsg)
            }

            // Step 4: 解析 SSE 流式响应
            // workflow/stream_run 返回 SSE 格式，每行以 data: 开头
            // 我们提取最终的 data 内容
            val resultText = parseSSEResponse(body)
            Log.d(TAG, "Workflow result: ${resultText?.take(500)}")

            UploadResult(
                isSuccess = true,
                responseText = resultText ?: body
            )
        } catch (e: Exception) {
            Log.e(TAG, "Upload exception", e)
            UploadResult(false, errorMessage = e.message ?: "上传异常")
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
     * 从错误响应中提取错误消息
     *
     * Coze 错误响应格式通常为: {"code":非零,"msg":"错误信息","data":null}
     */
    private fun parseErrorMessage(body: String?): String? {
        if (body.isNullOrEmpty()) return null

        // 尝试提取 msg 字段
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
