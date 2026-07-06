package com.silentflight.experience

import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.media.MediaPlayer
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.util.TypedValue
import android.view.View
import android.view.animation.AlphaAnimation
import android.view.animation.Animation
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.VideoView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.json.JSONObject
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone

class MainActivity : AppCompatActivity() {

    private lateinit var connectivityManager: ConnectivityManager
    private var videoView: VideoView? = null
    private val handler = Handler(Looper.getMainLooper())
    private val client = OkHttpClient()
    private lateinit var prefs: SharedPreferences

    private var isPlaybackStartedByUser = false
    private var isConfigLoaded = false
    private var earphonesConfirmed = false
    private var audioReady = false
    private var isMediaLoading = false
    private var countdownRunnable: Runnable? = null
    private var progressRunnable: Runnable? = null
    private var playbackRunnable: Runnable? = null
    private var fetchRunnable: Runnable? = null

    private val apiUrl = "https://raw.githubusercontent.com/Adarshtulsyan/Inflight-audio-app/main/config.json"

    @Volatile
    private var currentStartTime: Long = 0L
    private var serverClockOffset: Long = 0L
    private var lastFetchedTimeStr: String = ""

    private val defaultStartTime: Long by lazy {
        System.currentTimeMillis() + 120000 // 2 minutes from now
    }

    private lateinit var welcomeScreen: View
    private lateinit var mainContent: View
    private lateinit var enterCabinBtn: Button
    
    private lateinit var dadiImage: View
    private lateinit var earphoneRow: View
    private lateinit var playbackControls: View
    private lateinit var earphoneText: TextView
    private lateinit var confirmBtn: Button
    private lateinit var startBtn: Button
    private lateinit var stopBtn: Button
    private lateinit var statusText: TextView
    private lateinit var statusBadge: TextView
    private lateinit var deviceTimeText: TextView
    private lateinit var targetTimeText: TextView
    private lateinit var playerLayout: LinearLayout
    private lateinit var progressTrack: View
    private lateinit var progressFill: View
    private lateinit var currentTimeText: TextView
    private lateinit var remainingTimeText: TextView
    private lateinit var downloadPrompt: View

    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            handler.post { updateLiveStatus(true) }
        }
        override fun onLost(network: Network) {
            handler.post { updateLiveStatus(false) }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        connectivityManager = getSystemService(CONNECTIVITY_SERVICE) as ConnectivityManager
        prefs = getSharedPreferences("app_prefs", MODE_PRIVATE)
        
        welcomeScreen    = findViewById(R.id.welcomeScreen)
        mainContent      = findViewById(R.id.mainContent)
        enterCabinBtn    = findViewById(R.id.enterCabinBtn)

        dadiImage        = findViewById(R.id.dadiImage)
        videoView        = findViewById(R.id.videoView)
        earphoneRow      = findViewById(R.id.earphoneRow)
        playbackControls = findViewById(R.id.playbackControls)
        earphoneText     = findViewById(R.id.earphoneText)
        confirmBtn       = findViewById(R.id.confirmEarphonesBtn)
        startBtn         = findViewById(R.id.startBtn)
        stopBtn          = findViewById(R.id.stopBtn)
        statusText       = findViewById(R.id.statusText)
        statusBadge      = findViewById(R.id.statusBadge)
        deviceTimeText   = findViewById(R.id.deviceTimeText)
        targetTimeText   = findViewById(R.id.targetTimeText)
        playerLayout     = findViewById(R.id.playerLayout)
        progressTrack    = findViewById(R.id.progressTrack)
        progressFill     = findViewById(R.id.progressFill)
        currentTimeText  = findViewById(R.id.currentTimeText)
        remainingTimeText = findViewById(R.id.remainingTimeText)
        downloadPrompt   = findViewById(R.id.downloadPrompt)

        if (prefs.contains("start_time")) {
            currentStartTime = prefs.getLong("start_time", 0L)
            isConfigLoaded = true
            statusText.text = getString(R.string.ready)
        } else {
            currentStartTime = defaultStartTime
        }
        
        setupInitialState()
        setupButtons()
        
        setupMediaPlayer()

        // Register Network Listener
        try {
            val networkRequest = NetworkRequest.Builder()
                .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .build()
            connectivityManager.registerNetworkCallback(networkRequest, networkCallback)
        } catch (e: Exception) {
            Log.e("InflightSync", "Network callback registration failed")
        }
        
        // Initial checks - Move to background to avoid blocking main thread
        handler.postDelayed({
            val isOnline = try { isNetworkAvailable() } catch (e: Exception) { false }
            updateLiveStatus(isOnline)
            startConfigPolling()
        }, 500)
    }

    private fun setupInitialState() {
        welcomeScreen.visibility = View.VISIBLE
        mainContent.visibility = View.GONE
        
        earphoneRow.visibility = View.VISIBLE
        playbackControls.visibility = View.GONE
        playerLayout.visibility = View.GONE
        updateHeadsetStatus()
    }

    private fun updateLiveStatus(online: Boolean) {
        if (online) {
            statusBadge.text = "LIVE"
            statusBadge.setTextColor(ContextCompat.getColor(this, R.color.gold))
            statusBadge.alpha = 1.0f
        } else {
            statusBadge.text = "OFFLINE"
            statusBadge.setTextColor(ContextCompat.getColor(this, R.color.text_secondary))
            statusBadge.alpha = 0.5f
        }
    }

    private fun isNetworkAvailable(): Boolean {
        val activeNetwork = connectivityManager.activeNetwork ?: return false
        val capabilities = connectivityManager.getNetworkCapabilities(activeNetwork) ?: return false
        return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    private fun updateHeadsetStatus() {
        confirmBtn.isEnabled = true
        earphoneText.text = getString(R.string.earphone_prompt)
        earphoneText.setTextColor(ContextCompat.getColor(this, R.color.text_secondary))
        earphoneText.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
    }

    private fun isHeadsetConnected(): Boolean {
        return true
    }

    private fun setupMediaPlayer() {
        try {
            videoView?.setOnPreparedListener { mp ->
                Log.d("InflightSync", "MediaPlayer prepared. Duration: ${mp.duration}")
                audioReady = true
                isMediaLoading = false
                statusText.text = getString(R.string.ready)
                mp.isLooping = false
                if (earphonesConfirmed) {
                    startBtn.isEnabled = true
                }
            }
            
            videoView?.setOnCompletionListener { 
                Log.d("InflightSync", "Playback completed")
                onPlaybackComplete() 
            }
            
            videoView?.setOnErrorListener { mp, what, extra ->
                Log.e("InflightSync", "MediaPlayer error: $what, extra: $extra")
                audioReady = false
                isMediaLoading = false
                statusText.text = getString(R.string.audio_unavailable)
                downloadPrompt.visibility = View.VISIBLE
                
                // Fallback attempt if URI is null or invalid
                if (what == MediaPlayer.MEDIA_ERROR_UNKNOWN) {
                    Log.d("InflightSync", "Retrying media load after error")
                    loadMediaResource()
                }
                true
            }

            videoView?.setOnInfoListener { _, what, extra ->
                Log.d("InflightSync", "MediaPlayer info: $what, extra: $extra")
                false
            }
        } catch (e: Exception) {
            Log.e("InflightSync", "Error in setupMediaPlayer", e)
            audioReady = false
            statusText.text = getString(R.string.audio_unavailable)
        }
    }

    private fun loadMediaResource() {
        if (audioReady || isMediaLoading) return
        try {
            isMediaLoading = true
            // VideoView often needs to be VISIBLE to initialize its surface and trigger onPrepared
            videoView?.visibility = View.VISIBLE
            videoView?.alpha = 0.01f // Keep it effectively invisible but attached with a surface

            val videoUri = Uri.parse("android.resource://$packageName/${R.raw.dadi_audio}")
            Log.d("InflightSync", "Loading media resource: $videoUri")
            videoView?.setVideoURI(videoUri)
        } catch (e: Exception) {
            isMediaLoading = false
            Log.e("InflightSync", "Failed to load media resource", e)
        }
    }

    private fun setupButtons() {
        enterCabinBtn.setOnClickListener {
            Log.d("InflightSync", "Enter Cabin button clicked")
            transitionToMain()
        }

        confirmBtn.setOnClickListener {
            Log.d("InflightSync", "Confirm Headset button clicked. audioReady=$audioReady")
            earphonesConfirmed = true
            earphoneRow.visibility = View.GONE
            playbackControls.visibility = View.VISIBLE
            
            if (audioReady) {
                startBtn.isEnabled = true
            } else {
                startBtn.isEnabled = false
                statusText.text = "Waiting for audio to be ready..."
                // Try to load media again if it hasn't prepared yet
                loadMediaResource()
            }
        }

        startBtn.setOnClickListener {
            isPlaybackStartedByUser = true
            startBtn.isEnabled = false
            stopBtn.isEnabled = true
            schedulePlayback()
        }

        stopBtn.setOnClickListener {
            isPlaybackStartedByUser = false
            stopPlayback()
        }
    }

    private fun transitionToMain() {
        val fadeOut = AlphaAnimation(1f, 0f).apply {
            duration = 800
            setAnimationListener(object : Animation.AnimationListener {
                override fun onAnimationStart(animation: Animation?) {}
                override fun onAnimationRepeat(animation: Animation?) {}
                override fun onAnimationEnd(animation: Animation?) {
                    welcomeScreen.visibility = View.GONE
                    mainContent.visibility = View.VISIBLE
                    
                    // Re-trigger media loading once visibility is set
                    loadMediaResource()

                    val fadeIn = AlphaAnimation(0f, 1f).apply { duration = 800 }
                    mainContent.startAnimation(fadeIn)
                }
            })
        }
        welcomeScreen.startAnimation(fadeOut)
    }

    private fun startConfigPolling() {
        // UI Update Task (Every 1 second)
        val uiTask = object : Runnable {
            override fun run() {
                updateDiagnosticTimes()
                handler.postDelayed(this, 1000)
            }
        }
        handler.post(uiTask)

        // Network Polling Task (Every 15 seconds)
        val networkTask = object : Runnable {
            override fun run() {
                fetchRemoteConfig()
                handler.postDelayed(this, 15000)
            }
        }
        fetchRunnable = networkTask
        handler.post(networkTask)
    }

    private fun getSyncedTime(): Long = System.currentTimeMillis() + serverClockOffset

    private fun updateDiagnosticTimes() {
        val now = getSyncedTime()
        val sdf = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
        val deviceTime = sdf.format(now)
        
        val targetSdf = SimpleDateFormat("MMM d, HH:mm:ss", Locale.getDefault())
        targetSdf.timeZone = TimeZone.getTimeZone("Asia/Kolkata")
        val targetTime = targetSdf.format(currentStartTime)
        
        handler.post {
            deviceTimeText.text = "Device: $deviceTime"
            targetTimeText.text = "Target: $targetTime"
        }
    }

    private fun fetchRemoteConfig() {
        val request = Request.Builder()
            .url("$apiUrl?t=${System.currentTimeMillis()}")
            .cacheControl(okhttp3.CacheControl.FORCE_NETWORK)
            .addHeader("Cache-Control", "no-cache")
            .build()

        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                Log.e("InflightSync", "Network error: ${e.message}")
                handler.post { updateLiveStatus(false) }
            }

            override fun onResponse(call: Call, response: Response) {
                val rawBody = response.body?.string() ?: ""
                if (!response.isSuccessful) {
                    Log.e("InflightSync", "Fetch failed: ${response.code}")
                    handler.post { updateLiveStatus(false) }
                    return
                }

                try {
                    // Sync clock using Date header
                    val serverDateStr = response.header("Date")
                    if (serverDateStr != null) {
                        try {
                            val serverSdf = SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss z", Locale.US)
                            val serverDate = serverSdf.parse(serverDateStr)
                            if (serverDate != null) {
                                serverClockOffset = serverDate.time - System.currentTimeMillis()
                                Log.d("InflightSync", "Clock synced. Offset: ${serverClockOffset}ms")
                            }
                        } catch (e: Exception) {
                            Log.e("InflightSync", "Date header parse error")
                        }
                    }

                    val configJson = JSONObject(rawBody)
                    val timeStr = configJson.getString("startTime")
                    Log.d("InflightSync", "Fetched raw time string: $timeStr")
                    
                    val sdf = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US)
                    sdf.timeZone = TimeZone.getTimeZone("Asia/Kolkata")
                    val date = sdf.parse(timeStr) ?: return
                    val newTime = date.time

                    handler.post {
                        updateLiveStatus(true)
                        isConfigLoaded = true
                        val isFirstFetch = lastFetchedTimeStr.isEmpty()
                        val timeChanged = timeStr != lastFetchedTimeStr
                        val timeDrift = Math.abs(currentStartTime - newTime)

                        if (!isPlaybackStartedByUser) {
                            statusText.text = getString(R.string.ready)
                        }
                        
                        // Re-schedule if time string changed or significant drift (> 1s)
                        if (isFirstFetch || timeChanged || timeDrift > 1000) {
                            Log.d("InflightSync", "Remote Sync Update: $timeStr (Offset: ${serverClockOffset}ms)")
                            lastFetchedTimeStr = timeStr
                            currentStartTime = newTime
                            prefs.edit().putLong("start_time", newTime).apply()
                            
                            if (isPlaybackStartedByUser) {
                                schedulePlayback()
                            }
                        }
                    }
                } catch (e: Exception) {
                    Log.e("InflightSync", "JSON parse error: $rawBody")
                }
            }
        })
    }

    private fun schedulePlayback() {
        clearTasks()
        try {
            videoView?.let { 
                if (it.isPlaying) it.pause() 
                it.seekTo(0)
            }
        } catch (e: Exception) { }

        playerLayout.visibility = View.GONE
        // Ensure surface is maintained for immediate start while keeping it effectively invisible
        videoView?.visibility = View.VISIBLE
        videoView?.alpha = 0.01f
        dadiImage.visibility = View.VISIBLE

        val now = getSyncedTime()
        val startDelay = currentStartTime - now
        val duration = videoView?.duration?.toLong()?.coerceAtLeast(0L) ?: 0L
        val endDelay = (currentStartTime + duration) - now

        if (startDelay > 0) {
            val tick = object : Runnable {
                override fun run() {
                    if (!isPlaybackStartedByUser) return
                    
                    val nowMs = getSyncedTime()
                    val remainingMs = currentStartTime - nowMs
                    
                    if (remainingMs > 0) {
                        statusText.text = "Starts in ${formatCountdown((remainingMs + 999) / 1000)}"
                        // Self-correcting tick to stay synchronized with system clock
                        val delayUntilNextSec = 1000 - (nowMs % 1000)
                        handler.postDelayed(this, delayUntilNextSec)
                    } else {
                        statusText.text = "Starting shortly..."
                    }
                }
            }
            countdownRunnable = tick
            handler.post(tick)

            playbackRunnable = Runnable { 
                if (isPlaybackStartedByUser) {
                    val nowMs = getSyncedTime()
                    val msLate = (nowMs - currentStartTime).toInt().coerceAtLeast(0)
                    startAudio(msLate)
                }
            }
            handler.postDelayed(playbackRunnable!!, startDelay)
        } else if (endDelay > 0) {
            val msLate = (-startDelay).toInt()
            startAudio(msLate)
        } else {
            onPlaybackComplete()
        }
    }

    private fun startAudio(positionMs: Int) {
        videoView?.let { v ->
            try {
                v.animate().cancel()
                v.alpha = 0.01f
                v.visibility = View.VISIBLE
                
                // Smoothly fade in the video while keeping the dadiImage visible
                v.animate().alpha(1.0f).setDuration(1000).start()
                dadiImage.animate().cancel()
                dadiImage.alpha = 1.0f
                dadiImage.visibility = View.VISIBLE

                v.seekTo(positionMs)
                v.start()
                statusText.text = "Enjoying Cabin Journey"
                playerLayout.visibility = View.VISIBLE
                startProgressUpdates()
            } catch (e: Exception) {
                Log.e("InflightSync", "Playback start failed")
            }
        }
    }

    private fun startProgressUpdates() {
        progressRunnable?.let { handler.removeCallbacks(it) }
        val tick = object : Runnable {
            override fun run() {
                val player = videoView ?: return
                if (!isPlaybackStartedByUser) return

                val now = getSyncedTime()
                val durationMs = player.duration.toLong()
                val currentMs = player.currentPosition.toLong()
                
                // 1. Absolute Time Completion Check
                if (now >= (currentStartTime + durationMs) && durationMs > 0) {
                    onPlaybackComplete()
                    return
                }

                // 2. Player State Completion Check
                if (!player.isPlaying && currentMs >= (durationMs - 2000) && durationMs > 0) {
                    onPlaybackComplete()
                    return
                }

                // 3. UI Updates
                if (player.isPlaying && durationMs > 0) {
                    val trackWidth = progressTrack.width
                    if (trackWidth > 0) {
                        val fillPercentage = currentMs.toDouble() / durationMs.toDouble()
                        val fillWidth = (trackWidth * fillPercentage).toInt()
                        val params = progressFill.layoutParams
                        params.width = fillWidth
                        progressFill.layoutParams = params
                    }
                    
                    val remainingMs = (durationMs - currentMs).coerceAtLeast(0)
                    currentTimeText.text = formatTime(currentMs / 1000L)
                    remainingTimeText.text = if (remainingMs > 0) "-${formatTime(remainingMs / 1000L)}" else formatTime(0)
                }
                
                handler.postDelayed(this, 500)
            }
        }
        progressRunnable = tick
        handler.post(tick)
    }

    private fun stopPlayback() {
        isPlaybackStartedByUser = false
        clearTasks()
        videoView?.animate()?.cancel()
        videoView?.apply {
            try {
                if (isPlaying) pause()
                seekTo(0)
                // Keep surface ready for future playback
                visibility = View.VISIBLE
                alpha = 0.01f
            } catch (e: Exception) {}
        }
        dadiImage.animate()?.cancel()
        dadiImage.alpha = 1.0f
        dadiImage.visibility = View.VISIBLE
        statusText.text = getString(R.string.stopped)
        startBtn.isEnabled = true
        stopBtn.isEnabled = false
        playerLayout.visibility = View.GONE
    }

    private fun onPlaybackComplete() {
        if (!isPlaybackStartedByUser && statusText.text == getString(R.string.finished)) return
        
        isPlaybackStartedByUser = false
        clearTasks()
        statusText.text = getString(R.string.finished)
        
        // Final spiritual goodbye message
        earphoneRow.visibility = View.VISIBLE
        playbackControls.visibility = View.GONE
        confirmBtn.visibility = View.GONE
        
        earphoneText.text = getString(R.string.thank_you_message)
        earphoneText.setTextColor(ContextCompat.getColor(this, R.color.gold))
        earphoneText.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)

        playerLayout.visibility = View.GONE
        
        // Ensure surface is maintained for immediate start while keeping it effectively invisible
        videoView?.animate()?.cancel()
        videoView?.apply {
            try {
                if (isPlaying) pause()
                seekTo(0)
                visibility = View.VISIBLE
                alpha = 0.01f
            } catch (e: Exception) {}
        }
        
        dadiImage.animate()?.cancel()
        dadiImage.alpha = 1.0f
        dadiImage.visibility = View.VISIBLE
    }

    private fun clearTasks() {
        countdownRunnable?.let { handler.removeCallbacks(it) }
        progressRunnable?.let { handler.removeCallbacks(it) }
        playbackRunnable?.let { handler.removeCallbacks(it) }
        countdownRunnable = null
        progressRunnable = null
        playbackRunnable = null
    }

    private fun formatCountdown(totalSecs: Long): String {
        val days = totalSecs / 86400
        val hours = (totalSecs % 86400) / 3600
        val minutes = (totalSecs % 3600) / 60
        val seconds = totalSecs % 60

        return when {
            days > 0 -> "${days}d ${hours}h ${minutes}m"
            hours > 0 -> "${hours}h ${minutes}m ${seconds}s"
            minutes > 0 -> "${minutes}m ${seconds}s"
            else -> "${seconds}s"
        }
    }

    private fun formatTime(secs: Long): String {
        val h = secs / 3600
        val m = (secs % 3600) / 60
        val s = secs % 60
        return if (h > 0) "$h:${m.toString().padStart(2, '0')}:${s.toString().padStart(2, '0')}"
        else "$m:${s.toString().padStart(2, '0')}"
    }

    override fun onDestroy() {
        super.onDestroy()
        clearTasks()
        fetchRunnable?.let { handler.removeCallbacks(it) }
        videoView?.stopPlayback()
        videoView = null
        try { connectivityManager.unregisterNetworkCallback(networkCallback) } catch (_: Exception) {}
    }
}
