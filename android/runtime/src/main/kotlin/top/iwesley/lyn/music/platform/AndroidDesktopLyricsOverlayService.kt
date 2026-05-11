package top.iwesley.lyn.music.platform

import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import android.text.TextUtils
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.TextView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import top.iwesley.lyn.music.core.model.AndroidDiagnosticLogger
import top.iwesley.lyn.music.core.model.DesktopLyricsPlatformService
import top.iwesley.lyn.music.core.model.LyricsDocument
import top.iwesley.lyn.music.core.model.PlaybackSnapshot
import top.iwesley.lyn.music.core.model.Track
import top.iwesley.lyn.music.core.model.withSecureInMemoryCache
import top.iwesley.lyn.music.data.repository.DefaultLyricsRepository
import top.iwesley.lyn.music.data.repository.LyricsRepository
import top.iwesley.lyn.music.feature.player.findDesktopLyricsHighlightedLine
import top.iwesley.lyn.music.feature.player.resolveDesktopLyricsOverlayText

class AndroidDesktopLyricsOverlayService : Service() {
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var preferencesStore: AndroidAppPreferencesStore
    private lateinit var lyricsRepository: LyricsRepository
    private lateinit var windowManager: WindowManager
    private var overlayView: TextView? = null
    private var overlayParams: WindowManager.LayoutParams? = null
    private var userMoved = false
    private var dragStartRawX = 0f
    private var dragStartRawY = 0f
    private var dragStartX = 0
    private var dragStartY = 0
    private var currentLyricsRequestKey: String? = null
    private var currentLyrics: LyricsDocument? = null
    private var lyricsLoading = false
    private var lyricsLoadJob: Job? = null

    override fun onCreate() {
        super.onCreate()
        preferencesStore = AndroidAppPreferencesStore(applicationContext)
        lyricsRepository = createServiceLyricsRepository()
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        observeDesktopLyrics()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_HIDE -> hideOverlay()
            ACTION_STOP -> {
                hideOverlay()
                stopSelf()
            }
            else -> Unit
        }
        return START_STICKY
    }

    override fun onDestroy() {
        lyricsLoadJob?.cancel()
        hideOverlay()
        serviceScope.cancel()
        super.onDestroy()
    }

    private fun observeDesktopLyrics() {
        serviceScope.launch {
            combine(
                preferencesStore.showDesktopLyrics,
                AndroidPlaybackRuntimeRegistry.repository,
            ) { enabled, repository -> enabled to repository }
                .collectLatest { (enabled, repository) ->
                    if (!enabled || !canDrawOverlays(applicationContext)) {
                        if (enabled && !canDrawOverlays(applicationContext)) {
                            preferencesStore.setShowDesktopLyrics(false)
                        }
                        clearLyricsState()
                        hideOverlay()
                        if (!enabled) stopSelf()
                        return@collectLatest
                    }
                    val playbackRepository = repository
                    if (playbackRepository == null) {
                        clearLyricsState()
                        hideOverlay()
                    } else {
                        playbackRepository.snapshot.collectLatest(::updateForSnapshot)
                    }
                }
        }
    }

    private fun updateForSnapshot(snapshot: PlaybackSnapshot) {
        if (!canDrawOverlays(applicationContext)) {
            handleOverlayPermissionRevoked()
            return
        }
        val track = snapshot.currentTrack
        if (track == null) {
            clearLyricsState()
            hideOverlay()
            return
        }
        val lookupTrack = snapshot.toLyricsLookupTrack()
        val requestKey = lookupTrack.lyricsRequestKey()
        if (requestKey != currentLyricsRequestKey) {
            currentLyricsRequestKey = requestKey
            currentLyrics = null
            lyricsLoading = true
            lyricsLoadJob?.cancel()
            lyricsLoadJob = serviceScope.launch {
                val result = runCatching { lyricsRepository.getLyrics(lookupTrack) }.getOrNull()
                if (currentLyricsRequestKey != requestKey) return@launch
                currentLyrics = result?.document
                lyricsLoading = false
                renderSnapshot(snapshot)
            }
        }
        renderSnapshot(snapshot)
    }

    private fun renderSnapshot(snapshot: PlaybackSnapshot) {
        val highlightedLineIndex = findDesktopLyricsHighlightedLine(
            lyrics = currentLyrics,
            positionMs = snapshot.positionMs,
        )
        val text = resolveDesktopLyricsOverlayText(
            lyrics = currentLyrics,
            highlightedLineIndex = highlightedLineIndex,
            isLyricsLoading = lyricsLoading,
        )
        if (text == null) {
            hideOverlay()
        } else {
            showText(text)
        }
    }

    private fun showText(text: String) {
        if (!canDrawOverlays(applicationContext)) {
            handleOverlayPermissionRevoked()
            return
        }
        if (text.isBlank()) return
        val view = ensureOverlayView()
        val params = overlayParams ?: return
        view.text = text
        if (view.parent == null) {
            windowManager.addView(view, params)
        } else {
            windowManager.updateViewLayout(view, params)
        }
        if (!userMoved) {
            view.post { placeBottomCenter(view) }
        }
    }

    private fun hideOverlay() {
        val view = overlayView ?: return
        if (view.parent != null) {
            runCatching { windowManager.removeView(view) }
        }
    }

    private fun ensureOverlayView(): TextView {
        overlayView?.let { return it }
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            overlayWindowType(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 0
            y = 0
        }
        overlayParams = params
        return TextView(this).apply {
            setTextColor(Color.WHITE)
            textSize = 20f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.END
            setPadding(dp(24), dp(10), dp(24), dp(10))
            maxWidth = resources.displayMetrics.widthPixels - dp(32)
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(18).toFloat()
                setColor(Color.argb(178, 0, 0, 0))
            }
            setOnTouchListener(::handleDragTouch)
            overlayView = this
        }
    }

    private fun handleDragTouch(view: View, event: MotionEvent): Boolean {
        val params = overlayParams ?: return false
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                dragStartRawX = event.rawX
                dragStartRawY = event.rawY
                dragStartX = params.x
                dragStartY = params.y
                return true
            }

            MotionEvent.ACTION_MOVE -> {
                params.x = dragStartX + (event.rawX - dragStartRawX).toInt()
                params.y = dragStartY + (event.rawY - dragStartRawY).toInt()
                userMoved = true
                windowManager.updateViewLayout(view, params)
                return true
            }
        }
        return true
    }

    private fun placeBottomCenter(view: View) {
        val params = overlayParams ?: return
        params.x = ((resources.displayMetrics.widthPixels - view.width) / 2).coerceAtLeast(0)
        params.y = (resources.displayMetrics.heightPixels - view.height - dp(96)).coerceAtLeast(0)
        if (view.parent != null) {
            windowManager.updateViewLayout(view, params)
        }
    }

    private fun handleOverlayPermissionRevoked() {
        clearLyricsState()
        hideOverlay()
        serviceScope.launch {
            preferencesStore.setShowDesktopLyrics(false)
            stopSelf()
        }
    }

    private fun clearLyricsState() {
        lyricsLoadJob?.cancel()
        lyricsLoadJob = null
        currentLyricsRequestKey = null
        currentLyrics = null
        lyricsLoading = false
    }

    private fun createServiceLyricsRepository(): LyricsRepository {
        val logger = AndroidDiagnosticLogger(enabled = true, label = "Android Desktop Lyrics")
        val database = openAndroidRuntimeDatabase(applicationContext)
        val secureStore = AndroidCredentialStore(applicationContext, logger).withSecureInMemoryCache()
        val httpClient = AndroidLyricsHttpClient()
        val artworkCacheStore = createAndroidArtworkCacheStore(applicationContext)
        return DefaultLyricsRepository(
            database = database,
            httpClient = httpClient,
            secureCredentialStore = secureStore,
            audioTagGateway = AndroidAudioTagGateway(
                context = applicationContext,
                database = database,
                secureCredentialStore = secureStore,
                logger = logger,
            ),
            sameNameLyricsFileGateway = AndroidSameNameLyricsFileGateway(
                context = applicationContext,
                database = database,
                secureCredentialStore = secureStore,
                logger = logger,
            ),
            artworkCacheStore = artworkCacheStore,
            logger = logger,
        )
    }

    private fun dp(value: Int): Int {
        return (value * resources.displayMetrics.density).toInt()
    }

    private fun overlayWindowType(): Int {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }
    }

    private fun PlaybackSnapshot.toLyricsLookupTrack(): Track {
        val track = requireNotNull(currentTrack)
        return track.copy(
            title = currentDisplayTitle,
            artistName = currentDisplayArtistName,
            albumTitle = currentDisplayAlbumTitle,
        )
    }

    private fun Track.lyricsRequestKey(): String {
        return listOf(id, title, artistName.orEmpty(), albumTitle.orEmpty()).joinToString("|")
    }

    companion object {
        internal const val ACTION_START = "top.iwesley.lyn.music.action.DESKTOP_LYRICS_START"
        internal const val ACTION_HIDE = "top.iwesley.lyn.music.action.DESKTOP_LYRICS_HIDE"
        internal const val ACTION_STOP = "top.iwesley.lyn.music.action.DESKTOP_LYRICS_STOP"
    }
}

class AndroidDesktopLyricsPlatformService(
    private val context: Context,
) : DesktopLyricsPlatformService {
    private val appContext = context.applicationContext

    override val isSupported: Boolean = true
    override val consumesAppLyricsUpdates: Boolean = false

    override fun hasOverlayPermission(): Boolean = canDrawOverlays(appContext)

    override suspend fun requestOverlayPermission(): Boolean {
        if (hasOverlayPermission()) return true
        val intent = Intent(
            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
            Uri.parse("package:${appContext.packageName}"),
        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { appContext.startActivity(intent) }
        return hasOverlayPermission()
    }

    override suspend fun setDesktopLyricsEnabled(enabled: Boolean) {
        val action = if (enabled) {
            AndroidDesktopLyricsOverlayService.ACTION_START
        } else {
            AndroidDesktopLyricsOverlayService.ACTION_STOP
        }
        if (enabled && !hasOverlayPermission()) return
        sendServiceIntent(Intent(appContext, AndroidDesktopLyricsOverlayService::class.java).setAction(action))
    }

    override suspend fun updateLyrics(text: String) = Unit

    override suspend fun hideLyrics() {
        sendServiceIntent(
            Intent(appContext, AndroidDesktopLyricsOverlayService::class.java)
                .setAction(AndroidDesktopLyricsOverlayService.ACTION_HIDE),
        )
    }

    override suspend fun release() = Unit

    private fun sendServiceIntent(intent: Intent) {
        runCatching { appContext.startService(intent) }
    }
}

private fun canDrawOverlays(context: Context): Boolean {
    return Build.VERSION.SDK_INT < Build.VERSION_CODES.M || Settings.canDrawOverlays(context)
}
