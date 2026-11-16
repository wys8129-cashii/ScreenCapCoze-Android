package com.personal.screencapcoze

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 截图本地存储
 *
 * 每次通过 App 截图上传时，同时把图片保存一份到本地：
 *   - Android 10+：写入系统相册 Pictures/ScreenCapCoze（无需权限，系统图库可见）
 *   - Android 9 及以下：写入应用专属目录 Android/data/.../Pictures/ScreenCapCoze
 *
 * 「截图相册」页面按同样规则读取。
 */
object ScreenshotStore {

    /** 系统相册中的相对目录（Android 10+） */
    const val GALLERY_RELATIVE_PATH = "Pictures/ScreenCapCoze"

    /** 应用专属目录名（Android 9 及以下） */
    const val DIR_NAME = "ScreenCapCoze"

    private val fileNameFormatter = SimpleDateFormat("'SIMG_'yyyyMMdd_HHmmss_SSS", Locale.CHINA)

    /** 生成文件名：SIMG_20261116_101530_123.jpg */
    fun newFileName(): String {
        val base = fileNameFormatter.format(Date())
        return "$base.jpg"
    }

    /**
     * 保存 JPEG 到本地，返回 content Uri（Android 10+）或文件 Uri（旧版本）。
     * 保存失败返回 null（不影响上传主流程）。
     */
    fun save(context: Context, jpegBytes: ByteArray): Uri? {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                saveToMediaStore(context, jpegBytes)
            } else {
                saveToAppDir(context, jpegBytes)
            }
        } catch (e: Exception) {
            null
        }
    }

    private fun saveToMediaStore(context: Context, jpegBytes: ByteArray): Uri? {
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, newFileName())
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            put(MediaStore.Images.Media.RELATIVE_PATH, GALLERY_RELATIVE_PATH)
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }
        val resolver = context.contentResolver
        val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
            ?: return null
        resolver.openOutputStream(uri)?.use { it.write(jpegBytes) }
        values.clear()
        values.put(MediaStore.Images.Media.IS_PENDING, 0)
        resolver.update(uri, values, null, null)
        return uri
    }

    @Suppress("DEPRECATION")
    private fun saveToAppDir(context: Context, jpegBytes: ByteArray): Uri? {
        val dir = File(
            context.getExternalFilesDir(Environment.DIRECTORY_PICTURES),
            DIR_NAME
        )
        if (!dir.exists()) dir.mkdirs()
        val file = File(dir, newFileName())
        file.writeBytes(jpegBytes)
        return Uri.fromFile(file)
    }

    /** 截图数量（相册页显示用） */
    fun count(context: Context): Int = listAll(context).size

    /**
     * 列出所有通过 App 保存的截图（新 → 旧）。
     * 统一返回 [uri, fileName] 对。
     */
    fun listAll(context: Context): List<Pair<Uri, String>> {
        val result = mutableListOf<Pair<Uri, String>>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val projection = arrayOf(
                MediaStore.Images.Media._ID,
                MediaStore.Images.Media.DISPLAY_NAME
            )
            val selection = "${MediaStore.Images.Media.RELATIVE_PATH} LIKE ?"
            val args = arrayOf("$GALLERY_RELATIVE_PATH/%")
            context.contentResolver.query(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                projection, selection, args,
                "${MediaStore.Images.Media.DATE_ADDED} DESC"
            )?.use { c ->
                val idCol = c.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
                val nameCol = c.getColumnIndexOrThrow(MediaStore.Images.Media.DISPLAY_NAME)
                while (c.moveToNext()) {
                    val id = c.getLong(idCol)
                    val name = c.getString(nameCol)
                    val uri = android.content.ContentUris.withAppendedId(
                        MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id
                    )
                    result.add(uri to name)
                }
            }
        } else {
            val dir = File(
                context.getExternalFilesDir(Environment.DIRECTORY_PICTURES),
                DIR_NAME
            )
            dir.listFiles { f -> f.isFile && f.name.endsWith(".jpg") }
                ?.sortedByDescending { it.lastModified() }
                ?.forEach { result.add(Uri.fromFile(it) to it.name) }
        }
        return result
    }
}
