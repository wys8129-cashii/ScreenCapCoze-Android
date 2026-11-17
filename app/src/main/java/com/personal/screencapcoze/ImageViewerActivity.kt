package com.personal.screencapcoze

import android.app.Activity
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager2.widget.ViewPager2

/**
 * 全屏大图查看器：左右滑动切换，点击图片关闭。
 * 从「截图相册」点击缩略图进入。
 */
class ImageViewerActivity : AppCompatActivity() {

    companion object {
        private const val EXTRA_URIS = "uris"
        private const val EXTRA_NAMES = "names"
        private const val EXTRA_START = "start"

        fun start(activity: Activity, items: List<Pair<Uri, String>>, position: Int) {
            val intent = Intent(activity, ImageViewerActivity::class.java).apply {
                putStringArrayListExtra(EXTRA_URIS, ArrayList(items.map { it.first.toString() }))
                putStringArrayListExtra(EXTRA_NAMES, ArrayList(items.map { it.second }))
                putExtra(EXTRA_START, position)
            }
            activity.startActivity(intent)
        }
    }

    private lateinit var tvFileName: TextView
    private var names: List<String> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_image_viewer)

        val uris = intent.getStringArrayListExtra(EXTRA_URIS) ?: arrayListOf()
        names = intent.getStringArrayListExtra(EXTRA_NAMES) ?: arrayListOf()
        val start = intent.getIntExtra(EXTRA_START, 0).coerceIn(0, (uris.size - 1).coerceAtLeast(0))

        tvFileName = findViewById(R.id.tvFileName)
        val pager = findViewById<ViewPager2>(R.id.viewPager)
        pager.adapter = ViewerAdapter(uris)
        pager.setCurrentItem(start, false)
        pager.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) {
                showName(position)
            }
        })
        showName(start)
    }

    private fun showName(position: Int) {
        if (position in names.indices) {
            tvFileName.text = "${position + 1}/${names.size} · ${names[position]}"
        }
    }

    private inner class ViewerAdapter(
        private val uris: List<String>
    ) : RecyclerView.Adapter<ViewerAdapter.Holder>() {

        inner class Holder(view: View) : RecyclerView.ViewHolder(view) {
            val iv: ImageView = view.findViewById(R.id.ivFull)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
            val v = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_image_viewer, parent, false)
            return Holder(v)
        }

        override fun getItemCount(): Int = uris.size

        override fun onBindViewHolder(holder: Holder, position: Int) {
            holder.iv.setImageDrawable(null)
            holder.iv.setOnClickListener { finish() }
            Thread {
                val bmp = decodeFull(Uri.parse(uris[position]))
                if (bmp != null) {
                    runOnUiThread {
                        if (holder.bindingAdapterPosition == position) {
                            holder.iv.setImageBitmap(bmp)
                        }
                    }
                }
            }.start()
        }

        /** 解码到不超过屏幕尺寸，避免 OOM */
        private fun decodeFull(uri: Uri): Bitmap? {
            return try {
                val metrics = resources.displayMetrics
                val targetW = metrics.widthPixels
                val targetH = metrics.heightPixels
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                contentResolver.openInputStream(uri)?.use {
                    BitmapFactory.decodeStream(it, null, bounds)
                }
                var sample = 1
                while (bounds.outWidth / (sample * 2) >= targetW &&
                    bounds.outHeight / (sample * 2) >= targetH
                ) {
                    sample *= 2
                }
                val opts = BitmapFactory.Options().apply { inSampleSize = sample }
                contentResolver.openInputStream(uri)?.use {
                    BitmapFactory.decodeStream(it, null, opts)
                }
            } catch (e: Exception) {
                null
            }
        }
    }
}
