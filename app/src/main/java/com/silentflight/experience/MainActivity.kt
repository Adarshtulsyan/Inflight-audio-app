package com.silentflight.experience

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.media.AudioManager
import android.media.MediaPlayer
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
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

    private lateinit var audioManager: AudioManager
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

    private lateinit var earphoneRow: View
    private lateinit var playbackControls: View
    private lateinit var earphoneText: TextView
    private lateinit var confirmBtn: Button
    private lateinit var startBtn: Button
    private lateinit var stopBtn: Button
    private lateinit var statusText: TextView
    private lateinit var playerLayout: LinearLayout
    private lateinit var progressTrack: View
    private lateinit var progressFill: View
    private lateinit var currentTimeText: TextView
    private lateinit var remainingTimeText: TextView
    private lateinit var downloadPrompt: View

    private val headsetReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == AudioManager.ACTION_HEADSET_PLUG) {
                val state = intent.getIntExtra("state", -1)
                if (state == 1 && !earphonesConfirmed) {
                    confirmBtn.isEnabled = true
                    earphoneText.text = getString(R.string.earphone_confirmed)
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        audioManager = getSystemService(AUDIO_SERVICE) as AudioManager
        prefs = getSharedPreferences("app_prefs", MODE_PRIVATE)
        
        // Fix: If stored time is older than our current target, reset it
        val storedTime = prefs.getLong("start_time", 0L)
        currentStartTime = if (storedTime < defaultStartTime) defaultStartTime else storedTime

        earphoneRow      = findViewById(R.id.earphoneRow)
        playbackControls = findViewById(R.id.playbackControls)
        earphoneText     = findViewById(R.id.earphoneText)
        confirmBtn       = findViewById(R.id.confirmEarphonesBtn)
        startBtn         = findViewById(R.id.startBtn)
        stopBtn          = findViewById(R.id.stopBtn)
        statusText       = findViewById(R.id.statusText)
        playerLayout     = findViewById(R.id.playerLayout)
        progressTrack    = findViewById(R.id.progressTrack)
        progressFill     = findViewById(R.id.progressFill)
        currentTimeText  = findViewById(R.id.currentTimeText)
        remainingTimeText = findViewById(R.id.remainingTimeText)
        downloadPrompt   = findViewById(R.id.downloadPrompt)
        
        setupInitialState()
        setupMediaPlayer()
        setupButtons()

        @Suppress("DEPRECATION")
        registerReceiver(headsetReceiver, IntentFilter(AudioManager.ACTION_HEADSET_PLUG))
        startConfigPolling()
    }

    private fun setupInitialState() {
        earphoneRow.visibility = View.VISIBLE
        playbackControls.visibility = View.GONE
        playerLayout.visibility = View.GONE
        confirmBtn.isEnabled = true
        earphoneText.text = getString(R.string.earphone_prompt)
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
            }

            override fun onResponse(call: Call, response: Response) {
                val rawBody = response.body?.string() ?: ""
                if (!response.isSuccessful) return

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
                        val isFirstFetch = currentFileSha.isEmpty()
                        val shaChanged = sha != currentFileSha
                        
                        if (isFirstFetch || shaChanged || currentStartTime != newTime) {
                            Log.d("InflightSync", "Remote Update: $timeStr")
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
        val delay = currentStartTime - now

        Log.d("InflightSync", "Target: $currentStartTime, Now: $now, Diff: $delay")

        if (delay > 0) {
            // Future start time
            var remainingSecs = (delay / 1000)
            val tick = object : Runnable {
                override fun run() {
                    statusText.text = "Starts in ${formatCountdown(remainingSecs)}"
                    remainingSecs--
                    if (remainingSecs >= 0) {
                        handler.postDelayed(this, 1000)
                    }
                }
            }
            countdownRunnable = tick
            handler.post(tick)

            val startTask = Runnable {
                playbackRunnable = null
                countdownRunnable?.let { handler.removeCallbacks(it) }
                startAudio(0)
            }
            playbackRunnable = startTask
            handler.postDelayed(startTask, delay)
        } else {
            // Past or current start time
            val msLate = -delay 
            val duration = mediaPlayer?.duration?.toLong() ?: 0L
            
            if (duration > 0 && msLate > duration) {
                statusText.text = getString(R.string.finished)
                onPlaybackComplete()
            } else {
                startAudio(msLate.toInt().coerceAtLeast(0))
            }
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
        val tick = object : Runnable {
            override fun run() {
                val player = mediaPlayer ?: return
                if (!player.isPlaying) return

                val currentMs = player.currentPosition
                val durationMs = player.duration
                if (durationMs > 0) {
                    val trackWidth = progressTrack.width
                    if (trackWidth > 0) {
                        val fillWidth = (trackWidth.toLong() * currentMs / durationMs).toInt()
                        val params = progressFill.layoutParams
                        params.width = fillWidth
                        progressFill.layoutParams = params
                    }
                    currentTimeText.text = formatTime(currentMs / 1000L)
                    remainingTimeText.text = "-${formatTime((durationMs - currentMs) / 1000L)}"
                }
                handler.postDelayed(this, 1000)
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
        if (!isPlaybackStartedByUser) return
        clearTasks()
        statusText.text = getString(R.string.finished)
        startBtn.isEnabled = true
        stopBtn.isEnabled = false
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
        try { unregisterReceiver(headsetReceiver) } catch (_: Exception) {}
    }
}
