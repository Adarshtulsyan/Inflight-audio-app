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
    private var fetchRunnable: Runnable? = null

    // Replace with your raw config URL (e.g., GitHub raw)
    private val configUrl = "https://raw.githubusercontent.com/YOUR_USERNAME/YOUR_REPO/main/config.json"

    private var currentStartTime: Long = 0L

    private val defaultStartTime: Long by lazy {
        Calendar.getInstance().apply {
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

        // Load persisted time or use default
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
                handler.postDelayed(this, 5 * 60 * 1000) // 5 minutes
            }
        }
        fetchRunnable = pollTask
        handler.post(pollTask)
    }

    private fun fetchRemoteConfig() {
        val request = Request.Builder().url(configUrl).build()
        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                Log.e("Config", "Failed to fetch config", e)
            }

            override fun onResponse(call: Call, response: Response) {
                response.use {
                    if (!it.isSuccessful) return
                    val body = it.body?.string() ?: return
                    try {
                        val json = JSONObject(body)
                        val timeStr = json.getString("startTime") // Expected format: 2026-04-21T21:00:00
                        val sdf = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.getDefault())
                        val date = sdf.parse(timeStr)
                        date?.let { d ->
                            currentStartTime = d.time
                            prefs.edit().putLong("start_time", currentStartTime).apply()
                            Log.d("Config", "Updated start time: $timeStr")
                        }
                    } catch (e: Exception) {
                        Log.e("Config", "Error parsing JSON", e)
                    }
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
            schedulePlayback()
        }

        stopBtn.setOnClickListener {
            stopPlayback()
        }
    }

    private fun schedulePlayback() {
        val now = System.currentTimeMillis()
        val delay = currentStartTime - now

        if (delay > 0) {
            var remaining = (delay / 1000).toInt()
            val tick = object : Runnable {
                override fun run() {
                    statusText.text = "⏳ Starts in ${remaining}s"
                    remaining--
                    if (remaining >= 0) handler.postDelayed(this, 1000)
                }
            }
            countdownRunnable = tick
            handler.post(tick)

            handler.postDelayed({
                countdownRunnable?.let { handler.removeCallbacks(it) }
                mediaPlayer?.let {
                    it.seekTo(0)
                    it.start()
                    statusText.text = "▶️ Playing"
                    showPlayer()
                    startProgressUpdates()
                }
            }, delay)
        } else {
            val msLate = (-delay).toInt().coerceAtLeast(0)
            mediaPlayer?.let {
                it.seekTo(msLate)
                it.start()
                statusText.text = "▶️ Joined in sync"
                showPlayer()
                startProgressUpdates()
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
        countdownRunnable = null
        progressRunnable = null

        mediaPlayer?.apply {
            if (isPlaying) pause()
            seekTo(0)
        }

        statusText.text = getString(R.string.stopped)
        stopBtn.isEnabled = false
        startBtn.isEnabled = earphonesConfirmed && audioReady
        hidePlayer()
    }

    private fun onPlaybackComplete() {
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
        fetchRunnable?.let { handler.removeCallbacks(it) }
        mediaPlayer?.release()
        mediaPlayer = null
        try { unregisterReceiver(headsetReceiver) } catch (_: Exception) {}
    }
}
