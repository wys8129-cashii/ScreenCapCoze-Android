package com.personal.screencapcoze

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.util.Log
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * 应用内更新（OTA）
 *
 * 自建 App 无 Google Play，这里通过 GitHub Releases 作为更新通道：
 *   1. 启动时请求仓库的「最新 Release」信息（api.github.com）
 *   2. 解析 tag_name（版本名）/ body（更新说明）/ assets 里的 .apk 下载地址
 *   3. 与本地已安装版本比对，若服务器更新则提示用户
 *   4. 下载 APK 到应用私有目录，通过 FileProvider 交给系统安装器
 *   5. 覆盖安装（同包名 + 同签名 + 更高 versionCode）→ 应用数据自动保留
 *
 * ★ 使用前请把 GITHUB_OWNER / GITHUB_REPO 改成你自己的仓库。
 *   用 GitHub Releases 发布：把 app-release.apk 作为 Release 附件上传，
 *   并把标签命名为版本名（如 v1.6）。仓库需为 public（否则下载需要 token）。
 */
object Updater {

    private const val TAG = "Updater"

    // ======== 更新源配置（改成你的仓库）========
    const val GITHUB_OWNER = "wys8129-cashii"
    const val GITHUB_REPO = "ScreenCapCoze-Android"

    /**
     * 可选：GitHub 个人访问令牌（仅当仓库为 private 时需要，public 留空即可）。
     * 注意：写入 APK 里存在泄露风险，个人自用可接受；更推荐仓库设为 public。
     */
    private const val GITHUB_TOKEN = ""

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    /** 服务器上最新版本的信息 */
    data class UpdateInfo(
        val versionName: String,
        val versionCode: Int,
        val notes: String,
        val apkUrl: String,
        val apkName: String
    )

    /** 本地已安装版本号 / 版本名 */
    fun currentVersionCode(context: Context): Int {
        val pi = context.packageManager.getPackageInfo(context.packageName, 0)
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            pi.longVersionCode.toInt()
        } else {
            @Suppress("DEPRECATION") pi.versionCode
        }
    }

    fun currentVersionName(context: Context): String {
        val pi = context.packageManager.getPackageInfo(context.packageName, 0)
        return pi.versionName ?: "0"
    }

    /**
     * 检查更新。
     * @return 有新版本时返回 [UpdateInfo]；已是最新或检查失败返回 null。
     */
    suspend fun checkForUpdate(context: Context): UpdateInfo? = withContext(Dispatchers.IO) {
        try {
            val url = "https://api.github.com/repos/$GITHUB_OWNER/$GITHUB_REPO/releases/latest"
            val builder = Request.Builder()
                .url(url)
                .header("Accept", "application/vnd.github+json")
                .header("User-Agent", "ScreenCapCoze-Updater")
            if (GITHUB_TOKEN.isNotEmpty()) {
                builder.header("Authorization", "Bearer $GITHUB_TOKEN")
            }

            client.newCall(builder.build()).execute().use { resp ->
                if (!resp.isSuccessful) {
                    Log.w(TAG, "检查更新失败: HTTP ${resp.code}")
                    return@withContext null
                }
                val body = resp.body?.string() ?: return@withContext null
                val json = JSONObject(body)

                val tag = json.optString("tag_name").removePrefix("v").trim()
                val notes = json.optString("body", "")
                val assets = json.optJSONArray("assets")

                var apkUrl: String? = null
                var apkName: String? = null
                var remoteVersionCode = -1
                if (assets != null) {
                    for (i in 0 until assets.length()) {
                        val a = assets.getJSONObject(i)
                        val name = a.optString("name")
                        val dl = a.optString("browser_download_url")
                        when {
                            name.endsWith(".apk", ignoreCase = true) -> {
                                apkUrl = dl
                                apkName = name
                            }
                            name.equals("version.json", ignoreCase = true) -> {
                                remoteVersionCode = fetchVersionCode(dl)
                            }
                        }
                    }
                }

                if (apkUrl == null) {
                    Log.w(TAG, "最新 Release 中没有找到 .apk 资源")
                    return@withContext null
                }

                val info = UpdateInfo(
                    versionName = tag.ifEmpty { "unknown" },
                    versionCode = remoteVersionCode,
                    notes = notes,
                    apkUrl = apkUrl,
                    apkName = apkName ?: "app-release.apk"
                )

                if (isNewer(context, info)) info else null
            }
        } catch (e: Exception) {
            Log.w(TAG, "检查更新异常: ${e.message}")
            null
        }
    }

    /**
     * 判断服务器版本是否比本地新。
     * 优先用 version.json 里的 versionCode；没有则回退到版本名逐段比较。
     */
    private fun isNewer(context: Context, info: UpdateInfo): Boolean {
        if (info.versionCode > 0) {
            return info.versionCode > currentVersionCode(context)
        }
        return compareVersion(info.versionName, currentVersionName(context)) > 0
    }

    private fun fetchVersionCode(url: String): Int {
        return try {
            val req = Request.Builder().url(url).header("User-Agent", "ScreenCapCoze-Updater").build()
            client.newCall(req).execute().use { r ->
                if (!r.isSuccessful) return -1
                val j = JSONObject(r.body?.string() ?: return -1)
                j.optInt("versionCode", -1)
            }
        } catch (e: Exception) {
            -1
        }
    }

    private fun compareVersion(a: String, b: String): Int {
        val pa = a.split(".").mapNotNull { it.toIntOrNull() }
        val pb = b.split(".").mapNotNull { it.toIntOrNull() }
        val n = maxOf(pa.size, pb.size)
        for (i in 0 until n) {
            val x = pa.getOrElse(i) { 0 }
            val y = pb.getOrElse(i) { 0 }
            if (x != y) return x - y
        }
        return 0
    }

    /**
     * 下载 APK 到应用私有目录。
     * @param onProgress 进度回调（0-100）
     * @return 下载好的文件；失败返回 null。
     */
    suspend fun downloadApk(
        context: Context,
        info: UpdateInfo,
        onProgress: (Int) -> Unit
    ): File? = withContext(Dispatchers.IO) {
        try {
            val dir = File(context.getExternalFilesDir(null), "updates").apply { mkdirs() }
            val out = File(dir, info.apkName)
            // 上次可能残留，先删除
            if (out.exists()) out.delete()

            val req = Request.Builder().url(info.apkUrl)
                .header("User-Agent", "ScreenCapCoze-Updater")
                .build()
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) {
                    Log.w(TAG, "下载 APK 失败: HTTP ${resp.code}")
                    return@withContext null
                }
                val body = resp.body ?: return@withContext null
                val total = body.contentLength()
                body.byteStream().use { input ->
                    out.outputStream().use { output ->
                        val buf = ByteArray(8192)
                        var read: Int
                        var sum = 0L
                        while (input.read(buf).also { read = it } != -1) {
                            output.write(buf, 0, read)
                            sum += read
                            if (total > 0) onProgress((sum * 100 / total).toInt().coerceIn(0, 100))
                        }
                    }
                }
            }
            out
        } catch (e: Exception) {
            Log.w(TAG, "下载 APK 异常: ${e.message}")
            null
        }
    }

    /**
     * 触发系统安装。
     * 若未授予「安装未知应用」权限，会先跳转到对应设置页。
     * @return true 表示已弹出安装界面或权限设置页；false 表示失败。
     */
    fun installApk(context: Context, apkFile: File): Boolean {
        // Android 8.0+ 需要「安装未知应用」权限
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            if (!context.packageManager.canRequestPackageInstalls()) {
                val intent = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES).apply {
                    data = Uri.parse("package:${context.packageName}")
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(intent)
                return true
            }
        }

        return try {
            val uri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                apkFile
            )
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            true
        } catch (e: Exception) {
            Log.e(TAG, "触发安装失败", e)
            false
        }
    }
}
