package com.skyautoplayer.playback

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.util.Log
import com.skyautoplayer.application.PlaybackRuntime

/** Keeps the playback runtime's coroutine scope and process alive while the game is foreground. */
class PlaybackForegroundService : Service() {
    override fun onCreate() {
        super.onCreate()
        Log.i(TAG, "PlaybackForegroundService onCreate：前台播放服务已启动")
        PlaybackRuntime.initialize(this)
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(CHANNEL, "Playback", NotificationManager.IMPORTANCE_LOW))
        startForeground(ID, Notification.Builder(this, CHANNEL)
            .setContentTitle("Sky Auto Player")
            .setContentText("正在后台弹奏")
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setOngoing(true)
            .build())
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY
    override fun onDestroy() {
        Log.i(TAG, "PlaybackForegroundService onDestroy：停止服务作用域")
        PlaybackRuntime.serviceDestroyed()
        super.onDestroy()
    }
    override fun onBind(intent: Intent?): IBinder? = null

    companion object { private const val TAG = "AutoPlay"; private const val CHANNEL = "sky_playback_runtime"; private const val ID = 1003 }
}
