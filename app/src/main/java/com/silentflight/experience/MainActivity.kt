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
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
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

    private lateinit var audioManager: AudioManager
    private var mediaPlayer: MediaPlayer? = null
    private val handler = Handler(Looper.getMainLooper())
    private val client = OkHttpClient()
    private lateinit var prefs: SharedPreferences

    private var earphonesConfirmed = false
    private var audioReady = false
    private var countdownRunnable: Runnable? = null
    private var progressRunnable: Runnable? = null
    private var playbackRunnable: Runnable? = null
    private var fetchRunnable: Runnable? = null

    private val configUrl = "https://raw.githubusercontent.com/Adarshtulsyan/Inflight-audio-app/main/config.json"

    @Volatile
    private var currentStartTime: Long = 0L

    private val defaultStartTime: Long by lazy {
        Calendar.getInstance(TimeZone.getTimeZone("Asia/Kolkata")).apply {
            set(2026, Calendar.APRIL, 21, 20, 45, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis
    }

    private lateinit var earphoneRow: LinearLayout
    private lateinit var earphoneDot: View
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
    private lateinit var debugLogText: TextView

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

        earphoneRow     = findViewById(R.id.earphoneRow)
        earphoneDot     = findViewById(R.id.earphoneDot)
        earphoneText    = findViewById(R.id.earphoneText)
        confirmBtn      = findViewById(R.id.confirmEarphonesBtn)
        startBtn        = findViewById(R.id.startBtn)
        stopBtn         = findViewById(R.id.stopBtn)
        statusText      = findViewById(R.id.statusText)
        playerLayout    = findViewById(R.id.playerLayout)
        progressTrack   = findViewById(R.id.progressTrack)
        progressFill    = findViewById(R.id.progressFill)
        currentTimeText = findViewById(R.id.currentTimeText)
        remainingTimeText = findViewById(R.id.remainingTimeText)
        downloadPrompt  = findViewById(R.id.downloadPrompt)
        
        // Setup a simple debug log view if it doesn't exist in XML yet
        debugLogText = findViewById(R.id.debugLogText)
        debugLogText.setBackgroundColor(0xFF000000.toInt()) // Solid Black background
        debugLogText.setTextColor(0xFF00FF00.toInt())       // Bright Green text (Matrix style)

        addLog("V2.3 READY. Waiting for sync...")

        setupEarphones()
        setupMediaPlayer()
        setupButtons()

        @Suppress("DEPRECATION")
        registerReceiver(headsetReceiver, IntentFilter(AudioManager.ACTION_HEADSET_PLUG))

        startConfigPolling()
    }

    private fun addLog(message: String) {
        handler.post {
            val time = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(java.util.Date())
            val newLog = "[$time] $message\n${debugLogText.text}"
            debugLogText.text = newLog.take(500) // Keep last 500 chars
            Log.d("InflightApp", message)
        }
    }

    private fun startConfigPolling() {
        val pollTask = object : Runnable {
            override fun run() {
                fetchRemoteConfig()
                handler.postDelayed(this, 15 * 1000) // Poll every 15 seconds for testing
            }
        }
        fetchRunnable = pollTask
        handler.post(pollTask)
    }

    private fun fetchRemoteConfig() {
        // We will try to fetch with a very aggressive cache buster
        val timestamp = System.currentTimeMillis()
        val urlWithCacheBuster = "$configUrl?nocache=$timestamp"
        
        val request = Request.Builder()
            .url(urlWithCacheBuster)
            .header("Cache-Control", "no-cache, no-store, must-revalidate")
            .build()

        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                addLog("Net Error: ${e.message}")
            }

            override fun onResponse(call: Call, response: Response) {
                val body = response.body?.string()
                
                if (!response.isSuccessful || body == null) {
                    addLog("Error ${response.code}")
                    return
                }

                try {
                    val json = JSONObject(body)
                    val timeStr = json.getString("startTime")
                    
                    // Critical: Parse as IST
                    val sdf = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.getDefault())
                    sdf.timeZone = TimeZone.getTimeZone("Asia/Kolkata")
                    val date = sdf.parse(timeStr) ?: return
                    val newTime = date.time

                    handler.post {
                        val oldTime = currentStartTime
                        // Only log and update if it's actually different to reduce spam
                        if (oldTime != newTime) {
                            addLog("FOUND NEW TIME: $timeStr")
                            currentStartTime = newTime
                            prefs.edit().putLong("start_time", newTime).apply()
                            
                            if (stopBtn.isEnabled || mediaPlayer?.isPlaying == true) {
                                addLog("Jumping to $timeStr")
                                schedulePlayback()
                            }
                        } else {
                            // This confirms the app is successfully reaching the file
                            addLog("Live: $timeStr")
                        }
                    }
                } catch (e: Exception) {
                    addLog("JSON Error: ${e.message}")
                }
            }
        })
    }

    private fun setupEarphones() {
        earphoneText.text = getString(R.string.earphone_prompt)
        confirmBtn.visibility = View.VISIBLE
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
            earphoneRow.setBackgroundResource(R.drawable.earphone_row_bg_confirmed)
            earphoneDot.setBackgroundResource(R.drawable.dot_confirmed)
            earphoneText.text = getString(R.string.earphone_confirmed)
            earphoneText.setTextColor(ContextCompat.getColor(this, R.color.earphone_text_confirmed))
            confirmBtn.visibility = View.GONE
            updateStartButton()
        }

        startBtn.setOnClickListener {
            startBtn.isEnabled = false
            stopBtn.isEnabled = true
            addLog("User started playback manually")
            schedulePlayback()
        }

        stopBtn.setOnClickListener {
            addLog("User stopped playback")
            stopPlayback()
        }
    }

    private fun schedulePlayback() {
        countdownRunnable?.let { handler.removeCallbacks(it) }
        progressRunnable?.let { handler.removeCallbacks(it) }
        playbackRunnable?.let { handler.removeCallbacks(it) }
        
        try {
            mediaPlayer?.let { if (it.isPlaying) it.pause() }
        } catch (e: Exception) { }

        val now = System.currentTimeMillis()
        val delay = currentStartTime - now

        if (delay > 0) {
            var remaining = (delay / 1000)
            val tick = object : Runnable {
                override fun run() {
                    statusText.text = "⏳ Starts in ${remaining}s"
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
                    statusText.text = "▶️ Playing"
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
                statusText.text = "🏁 Session has finished"
                addLog("Sync error: Time is beyond audio length")
                stopPlayback()
            } else {
                val msLate = msLateLong.toInt().coerceAtLeast(0)
                addLog("Syncing: Seeking to $msLate ms")
                mediaPlayer?.let {
                    it.seekTo(msLate)
                    it.start()
                    statusText.text = "▶️ Joined in sync"
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
        countdownRunnable = null
        progressRunnable = null
        playbackRunnable = null

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
        addLog("Playback finished naturally")
        progressRunnable?.let { handler.removeCallbacks(it) }
        progressRunnable = null
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
