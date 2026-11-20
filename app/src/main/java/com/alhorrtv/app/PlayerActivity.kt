package com.alhorrtv.app

import android.app.Dialog
import android.app.UiModeManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.StateListDrawable
import android.media.AudioManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.TextUtils
import android.text.TextWatcher
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.WindowManager
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.common.VideoSize
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import androidx.media3.ui.TimeBar
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import java.util.Locale
import java.util.concurrent.Executors

/**
 * VideoPlayer screen aligned 1:1 with Web VideoPlayer.tsx on top of Media3 ExoPlayer.
 * Preserves full D-pad TV navigation + touch controls.
 */
@androidx.annotation.OptIn(UnstableApi::class)
class PlayerActivity : FullscreenActivity() {
    override val followsTheme: Boolean get() = false
    override val usesTvCanvas: Boolean get() = false
    override val restoresFocus: Boolean get() = false

    private val executor = Executors.newSingleThreadExecutor()
    private val ui = Handler(Looper.getMainLooper())
    private var player: ExoPlayer? = null
    private lateinit var trackSelector: DefaultTrackSelector
    private lateinit var repository: XtreamRepository
    private lateinit var playerView: PlayerView
    private lateinit var bufferingSpinner: ProgressBar

    // views
    private lateinit var statusBanner: TextView
    private lateinit var controlsRoot: View
    private lateinit var topBar: View
    private lateinit var lowerBar: View
    private lateinit var episodesPanel: LinearLayout
    private lateinit var episodesRow: LinearLayout
    private lateinit var episodesScroll: HorizontalScrollView
    private lateinit var episodesPanelTitle: TextView
    private lateinit var actionBar: View
    private lateinit var timeline: View
    private lateinit var rewindButton: ImageButton
    private lateinit var forwardButton: ImageButton
    private lateinit var playPauseButton: ImageButton
    private lateinit var prevChannelView: TextView
    private lateinit var channelListView: TextView
    private lateinit var nextChannelView: TextView
    private lateinit var prevEpisodeButton: ImageButton
    private lateinit var nextEpisodeButton: ImageButton
    private lateinit var nextEpisodeCard: View
    private lateinit var listButton: LinearLayout
    private lateinit var listButtonText: TextView
    private lateinit var listButtonIcon: ImageView
    private lateinit var backButton: ImageButton
    private lateinit var infoButton: ImageButton
    private lateinit var headerTitle: TextView
    private lateinit var headerSubtitle: TextView
    private lateinit var poster: ImageView
    private lateinit var liveBadge: LinearLayout
    private lateinit var audioManager: AudioManager
    private lateinit var volumeSeek: SeekBar
    private lateinit var muteButton: ImageButton
    private lateinit var repeatButton: View
    private lateinit var repeatButtonIcon: ImageView
    private var mutedByButton = false
    private var preMuteVolume = 0

    // intent state
    private var server = ""; private var username = ""; private var password = ""
    private var categoryId = ""; private var currentId = ""; private var currentName = ""
    private var markKind = ""; private var markId = ""
    private var initialUrl = ""
    private var imageUrl = ""
    private var seriesId = ""; private var seriesName = ""; private var currentEpisodeId = ""

    // playback state
    private var episodes: List<EpisodeEntity> = emptyList()
    private var epIndex = -1
    private var resizeModeIndex = 0
    private var repeatEnabled = false
    private var qualityHeight = 0
    private var completed = false
    private var retryCount = 0
    private var lastKnownPosition = 0L
    private var shouldResumeOnStart = true

    // controller state
    private var controlsVisible = true
    private var pickerOpen = false
    private var scrubbing = false
    private var episodesPanelOpen = false
    private var categoryLabel = ""

    private val isLiveContent: Boolean get() = markKind == Kinds.LIVE
    private val isSeries: Boolean get() = seriesId.isNotBlank()
    private val isTv: Boolean by lazy {
        (getSystemService(UI_MODE_SERVICE) as? UiModeManager)?.currentModeType == Configuration.UI_MODE_TYPE_TELEVISION ||
            packageManager.hasSystemFeature("android.software.leanback")
    }

    private val resizeModes = intArrayOf(
        AspectRatioFrameLayout.RESIZE_MODE_FIT,
        AspectRatioFrameLayout.RESIZE_MODE_ZOOM,
        AspectRatioFrameLayout.RESIZE_MODE_FILL
    )
    private val resizeLabels = arrayOf("ملاءمة (Fit)", "تكبير (Zoom)", "ملء الشاشة (Fill)")
    private val resizeShort = arrayOf("Fit", "Zoom", "Fill")

    private val hideRunnable = Runnable { if (canAutoHide()) hideControls() }
    private val liveRetryRunnable = Runnable {
        if (!isFinishing && !isDestroyed) {
            player?.prepare()
            player?.playWhenReady = true
        }
    }
    private val hideStatusRunnable = Runnable {
        statusBanner.animate().alpha(0f).setDuration(250).withEndAction { statusBanner.visibility = View.GONE }.start()
    }

    private fun bg(task: () -> Unit) { try { executor.execute(task) } catch (_: Exception) { } }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_player)
        applyImmersiveFullscreen()
        server = intent.getStringExtra("server").orEmpty()
        username = intent.getStringExtra("username").orEmpty()
        password = intent.getStringExtra("password").orEmpty()
        categoryId = intent.getStringExtra("category_id").orEmpty()
        currentId = intent.getStringExtra("content_id").orEmpty()
        currentName = intent.getStringExtra("title") ?: "ALHUR TV"
        markKind = intent.getStringExtra("mark_kind").orEmpty()
        markId = intent.getStringExtra("mark_id").orEmpty()
        initialUrl = intent.getStringExtra("url").orEmpty()
        imageUrl = intent.getStringExtra("image").orEmpty()
        seriesId = intent.getStringExtra("series_id").orEmpty()
        seriesName = intent.getStringExtra("series_name").orEmpty()
        currentEpisodeId = intent.getStringExtra("episode_id").orEmpty()
        if (isSeries && imageUrl.isBlank()) imageUrl = intent.getStringExtra("series_image").orEmpty()
        repository = XtreamRepository(this, server, username, password)

        if (initialUrl.isBlank() || !(initialUrl.startsWith("https://") || initialUrl.startsWith("http://"))) {
            Toast.makeText(this, "رابط غير صالح. تحقق من الرابط وصلاحية الاشتراك.", Toast.LENGTH_LONG).show()
            finish(); return
        }

        bindViews()
        setupControls()
        applyContentLayout()
        updateHeader()

        if (isLiveContent && categoryId.isNotBlank()) bg {
            val name = repository.categoryName("get_live_streams", categoryId)
            runOnUiThread { categoryLabel = name; updateHeader() }
        }
        if (isSeries) loadSeriesEpisodes()

        if (markKind == "get_vod_streams" && markId.isNotBlank()) {
            bg {
                val saved = repository.mark(markKind, markId)?.positionMs ?: 0L
                runOnUiThread {
                    if (isFinishing || isDestroyed || player != null) return@runOnUiThread
                    if (saved > 15_000L) {
                        createPlayer(initialUrl, saved, true)
                        showStatus("تم استئناف التشغيل من ${formatTime(saved)}")
                    } else createPlayer(initialUrl, 0L, true)
                }
            }
        } else if (isSeries) {
            val point = ResumeStore.seriesPoint(this, seriesId)
            val saved = if (point != null && point.episodeId == currentEpisodeId) point.positionMs else 0L
            if (saved > 15_000L) {
                createPlayer(initialUrl, saved, true)
                showStatus("تم استئناف التشغيل من ${formatTime(saved)}")
            } else createPlayer(initialUrl, 0L, true)
        } else createPlayer(initialUrl, 0L, true)

        playerView.post { playerView.showController(); showControls(requestFocus = true) }
    }

    private fun bindViews() {
        playerView = findViewById(R.id.playerView)
        playerView.controllerShowTimeoutMs = 0
        playerView.controllerHideOnTouch = false
        bufferingSpinner = findViewById(R.id.playerBuffering)
        statusBanner = findViewById(R.id.playerTitle)
        controlsRoot = findViewById(R.id.controlsRoot)
        topBar = findViewById(R.id.topBar)
        lowerBar = findViewById(R.id.lowerControlBar)
        episodesPanel = findViewById(R.id.episodesPanel)
        episodesRow = findViewById(R.id.episodesRow)
        episodesScroll = findViewById(R.id.episodesScroll)
        episodesPanelTitle = findViewById(R.id.episodesPanelTitle)
        actionBar = findViewById(R.id.playerActionBar)
        timeline = findViewById(R.id.videoTimeline)
        rewindButton = findViewById(R.id.rewindButton)
        forwardButton = findViewById(R.id.forwardButton)
        playPauseButton = findViewById(R.id.playPauseButton)
        prevChannelView = findViewById(R.id.previousChannel)
        channelListView = findViewById(R.id.channelListButton)
        nextChannelView = findViewById(R.id.nextChannel)
        prevEpisodeButton = findViewById(R.id.prevEpisodeButton)
        nextEpisodeButton = findViewById(R.id.nextEpisodeButton)
        nextEpisodeCard = findViewById(R.id.nextEpisodeCard)
        listButton = findViewById(R.id.listButton)
        listButtonText = findViewById(R.id.listButtonText)
        listButtonIcon = findViewById(R.id.listButtonIcon)
        backButton = findViewById(R.id.playerBackButton)
        infoButton = findViewById(R.id.infoButton)
        headerTitle = findViewById(R.id.playerHeaderTitle)
        headerSubtitle = findViewById(R.id.playerHeaderSubtitle)
        poster = findViewById(R.id.playerPoster)
        liveBadge = findViewById(R.id.liveBadge)
        volumeSeek = findViewById(R.id.volumeSeek)
        muteButton = findViewById(R.id.muteButton)
        repeatButton = findViewById(R.id.repeatButton)
        repeatButtonIcon = findViewById(R.id.repeatButtonIcon)
        audioManager = getSystemService(AUDIO_SERVICE) as AudioManager
    }

    private fun attachCircleFocusTint(btn: ImageButton) {
        btn.setOnFocusChangeListener { _, focused ->
            btn.setColorFilter(if (focused) Color.parseColor("#001018") else Color.WHITE)
        }
    }

    private fun setupControls() {
        playerView.setOnClickListener { if (controlsVisible) hideControls() else showControls() }
        controlsRoot.setOnClickListener { hideControls() }
        controlsRoot.isFocusable = false

        liveBadge.background = roundBg(0x33FF3131, 6)
        episodesPanel.background = roundBg(0xF2131722.toInt(), 16, UI.STROKE, 1)
        poster.clipRounded(dpf(8))
        findViewById<View>(R.id.nextEpisodeThumb).clipRounded(dpf(6))

        backButton.setOnClickListener { finish() }
        infoButton.setOnClickListener { showInfoDialog() }
        attachCircleFocusTint(backButton)
        attachCircleFocusTint(infoButton)
        attachCircleFocusTint(prevEpisodeButton)
        attachCircleFocusTint(rewindButton)
        attachCircleFocusTint(forwardButton)
        attachCircleFocusTint(nextEpisodeButton)

        listButton.setOnFocusChangeListener { _, focused ->
            val c = if (focused) Color.parseColor("#001018") else Color.WHITE
            listButtonText.setTextColor(c)
            listButtonIcon.setColorFilter(c)
        }
        listButton.setOnClickListener { if (isLiveContent) showChannelList() else toggleEpisodesPanel() }
        findViewById<View>(R.id.episodesCloseButton).setOnClickListener { closeEpisodesPanel() }

        playPauseButton.setOnClickListener { togglePlayPause(); showControls() }

        rewindButton.setOnClickListener { seekBy(-10_000L); showControls() }
        forwardButton.setOnClickListener { seekBy(10_000L); showControls() }
        prevEpisodeButton.setOnClickListener { if (epIndex > 0) playEpisode(epIndex - 1) }
        nextEpisodeButton.setOnClickListener { if (epIndex in 0 until episodes.size - 1) playEpisode(epIndex + 1) }
        nextEpisodeCard.setOnClickListener { if (epIndex in 0 until episodes.size - 1) playEpisode(epIndex + 1) }

        prevChannelView.setOnFocusChangeListener { v, focused ->
            v.animate().scaleX(if (focused) 1.05f else 1f).scaleY(if (focused) 1.05f else 1f).setDuration(100).start()
            prevChannelView.setTextColor(Color.WHITE)
        }
        nextChannelView.setOnFocusChangeListener { v, focused ->
            v.animate().scaleX(if (focused) 1.05f else 1f).scaleY(if (focused) 1.05f else 1f).setDuration(100).start()
            nextChannelView.setTextColor(Color.WHITE)
        }
        channelListView.setOnFocusChangeListener { v, focused ->
            v.animate().scaleX(if (focused) 1.05f else 1f).scaleY(if (focused) 1.05f else 1f).setDuration(100).start()
            channelListView.setTextColor(if (focused) Color.parseColor("#001018") else Color.parseColor("#7BE6FF"))
        }
        prevChannelView.setOnClickListener { changeChannel(-1); showControls() }
        nextChannelView.setOnClickListener { changeChannel(1); showControls() }
        channelListView.isEnabled = currentId.isNotBlank()
        channelListView.alpha = if (currentId.isNotBlank()) 1f else 0.5f
        channelListView.setOnClickListener { showChannelList() }

        findViewById<View>(R.id.audioButton).setOnClickListener { showAudioDialog() }
        findViewById<View>(R.id.subtitleButton).setOnClickListener { showSubtitleDialog() }
        findViewById<View>(R.id.qualityButton).setOnClickListener { showQualityDialog() }
        findViewById<View>(R.id.speedButton).setOnClickListener { showSpeedDialog() }
        findViewById<View>(R.id.resizeVideoButton).setOnClickListener {
            resizeModeIndex = (resizeModeIndex + 1) % resizeModes.size
            playerView.resizeMode = resizeModes[resizeModeIndex]
            findViewById<TextView>(R.id.resizeVideoButtonValue).text = resizeShort[resizeModeIndex]
            showStatus(resizeLabels[resizeModeIndex])
            showControls()
        }
        repeatButton.setOnClickListener {
            repeatEnabled = !repeatEnabled
            repeatButton.isSelected = repeatEnabled
            repeatButtonIcon.setColorFilter(if (repeatEnabled) UI.NEON else Color.WHITE)
            player?.repeatMode = if (repeatEnabled) Player.REPEAT_MODE_ONE else Player.REPEAT_MODE_OFF
            showStatus(if (repeatEnabled) "تم تفعيل التكرار" else "تم إيقاف التكرار")
            showControls()
        }
        findViewById<TextView>(R.id.resizeVideoButtonValue).text = resizeShort[resizeModeIndex]

        (findViewById<View>(androidx.media3.ui.R.id.exo_progress) as? TimeBar)?.addListener(object : TimeBar.OnScrubListener {
            override fun onScrubStart(timeBar: TimeBar, position: Long) { scrubbing = true; ui.removeCallbacks(hideRunnable) }
            override fun onScrubMove(timeBar: TimeBar, position: Long) = Unit
            override fun onScrubStop(timeBar: TimeBar, position: Long, canceled: Boolean) { scrubbing = false; scheduleHide() }
        })

        volumeSeek.max = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        syncVolume()
        volumeSeek.isFocusable = !isTv
        volumeSeek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, progress, 0)
                    mutedByButton = progress == 0
                    muteButton.setColorFilter(if (progress == 0) Color.parseColor("#FF7B8B") else Color.WHITE)
                    scheduleHide()
                }
            }
            override fun onStartTrackingTouch(seekBar: SeekBar) { ui.removeCallbacks(hideRunnable) }
            override fun onStopTrackingTouch(seekBar: SeekBar) { scheduleHide() }
        })
        muteButton.setOnClickListener {
            val cur = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
            if (cur > 0) {
                preMuteVolume = cur
                audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, 0, 0)
                mutedByButton = true
            } else {
                val restore = if (preMuteVolume > 0) preMuteVolume else (volumeSeek.max / 2).coerceAtLeast(1)
                audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, restore, 0)
                mutedByButton = false
            }
            syncVolume()
            showControls()
        }
        val volumeBox = findViewById<View>(R.id.volumeBox)
        val volumeVisible = resources.configuration.screenWidthDp >= 560
        volumeBox.visibility = if (volumeVisible) View.VISIBLE else View.GONE
        wireFocusOrder(volumeVisible)
    }

    private fun wireFocusOrder(volumeVisible: Boolean) {
        val live = isLiveContent
        val timeBar = findViewById<View>(androidx.media3.ui.R.id.exo_progress)
        val audioBtn = findViewById<View>(R.id.audioButton)
        val subtitleBtn = findViewById<View>(R.id.subtitleButton)
        val qualityBtn = findViewById<View>(R.id.qualityButton)
        val speedBtn = findViewById<View>(R.id.speedButton)
        val resizeBtn = findViewById<View>(R.id.resizeVideoButton)

        backButton.nextFocusDownId = R.id.playPauseButton
        infoButton.nextFocusDownId = R.id.playPauseButton
        listButton.nextFocusDownId = R.id.playPauseButton
        playPauseButton.nextFocusUpId = R.id.playerBackButton

        if (live) {
            playPauseButton.nextFocusDownId = R.id.channelListButton
            prevChannelView.nextFocusUpId = R.id.playPauseButton
            channelListView.nextFocusUpId = R.id.playPauseButton
            nextChannelView.nextFocusUpId = R.id.playPauseButton
            nextChannelView.nextFocusRightId = if (volumeVisible) R.id.muteButton else R.id.audioButton
            muteButton.nextFocusLeftId = R.id.nextChannel
            muteButton.nextFocusRightId = R.id.audioButton
            muteButton.nextFocusUpId = R.id.playPauseButton
            audioBtn.nextFocusLeftId = if (volumeVisible) R.id.muteButton else R.id.nextChannel
            audioBtn.nextFocusUpId = R.id.playPauseButton
            subtitleBtn.nextFocusUpId = R.id.playPauseButton
            qualityBtn.nextFocusUpId = R.id.playPauseButton
            resizeBtn.nextFocusUpId = R.id.playPauseButton
            resizeBtn.nextFocusRightId = R.id.resizeVideoButton
        } else {
            playPauseButton.nextFocusDownId = androidx.media3.ui.R.id.exo_progress
            rewindButton.nextFocusDownId = androidx.media3.ui.R.id.exo_progress
            forwardButton.nextFocusDownId = androidx.media3.ui.R.id.exo_progress
            timeBar.nextFocusUpId = R.id.playPauseButton
            timeBar.nextFocusDownId = R.id.audioButton
            muteButton.nextFocusUpId = androidx.media3.ui.R.id.exo_progress
            muteButton.nextFocusRightId = R.id.audioButton
            audioBtn.nextFocusUpId = androidx.media3.ui.R.id.exo_progress
            audioBtn.nextFocusLeftId = if (volumeVisible) R.id.muteButton else View.NO_ID
            subtitleBtn.nextFocusUpId = androidx.media3.ui.R.id.exo_progress
            qualityBtn.nextFocusUpId = androidx.media3.ui.R.id.exo_progress
            speedBtn.nextFocusUpId = androidx.media3.ui.R.id.exo_progress
            resizeBtn.nextFocusUpId = androidx.media3.ui.R.id.exo_progress
        }
    }

    private fun applyContentLayout() {
        val live = isLiveContent
        timeline.visibility = if (live) View.GONE else View.VISIBLE
        rewindButton.visibility = if (live) View.GONE else View.VISIBLE
        forwardButton.visibility = if (live) View.GONE else View.VISIBLE
        actionBar.visibility = if (live) View.VISIBLE else View.GONE
        findViewById<View>(R.id.speedButton).visibility = if (live) View.GONE else View.VISIBLE
        repeatButton.visibility = if (live) View.GONE else View.VISIBLE
        liveBadge.visibility = if (live) View.VISIBLE else View.GONE
        listButton.visibility = if (live || isSeries) View.VISIBLE else View.GONE
        listButtonText.text = if (live) "القنوات" else "الحلقات"
        listButton.contentDescription = listButtonText.text
        wireFocusOrder(findViewById<View>(R.id.volumeBox).visibility == View.VISIBLE)
    }

    private fun updateHeader() {
        val kind: String
        when {
            isLiveContent -> {
                kind = Kinds.LIVE
                headerTitle.text = currentName
                headerSubtitle.text = categoryLabel.takeIf { it.isNotBlank() && it != categoryId } ?: ""
                headerSubtitle.visibility = if (headerSubtitle.text.isBlank()) View.GONE else View.VISIBLE
            }
            isSeries -> {
                kind = Kinds.SERIES
                headerTitle.text = currentName
                val ep = episodes.getOrNull(epIndex)
                val infoLine = if (ep != null) "${seriesName.ifBlank { "" }}  •  م${seasonNumber(ep)} · ح${episodeNumber(ep)}".trim(' ', '•') else seriesName
                headerSubtitle.text = infoLine
                headerSubtitle.visibility = if (infoLine.isBlank()) View.GONE else View.VISIBLE
            }
            else -> {
                kind = Kinds.MOVIES
                headerTitle.text = currentName
                headerSubtitle.text = ""
                headerSubtitle.visibility = View.GONE
            }
        }
        if (imageUrl.isNotBlank()) {
            poster.visibility = View.VISIBLE
            ImageLoader.bind(this, poster, imageUrl, Color.rgb(35, 35, 55), currentName, kind)
        } else poster.visibility = View.GONE
    }

    // ───────────────────────── controller visibility ─────────────────────────

    private fun canAutoHide(): Boolean =
        controlsVisible && !pickerOpen && !scrubbing && !episodesPanelOpen && player?.isPlaying == true

    private fun scheduleHide() {
        ui.removeCallbacks(hideRunnable)
        if (controlsVisible) ui.postDelayed(hideRunnable, 4500L)
    }

    private fun showControls(requestFocus: Boolean = false) {
        ui.removeCallbacks(hideRunnable)
        if (!controlsVisible) {
            controlsVisible = true
            controlsRoot.animate().cancel()
            controlsRoot.visibility = View.VISIBLE
            controlsRoot.alpha = 0f
            controlsRoot.animate().alpha(1f).setDuration(220).start()
            val shift = resources.displayMetrics.density * 18f
            topBar.translationY = -shift; lowerBar.translationY = shift
            topBar.animate().translationY(0f).setDuration(220).start()
            lowerBar.animate().translationY(0f).setDuration(220).start()
            syncVolume()
            if (requestFocus || isTv) focusPrimary()
        } else if (requestFocus && !hasControlFocus()) focusPrimary()
        scheduleHide()
    }

    private fun hideControls() {
        if (!controlsVisible || pickerOpen || episodesPanelOpen) return
        controlsVisible = false
        ui.removeCallbacks(hideRunnable)
        playerView.requestFocus()
        controlsRoot.animate().cancel()
        controlsRoot.animate().alpha(0f).setDuration(260).withEndAction {
            if (!controlsVisible) controlsRoot.visibility = View.INVISIBLE
        }.start()
    }

    private fun focusPrimary() {
        playPauseButton.requestFocus()
    }

    private fun hasControlFocus(): Boolean = controlsVisible && currentFocus != null && currentFocus !== playerView

    private fun syncVolume() {
        if (!::volumeSeek.isInitialized || !::audioManager.isInitialized) return
        val v = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
        if (volumeSeek.progress != v) volumeSeek.progress = v
        if (::muteButton.isInitialized) {
            muteButton.setColorFilter(if (v == 0 || mutedByButton) Color.parseColor("#FF7B8B") else Color.WHITE)
        }
    }

    private val volumeReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(context: android.content.Context?, intent: android.content.Intent?) { syncVolume() }
    }
    private val volumeObserver = object : android.database.ContentObserver(Handler(Looper.getMainLooper())) {
        override fun onChange(selfChange: Boolean) { syncVolume() }
    }
    private var volumeWatching = false

    private fun startVolumeWatch() {
        if (volumeWatching) return
        volumeWatching = true
        val filter = android.content.IntentFilter("android.media.VOLUME_CHANGED_ACTION")
        try {
            if (android.os.Build.VERSION.SDK_INT >= 33) registerReceiver(volumeReceiver, filter, RECEIVER_NOT_EXPORTED)
            else registerReceiver(volumeReceiver, filter)
        } catch (_: Throwable) {}
        try { contentResolver.registerContentObserver(android.provider.Settings.System.CONTENT_URI, true, volumeObserver) } catch (_: Throwable) {}
        syncVolume()
    }

    private fun stopVolumeWatch() {
        if (!volumeWatching) return
        volumeWatching = false
        try { unregisterReceiver(volumeReceiver) } catch (_: Throwable) {}
        try { contentResolver.unregisterContentObserver(volumeObserver) } catch (_: Throwable) {}
    }

    private fun showStatus(message: String) {
        ui.removeCallbacks(hideStatusRunnable)
        statusBanner.animate().cancel()
        statusBanner.text = message; statusBanner.alpha = 1f; statusBanner.visibility = View.VISIBLE
        ui.postDelayed(hideStatusRunnable, 3200L)
    }

    // ───────────────────────── player ─────────────────────────

    private fun createPlayer(url: String, resumePosition: Long = 0L, autoPlay: Boolean = true) {
        if (url.isBlank()) return
        ui.removeCallbacks(liveRetryRunnable)
        qualityHeight = 0; retryCount = 0; completed = false
        bufferingSpinner.visibility = View.VISIBLE

        val existing = player
        if (existing != null) {
            existing.trackSelectionParameters = existing.trackSelectionParameters.buildUpon()
                .clearOverridesOfType(C.TRACK_TYPE_VIDEO)
                .clearOverridesOfType(C.TRACK_TYPE_AUDIO)
                .clearOverridesOfType(C.TRACK_TYPE_TEXT)
                .build()
            existing.repeatMode = if (repeatEnabled) Player.REPEAT_MODE_ONE else Player.REPEAT_MODE_OFF
            existing.setMediaItem(MediaItem.fromUri(url), resumePosition)
            existing.prepare()
            existing.playWhenReady = autoPlay
            refreshTiles()
            return
        }

        trackSelector = DefaultTrackSelector(this)
        val httpFactory = DefaultHttpDataSource.Factory()
            .setUserAgent("AL-HUR-AL-ASLI/1.0 (Android)")
            .setAllowCrossProtocolRedirects(true)
            .setConnectTimeoutMs(10_000)
            .setReadTimeoutMs(15_000)
        val mediaSourceFactory = DefaultMediaSourceFactory(DefaultDataSource.Factory(this, httpFactory))

        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(
                if (isLiveContent) 10_000 else 20_000,
                if (isLiveContent) 30_000 else 60_000,
                if (isLiveContent) 600 else 1_200,
                if (isLiveContent) 1_500 else 2_500
            )
            .setPrioritizeTimeOverSizeThresholds(true)
            .build()

        val audioAttributes = AudioAttributes.Builder()
            .setUsage(C.USAGE_MEDIA)
            .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
            .build()

        player = ExoPlayer.Builder(this)
            .setTrackSelector(trackSelector)
            .setMediaSourceFactory(mediaSourceFactory)
            .setLoadControl(loadControl)
            .setAudioAttributes(audioAttributes, true)
            .setHandleAudioBecomingNoisy(true)
            .build()
            .also { exo ->
                playerView.player = exo
                exo.repeatMode = if (repeatEnabled) Player.REPEAT_MODE_ONE else Player.REPEAT_MODE_OFF
                exo.addListener(object : Player.Listener {
                    override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) { syncPlayPause() }
                    override fun onIsPlayingChanged(isPlaying: Boolean) {
                        syncPlayPause()
                        setPlaybackScreenOn(isPlaying)
                        if (isPlaying) {
                            bufferingSpinner.visibility = View.GONE
                            scheduleHide()
                        } else {
                            showControls()
                        }
                    }
                    override fun onPlayerError(error: PlaybackException) {
                        bufferingSpinner.visibility = View.GONE
                        handlePlayerError(error)
                    }
                    override fun onPlaybackStateChanged(playbackState: Int) {
                        syncPlayPause()
                        when (playbackState) {
                            Player.STATE_BUFFERING -> bufferingSpinner.visibility = View.VISIBLE
                            Player.STATE_READY -> {
                                bufferingSpinner.visibility = View.GONE
                                retryCount = 0
                                refreshTiles()
                            }
                            Player.STATE_ENDED -> {
                                bufferingSpinner.visibility = View.GONE
                                handleEnded()
                            }
                            Player.STATE_IDLE -> {
                                bufferingSpinner.visibility = View.GONE
                                setPlaybackScreenOn(false)
                            }
                            else -> Unit
                        }
                    }
                    override fun onTracksChanged(tracks: Tracks) { refreshTiles() }
                    override fun onVideoSizeChanged(videoSize: VideoSize) { refreshTiles() }
                    override fun onPlaybackParametersChanged(playbackParameters: PlaybackParameters) { refreshTiles() }
                })
                exo.setMediaItem(MediaItem.fromUri(url), resumePosition)
                exo.prepare()
                exo.playWhenReady = autoPlay
            }
        refreshTiles()
    }

    private fun handleEnded() {
        setPlaybackScreenOn(false)
        when {
            isLiveContent -> { showStatus("انتهى البث أو انقطع المصدر"); showControls() }
            isSeries && epIndex in 0 until episodes.size - 1 -> {
                showStatus("جارٍ تشغيل الحلقة التالية…")
                playEpisode(epIndex + 1)
            }
            else -> {
                completed = true
                if (isSeries) ResumeStore.clearSeries(this, seriesId)
                if (markKind.isNotBlank() && markId.isNotBlank()) bg { repository.saveProgress(markKind, markId, 0L) }
                showControls(requestFocus = true)
            }
        }
    }

    private fun handlePlayerError(error: PlaybackException) {
        setPlaybackScreenOn(false)
        val exo = player ?: return
        if (error.errorCode == PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW) {
            exo.seekToDefaultPosition(); exo.prepare(); return
        }
        val network = error.errorCode in PlaybackException.ERROR_CODE_IO_UNSPECIFIED..PlaybackException.ERROR_CODE_IO_READ_POSITION_OUT_OF_RANGE
        if (isLiveContent && network && retryCount < 2) {
            retryCount++
            showStatus("انقطع البث… إعادة المحاولة ($retryCount/2)")
            ui.removeCallbacks(liveRetryRunnable)
            ui.postDelayed(liveRetryRunnable, 1500L)
            return
        }
        showStatus("تعذر التشغيل")
        showControls()
        showErrorDialog()
    }

    private fun seekBy(deltaMs: Long) {
        val exo = player ?: return
        if (isLiveContent || !exo.isCurrentMediaItemSeekable) return
        val target = (exo.currentPosition + deltaMs).coerceAtLeast(0L)
        val duration = exo.duration.takeIf { it > 0L && it != C.TIME_UNSET }
        exo.seekTo(duration?.let { target.coerceAtMost(it) } ?: target)
    }

    private fun togglePlayPause() {
        val exo = player ?: return
        if (exo.playWhenReady && exo.playbackState != Player.STATE_ENDED) { exo.pause(); syncPlayPause() } else playNow()
    }

    private fun playNow() {
        val exo = player ?: return
        if (exo.playbackState == Player.STATE_ENDED) exo.seekToDefaultPosition()
        if (exo.playbackState == Player.STATE_IDLE) exo.prepare()
        exo.play(); syncPlayPause()
    }

    private fun syncPlayPause() {
        val exo = player ?: return
        val showPlay = !exo.playWhenReady || exo.playbackState == Player.STATE_ENDED
        playPauseButton.setImageResource(if (showPlay) R.drawable.ic_play else R.drawable.ic_pause)
        playPauseButton.contentDescription = if (showPlay) "تشغيل" else "إيقاف مؤقت"
    }

    // ───────────────────────── series ─────────────────────────

    private fun seasonNumber(ep: EpisodeEntity) = ep.seasonKey.filter { it.isDigit() }.toIntOrNull() ?: 1
    private fun episodeNumber(ep: EpisodeEntity) = ep.episodeNumber.filter { it.isDigit() }.toIntOrNull() ?: 1

    private fun loadSeriesEpisodes() = bg {
        try {
            val dao = ContentDatabase.get(this).episodeDao()
            val seasons = dao.seasons(seriesId).sortedWith(compareBy({ it.toIntOrNull() ?: Int.MAX_VALUE }, { it }))
            val all = ArrayList<EpisodeEntity>()
            seasons.forEach { all += dao.page(seriesId, it, 2000, 0) }
            val index = all.indexOfFirst { it.episodeId == currentEpisodeId }
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                episodes = all; epIndex = index
                refreshSeriesUi()
            }
        } catch (_: Exception) { }
    }

    private fun refreshSeriesUi() {
        val ready = episodes.isNotEmpty() && epIndex >= 0
        listButton.visibility = if (isLiveContent || isSeries) View.VISIBLE else View.GONE
        val hasPrev = ready && epIndex > 0
        val hasNext = ready && epIndex < episodes.size - 1
        prevEpisodeButton.visibility = if (hasPrev) View.VISIBLE else View.GONE
        nextEpisodeButton.visibility = if (hasNext) View.VISIBLE else View.GONE
        nextEpisodeCard.visibility = if (hasNext) View.VISIBLE else View.GONE
        if (hasNext) {
            val next = episodes[epIndex + 1]
            val title = next.title.ifBlank { "الموسم ${seasonNumber(next)} · الحلقة ${episodeNumber(next)}" }
            findViewById<TextView>(R.id.nextEpisodeText).text = "الحلقة التالية: $title"
            ImageLoader.bind(this, findViewById(R.id.nextEpisodeThumb), next.imageUrl, Color.rgb(35, 35, 55), next.title, Kinds.SERIES)
        }
        updateHeader()
        if (episodesPanelOpen) buildEpisodesPanel()
    }

    private fun playEpisode(index: Int) {
        val ep = episodes.getOrNull(index) ?: return
        epIndex = index; currentEpisodeId = ep.episodeId
        currentName = ep.title.ifBlank { "الحلقة ${episodeNumber(ep)}" }
        if (ep.imageUrl.isNotBlank()) imageUrl = ep.imageUrl
        initialUrl = repository.mediaUrl("series", ep.episodeId, ep.extension)
        val point = ResumeStore.seriesPoint(this, seriesId)
        val saved = if (point != null && point.episodeId == ep.episodeId && point.positionMs > 15_000L) point.positionMs else 0L
        lastKnownPosition = saved
        createPlayer(initialUrl, saved, true)
        refreshSeriesUi()
        if (episodesPanelOpen) closeEpisodesPanel()
        showControls()
    }

    private fun toggleEpisodesPanel() { if (episodesPanelOpen) closeEpisodesPanel() else openEpisodesPanel() }

    private fun openEpisodesPanel() {
        if (episodes.isEmpty() || epIndex < 0) {
            showStatus("جاري تحميل قائمة الحلقات…")
            return
        }
        episodesPanelOpen = true
        ui.removeCallbacks(hideRunnable)
        buildEpisodesPanel()
        episodesPanel.visibility = View.VISIBLE
    }

    private fun closeEpisodesPanel() {
        episodesPanelOpen = false
        episodesPanel.visibility = View.GONE
        listButton.requestFocus()
        scheduleHide()
    }

    private fun buildEpisodesPanel() {
        val current = episodes.getOrNull(epIndex) ?: return
        episodesPanelTitle.text = "حلقات ${seriesName.ifBlank { currentName }}  •  الموسم ${seasonNumber(current)}"
        episodesRow.removeAllViews()
        val gap = dp(8)
        var selectedCard: View? = null
        episodes.forEachIndexed { index, ep ->
            val active = index == epIndex
            val card = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_VERTICAL
                setBackgroundResource(R.drawable.bg_player_ep_card)
                isFocusable = true; isClickable = true
                isSelected = active
                setPadding(dp(12), dp(8), dp(12), dp(8))
                contentDescription = ep.title.ifBlank { "الحلقة ${episodeNumber(ep)}" }
            }
            card.addView(tv("م${seasonNumber(ep)} · ح${episodeNumber(ep)}", 11f, UI.NEON, true).apply {
                maxLines = 1
            })
            card.addView(tv(ep.title.ifBlank { "الحلقة ${episodeNumber(ep)}" }, 12f, if (active) Color.WHITE else Color.parseColor("#CBD5E1"), true).apply {
                maxLines = 1; ellipsize = TextUtils.TruncateAt.END
            }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(3) })
            card.setOnClickListener { if (active) closeEpisodesPanel() else playEpisode(index) }
            episodesRow.addView(card, LinearLayout.LayoutParams(dp(168), dp(58)).apply { marginEnd = gap })
            if (active) selectedCard = card
        }
        selectedCard?.let { card ->
            card.post {
                episodesScroll.scrollTo((card.left - gap).coerceAtLeast(0), 0)
                if (isTv || hasControlFocus()) card.requestFocus()
            }
        }
    }

    // ───────────────────────── Web-matched Dark Modals ─────────────────────────

    private fun showDarkModal(title: String, widthDp: Int = 420, fill: (LinearLayout, Dialog) -> Unit) {
        val before = currentFocus
        pickerOpen = true
        ui.removeCallbacks(hideRunnable)

        val dialog = Dialog(this, android.R.style.Theme_DeviceDefault_Dialog_NoActionBar)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutDirection = View.LAYOUT_DIRECTION_RTL
            background = roundBg(Color.parseColor("#131722"), 16, UI.STROKE, 1)
            clipRounded(dpf(16))
        }
        val head = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(Color.parseColor("#0F131E"))
            setPadding(dp(18), dp(12), dp(18), dp(12))
        }
        head.addView(tv(title, 14f, Color.WHITE, true), LinearLayout.LayoutParams(0, -2, 1f))
        head.addView(tv("✕", 14f, UI.MUTED, true).apply {
            gravity = Gravity.CENTER
            makeFocusable(1.08f, UI.NEON, 1, 8)
            setOnClickListener { dialog.dismiss() }
        }, LinearLayout.LayoutParams(dp(28), dp(28)))
        root.addView(head)
        root.addView(View(this).apply { setBackgroundColor(UI.STROKE) }, LinearLayout.LayoutParams(-1, dp(1)))

        val body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(12), dp(14), dp(14))
        }
        fill(body, dialog)
        root.addView(body)

        dialog.setOnDismissListener {
            pickerOpen = false
            showControls()
            ui.post { before?.takeIf { it.isShown && it.isFocusable }?.requestFocus() }
        }
        dialog.setContentView(root)
        dialog.show()
        dialog.window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            setLayout(dp(widthDp), WindowManager.LayoutParams.WRAP_CONTENT)
            setDimAmount(0.70f)
            setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        }
    }

    private fun modalOptionRow(label: String, selected: Boolean, onClick: () -> Unit): LinearLayout {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14), dp(10), dp(14), dp(10))
            val idle = if (selected) roundBg(0x3300D2FF, 12, UI.NEON, 1) else roundBg(UI.PANEL2, 12, UI.STROKE, 1)
            val focused = roundBg(0x4D00D2FF, 12, Color.WHITE, 2)
            background = StateListDrawable().apply {
                addState(intArrayOf(android.R.attr.state_focused), focused)
                addState(intArrayOf(android.R.attr.state_pressed), focused)
                addState(intArrayOf(), idle)
            }
            isFocusable = true; isClickable = true
            setOnClickListener { onClick() }
        }
        row.addView(tv(label, 13f, if (selected) Color.WHITE else Color.parseColor("#E2E8F0"), true).apply {
            maxLines = 1; ellipsize = TextUtils.TruncateAt.END
        }, LinearLayout.LayoutParams(0, -2, 1f))
        if (selected) row.addView(tv("✓", 14f, UI.NEON, true))
        return row
    }

    private fun showPicker(title: String, labels: List<String>, checked: Int, onPick: (Int) -> Unit) {
        showDarkModal(title, 380) { body, dialog ->
            val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            val scroll = ScrollView(this).apply { isVerticalScrollBarEnabled = false; addView(list) }
            var focusTarget: View? = null
            labels.forEachIndexed { idx, label ->
                val row = modalOptionRow(label, idx == checked) {
                    dialog.dismiss()
                    onPick(idx)
                }
                list.addView(row, LinearLayout.LayoutParams(-1, -2).apply {
                    if (idx < labels.size - 1) bottomMargin = dp(8)
                })
                if (idx == checked || (checked < 0 && idx == 0)) focusTarget = row
            }
            val maxH = dp(280)
            body.addView(scroll, LinearLayout.LayoutParams(-1, if (labels.size > 5) maxH else -2))
            focusTarget?.post { focusTarget?.requestFocus() }
        }
    }

    // ───────────────────────── tiles / track pickers ─────────────────────────

    private fun trackLabel(format: androidx.media3.common.Format, fallback: String): String {
        format.label?.takeIf { it.isNotBlank() }?.let { return it }
        format.language?.takeIf { it.isNotBlank() && it != "und" }?.let { code ->
            val name = Locale(code).getDisplayLanguage(Locale("ar"))
            return if (name.isNotBlank()) name else code
        }
        return fallback
    }

    private fun selectedLabel(type: Int): String {
        val exo = player ?: return ""
        for (group in exo.currentTracks.groups) {
            if (group.type != type) continue
            for (i in 0 until group.length) if (group.isTrackSelected(i)) return trackLabel(group.getTrackFormat(i), "")
        }
        return ""
    }

    private fun refreshTiles() {
        val exo = player
        val height = exo?.videoSize?.height ?: 0
        findViewById<TextView>(R.id.qualityButtonValue).text = if (height > 0) "${height}p" else "الجودة"
        findViewById<View>(R.id.qualityButton).alpha = if (qualityOptions().size >= 2) 1f else 0.72f

        val speed = exo?.playbackParameters?.speed ?: 1f
        findViewById<TextView>(R.id.speedButtonValue).text =
            if (speed == speed.toInt().toFloat()) "${speed.toInt()}.0x" else "${speed}x"

        val hasText = exo?.currentTracks?.groups?.any { it.type == C.TRACK_TYPE_TEXT && it.length > 0 } == true
        val textOff = exo?.trackSelectionParameters?.disabledTrackTypes?.contains(C.TRACK_TYPE_TEXT) == true
        val subText = if (!hasText || textOff) "الترجمة" else selectedLabel(C.TRACK_TYPE_TEXT).ifBlank { "الترجمة" }
        findViewById<TextView>(R.id.subtitleButtonValue).text = subText
        findViewById<View>(R.id.subtitleButton).alpha = if (hasText) 1f else 0.72f

        val audioCount = exo?.currentTracks?.groups?.count { it.type == C.TRACK_TYPE_AUDIO && it.length > 0 } ?: 0
        val audioName = selectedLabel(C.TRACK_TYPE_AUDIO)
        findViewById<TextView>(R.id.audioButtonValue).text = if (audioName.isNotBlank()) audioName else "الصوت"
        findViewById<View>(R.id.audioButton).alpha = if (audioCount > 0) 1f else 0.72f
    }

    private data class QualityOption(val group: Tracks.Group, val index: Int, val height: Int)

    private fun qualityOptions(): List<QualityOption> {
        val exo = player ?: return emptyList()
        val all = ArrayList<QualityOption>()
        exo.currentTracks.groups.filter { it.type == C.TRACK_TYPE_VIDEO }.forEach { group ->
            for (i in 0 until group.length) if (group.isTrackSupported(i)) {
                val h = group.getTrackFormat(i).height
                if (h > 0) all += QualityOption(group, i, h)
            }
        }
        return all.groupBy { it.height }
            .map { (_, list) -> list.maxByOrNull { it.group.getTrackFormat(it.index).bitrate }!! }
            .sortedByDescending { it.height }
    }

    private fun showQualityDialog() {
        val exo = player ?: return
        val options = qualityOptions()
        if (options.size < 2) {
            val h = exo.videoSize.height
            showDarkModal("جودة البث", 360) { body, dialog ->
                val msg = if (h > 0) "الجودة الحالية: ${h}p (المصدر يوفّر جودة واحدة فقط)." else "تعمل الجودة التلقائية (Auto) حاليًا."
                body.addView(modalOptionRow("تلقائي (Auto)${if (h > 0) " · ${h}p" else ""}", true) { dialog.dismiss() })
                body.addView(tv(msg, 11.5f, UI.MUTED).apply { setPadding(dp(4), dp(8), dp(4), 0) })
            }
            return
        }
        val labels = listOf("تلقائي (Auto)") + options.map { "${it.height}p" }
        val checked = if (qualityHeight == 0) 0 else options.indexOfFirst { it.height == qualityHeight } + 1
        showPicker("جودة البث", labels, checked) { which ->
            val builder = exo.trackSelectionParameters.buildUpon().clearOverridesOfType(C.TRACK_TYPE_VIDEO)
            if (which == 0) qualityHeight = 0
            else {
                val option = options[which - 1]
                qualityHeight = option.height
                builder.setOverrideForType(TrackSelectionOverride(option.group.mediaTrackGroup, listOf(option.index)))
            }
            exo.trackSelectionParameters = builder.build()
            showStatus("الجودة: ${labels[which]}")
        }
    }

    private fun showAudioDialog() {
        val exo = player ?: return
        val entries = ArrayList<String>()
        val choices = ArrayList<Pair<Tracks.Group, Int>>()
        var checked = -1
        exo.currentTracks.groups.filter { it.type == C.TRACK_TYPE_AUDIO && it.length > 0 }.forEach { group ->
            for (index in 0 until group.length) {
                if (!group.isTrackSupported(index)) continue
                if (group.isTrackSelected(index)) checked = choices.size
                entries += trackLabel(group.getTrackFormat(index), "مسار صوت ${choices.size + 1}")
                choices += group to index
            }
        }
        if (choices.isEmpty()) {
            showDarkModal("مسارات الصوت", 360) { body, _ ->
                body.addView(tv("المسار الافتراضي قيد التشغيل.", 12.5f, UI.MUTED).apply {
                    gravity = Gravity.CENTER; setPadding(0, dp(14), 0, dp(14))
                })
            }
            return
        }
        showPicker("مسارات الصوت", entries, checked) { which ->
            val (group, index) = choices[which]
            exo.trackSelectionParameters = exo.trackSelectionParameters.buildUpon()
                .setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, false)
                .setOverrideForType(TrackSelectionOverride(group.mediaTrackGroup, listOf(index)))
                .build()
        }
    }

    private fun showSubtitleDialog() {
        val exo = player ?: return
        val entries = ArrayList<String>().apply { add("إيقاف الترجمة") }
        val choices = ArrayList<Pair<Tracks.Group, Int>>()
        var checked = 0
        val off = exo.trackSelectionParameters.disabledTrackTypes.contains(C.TRACK_TYPE_TEXT)
        exo.currentTracks.groups.filter { it.type == C.TRACK_TYPE_TEXT && it.length > 0 }.forEach { group ->
            for (index in 0 until group.length) {
                if (!group.isTrackSupported(index)) continue
                if (!off && group.isTrackSelected(index)) checked = choices.size + 1
                entries += trackLabel(group.getTrackFormat(index), "ترجمة ${choices.size + 1}")
                choices += group to index
            }
        }
        showPicker("الترجمة", entries, checked) { which ->
            val builder = exo.trackSelectionParameters.buildUpon()
            if (which == 0) builder.setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
            else if (which - 1 in choices.indices) {
                val (group, index) = choices[which - 1]
                builder.setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
                    .setOverrideForType(TrackSelectionOverride(group.mediaTrackGroup, listOf(index)))
            }
            exo.trackSelectionParameters = builder.build()
        }
    }

    private fun showSpeedDialog() {
        val exo = player ?: return
        val values = floatArrayOf(0.5f, 0.75f, 1.0f, 1.25f, 1.5f, 2.0f)
        val labels = values.map { if (it == it.toInt().toFloat()) "${it.toInt()}.0x" else "${it}x" }
        val current = exo.playbackParameters.speed
        val checked = values.indexOfFirst { kotlin.math.abs(it - current) < 0.01f }
        showPicker("سرعة التشغيل", labels, checked) { which -> exo.playbackParameters = PlaybackParameters(values[which]) }
    }

    private fun showInfoDialog() {
        val exo = player
        showDarkModal("معلومات التشغيل", 400) { body, _ ->
            fun infoCard(label: String, value: String) {
                val row = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    background = roundBg(UI.PANEL2, 12, UI.STROKE, 1)
                    setPadding(dp(14), dp(10), dp(14), dp(10))
                }
                row.addView(tv(label, 12f, UI.MUTED, true), LinearLayout.LayoutParams(0, -2, 1f))
                row.addView(tv(value, 12.5f, Color.WHITE, true))
                body.addView(row, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(8) })
            }

            // Title Card
            val titleCard = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                background = roundBg(UI.PANEL2, 12, UI.STROKE, 1)
                setPadding(dp(14), dp(10), dp(14), dp(10))
            }
            titleCard.addView(tv("العنوان", 11f, UI.MUTED))
            titleCard.addView(tv(if (isSeries) seriesName.ifBlank { currentName } else currentName, 13.5f, Color.WHITE, true).apply {
                setPadding(0, dp(2), 0, 0)
            })
            body.addView(titleCard, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(8) })

            infoCard("النوع", when { isLiveContent -> "بث مباشر"; isSeries -> "مسلسل"; else -> "فيلم" })
            episodes.getOrNull(epIndex)?.let {
                infoCard("الحلقة", "الموسم ${seasonNumber(it)} - الحلقة ${episodeNumber(it)}")
            }
            if (isLiveContent && categoryLabel.isNotBlank()) infoCard("التصنيف", categoryLabel)
            infoCard("الأبعاد", resizeLabels[resizeModeIndex])

            if (exo != null) {
                val video = exo.videoFormat
                val size = exo.videoSize
                if (size.width > 0 && size.height > 0) {
                    val fps = video?.frameRate?.takeIf { it > 0f }?.let { " · ${it.toInt()} fps" }.orEmpty()
                    infoCard("الدقة", "${size.width}×${size.height}$fps")
                }
                video?.sampleMimeType?.let { infoCard("ترميز الفيديو", it.substringAfter('/').uppercase(Locale.US)) }
                video?.bitrate?.takeIf { it > 0 }?.let { infoCard("معدل البت", "${it / 1000} kbps") }
            }
        }
    }

    private fun formatTime(ms: Long): String {
        val total = (ms / 1000).coerceAtLeast(0)
        val h = total / 3600; val m = (total % 3600) / 60; val s = total % 60
        return if (h > 0) String.format(Locale.US, "%d:%02d:%02d", h, m, s) else String.format(Locale.US, "%02d:%02d", m, s)
    }

    // ───────────────────────── errors / alternate player ─────────────────────────

    private fun showErrorDialog() {
        if (isFinishing || isDestroyed) return
        val prefs = getSharedPreferences("player_options", MODE_PRIVATE)
        val offerAlternate = prefs.getBoolean("offer_alternate_on_error", true)

        showDarkModal("تعذر التشغيل", 430) { body, dialog ->
            body.addView(tv("تعذر تشغيل هذا الملف أو البث في المشغّل الداخلي.", 13f, Color.parseColor("#FF7B8B"), true))

            if (offerAlternate) {
                val altBox = LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    background = roundBg(UI.PANEL2, 12, UI.STROKE, 1)
                    setPadding(dp(12), dp(12), dp(12), dp(12))
                }
                altBox.addView(tv("يمكنك فتح البث في مشغّل خارجي مثل VLC أو نسخ الرابط:", 11.5f, UI.MUTED))
                val altRow = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                }
                val openExt = tv("فتح في VLC / مشغل خارجي", 11.5f, UI.NEON, true).apply {
                    gravity = Gravity.CENTER; setPadding(dp(12), dp(8), dp(12), dp(8))
                    background = roundBg(0x3300D2FF, 10, 0x6600D2FF, 1)
                    makeFocusable(1.03f, UI.NEON, 1, 10)
                    setOnClickListener { openAlternatePlayer() }
                }
                val copyBtn = tv("نسخ الرابط", 11.5f, Color.WHITE, true).apply {
                    gravity = Gravity.CENTER; setPadding(dp(12), dp(8), dp(12), dp(8))
                    background = roundBg(Color.parseColor("#0A0C10"), 10, UI.STROKE, 1)
                    makeFocusable(1.03f, UI.NEON, 1, 10)
                    setOnClickListener {
                        (getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager)
                            ?.setPrimaryClip(ClipData.newPlainText("stream_url", initialUrl))
                        Toast.makeText(this@PlayerActivity, "تم نسخ رابط البث", Toast.LENGTH_SHORT).show()
                    }
                }
                altRow.addView(openExt, LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = dp(8) })
                altRow.addView(copyBtn)
                altBox.addView(altRow, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(10) })
                body.addView(altBox, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(12) })
            }

            val btns = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.END
            }
            val closeBtn = tv("إغلاق", 12f, Color.WHITE, true).apply {
                gravity = Gravity.CENTER; setPadding(dp(16), dp(9), dp(16), dp(9))
                background = roundBg(UI.PANEL2, 12, UI.STROKE, 1)
                makeFocusable(1.04f, UI.NEON, 2, 12)
                setOnClickListener { dialog.dismiss() }
            }
            val retryBtn = tv("↻  إعادة المحاولة", 12f, Color.parseColor("#001018"), true).apply {
                gravity = Gravity.CENTER; setPadding(dp(16), dp(9), dp(16), dp(9))
                background = roundBg(UI.NEON, 12)
                makeFocusable(1.04f, Color.WHITE, 2, 12)
                setOnClickListener {
                    dialog.dismiss()
                    val pos = if (isLiveContent) 0L else (player?.currentPosition ?: 0L)
                    createPlayer(initialUrl, pos, true)
                }
            }
            btns.addView(closeBtn, LinearLayout.LayoutParams(-2, -2).apply { marginEnd = dp(8) })
            btns.addView(retryBtn)
            body.addView(btns, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(16) })
            retryBtn.post { retryBtn.requestFocus() }
        }
    }

    private fun openAlternatePlayer() {
        val url = initialUrl
        if (url.isBlank() || !(url.startsWith("https://") || url.startsWith("http://"))) {
            Toast.makeText(this, "رابط البث غير صالح.", Toast.LENGTH_LONG).show()
            return
        }
        try {
            val viewIntent = android.content.Intent(android.content.Intent.ACTION_VIEW).apply {
                setDataAndType(android.net.Uri.parse(url), "video/*")
                addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            startActivity(android.content.Intent.createChooser(viewIntent, "اختر مشغّل الفيديو"))
        } catch (_: android.content.ActivityNotFoundException) {
            Toast.makeText(this, "ثبّت مشغّلًا متوافقًا مثل VLC ثم أعد المحاولة.", Toast.LENGTH_LONG).show()
        } catch (_: Exception) {
            Toast.makeText(this, "تعذر فتح المشغّل البديل.", Toast.LENGTH_LONG).show()
        }
    }

    // ───────────────────────── live channels ─────────────────────────

    private fun changeChannel(direction: Int) {
        if (currentId.isBlank()) { Toast.makeText(this, "قائمة القنوات غير متاحة لهذا التشغيل", Toast.LENGTH_SHORT).show(); return }
        bg {
            val next = repository.channelNeighbor(currentId, categoryId, direction)
            runOnUiThread {
                if (next == null) { Toast.makeText(this, "لا توجد قناة أخرى في هذا القسم.", Toast.LENGTH_SHORT).show(); return@runOnUiThread }
                playChannel(next)
            }
        }
    }

    private fun showChannelList() {
        showDarkModal("قائمة القنوات", 430) { body, dialog ->
            val queryInput = EditText(this).apply {
                hint = "ابحث عن قناة…"
                setHintTextColor(UI.MUTED)
                setTextColor(Color.WHITE)
                textSize = 13.5f
                setSingleLine(true)
                isFocusable = true
                setPadding(dp(14), dp(9), dp(14), dp(9))
                background = roundBg(UI.PANEL2, 12, UI.STROKE, 1)
            }
            val emptyNote = tv("جارٍ تحميل القنوات…", 12f, UI.MUTED).apply {
                gravity = Gravity.CENTER
                setPadding(0, dp(12), 0, dp(8))
            }
            val list = ArrayList<ContentEntity>()
            val recycler = RecyclerView(this).apply {
                layoutManager = LinearLayoutManager(this@PlayerActivity)
                overScrollMode = View.OVER_SCROLL_NEVER
                itemAnimator = null
            }
            val chAdapter = object : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
                init { setHasStableIds(true) }
                override fun getItemCount(): Int = list.size
                override fun getItemId(position: Int): Long = list[position].contentId.hashCode().toLong()
                override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
                    val row = modalOptionRow("", false) {}
                    row.layoutParams = RecyclerView.LayoutParams(-1, -2).apply { bottomMargin = dp(6) }
                    return object : RecyclerView.ViewHolder(row) {}
                }
                override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
                    val ch = list[position]
                    val active = ch.contentId == currentId
                    val row = holder.itemView as LinearLayout
                    row.removeAllViews()
                    val idle = if (active) roundBg(0x3300D2FF, 12, UI.NEON, 1) else roundBg(UI.PANEL2, 12, UI.STROKE, 1)
                    val focused = roundBg(0x4D00D2FF, 12, Color.WHITE, 2)
                    row.background = StateListDrawable().apply {
                        addState(intArrayOf(android.R.attr.state_focused), focused)
                        addState(intArrayOf(android.R.attr.state_pressed), focused)
                        addState(intArrayOf(), idle)
                    }
                    row.addView(tv(ch.name, 13f, Color.WHITE, true).apply {
                        maxLines = 1; ellipsize = TextUtils.TruncateAt.END
                    }, LinearLayout.LayoutParams(0, -2, 1f))
                    if (active) row.addView(tv("✓", 14f, UI.NEON, true))
                    row.setOnClickListener {
                        playChannel(ch)
                        dialog.dismiss()
                    }
                }
            }
            recycler.adapter = chAdapter

            var query = ""; var offset = 0; var loading = false; var hasMore = true; var generation = 0
            fun load(reset: Boolean) {
                if (reset) {
                    generation++; query = queryInput.text.toString(); offset = 0; hasMore = true; list.clear(); chAdapter.notifyDataSetChanged(); loading = false
                }
                if (loading || !hasMore) return
                loading = true
                val gen = generation; val q = query; val off = offset
                bg {
                    val page = repository.searchChannels(q, categoryId, 80, off)
                    runOnUiThread {
                        if (!dialog.isShowing || gen != generation) return@runOnUiThread
                        val from = list.size
                        list.addAll(page)
                        chAdapter.notifyItemRangeInserted(from, page.size)
                        offset += page.size; hasMore = page.size == 80; loading = false
                        emptyNote.visibility = if (list.isEmpty()) View.VISIBLE else View.GONE
                        emptyNote.text = "لا توجد قنوات."
                    }
                }
            }

            recycler.addOnScrollListener(object : RecyclerView.OnScrollListener() {
                override fun onScrolled(rv: RecyclerView, dx: Int, dy: Int) {
                    val lm = rv.layoutManager as LinearLayoutManager
                    if (!loading && hasMore && lm.findLastVisibleItemPosition() >= list.size - 8) load(false)
                }
            })
            queryInput.addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) { load(true) }
                override fun afterTextChanged(s: android.text.Editable?) = Unit
            })

            body.addView(queryInput, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(8) })
            body.addView(emptyNote)
            body.addView(recycler, LinearLayout.LayoutParams(-1, dp(260)))
            load(true)
            queryInput.post { queryInput.requestFocus() }
        }
    }

    private fun playChannel(channel: ContentEntity) {
        currentId = channel.contentId; currentName = channel.name; categoryId = channel.category
        markId = channel.contentId
        imageUrl = channel.imageUrl
        initialUrl = repository.mediaUrl("live", channel.contentId, channel.extension)
        updateHeader()
        showStatus("📡 ${channel.name}")
        createPlayer(initialUrl, 0L, true)
        showControls(requestFocus = !hasControlFocus())
    }

    // ───────────────────────── system / keys / lifecycle ─────────────────────────

    private fun setPlaybackScreenOn(playing: Boolean) {
        if (playing && !isFinishing && !isDestroyed) {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) applyImmersiveFullscreen()
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        if (ev.action == MotionEvent.ACTION_DOWN && controlsVisible) scheduleHide()
        return super.dispatchTouchEvent(ev)
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN && controlsVisible) scheduleHide()
        return super.dispatchKeyEvent(event)
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        val focused = hasControlFocus()
        val step = 10_000L * (1 + (event.repeatCount / 4).coerceAtMost(6))
        return when (keyCode) {
            KeyEvent.KEYCODE_BACK -> when {
                episodesPanelOpen -> { closeEpisodesPanel(); true }
                controlsVisible && isTv -> { hideControls(); true }
                else -> super.onKeyDown(keyCode, event)
            }
            KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT -> if (focused) super.onKeyDown(keyCode, event) else {
                if (!isLiveContent) seekBy(if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT) -step else step)
                showControls(); true
            }
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER ->
                if (focused) super.onKeyDown(keyCode, event)
                else { if (!controlsVisible) showControls(requestFocus = true) else togglePlayPause(); true }
            KeyEvent.KEYCODE_DPAD_UP -> if (focused) super.onKeyDown(keyCode, event)
                else { if (isLiveContent && !controlsVisible) changeChannel(1) else showControls(requestFocus = true); true }
            KeyEvent.KEYCODE_DPAD_DOWN -> if (focused) super.onKeyDown(keyCode, event)
                else { if (isLiveContent && !controlsVisible) changeChannel(-1) else showControls(requestFocus = true); true }
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_HEADSETHOOK -> { togglePlayPause(); showControls(); true }
            KeyEvent.KEYCODE_MEDIA_PLAY -> { playNow(); showControls(); true }
            KeyEvent.KEYCODE_MEDIA_PAUSE, KeyEvent.KEYCODE_MEDIA_STOP -> { player?.pause(); syncPlayPause(); showControls(); true }
            KeyEvent.KEYCODE_MEDIA_REWIND -> { seekBy(-step); showControls(); true }
            KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> { seekBy(step); showControls(); true }
            KeyEvent.KEYCODE_MEDIA_NEXT -> { if (isSeries && epIndex in 0 until episodes.size - 1) playEpisode(epIndex + 1) else if (isLiveContent) changeChannel(1); true }
            KeyEvent.KEYCODE_MEDIA_PREVIOUS -> { if (isSeries && epIndex > 0) playEpisode(epIndex - 1) else if (isLiveContent) changeChannel(-1); true }
            KeyEvent.KEYCODE_VOLUME_UP, KeyEvent.KEYCODE_VOLUME_DOWN, KeyEvent.KEYCODE_VOLUME_MUTE -> {
                val handled = super.onKeyDown(keyCode, event)
                ui.postDelayed({ syncVolume() }, 80); ui.postDelayed({ syncVolume() }, 300)
                handled
            }
            KeyEvent.KEYCODE_CHANNEL_UP -> { changeChannel(1); true }
            KeyEvent.KEYCODE_CHANNEL_DOWN -> { changeChannel(-1); true }
            KeyEvent.KEYCODE_MENU -> { if (isLiveContent) showChannelList() else if (isSeries) { showControls(); toggleEpisodesPanel() } else showControls(requestFocus = true); true }
            else -> super.onKeyDown(keyCode, event)
        }
    }

    override fun onPause() {
        val current = player?.currentPosition ?: 0L
        if (!completed && markKind.isNotBlank() && markId.isNotBlank() && current > 0L) bg { repository.saveProgress(markKind, markId, current) }
        if (!completed && isSeries && currentEpisodeId.isNotBlank() && current > 0L) {
            val ep = episodes.getOrNull(epIndex)
            val title = if (ep != null) "S%02dE%02d".format(Locale.US, seasonNumber(ep), episodeNumber(ep)) else currentName
            ResumeStore.saveSeries(this, seriesId, currentEpisodeId, current, initialUrl.substringAfterLast('.', "mp4").substringBefore('?'), title)
        }
        super.onPause()
    }

    override fun onStart() {
        super.onStart()
        startVolumeWatch()
        if (player == null && initialUrl.isNotBlank() && ::playerView.isInitialized) {
            createPlayer(initialUrl, lastKnownPosition, shouldResumeOnStart)
        } else if (shouldResumeOnStart) {
            player?.play()
        }
    }

    override fun onStop() {
        stopVolumeWatch()
        setPlaybackScreenOn(false)
        super.onStop()
        lastKnownPosition = player?.currentPosition ?: lastKnownPosition
        shouldResumeOnStart = player?.playWhenReady ?: true
        player?.pause()
    }

    override fun onDestroy() {
        ui.removeCallbacksAndMessages(null)
        player?.release(); player = null
        executor.shutdown(); super.onDestroy()
    }
}
