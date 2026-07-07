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
    private var isLive = false
    private var earphonesConfirmed = false
    private var audioReady = false
    private var isMediaLoading = false
    private var countdownRunnable: Runnable? = null
    private var progressRunnable: Runnable? = null
    private var playbackRunnable: Runnable? = null
    private var uiRunnable: Runnable? = null
    private var fetchRunnable: Runnable? = null

    private val apiUrl = "https://raw.githubusercontent.com/Adarshtulsyan/Inflight-audio-app/main/config.json"

    @Volatile
    private var currentStartTime: Long = Long.MAX_VALUE
    private var serverClockOffset: Long = 0L
    @Volatile
    private var lastFetchedTimeStr: String = ""
    @Volatile
    private var lastSyncStr: String = "Never"
    @Volatile
    private var isFinished: Boolean = false
    
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
        } else {
            currentStartTime = Long.MAX_VALUE
            isConfigLoaded = false
        }
        statusText.text = getString(R.string.checking_audio)
        
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
        
        // Initial checks
        val isOnline = try { isNetworkAvailable() } catch (e: Exception) { false }
        updateLiveStatus(isOnline)
        startConfigPolling()
    }

    private fun setupInitialState() {
        welcomeScreen.visibility = View.VISIBLE
        mainContent.visibility = View.GONE
        
        earphoneRow.visibility = View.VISIBLE
        playbackControls.visibility = View.GONE
        playerLayout.visibility = View.GONE
        startBtn.isEnabled = false
        stopBtn.isEnabled = false
        updateHeadsetStatus()
    }

    private fun updateLiveStatus(online: Boolean) {
        if (online) {
            if (isLive) {
                statusBadge.text = "LIVE"
                statusBadge.setTextColor(ContextCompat.getColor(this, R.color.gold))
                statusBadge.alpha = 1.0f
            } else {
                statusBadge.text = "SYNCING"
                statusBadge.setTextColor(ContextCompat.getColor(this, R.color.gold))
                statusBadge.alpha = 0.7f
            }
        } else {
            statusBadge.text = "OFFLINE"
            statusBadge.setTextColor(ContextCompat.getColor(this, R.color.text_secondary))
            statusBadge.alpha = 0.5f
        }
        refreshStatusText()
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
                refreshStatusText()
                mp.isLooping = false
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
            
            refreshStatusText()
            if (!audioReady) {
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
                refreshStatusText()
                handler.postDelayed(this, 1000)
            }
        }
        uiRunnable = uiTask
        handler.post(uiTask)

        // Network Polling Task (Every 10 seconds)
        val networkTask = object : Runnable {
            override fun run() {
                Log.d("InflightSync", "Triggering config poll...")
                fetchRemoteConfig()
                handler.postDelayed(this, 10000)
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
        
        val targetTime: String
        if (currentStartTime == Long.MAX_VALUE) {
            targetTime = "Waiting for Sync"
        } else {
            val targetSdf = SimpleDateFormat("MMM d, HH:mm:ss", Locale.getDefault())
            targetSdf.timeZone = TimeZone.getTimeZone("Asia/Kolkata")
            targetTime = targetSdf.format(currentStartTime)
        }
        
        handler.post {
            deviceTimeText.text = "Device: $deviceTime"
            targetTimeText.text = "Target: $targetTime"
        }
    }

    private fun fetchRemoteConfig() {
        val timestamp = System.currentTimeMillis()
        val syncSdf = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
        
        // Append unique query params to force GitHub to bypass its edge cache
        val urlWithCacheBust = "$apiUrl?cb=$timestamp"

        val request = Request.Builder()
            .url(urlWithCacheBust)
            .header("Cache-Control", "no-cache")
            .build()

        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                Log.e("InflightSync", "Poll Failed: ${e.message}")
                handler.post { 
                    lastSyncStr = "${syncSdf.format(System.currentTimeMillis())} (Error: ${e.message})"
                    isLive = false
                    updateLiveStatus(isNetworkAvailable()) 
                }
            }

            override fun onResponse(call: Call, response: Response) {
                val responseTime = System.currentTimeMillis()
                val serverDateHeader = response.header("Date")
                val rawBody = response.body?.string() ?: ""
                Log.d("InflightSync", "Raw Response: $rawBody")
                
                handler.post {
                    lastSyncStr = syncSdf.format(responseTime)
                    
                    // Sync clock using Date header to prevent device time manipulation
                    serverDateHeader?.let { dateStr ->
                        try {
                            val headerSdf = SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss zzz", Locale.US)
                            headerSdf.timeZone = TimeZone.getTimeZone("GMT")
                            val serverDate = headerSdf.parse(dateStr)
                            serverDate?.let { date ->
                                serverClockOffset = date.time - responseTime
                                Log.d("InflightSync", "Server Clock Offset: $serverClockOffset ms")
                            }
                        } catch (e: Exception) {
                            Log.e("InflightSync", "Failed to parse Date header: $dateStr")
                        }
                    }

                    if (!response.isSuccessful) {
                        lastSyncStr += " (HTTP ${response.code})"
                        isLive = false
                        updateLiveStatus(isNetworkAvailable()) 
                        return@post
                    }

                    try {
                        val configJson = JSONObject(rawBody)
                        val timeStr = configJson.optString("startTime", "").trim()
                        
                        if (timeStr.isEmpty()) {
                            lastSyncStr += " (Missing Key)"
                            return@post
                        }
                        
                        // Use a more lenient parser or check for 'Z' suffix
                        val sdf = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US)
                        sdf.timeZone = TimeZone.getTimeZone("Asia/Kolkata")
                        val date = sdf.parse(timeStr) ?: throw Exception("Parse failed")
                        val newTime = date.time

                        isLive = true
                        isConfigLoaded = true
                        updateLiveStatus(true)
                        
                        if (timeStr != lastFetchedTimeStr) {
                            Log.i("InflightSync", "New Time Synced: $timeStr")
                            lastFetchedTimeStr = timeStr
                            currentStartTime = newTime
                            prefs.edit().putLong("start_time", newTime).apply()
                            
                            if (isFinished) {
                                isFinished = false
                                resetToReadyState()
                            }

                            if (isPlaybackStartedByUser) {
                                schedulePlayback()
                            }
                        }
                    } catch (e: Exception) {
                        Log.e("InflightSync", "Parse Error: ${e.message}")
                        lastSyncStr += " (Parse Error)"
                    }
                }
            }
        })
    }

    private fun resetToReadyState() {
        if (earphonesConfirmed) {
            earphoneRow.visibility = View.GONE
            playbackControls.visibility = View.VISIBLE
        } else {
            earphoneRow.visibility = View.VISIBLE
            playbackControls.visibility = View.GONE
        }
        confirmBtn.visibility = View.VISIBLE
        updateHeadsetStatus()
        statusText.text = getString(R.string.ready)
    }

    private fun schedulePlayback() {
        if (!isConfigLoaded) {
            statusText.text = "Waiting for Sync..."
            return
        }
        
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
        
        // Match iOS logic: use player duration if available, else fallback to 20 mins (1,200,000 ms)
        val audioDuration = if ((videoView?.duration ?: 0) > 0) videoView?.duration?.toLong() ?: 0L else 1200000L
        val endDelay = (currentStartTime + audioDuration) - now

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
        
        refreshStatusText()
        statusText.text = getString(R.string.stopped)
        stopBtn.isEnabled = false
        playerLayout.visibility = View.GONE
    }

    private fun onPlaybackComplete() {
        if (!isPlaybackStartedByUser && isFinished) return
        
        isPlaybackStartedByUser = false
        isFinished = true
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

    private fun refreshStatusText() {
        if (isPlaybackStartedByUser || 
            isFinished ||
            statusText.text == getString(R.string.stopped)) return

        val online = try { isNetworkAvailable() } catch (e: Exception) { false }
        
        var isActuallyReady = false
        
        val newStatus = when {
            // 1. If we have a configuration (live or cached), we are ready for the journey
            isConfigLoaded || isLive -> {
                if (!audioReady && earphonesConfirmed) {
                    "Waiting for audio to be ready..."
                } else {
                    isActuallyReady = true
                    getString(R.string.ready)
                }
            }
            
            // 2. If online but haven't synced yet (and no cache)
            online -> getString(R.string.checking_audio)
            
            // 3. Offline and no config
            else -> "Sync required (Check Internet)"
        }

        if (statusText.text != newStatus) {
            statusText.text = newStatus
        }

        // Update button states based on consolidated sync and audio readiness
        if (earphonesConfirmed) {
            startBtn.isEnabled = isActuallyReady
            stopBtn.isEnabled = false
        }
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
        uiRunnable?.let { handler.removeCallbacks(it) }
        videoView?.stopPlayback()
        videoView = null
        try { connectivityManager.unregisterNetworkCallback(networkCallback) } catch (_: Exception) {}
    }
}
