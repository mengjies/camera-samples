package com.android.example.cameraxbasic

import android.app.Application
import android.util.Log
import androidx.camera.camera2.Camera2Config
import androidx.camera.core.CameraXConfig

/**
 * 将CameraX日志记录级别设置为Log.ERROR以避免过多的logcat消息。
 * 提到https://developer.android.com/reference/androidx/camera/core/CameraXConfig.Builder#setMinimumLoggingLevel（int）以获取详细信息。
 *
 */
class MainApplication : Application(), CameraXConfig.Provider {
    //CameraXConfig 用于向CameraX添加实现和用户特定行为的配置。
    override fun getCameraXConfig(): CameraXConfig {
        return CameraXConfig.Builder.fromConfig(Camera2Config.defaultConfig())
            .setMinimumLoggingLevel(Log.ERROR).build()
    }

}