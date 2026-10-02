package com.personal.screencapcoze

import android.app.PendingIntent
import android.content.Intent
import android.graphics.drawable.Icon
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import android.util.Log

/**
 * Quick Settings Tile - 下拉控制中心的截图上传按钮
 *
 * 点击后触发截图流程：
 * 1. 启动 MainActivity 获取 MediaProjection 授权
 * 2. 授权成功后自动启动 ScreenCaptureService 截图
 * 3. 截图完成后自动上传到 Coze API
 */
class ScreenCapTileService : TileService() {

    companion object {
        private const val TAG = "ScreenCapTile"
        const val ACTION_FROM_TILE = "com.personal.screencapcoze.FROM_TILE"
    }

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "TileService created")
    }

    override fun onStartListening() {
        super.onStartListening()
        // 保持 active 状态，让 Tile 在控制中心始终高亮显示，像普通按钮。
        updateTileState(Tile.STATE_ACTIVE)
    }

    override fun onStopListening() {
        super.onStopListening()
    }

    override fun onClick() {
        super.onClick()

        Log.d(TAG, "Tile clicked - starting capture flow")

        // 使用 startActivityAndCollapse 启动 Activity 并折叠控制面板。
        // 这是 TileService 推荐的方式，比直接 startActivity 更可靠。
        val intent = Intent(this, MainActivity::class.java).apply {
            action = ACTION_FROM_TILE
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            val pendingIntent = PendingIntent.getActivity(
                this, 0, intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            startActivityAndCollapse(pendingIntent)
        } else {
            @Suppress("DEPRECATION")
            startActivityAndCollapse(intent)
        }
    }

    private fun updateTileState(state: Int) {
        val tile = qsTile ?: return
        tile.state = state
        tile.icon = Icon.createWithResource(this, R.drawable.ic_braces)
        tile.label = getString(R.string.tile_label)
        tile.subtitle = "点击截图"
        tile.updateTile()
    }

    /** 由 ScreenCaptureService 调用，截图完成后恢复 tile 状态 */
    fun resetTile() {
        updateTileState(Tile.STATE_INACTIVE)
    }
}
