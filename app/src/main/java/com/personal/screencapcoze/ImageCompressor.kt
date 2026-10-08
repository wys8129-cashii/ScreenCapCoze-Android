package com.personal.screencapcoze

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.ByteArrayOutputStream

/**
 * 截图压缩工具：把 Bitmap 压缩到 <= [MAX_BYTES]（默认 1MB），
 * 优先降 JPEG 质量以尽量保留清晰度，质量降到底仍超限时才等比缩小分辨率。
 *
 * 策略（质量优先）：
 *   1. 从 [START_QUALITY]=85 起，每次 -6，直到体积达标或降到 [MIN_QUALITY]=55；
 *   2. 若质量已到底仍超 [MAX_BYTES]，则把长边缩到 [MAX_LONG_EDGE]=2000px 以内再循环压质量；
 *   3. 已缩到分辨率下限仍超，则保底返回当前最小体积（不应发生）。
 *
 * 文字类截图在 1080p 屏上通常仅靠降质量即可压到 1MB 内，基本不缩分辨率、不糊。
 */
object ImageCompressor {

    const val MAX_BYTES = 1_000_000L // 1MB 上限
    private const val START_QUALITY = 85
    private const val MIN_QUALITY = 55
    private const val MAX_LONG_EDGE = 2000

    /**
     * 将 Bitmap 压缩为受大小约束的 JPEG 字节。
     * 注意：若内部对 Bitmap 做了缩放，缩放产生的中间 Bitmap 会被回收；
     * 传入的原始 [bitmap] 不会被本方法回收，调用方负责。
     */
    fun compress(bitmap: Bitmap, maxBytes: Long = MAX_BYTES): ByteArray {
        var current: Bitmap = bitmap
        var quality = START_QUALITY
        var result: ByteArray? = null
        try {
            while (true) {
                val out = ByteArrayOutputStream()
                current.compress(Bitmap.CompressFormat.JPEG, quality, out)
                val bytes = out.toByteArray()

                if (bytes.size <= maxBytes) {
                    result = bytes
                    break
                }

                // 还能降质量：继续压
                if (quality > MIN_QUALITY) {
                    quality -= 6
                    continue
                }

                // 质量已到底，尝试缩小分辨率
                val longEdge = maxOf(current.width, current.height)
                if (longEdge <= MAX_LONG_EDGE) {
                    result = bytes // 已达分辨率下限，保底返回
                    break
                }
                val scale = (MAX_LONG_EDGE.toFloat() / longEdge).coerceAtMost(0.9f)
                val w = (current.width * scale).toInt().coerceAtLeast(1)
                val h = (current.height * scale).toInt().coerceAtLeast(1)
                val scaled = Bitmap.createScaledBitmap(current, w, h, true)
                if (current !== bitmap) current.recycle()
                current = scaled
                quality = START_QUALITY
            }
        } finally {
            // 仅回收内部缩放产生的中间 Bitmap，原始 bitmap 留给调用方
            if (current !== bitmap) current.recycle()
        }
        return result!!
    }

    /** 从字节解码为 Bitmap（读取文件 / 分享 uri 时用） */
    fun decode(bytes: ByteArray): Bitmap? = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
}
