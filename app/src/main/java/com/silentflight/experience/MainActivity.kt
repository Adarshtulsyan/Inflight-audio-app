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

/**
 * Premium Inflight Audio Experience (V4.0)
 * Cleaned up logs, enhanced UI, and remote-sync ready.
 */
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
            set(2026, Calendar.APRIL, 21, 20, 45, 0)
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
                    confirmBtn.visibility = View.VISIBLE
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        audioManager = getSystemService(AUDIO_SERVICE) as AudioManager
        prefs = getSharedPreferences("app_prefs", MODE_PRIVATE)
        currentStartTime = prefs.getLong("start_time", defaultStartTime)

        // View Binding
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
        
        setupEarphones()
        setupMediaPlayer()
        setupButtons()

        @Suppress("DEPRECATION")
        registerReceiver(headsetReceiver, IntentFilter(AudioManager.ACTION_HEADSET_PLUG))

        startConfigPolling()
    }

    private fun startConfigPolling() {
        val pollTask = object : Runnable {
            override fun run() {
                fetchRemoteConfig()
                handler.postDelayed(this, 10 * 1000) 
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
            override fun onFailure(call: Call, e: IOException) {}

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
                        currentFileSha = sha

                        if (isFirstFetch || currentStartTime != newTime) {
                            Log.d("InflightSync", "Syncing state. FirstFetch=$isFirstFetch, Time=$timeStr")
                            currentStartTime = newTime
                            prefs.edit().putLong("start_time", newTime).apply()
                            
                            // Only force a jump if we are already playing or if the time actually changed
                            if (isPlaybackStartedByUser && (shaChanged || currentStartTime != newTime)) {
                                Log.d("InflightSync", "Re-scheduling playback")
                                schedulePlayback()
                            }
                        }
                    }
                } catch (e: Exception) {}
            }
        })
    }

    private fun setupEarphones() {
        earphoneText.text = getString(R.string.earphone_prompt)
        confirmBtn.visibility = View.VISIBLE
        earphoneRow.visibility = View.VISIBLE
        playbackControls.visibility = View.INVISIBLE
    }

    private fun setupMediaPlayer() {
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
        updateStartButton()
    }

    private fun updateStartButton() {
        startBtn.isEnabled = audioReady && earphonesConfirmed
    }

    private fun setupButtons() {
        confirmBtn.setOnClickListener {
            earphonesConfirmed = true
            earphoneRow.visibility = View.GONE
            playbackControls.visibility = View.VISIBLE
            updateStartButton()
        }

        startBtn.setOnClickListener {
            isPlaybackStartedByUser = true
            startBtn.isEnabled = false
            stopBtn.isEnabled = true
            Log.d("InflightSync", "User started playback. Current StartTime: $currentStartTime")
            schedulePlayback()
        }

        stopBtn.setOnClickListener {
            isPlaybackStartedByUser = false
            stopPlayback()
        }
    }

    private fun schedulePlayback() {
        countdownRunnable?.let { handler.removeCallbacks(it) }
        progressRunnable?.let { handler.removeCallbacks(it) }
        playbackRunnable?.let { handler.removeCallbacks(it) }
        
        try {
            mediaPlayer?.let { 
                if (it.isPlaying) it.pause() 
                it.seekTo(0)
            }
        } catch (e: Exception) { }

        hidePlayer()

        val now = System.currentTimeMillis()
        val delay = currentStartTime - now

        if (delay > 0) {
            var remaining = (delay / 1000)
            val tick = object : Runnable {
                override fun run() {
                    statusText.text = "Starts in ${formatSeconds(remaining)}"
                    remaining--
                    if (remaining >= 0) {
                        handler.postDelayed(this, 1000)
                    }
                }
            }
            countdownRunnable = tick
            handler.post(tick)

            val startTask = Runnable {
                playbackRunnable = null
                countdownRunnable?.let { handler.removeCallbacks(it) }
                mediaPlayer?.let {
                    it.seekTo(0)
                    it.start()
                    statusText.text = "Enjoying Silent Flight"
                    showPlayer()
                    startProgressUpdates()
                }
            }
            playbackRunnable = startTask
            handler.postDelayed(startTask, delay)
        } else {
            val msLateLong = -delay
            val duration = mediaPlayer?.duration ?: 0
            
            if (duration > 0 && msLateLong > duration) {
                statusText.text = "Session has finished"
                stopPlayback()
            } else {
                val msLate = msLateLong.toInt().coerceAtLeast(0)
                mediaPlayer?.let {
                    it.seekTo(msLate)
                    it.start()
                    statusText.text = "Joined in sync"
                    showPlayer()
                    startProgressUpdates()
                }
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
                handler.postDelayed(this, 500)
            }
        }
        progressRunnable = tick
        handler.post(tick)
    }

    private fun stopPlayback() {
        countdownRunnable?.let { handler.removeCallbacks(it) }
        progressRunnable?.let { handler.removeCallbacks(it) }
        playbackRunnable?.let { handler.removeCallbacks(it) }
        
        mediaPlayer?.apply {
            try {
                if (isPlaying) pause()
                seekTo(0)
            } catch (e: Exception) {}
        }

        statusText.text = getString(R.string.stopped)
        stopBtn.isEnabled = false
        startBtn.isEnabled = earphonesConfirmed && audioReady
        hidePlayer()
    }

    private fun onPlaybackComplete() {
        progressRunnable?.let { handler.removeCallbacks(it) }
        statusText.text = getString(R.string.finished)
        stopBtn.isEnabled = false
        startBtn.isEnabled = earphonesConfirmed && audioReady
        hidePlayer()
    }

    private fun showPlayer() {
        playerLayout.visibility = View.VISIBLE
    }

    private fun hidePlayer() {
        playerLayout.visibility = View.GONE
        val params = progressFill.layoutParams
        params.width = 0
        progressFill.layoutParams = params
        currentTimeText.text = "0:00"
        remainingTimeText.text = "-0:00"
    }

    private fun formatSeconds(totalSecs: Long): String {
        val m = totalSecs / 60
        val s = totalSecs % 60
        return if (m > 0) "${m}m ${s}s" else "${s}s"
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
        countdownRunnable?.let { handler.removeCallbacks(it) }
        progressRunnable?.let { handler.removeCallbacks(it) }
        playbackRunnable?.let { handler.removeCallbacks(it) }
        fetchRunnable?.let { handler.removeCallbacks(it) }
        mediaPlayer?.release()
        mediaPlayer = null
        try { unregisterReceiver(headsetReceiver) } catch (_: Exception) {}
    }
}
