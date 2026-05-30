package org.tan.cdntest

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.media.MediaPlayer
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper

import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.MediaController
import android.widget.ProgressBar
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import android.widget.VideoView
import androidx.appcompat.app.AppCompatActivity

class PreviewPlayerActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_URL = "url"
        const val EXTRA_TYPE = "type"
        const val EXTRA_FILE_NAME = "file_name"
        const val EXTRA_IS_M3U8 = "is_m3u8"

        fun start(context: Context, url: String, type: String, fileName: String, isM3u8: Boolean = false) {
            val intent = Intent(context, PreviewPlayerActivity::class.java).apply {
                putExtra(EXTRA_URL, url)
                putExtra(EXTRA_TYPE, type)
                putExtra(EXTRA_FILE_NAME, fileName)
                putExtra(EXTRA_IS_M3U8, isM3u8)
            }
            context.startActivity(intent)
        }
    }

    private lateinit var videoView: VideoView
    private lateinit var layoutAudio: View
    private lateinit var ivAudioCover: ImageView
    private lateinit var tvAudioTitle: TextView
    private lateinit var layoutTop: View
    private lateinit var layoutBottom: View
    private lateinit var tvTitle: TextView
    private lateinit var btnPlayPause: ImageButton
    private lateinit var seekBar: SeekBar
    private lateinit var tvCurrentTime: TextView
    private lateinit var tvTotalTime: TextView
    private lateinit var loadingIndicator: ProgressBar

    private var mediaPlayer: MediaPlayer? = null
    private var audioManager: AudioManager? = null
    private val handler = Handler(Looper.getMainLooper())
    private var isPrepared = false
    private var controlsVisible = true
    private var isAudio = false

    private lateinit var url: String
    private lateinit var type: String
    private lateinit var fileName: String

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.insetsController?.let {
                it.hide(android.view.WindowInsets.Type.statusBars() or android.view.WindowInsets.Type.navigationBars())
                it.systemBarsBehavior = android.view.WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        } else {
            @Suppress("DEPRECATION")
            window.setFlags(
                WindowManager.LayoutParams.FLAG_FULLSCREEN,
                WindowManager.LayoutParams.FLAG_FULLSCREEN
            )
        }

        setContentView(R.layout.activity_preview_player)

        url = intent.getStringExtra(EXTRA_URL) ?: run { finish(); return }
        type = intent.getStringExtra(EXTRA_TYPE) ?: "video"
        fileName = intent.getStringExtra(EXTRA_FILE_NAME) ?: "未知"
        isAudio = type == "audio"

        initViews()
        setupControls()
        setupGestures()

        if (isAudio) {
            setupAudioPlayer()
        } else {
            setupVideoPlayer()
        }
    }

    private fun initViews() {
        videoView = findViewById(R.id.videoView)
        layoutAudio = findViewById(R.id.layoutAudio)
        ivAudioCover = findViewById(R.id.ivAudioCover)
        tvAudioTitle = findViewById(R.id.tvAudioTitle)
        layoutTop = findViewById(R.id.layoutTop)
        layoutBottom = findViewById(R.id.layoutBottom)
        tvTitle = findViewById(R.id.tvTitle)
        btnPlayPause = findViewById(R.id.btnPlayPause)
        seekBar = findViewById(R.id.seekBar)
        tvCurrentTime = findViewById(R.id.tvCurrentTime)
        tvTotalTime = findViewById(R.id.tvTotalTime)
        loadingIndicator = findViewById(R.id.loadingIndicator)

        tvTitle.text = fileName
        audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
    }

    private fun setupControls() {
        findViewById<View>(R.id.btnBack).setOnClickListener { finish() }
        findViewById<View>(R.id.btnDownload).setOnClickListener {
            val mimeType = if (isAudio) "audio/*" else "video/*"
            val cleanName = fileName.split("?")[0].split("#")[0]
            val destFile = java.io.File(DownloadHelper.getDownloadDir(this), cleanName)
            DownloadEngine.enqueue(this, url, cleanName, destFile.absolutePath, mimeType)
            Toast.makeText(this, "已加入下载: $cleanName", Toast.LENGTH_SHORT).show()
        }

        btnPlayPause.setOnClickListener {
            if (isAudio) {
                toggleAudioPlayPause()
            } else {
                toggleVideoPlayPause()
            }
        }

        seekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    if (isAudio) {
                        mediaPlayer?.seekTo(progress)
                    } else {
                        videoView.seekTo(progress)
                    }
                }
            }
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {}
        })

        // Tap to toggle controls
        videoView.setOnClickListener { toggleControls() }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun setupGestures() {
        val gestureDetector = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                toggleControls()
                return true
            }

            override fun onDoubleTap(e: MotionEvent): Boolean {
                if (!isAudio) {
                    toggleVideoPlayPause()
                }
                return true
            }
        })

        videoView.setOnTouchListener { _, event ->
            gestureDetector.onTouchEvent(event)
            true
        }
    }

    private fun setupVideoPlayer() {
        // Read player kernel preference (EXO/IJK)
        // When ExoPlayer/IJKPlayer libraries are integrated, branch here:
        // val kernel = DownloadHelper.getPlayerKernel(this)
        // when (kernel) { "exo" -> setupExoPlayer(); "ijk" -> setupIjkPlayer() }
        videoView.visibility = View.VISIBLE
        layoutAudio.visibility = View.GONE

        val mediaController = MediaController(this)
        mediaController.setAnchorView(videoView)
        videoView.setMediaController(mediaController)

        loadingIndicator.visibility = View.VISIBLE

        videoView.setVideoURI(Uri.parse(url))
        videoView.setOnPreparedListener { mp ->
            isPrepared = true
            loadingIndicator.visibility = View.GONE
            seekBar.max = mp.duration
            tvTotalTime.text = formatTime(mp.duration)
            mp.start()
            btnPlayPause.setImageResource(android.R.drawable.ic_media_pause)
            startProgressUpdate()
        }

        videoView.setOnCompletionListener {
            btnPlayPause.setImageResource(android.R.drawable.ic_media_play)
            seekBar.progress = 0
            tvCurrentTime.text = formatTime(0)
            handler.removeCallbacksAndMessages(null)
        }

        videoView.setOnErrorListener { _, _, _ ->
            loadingIndicator.visibility = View.GONE
            Toast.makeText(this, "播放失败", Toast.LENGTH_SHORT).show()
            true
        }
    }

    private fun setupAudioPlayer() {
        videoView.visibility = View.GONE
        layoutAudio.visibility = View.VISIBLE
        tvAudioTitle.text = fileName

        loadingIndicator.visibility = View.VISIBLE

        mediaPlayer = MediaPlayer().apply {
            setDataSource(this@PreviewPlayerActivity, Uri.parse(url))
            prepareAsync()

            setOnPreparedListener { mp ->
                isPrepared = true
                loadingIndicator.visibility = View.GONE
                seekBar.max = mp.duration
                tvTotalTime.text = formatTime(mp.duration)
                mp.start()
                btnPlayPause.setImageResource(android.R.drawable.ic_media_pause)
                startProgressUpdate()
            }

            setOnCompletionListener {
                btnPlayPause.setImageResource(android.R.drawable.ic_media_play)
                seekBar.progress = 0
                tvCurrentTime.text = formatTime(0)
                handler.removeCallbacksAndMessages(null)
            }

            setOnErrorListener { _, _, _ ->
                loadingIndicator.visibility = View.GONE
                Toast.makeText(this@PreviewPlayerActivity, "播放失败", Toast.LENGTH_SHORT).show()
                true
            }
        }
    }

    private fun toggleVideoPlayPause() {
        if (!isPrepared) return
        if (videoView.isPlaying) {
            videoView.pause()
            btnPlayPause.setImageResource(android.R.drawable.ic_media_play)
            handler.removeCallbacksAndMessages(null)
        } else {
            videoView.start()
            btnPlayPause.setImageResource(android.R.drawable.ic_media_pause)
            startProgressUpdate()
        }
    }

    private fun toggleAudioPlayPause() {
        val mp = mediaPlayer ?: return
        if (!isPrepared) return
        if (mp.isPlaying) {
            mp.pause()
            btnPlayPause.setImageResource(android.R.drawable.ic_media_play)
            handler.removeCallbacksAndMessages(null)
        } else {
            mp.start()
            btnPlayPause.setImageResource(android.R.drawable.ic_media_pause)
            startProgressUpdate()
        }
    }

    private fun startProgressUpdate() {
        val updateRunnable = object : Runnable {
            override fun run() {
                val current = if (isAudio) mediaPlayer?.currentPosition else videoView.currentPosition
                if (current != null) {
                    seekBar.progress = current
                    tvCurrentTime.text = formatTime(current)
                }
                if (isPrepared) {
                    handler.postDelayed(this, 500)
                }
            }
        }
        handler.post(updateRunnable)
    }

    private fun toggleControls() {
        controlsVisible = !controlsVisible
        val visibility = if (controlsVisible) View.VISIBLE else View.GONE
        layoutTop.animate().alpha(if (controlsVisible) 1f else 0f).setDuration(200).start()
        layoutBottom.animate().alpha(if (controlsVisible) 1f else 0f).setDuration(200).start()
        layoutTop.visibility = visibility
        layoutBottom.visibility = visibility
    }

    private fun formatTime(ms: Int): String {
        val totalSec = ms / 1000
        val h = totalSec / 3600
        val m = (totalSec % 3600) / 60
        val s = totalSec % 60
        return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%02d:%02d".format(m, s)
    }

    override fun onPause() {
        super.onPause()
        if (isAudio) {
            mediaPlayer?.pause()
        } else {
            videoView.pause()
        }
        btnPlayPause.setImageResource(android.R.drawable.ic_media_play)
        handler.removeCallbacksAndMessages(null)
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        mediaPlayer?.release()
        mediaPlayer = null
        super.onDestroy()
    }
}
