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
    private var mediaPlayer: MediaPlayer? = null
    private val handler = Handler(Looper.getMainLooper())
    private val client = OkHttpClient()
    private lateinit var prefs: SharedPreferences

    private var isPlaybackStartedByUser = false
    private var earphonesConfirmed = false
    private var audioReady = false
    private var countdownRunnable: Runnable? = null
    private var progressRunnable: Runnable? = null
    private var playbackRunnable: Runnable? = null
    private var fetchRunnable: Runnable? = null

    private val apiUrl = "https://api.github.com/repos/Adarshtulsyan/Inflight-audio-app/contents/config.json"

    @Volatile
    private var currentStartTime: Long = 0L
    private var currentFileSha: String = ""

    private val defaultStartTime: Long by lazy {
        Calendar.getInstance(TimeZone.getTimeZone("Asia/Kolkata")).apply {
            set(2026, Calendar.APRIL, 22, 12, 19, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis
    }

    private lateinit var welcomeScreen: View
    private lateinit var mainContent: View
    private lateinit var enterCabinBtn: Button
    
    private lateinit var earphoneRow: View
    private lateinit var playbackControls: View
    private lateinit var earphoneText: TextView
    private lateinit var confirmBtn: Button
    private lateinit var startBtn: Button
    private lateinit var stopBtn: Button
    private lateinit var statusText: TextView
    private lateinit var statusBadge: TextView
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
        
        val storedTime = prefs.getLong("start_time", 0L)
        currentStartTime = if (storedTime < defaultStartTime) defaultStartTime else storedTime

        welcomeScreen    = findViewById(R.id.welcomeScreen)
        mainContent      = findViewById(R.id.mainContent)
        enterCabinBtn    = findViewById(R.id.enterCabinBtn)

        earphoneRow      = findViewById(R.id.earphoneRow)
        playbackControls = findViewById(R.id.playbackControls)
        earphoneText     = findViewById(R.id.earphoneText)
        confirmBtn       = findViewById(R.id.confirmEarphonesBtn)
        startBtn         = findViewById(R.id.startBtn)
        stopBtn          = findViewById(R.id.stopBtn)
        statusText       = findViewById(R.id.statusText)
        statusBadge      = findViewById(R.id.statusBadge)
        playerLayout     = findViewById(R.id.playerLayout)
        progressTrack    = findViewById(R.id.progressTrack)
        progressFill     = findViewById(R.id.progressFill)
        currentTimeText  = findViewById(R.id.currentTimeText)
        remainingTimeText = findViewById(R.id.remainingTimeText)
        downloadPrompt   = findViewById(R.id.downloadPrompt)
        
        setupInitialState()
        setupMediaPlayer()
        setupButtons()

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
    }

    private fun isHeadsetConnected(): Boolean {
        return true
    }

    private fun setupMediaPlayer() {
        try {
            mediaPlayer = MediaPlayer.create(this, R.raw.audio)
            if (mediaPlayer != null) {
                audioReady = true
                statusText.text = getString(R.string.ready)
                mediaPlayer?.setOnCompletionListener { onPlaybackComplete() }
            } else {
                audioReady = false
                statusText.text = getString(R.string.audio_unavailable)
                downloadPrompt.visibility = View.VISIBLE
            }
        } catch (e: Exception) {
            audioReady = false
            statusText.text = getString(R.string.audio_unavailable)
        }
    }

    private fun setupButtons() {
        enterCabinBtn.setOnClickListener {
            transitionToMain()
        }

        confirmBtn.setOnClickListener {
            earphonesConfirmed = true
            earphoneRow.visibility = View.GONE
            playbackControls.visibility = View.VISIBLE
            startBtn.isEnabled = audioReady
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
                    val fadeIn = AlphaAnimation(0f, 1f).apply { duration = 800 }
                    mainContent.startAnimation(fadeIn)
                }
            })
        }
        welcomeScreen.startAnimation(fadeOut)
    }

    private fun startConfigPolling() {
        val pollTask = object : Runnable {
            override fun run() {
                fetchRemoteConfig()
                handler.postDelayed(this, 15000) 
            }
        }
        fetchRunnable = pollTask
        handler.post(pollTask)
    }

    private fun fetchRemoteConfig() {
        val request = Request.Builder()
            .url(apiUrl)
            .cacheControl(okhttp3.CacheControl.FORCE_NETWORK)
            .header("Accept", "application/vnd.github.v3+json")
            .build()

        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                Log.e("InflightSync", "Network error")
                handler.post { updateLiveStatus(false) }
            }

            override fun onResponse(call: Call, response: Response) {
                val rawBody = response.body?.string() ?: ""
                if (!response.isSuccessful) {
                    handler.post { updateLiveStatus(false) }
                    return
                }

                try {
                    val json = JSONObject(rawBody)
                    val sha = json.getString("sha")
                    val contentBase64 = json.getString("content").replace("\n", "")
                    val decodedBytes = android.util.Base64.decode(contentBase64, android.util.Base64.DEFAULT)
                    val configJson = JSONObject(String(decodedBytes))
                    val timeStr = configJson.getString("startTime")
                    
                    val sdf = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.getDefault())
                    sdf.timeZone = TimeZone.getTimeZone("Asia/Kolkata")
                    val date = sdf.parse(timeStr) ?: return
                    val newTime = date.time

                    handler.post {
                        updateLiveStatus(true)
                        val isFirstFetch = currentFileSha.isEmpty()
                        val shaChanged = sha != currentFileSha
                        val timeDrift = Math.abs(currentStartTime - newTime)
                        
                        // Only re-schedule if the SHA changed or the time drift is significant (> 2s)
                        if (isFirstFetch || shaChanged || timeDrift > 2000) {
                            Log.d("InflightSync", "Remote Sync Update: $timeStr (Drift: ${timeDrift}ms)")
                            currentFileSha = sha
                            currentStartTime = newTime
                            prefs.edit().putLong("start_time", newTime).apply()
                            
                            if (isPlaybackStartedByUser) {
                                schedulePlayback()
                            }
                        }
                    }
                } catch (e: Exception) {
                    Log.e("InflightSync", "Parse error")
                }
            }
        })
    }

    private fun schedulePlayback() {
        clearTasks()
        try {
            mediaPlayer?.let { 
                if (it.isPlaying) it.pause() 
                it.seekTo(0)
            }
        } catch (e: Exception) { }

        playerLayout.visibility = View.GONE

        val now = System.currentTimeMillis()
        val startDelay = currentStartTime - now
        val duration = mediaPlayer?.duration?.toLong() ?: 0L
        val endDelay = (currentStartTime + duration) - now

        if (startDelay > 0) {
            var remainingSecs = (startDelay / 1000)
            val tick = object : Runnable {
                override fun run() {
                    if (!isPlaybackStartedByUser) return
                    
                    if (remainingSecs > 0) {
                        statusText.text = "Starts in ${formatCountdown(remainingSecs)}"
                        remainingSecs--
                        handler.postDelayed(this, 1000)
                    } else {
                        statusText.text = "Starting shortly..."
                    }
                }
            }
            countdownRunnable = tick
            handler.post(tick)

            playbackRunnable = Runnable { 
                if (isPlaybackStartedByUser) startAudio(0) 
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
        mediaPlayer?.let {
            try {
                it.seekTo(positionMs)
                it.start()
                statusText.text = "Enjoying Cabin Audio"
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
                val player = mediaPlayer ?: return
                if (!isPlaybackStartedByUser) return

                val now = System.currentTimeMillis()
                val durationMs = player.duration.toLong()
                val currentMs = player.currentPosition.toLong()
                
                // 1. Absolute Time Completion Check
                // If current time is past the scheduled end time, finish.
                if (now >= (currentStartTime + durationMs)) {
                    onPlaybackComplete()
                    return
                }

                // 2. Player State Completion Check
                if (!player.isPlaying && currentMs >= (durationMs - 2000)) {
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
        mediaPlayer?.apply {
            try {
                if (isPlaying) pause()
                seekTo(0)
            } catch (e: Exception) {}
        }
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
        
        mediaPlayer?.apply {
            try {
                if (isPlaying) stop()
                seekTo(0)
            } catch (e: Exception) {}
        }
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
        mediaPlayer?.release()
        mediaPlayer = null
        try { connectivityManager.unregisterNetworkCallback(networkCallback) } catch (_: Exception) {}
    }
}
