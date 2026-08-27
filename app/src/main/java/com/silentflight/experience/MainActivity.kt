package com.silentflight.experience

import android.Manifest
import android.content.*
import android.content.pm.PackageManager
import android.media.MediaPlayer
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.Uri
import android.os.*
import android.util.Log
import android.util.TypedValue
import android.view.View
import android.view.animation.AlphaAnimation
import android.view.animation.Animation
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.json.JSONObject
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

class MainActivity : AppCompatActivity() {

    private lateinit var connectivityManager: ConnectivityManager
    private val handler = Handler(Looper.getMainLooper())
    private val client = OkHttpClient()
    private lateinit var prefs: SharedPreferences

    private var audioService: AudioService? = null
    private var isBound = false

    private var isPlaybackStartedByUser = false
    private var isConfigLoaded = false
    private var isLive = false
    private var earphonesConfirmed = false
    private var audioReady = false
    private var countdownRunnable: Runnable? = null
    private var progressRunnable: Runnable? = null
    private var playbackRunnable: Runnable? = null
    private var uiRunnable: Runnable? = null
    private var fetchRunnable: Runnable? = null

    private val apiUrl = "https://raw.githubusercontent.com/Adarshtulsyan/Inflight-audio-app/main/config.json"

    @Volatile
    private var officialStartTime: Long = Long.MAX_VALUE
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
    private lateinit var replayBtn: Button
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

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val binder = service as AudioService.AudioBinder
            audioService = binder.getService()
            isBound = true
            // If service is already playing, sync UI
            if (audioService?.isPlaying() == true) {
                isPlaybackStartedByUser = true
                startBtn.isEnabled = false
                stopBtn.isEnabled = true
                playerLayout.visibility = View.VISIBLE
                startProgressUpdates()
            }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            audioService = null
            isBound = false
        }
    }

    private val playbackReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                "PLAYBACK_COMPLETE" -> onPlaybackComplete()
                "PLAYBACK_STOPPED" -> {
                    isPlaybackStartedByUser = false
                    stopPlayback()
                }
            }
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
        earphoneRow      = findViewById(R.id.earphoneRow)
        playbackControls = findViewById(R.id.playbackControls)
        earphoneText     = findViewById(R.id.earphoneText)
        confirmBtn       = findViewById(R.id.confirmEarphonesBtn)
        startBtn         = findViewById(R.id.startBtn)
        stopBtn          = findViewById(R.id.stopBtn)
        replayBtn        = findViewById(R.id.replayBtn)
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
            officialStartTime = prefs.getLong("start_time", 0L)
            currentStartTime = officialStartTime
            isConfigLoaded = true
        } else {
            officialStartTime = Long.MAX_VALUE
            currentStartTime = Long.MAX_VALUE
            isConfigLoaded = false
        }
        statusText.text = getString(R.string.checking_audio)
        
        setupInitialState()
        setupButtons()
        
        checkAudioReadiness()

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

        // Bind to service
        Intent(this, AudioService::class.java).also { intent ->
            bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
        }

        // Register playback receiver
        val filter = IntentFilter().apply {
            addAction("PLAYBACK_COMPLETE")
            addAction("PLAYBACK_STOPPED")
        }
        registerReceiver(playbackReceiver, filter, if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) Context.RECEIVER_NOT_EXPORTED else 0)
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

    private fun checkAudioReadiness() {
        // Since it's in a service now, we assume it's ready if the resource exists
        audioReady = true 
        refreshStatusText()
    }

    private fun setupButtons() {
        enterCabinBtn.setOnClickListener {
            transitionToMain()
        }

        confirmBtn.setOnClickListener {
            earphonesConfirmed = true
            earphoneRow.visibility = View.GONE
            playbackControls.visibility = View.VISIBLE
            refreshStatusText()
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

        replayBtn.setOnClickListener {
            replayJourney()
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
                    val fadeIn = AlphaAnimation(0f, 1f).apply { duration = 800 }
                    mainContent.startAnimation(fadeIn)
                }
            })
        }
        welcomeScreen.startAnimation(fadeOut)
    }

    private fun startConfigPolling() {
        val uiTask = object : Runnable {
            override fun run() {
                updateDiagnosticTimes()
                refreshStatusText()
                handler.postDelayed(this, 1000)
            }
        }
        uiRunnable = uiTask
        handler.post(uiTask)

        val networkTask = object : Runnable {
            override fun run() {
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
        if (officialStartTime == Long.MAX_VALUE) {
            targetTime = "Waiting for Sync"
        } else {
            val targetSdf = SimpleDateFormat("MMM d, HH:mm:ss", Locale.getDefault())
            targetSdf.timeZone = TimeZone.getTimeZone("Asia/Kolkata")
            targetTime = targetSdf.format(officialStartTime)
        }
        
        handler.post {
            deviceTimeText.text = "Device: $deviceTime"
            targetTimeText.text = "Target: $targetTime"
        }
    }

    private fun fetchRemoteConfig() {
        val timestamp = System.currentTimeMillis()
        val syncSdf = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
        val urlWithCacheBust = "$apiUrl?cb=$timestamp"

        val request = Request.Builder()
            .url(urlWithCacheBust)
            .header("Cache-Control", "no-cache")
            .build()

        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
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
                
                handler.post {
                    lastSyncStr = syncSdf.format(responseTime)
                    
                    serverDateHeader?.let { dateStr ->
                        try {
                            val headerSdf = SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss zzz", Locale.US)
                            headerSdf.timeZone = TimeZone.getTimeZone("GMT")
                            val serverDate = headerSdf.parse(dateStr)
                            serverDate?.let { date ->
                                serverClockOffset = date.time - responseTime
                            }
                        } catch (e: Exception) {}
                    }

                    if (!response.isSuccessful) {
                        isLive = false
                        updateLiveStatus(isNetworkAvailable()) 
                        return@post
                    }

                    try {
                        val configJson = JSONObject(rawBody)
                        val timeStr = configJson.optString("startTime", "").trim()
                        
                        if (timeStr.isEmpty()) return@post
                        
                        val sdf = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US)
                        sdf.timeZone = TimeZone.getTimeZone("Asia/Kolkata")
                        val date = sdf.parse(timeStr) ?: throw Exception("Parse failed")
                        val newTime = date.time

                        isLive = true
                        isConfigLoaded = true
                        updateLiveStatus(true)
                        
                        if (timeStr != lastFetchedTimeStr) {
                            lastFetchedTimeStr = timeStr
                            officialStartTime = newTime
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
                        isLive = false
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
        replayBtn.visibility = View.GONE
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

        playerLayout.visibility = View.GONE
        dadiImage.visibility = View.VISIBLE

        val now = getSyncedTime()
        val startDelay = currentStartTime - now
        
        // Use fixed duration if service not yet available
        val audioDuration = 6178612L 
        val endDelay = (currentStartTime + audioDuration) - now

        if (startDelay > 0) {
            val tick = object : Runnable {
                override fun run() {
                    if (!isPlaybackStartedByUser) return
                    val nowMs = getSyncedTime()
                    val remainingMs = currentStartTime - nowMs
                    if (remainingMs > 0) {
                        statusText.text = "Starts in ${formatCountdown((remainingMs + 999) / 1000)}"
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
        val intent = Intent(this, AudioService::class.java).apply {
            action = "START_PLAYBACK"
            putExtra("POSITION", positionMs)
        }
        startForegroundService(intent)
        
        statusText.text = "Enjoying Cabin Journey"
        playerLayout.visibility = View.VISIBLE
        startProgressUpdates()
    }

    private fun startProgressUpdates() {
        progressRunnable?.let { handler.removeCallbacks(it) }
        val tick = object : Runnable {
            override fun run() {
                if (!isPlaybackStartedByUser || audioService == null) return

                val durationMs = audioService?.getDuration()?.toLong() ?: 6178612L
                val currentMs = audioService?.getCurrentPosition()?.toLong() ?: 0L
                
                if (durationMs > 0) {
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
        
        val intent = Intent(this, AudioService::class.java).apply {
            action = "STOP_PLAYBACK"
        }
        startService(intent)
        
        refreshStatusText()
        statusText.text = getString(R.string.stopped)
        stopBtn.isEnabled = false
        playerLayout.visibility = View.GONE
    }

    private fun replayJourney() {
        isFinished = false
        earphonesConfirmed = true
        isPlaybackStartedByUser = true
        
        earphoneRow.visibility = View.GONE
        playbackControls.visibility = View.VISIBLE
        replayBtn.visibility = View.GONE
        
        currentStartTime = getSyncedTime()
        startAudio(0)
    }

    private fun onPlaybackComplete() {
        if (!isPlaybackStartedByUser && isFinished) return
        
        isPlaybackStartedByUser = false
        isFinished = true
        clearTasks()
        statusText.text = getString(R.string.finished)
        
        earphoneRow.visibility = View.VISIBLE
        playbackControls.visibility = View.GONE
        confirmBtn.visibility = View.GONE
        replayBtn.visibility = View.VISIBLE
        
        earphoneText.text = getString(R.string.thank_you_message)
        earphoneText.setTextColor(ContextCompat.getColor(this, R.color.gold))
        earphoneText.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)

        playerLayout.visibility = View.GONE
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
            isConfigLoaded || isLive -> {
                if (!audioReady && earphonesConfirmed) {
                    "Waiting for audio to be ready..."
                } else {
                    isActuallyReady = true
                    getString(R.string.ready)
                }
            }
            online -> getString(R.string.checking_audio)
            else -> "Sync required (Check Internet)"
        }

        if (statusText.text != newStatus) {
            statusText.text = newStatus
        }

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
        if (isBound) {
            unbindService(serviceConnection)
            isBound = false
        }
        try { unregisterReceiver(playbackReceiver) } catch (_: Exception) {}
        try { connectivityManager.unregisterNetworkCallback(networkCallback) } catch (_: Exception) {}
    }
}
