package com.personal.screencapcoze

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView

/**
 * 截图相册：展示所有通过 App 截图保存的图片（新 → 旧）。
 *
 * 数据来自 ScreenshotStore（系统相册 Pictures/ScreenCapCoze 或应用专属目录）。
 */
class GalleryActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_gallery)

        findViewById<View>(R.id.btnGalleryBack).setOnClickListener { finish() }

        val rv = findViewById<RecyclerView>(R.id.galleryGrid)
        rv.layoutManager = GridLayoutManager(this, 3)
        rv.adapter = GalleryAdapter(loadItems())

        val tvCount = findViewById<TextView>(R.id.tvGalleryCount)
        tvCount.text = getString(R.string.gallery_count, rv.adapter!!.itemCount)
    }

    private fun loadItems(): List<Pair<Uri, String>> = ScreenshotStore.listAll(this)

    private inner class GalleryAdapter(
        private val items: List<Pair<Uri, String>>
    ) : RecyclerView.Adapter<GalleryAdapter.Holder>() {

        val uris: List<Pair<Uri, String>> get() = items

        inner class Holder(view: View) : RecyclerView.ViewHolder(view) {
            val iv: ImageView = view.findViewById(R.id.itemImage)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
            val v = layoutInflater.inflate(R.layout.item_gallery, parent, false)
            return Holder(v)
        }

        override fun getItemCount(): Int = items.size

        override fun onBindViewHolder(holder: Holder, position: Int) {
            val (uri, name) = items[position]
            holder.iv.tag = null
            // 后台解码缩略图，避免 OOM
            Thread {
                val bmp = decodeThumb(uri, 512)
                if (bmp != null) {
                    runOnUiThread {
                        holder.iv.tag = uri
                        holder.iv.setImageBitmap(bmp)
                    }
                }
            }.start()
            holder.iv.setOnClickListener {
                ImageViewerActivity.start(
                    this@GalleryActivity, items, holder.bindingAdapterPosition
                )
            }
        }

        private fun decodeThumb(uri: Uri, target: Int): Bitmap? {
            return try {
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
                var sample = 1
                while (bounds.outWidth / sample > target || bounds.outHeight / sample > target) {
                    sample *= 2
                }
                val opts = BitmapFactory.Options().apply { inSampleSize = sample }
                contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) }
            } catch (e: Exception) {
                null
            }
        }
    }
}
