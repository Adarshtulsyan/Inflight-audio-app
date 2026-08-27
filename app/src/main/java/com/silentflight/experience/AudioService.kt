package com.silentflight.experience

import android.app.*
import android.content.Intent
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.net.Uri
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat

class AudioService : Service() {

    private var mediaPlayer: MediaPlayer? = null
    private val binder = AudioBinder()
    
    private val CHANNEL_ID = "AudioPlaybackChannel"
    private val NOTIFICATION_ID = 1

    inner class AudioBinder : Binder() {
        fun getService(): AudioService = this@AudioService
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action
        when (action) {
            "START_PLAYBACK" -> {
                val position = intent.getIntExtra("POSITION", 0)
                startPlayback(position)
            }
            "STOP_PLAYBACK" -> {
                stopPlayback()
            }
        }
        return START_NOT_STICKY
    }

    private fun startPlayback(position: Int) {
        try {
            mediaPlayer?.release()
            mediaPlayer = MediaPlayer().apply {
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .build()
                )
                
                // Track resource and status
                val videoUri = Uri.parse("android.resource://$packageName/${R.raw.dadi_audio}")
                Log.d("AudioService", "Initializing MediaPlayer with resource: $videoUri")
                
                setDataSource(this@AudioService, videoUri)
                
                setOnPreparedListener { mp ->
                    Log.d("AudioService", "MediaPlayer Prepared. Seeking to $position ms")
                    mp.seekTo(position)
                    mp.start()
                    
                    // Show notification for foreground status
                    try {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                            startForeground(NOTIFICATION_ID, createNotification(), android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
                        } else {
                            startForeground(NOTIFICATION_ID, createNotification())
                        }
                    } catch (e: Exception) {
                        Log.e("AudioService", "Failed to start foreground", e)
                    }
                }
                
                setOnErrorListener { mp, what, extra ->
                    Log.e("AudioService", "MediaPlayer Error: what=$what, extra=$extra")
                    // Handle LFS pointer issue if it occurs
                    if (what == MediaPlayer.MEDIA_ERROR_UNKNOWN && extra == -2147483648) {
                        Log.e("AudioService", "CRITICAL: Audio file unreadable. Likely a Git LFS pointer.")
                    }
                    stopPlayback()
                    true
                }
                
                setOnCompletionListener {
                    Log.d("AudioService", "Playback Complete")
                    stopPlayback()
                    sendBroadcast(Intent("PLAYBACK_COMPLETE"))
                }
                
                prepareAsync()
            }
        } catch (e: Exception) {
            Log.e("AudioService", "Exception in startPlayback", e)
        }
    }

    private fun stopPlayback() {
        Log.d("AudioService", "Stopping Playback")
        mediaPlayer?.stop()
        mediaPlayer?.release()
        mediaPlayer = null
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
        sendBroadcast(Intent("PLAYBACK_STOPPED"))
    }

    fun isPlaying(): Boolean = mediaPlayer?.isPlaying ?: false
    
    fun getDuration(): Int = mediaPlayer?.duration ?: 0
    
    fun getCurrentPosition(): Int = mediaPlayer?.currentPosition ?: 0

    private fun createNotification(): Notification {
        val stopIntent = Intent(this, AudioService::class.java).apply {
            action = "STOP_PLAYBACK"
        }
        val stopPendingIntent = PendingIntent.getService(this, 0, stopIntent, PendingIntent.FLAG_IMMUTABLE)

        val mainIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val mainPendingIntent = PendingIntent.getActivity(this, 0, mainIntent, PendingIntent.FLAG_IMMUTABLE)

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Inflight Journey")
            .setContentText("Enjoying Rani Sati Dadi Mangal Path")
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentIntent(mainPendingIntent)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Stop", stopPendingIntent)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Audio Playback",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Controls for background audio playback"
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }
}
