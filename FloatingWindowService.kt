package com.example.screencapture

import android.animation.ValueAnimator
import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.ServiceInfo
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import android.widget.Toast
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.ComposeView
import androidx.core.app.NotificationCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.abs

class FloatingWindowService : Service() {

    companion object {
        private const val TAG = "FloatingWindowService"
        private const val NOTIFICATION_ID = 1001
        private const val CHANNEL_ID = "screen_capture_channel"

        const val ACTION_START_PROJECTION = "com.example.screencapture.ACTION_START_PROJECTION"
        const val ACTION_STOP_SERVICE = "com.example.screencapture.ACTION_STOP_SERVICE"
        const val EXTRA_RESULT_CODE = "EXTRA_RESULT_CODE"
        const val EXTRA_RESULT_DATA = "EXTRA_RESULT_DATA"

        private const val CROP_PREFS_NAME = "screen_translator_crop_prefs"
        private const val KEY_HAS_LAST_RECT = "has_last_rect"
        private const val KEY_RECT_LEFT = "rect_left"
        private const val KEY_RECT_TOP = "rect_top"
        private const val KEY_RECT_RIGHT = "rect_right"
        private const val KEY_RECT_BOTTOM = "rect_bottom"

        private const val BUTTON_SIZE_DP = 52f
        private const val EDGE_MARGIN_DP = 8f
        private const val TOP_SAFE_MARGIN_DP = 48f
        private const val BOTTOM_NAV_SAFE_MARGIN_DP = 84f
        private const val IDLE_TIMEOUT_MS = 3000L
        private const val IDLE_ALPHA = 0.35f
        private const val DOUBLE_TAP_TIMEOUT_MS = 240L
        private const val LONG_PRESS_TIMEOUT_MS = 800L
    }

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val mainHandler = Handler(Looper.getMainLooper())

    private lateinit var windowManager: WindowManager
    private lateinit var screenCaptureManager: ScreenCaptureManager
    private lateinit var translationManager: TranslationManager
    private lateinit var cropPrefs: SharedPreferences

    private var floatingView: View? = null
    private var iconCanvasView: View? = null
    private lateinit var floatingParams: WindowManager.LayoutParams
    private var cropOverlayView: View? = null
    private var activeComposeOwner: OverlayComposeLifecycleOwner? = null

    private var lastCropRect: Rect? = null
    private var isAutoWatchActive = false // Trạng thái tính năng Auto-Watch (Tính năng 4)

    private var idleDimJob: Job? = null
    private var singleTapJob: Job? = null
    private var longPressJob: Job? = null
    private var autoWatchJob: Job? = null
    private var snapAnimator: ValueAnimator? = null
    private var isCapturing = false

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        cropPrefs = getSharedPreferences(CROP_PREFS_NAME, Context.MODE_PRIVATE)

        lastCropRect = loadLastCropRectFromPrefs()

        screenCaptureManager = ScreenCaptureManager(
            context = this,
            onProjectionStopped = {
                stopSelf()
            }
        )

        translationManager = TranslationManager(this)
        createNotificationChannel()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        if (cropOverlayView != null) {
            removeCropOverlay()
            restoreFloatingButton()
        }
        floatingView?.let { v ->
            if (v.isAttachedToWindow) {
                val bounds = windowManager.maximumWindowMetrics.bounds
                val screenWidth = bounds.width()
                val screenHeight = bounds.height()
                val buttonSizePx = dpToPx(BUTTON_SIZE_DP)
                val edgeMarginPx = dpToPx(EDGE_MARGIN_DP)
                val minY = dpToPx(TOP_SAFE_MARGIN_DP)
                val maxY = (screenHeight - buttonSizePx - dpToPx(BOTTOM_NAV_SAFE_MARGIN_DP))
                    .coerceAtLeast(minY)

                floatingParams.x = if (floatingParams.x < screenWidth / 2) {
                    edgeMarginPx
                } else {
                    screenWidth - buttonSizePx - edgeMarginPx
                }
                floatingParams.y = floatingParams.y.coerceIn(minY, maxY)
                windowManager.updateViewLayout(v, floatingParams)
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP_SERVICE -> {
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_START_PROJECTION -> {
                val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, Activity.RESULT_CANCELED)
                val resultData: Intent? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableExtra(EXTRA_RESULT_DATA, Intent::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra(EXTRA_RESULT_DATA)
                }

                if (resultCode == Activity.RESULT_OK && resultData != null) {
                    try {
                        startMediaProjectionForeground()
                        screenCaptureManager.initialize(resultCode, resultData)
                        showFloatingButtonIfNeeded()
                    } catch (e: Exception) {
                        Log.e(TAG, "Khởi tạo MediaProjection thất bại", e)
                        stopSelf()
                    }
                } else {
                    Log.e(TAG, "Dữ liệu MediaProjection không hợp lệ. Dừng Service.")
                    stopSelf()
                }
            }
        }
        return START_NOT_STICKY
    }

    private fun startMediaProjectionForeground() {
        val notification = buildNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun showFloatingButtonIfNeeded() {
        if (floatingView != null) return

        val buttonSizePx = dpToPx(BUTTON_SIZE_DP)
        val edgeMarginPx = dpToPx(EDGE_MARGIN_DP)

        val button = createStyledFloatingButton(buttonSizePx)

        floatingParams = WindowManager.LayoutParams(
            buttonSizePx,
            buttonSizePx,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = edgeMarginPx
            y = dpToPx(220f)
            windowAnimations = 0
        }

        setupDragSnapAndClick(button)
        floatingView = button
        windowManager.addView(floatingView, floatingParams)

        scheduleIdleDimming()
    }

    private fun createStyledFloatingButton(sizePx: Int): View {
        val container = FrameLayout(this).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.parseColor("#E6162032"))
                setStroke(dpToPx(2f), Color.parseColor("#8AB4F8"))
            }
            elevation = dpToPx(6f).toFloat()
        }

        val iconView = object : View(this) {
            private val cornerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.parseColor("#8AB4F8")
                style = Paint.Style.STROKE
                strokeWidth = dpToPx(1.8f).toFloat()
                strokeCap = Paint.Cap.ROUND
            }

            private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.WHITE
                textSize = dpToPx(13.5f).toFloat()
                typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
                textAlign = Paint.Align.CENTER
            }

            private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.parseColor("#FDD663")
                style = Paint.Style.FILL
            }

            override fun onDraw(canvas: Canvas) {
                super.onDraw(canvas)
                val w = width.toFloat()
                val h = height.toFloat()
                val pad = w * 0.24f
                val cornerLen = w * 0.13f

                // Đổi màu viền sang xanh lá nếu Auto-Watch đang hoạt động
                if (isAutoWatchActive) {
                    cornerPaint.color = Color.parseColor("#81C995")
                } else {
                    cornerPaint.color = Color.parseColor("#8AB4F8")
                }

                canvas.drawLine(pad, pad, pad + cornerLen, pad, cornerPaint)
                canvas.drawLine(pad, pad, pad, pad + cornerLen, cornerPaint)
                canvas.drawLine(w - pad, pad, w - pad - cornerLen, pad, cornerPaint)
                canvas.drawLine(w - pad, pad, w - pad, pad + cornerLen, cornerPaint)
                canvas.drawLine(pad, h - pad, pad + cornerLen, h - pad, cornerPaint)
                canvas.drawLine(pad, h - pad, pad, h - pad - cornerLen, cornerPaint)
                canvas.drawLine(w - pad, h - pad, w - pad - cornerLen, h - pad, cornerPaint)
                canvas.drawLine(w - pad, h - pad, w - pad, h - pad - cornerLen, cornerPaint)

                val textY = h / 2f - (textPaint.descent() + textPaint.ascent()) / 2f
                canvas.drawText(if (isAutoWatchActive) "ON" else "A/V", w / 2f, textY, textPaint)

                if (lastCropRect != null && !isAutoWatchActive) {
                    canvas.drawCircle(w / 2f, h * 0.74f, dpToPx(2.2f).toFloat(), dotPaint)
                }
            }
        }

        iconCanvasView = iconView
        container.addView(iconView, FrameLayout.LayoutParams(sizePx, sizePx))
        return container
    }

    private fun setupDragSnapAndClick(view: View) {
        var initialX = 0
        var initialY = 0
        var initialTouchX = 0f
        var initialTouchY = 0f
        var lastTapUpTime = 0L

        view.setOnTouchListener { v, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    wakeUpFloatingButton(scaleUp = true)
                    snapAnimator?.cancel()

                    initialX = floatingParams.x
                    initialY = floatingParams.y
                    initialTouchX = event.rawX
                    initialTouchY = event.rawY

                    // TÍNH NĂNG 4: Hẹn giờ Nhấn giữ (Long-Press 0.8s) để bật/tắt Auto-Watch
                    longPressJob?.cancel()
                    longPressJob = serviceScope.launch {
                        delay(LONG_PRESS_TIMEOUT_MS)
                        toggleAutoWatchMode()
                    }
                    true
                }

                MotionEvent.ACTION_MOVE -> {
                    val deltaX = abs(event.rawX - initialTouchX)
                    val deltaY = abs(event.rawY - initialTouchY)
                    if (deltaX >= 15 || deltaY >= 15) {
                        singleTapJob?.cancel()
                        longPressJob?.cancel() // Hủy nhấn giữ nếu người dùng kéo nút đi
                    }

                    val bounds = windowManager.maximumWindowMetrics.bounds
                    val screenWidth = bounds.width()
                    val screenHeight = bounds.height()
                    val buttonSizePx = dpToPx(BUTTON_SIZE_DP)

                    val minY = dpToPx(TOP_SAFE_MARGIN_DP)
                    val maxY = (screenHeight - buttonSizePx - dpToPx(BOTTOM_NAV_SAFE_MARGIN_DP))
                        .coerceAtLeast(minY)

                    val rawNewX = initialX + (event.rawX - initialTouchX).toInt()
                    val rawNewY = initialY + (event.rawY - initialTouchY).toInt()

                    floatingParams.x = rawNewX.coerceIn(0, screenWidth - buttonSizePx)
                    floatingParams.y = rawNewY.coerceIn(minY, maxY)

                    if (v.isAttachedToWindow) {
                        windowManager.updateViewLayout(v, floatingParams)
                    }
                    true
                }

                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    longPressJob?.cancel() // Hủy lệnh nhấn giữ khi nhấc tay lên
                    v.animate().scaleX(1f).scaleY(1f).setDuration(120).start()

                    val deltaX = abs(event.rawX - initialTouchX)
                    val deltaY = abs(event.rawY - initialTouchY)

                    if (event.actionMasked == MotionEvent.ACTION_UP && deltaX < 15 && deltaY < 15) {
                        v.performClick()
                        val now = SystemClock.uptimeMillis()

                        if (now - lastTapUpTime <= DOUBLE_TAP_TIMEOUT_MS) {
                            singleTapJob?.cancel()
                            lastTapUpTime = 0L

                            if (lastCropRect != null) {
                                triggerScreenCapture(useSavedRect = true)
                            } else {
                                Toast.makeText(
                                    this@FloatingWindowService,
                                    "Chưa có vùng nhớ. Hãy kéo khoanh vùng lần đầu!",
                                    Toast.LENGTH_SHORT
                                ).show()
                                triggerScreenCapture(useSavedRect = false)
                            }
                        } else {
                            lastTapUpTime = now
                            singleTapJob?.cancel()
                            singleTapJob = serviceScope.launch {
                                delay(DOUBLE_TAP_TIMEOUT_MS)
                                lastTapUpTime = 0L
                                triggerScreenCapture(useSavedRect = false)
                            }
                        }
                    } else {
                        snapToNearestEdge(v)
                        scheduleIdleDimming()
                    }
                    true
                }

                else -> false
            }
        }
    }

    /**
     * Bật/Tắt chế độ Auto-Watch (Canh chừng phụ đề tự động)
     */
    private fun toggleAutoWatchMode() {
        isAutoWatchActive = !isAutoWatchActive
        iconCanvasView?.invalidate()

        if (isAutoWatchActive) {
            if (lastCropRect == null) {
                Toast.makeText(this, "Hãy kéo khoanh vùng hội thoại ít nhất 1 lần trước khi bật Auto-Watch!", Toast.LENGTH_LONG).show()
                isAutoWatchActive = false
                iconCanvasView?.invalidate()
                return
            }
            Toast.makeText(this, "Đã BẬT Auto-Watch: Đang theo dõi phụ đề game...", Toast.LENGTH_SHORT).show()
            startAutoWatchLoop()
        } else {
            Toast.makeText(this, "Đã TẮT Auto-Watch", Toast.LENGTH_SHORT).show()
            stopAutoWatchLoop()
        }
    }

    /**
     * Vòng lặp Auto-Watch: Kiểm tra mỗi 1.2s, chỉ OCR khi phát hiện thay đổi Pixel trên vùng phụ đề
     */
    private fun startAutoWatchLoop() {
        autoWatchJob?.cancel()
        autoWatchJob = serviceScope.launch {
            var previousHash = 0L
            while (isAutoWatchActive) {
                delay(1200L) // Kiểm tra mỗi 1.2 giây
                if (!isCapturing && screenCaptureManager.isReady && lastCropRect != null) {
                    val rect = lastCropRect!!
                    val bitmap = screenCaptureManager.captureScreen()
                    if (bitmap != null) {
                        try {
                            val cropped = cropBitmapSafely(bitmap, rect)
                            if (cropped != null) {
                                val currentHash = computeSimplePixelHash(cropped)
                                if (currentHash != previousHash) {
                                    previousHash = currentHash
                                    // Phát hiện thay đổi câu thoại -> Tự động dịch và hiện Card kết quả
                                    onCaptureSuccess(bitmap, rect)
                                    // Tạm dừng vòng lặp Auto-Watch trong lúc hiển thị Card cho người đọc
                                    isAutoWatchActive = false
                                    iconCanvasView?.invalidate()
                                    break
                                }
                                if (cropped !== bitmap && !cropped.isRecycled) cropped.recycle()
                            }
                        } finally {
                            if (!bitmap.isRecycled) bitmap.recycle()
                        }
                    }
                }
            }
        }
    }

    private fun stopAutoWatchLoop() {
        autoWatchJob?.cancel()
        autoWatchJob = null
    }

    /**
     * Thuật toán băm điểm ảnh (Pixel Hash) siêu nhẹ để kiểm tra xem phụ đề đã đổi chưa (Tốn chưa tới 0.1ms)
     */
    private fun computeSimplePixelHash(bitmap: Bitmap): Long {
        var hash = 0L
        val stepX = max(1, bitmap.width / 10)
        val stepY = max(1, bitmap.height / 10)
        for (x in 0 until bitmap.width step stepX) {
            for (y in 0 until bitmap.height step stepY) {
                val pixel = bitmap.getPixel(x, y)
                hash = 31 * hash + pixel.toLong()
            }
        }
        return hash
    }

    private fun cropBitmapSafely(source: Bitmap, rect: Rect): Bitmap? {
        return try {
            val x = rect.left.roundToInt().coerceIn(0, source.width - 1)
            val y = rect.top.roundToInt().coerceIn(0, source.height - 1)
            val width = rect.width.roundToInt().coerceAtMost(source.width - x)
            val height = rect.height.roundToInt().coerceAtMost(source.height - y)
            if (width < 32 || height < 32) return null
            Bitmap.createBitmap(source, x, y, width, height)
        } catch (_: Exception) {
            null
        }
    }

    private fun snapToNearestEdge(view: View) {
        val bounds = windowManager.maximumWindowMetrics.bounds
        val screenWidth = bounds.width()
        val buttonSizePx = dpToPx(BUTTON_SIZE_DP)
        val edgeMarginPx = dpToPx(EDGE_MARGIN_DP)

        val currentCenterX = floatingParams.x + buttonSizePx / 2
        val targetX = if (currentCenterX < screenWidth / 2) {
            edgeMarginPx
        } else {
            screenWidth - buttonSizePx - edgeMarginPx
        }

        snapAnimator?.cancel()
        snapAnimator = ValueAnimator.ofInt(floatingParams.x, targetX).apply {
            duration = 220L
            interpolator = DecelerateInterpolator()
            addUpdateListener { animator ->
                if (view.isAttachedToWindow && view.visibility == View.VISIBLE) {
                    floatingParams.x = animator.animatedValue as Int
                    windowManager.updateViewLayout(view, floatingParams)
                }
            }
            start()
        }
    }

    private fun wakeUpFloatingButton(scaleUp: Boolean = false) {
        idleDimJob?.cancel()
        floatingView?.let { v ->
            v.animate().cancel()
            v.alpha = 1.0f
            if (scaleUp) {
                v.scaleX = 1.08f
                v.scaleY = 1.08f
            } else {
                v.scaleX = 1.0f
                v.scaleY = 1.0f
            }
        }
    }

    private fun scheduleIdleDimming() {
        idleDimJob?.cancel()
        idleDimJob = serviceScope.launch {
            delay(IDLE_TIMEOUT_MS)
            floatingView?.let { v ->
                if (v.isAttachedToWindow && v.visibility == View.VISIBLE && !isCapturing && !isAutoWatchActive) {
                    v.animate()
                        .alpha(IDLE_ALPHA)
                        .scaleX(0.90f)
                        .scaleY(0.90f)
                        .setDuration(300L)
                        .start()
                }
            }
        }
    }

    private fun triggerScreenCapture(useSavedRect: Boolean) {
        if (isCapturing || !screenCaptureManager.isReady) return
        isCapturing = true

        serviceScope.launch {
            try {
                idleDimJob?.cancel()
                snapAnimator?.cancel()
                floatingView?.animate()?.cancel()
                floatingView?.visibility = View.GONE

                delay(90)

                val bitmap = screenCaptureManager.captureScreen()
                if (bitmap != null) {
                    val autoRect = if (useSavedRect) lastCropRect else null
                    onCaptureSuccess(bitmap, autoRect)
                } else {
                    restoreFloatingButton()
                }
            } finally {
                isCapturing = false
            }
        }
    }

    private fun restoreFloatingButton() {
        floatingView?.let { v ->
            v.alpha = 1.0f
            v.scaleX = 1.0f
            v.scaleY = 1.0f
            v.visibility = View.VISIBLE
        }
        iconCanvasView?.invalidate()
        scheduleIdleDimming()
    }

    private fun onCaptureSuccess(bitmap: Bitmap, autoCropRect: Rect?) {
        removeCropOverlay()

        val overlayParams = WindowManager.LayoutParams(
            bitmap.width,
            bitmap.height,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 0
            y = 0
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
            windowAnimations = 0
        }

        val composeOwner = OverlayComposeLifecycleOwner()
        composeOwner.onCreate()
        activeComposeOwner = composeOwner

        val dismissAction = {
            removeCropOverlay()
            restoreFloatingButton()
            mainHandler.postDelayed({
                if (!bitmap.isRecycled) {
                    bitmap.recycle()
                }
            }, 200L)
        }

        val rootContainer = object : FrameLayout(this) {
            override fun dispatchKeyEvent(event: KeyEvent): Boolean {
                if (event.keyCode == KeyEvent.KEYCODE_BACK && event.action == KeyEvent.ACTION_UP) {
                    dismissAction()
                    return true
                }
                return super.dispatchKeyEvent(event)
            }
        }.apply {
            isFocusable = true
            isFocusableInTouchMode = true
            setViewTreeLifecycleOwner(composeOwner)
            setViewTreeViewModelStoreOwner(composeOwner)
            setViewTreeSavedStateRegistryOwner(composeOwner)
        }

        val composeView = ComposeView(this).apply {
            setContent {
                CropOverlayScreen(
                    screenshotBitmap = bitmap,
                    translationManager = translationManager,
                    initialCropRect = autoCropRect,
                    onSaveCropRect = { newRect ->
                        saveLastCropRectToPrefs(newRect)
                    },
                    onDismiss = dismissAction
                )
            }
        }

        rootContainer.addView(
            composeView,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        )

        cropOverlayView = rootContainer
        windowManager.addView(rootContainer, overlayParams)
        rootContainer.requestFocus()
    }

    private fun saveLastCropRectToPrefs(rect: Rect) {
        lastCropRect = rect
        cropPrefs.edit()
            .putBoolean(KEY_HAS_LAST_RECT, true)
            .putFloat(KEY_RECT_LEFT, rect.left)
            .putFloat(KEY_RECT_TOP, rect.top)
            .putFloat(KEY_RECT_RIGHT, rect.right)
            .putFloat(KEY_RECT_BOTTOM, rect.bottom)
            .apply()
        iconCanvasView?.invalidate()
    }

    private fun loadLastCropRectFromPrefs(): Rect? {
        if (!cropPrefs.getBoolean(KEY_HAS_LAST_RECT, false)) return null
        val left = cropPrefs.getFloat(KEY_RECT_LEFT, 0f)
        val top = cropPrefs.getFloat(KEY_RECT_TOP, 0f)
        val right = cropPrefs.getFloat(KEY_RECT_RIGHT, 0f)
        val bottom = cropPrefs.getFloat(KEY_RECT_BOTTOM, 0f)
        return if (right - left >= 40f && bottom - top >= 40f) {
            Rect(left, top, right, bottom)
        } else null
    }

    private fun removeCropOverlay() {
        cropOverlayView?.let { view ->
            if (view.isAttachedToWindow) {
                windowManager.removeView(view)
            }
        }
        cropOverlayView = null
        activeComposeOwner?.onDestroy()
        activeComposeOwner = null
    }

    private fun dpToPx(dp: Float): Int {
        return TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            dp,
            resources.displayMetrics
        ).toInt()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Dịch vụ chụp màn hình",
                NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(): Notification {
        val stopIntent = Intent(this, FloatingWindowService::class.java).apply {
            action = ACTION_STOP_SERVICE
        }
        val stopPendingIntent = PendingIntent.getService(
            this,
            0,
            stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Screen Translator đang chạy")
            .setContentText("Nhấn giữ nút A/V để bật Auto-Watch phụ đề")
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .addAction(
                android.R.drawable.ic_menu_close_clear_cancel,
                "Tắt nút nổi",
                stopPendingIntent
            )
            .build()
    }

    override fun onDestroy() {
        super.onDestroy()
        stopAutoWatchLoop()
        idleDimJob?.cancel()
        singleTapJob?.cancel()
        longPressJob?.cancel()
        snapAnimator?.cancel()
        serviceScope.cancel()
        removeCropOverlay()
        floatingView?.let {
            it.animate().cancel()
            if (it.isAttachedToWindow) {
                windowManager.removeView(it)
            }
        }
        floatingView = null
        screenCaptureManager.releaseAll()
        translationManager.release()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}

private class OverlayComposeLifecycleOwner :
    SavedStateRegistryOwner, ViewModelStoreOwner {
    private val lifecycleRegistry = LifecycleRegistry(this)
    private val savedStateRegistryController = SavedStateRegistryController.create(this)
    private val store = ViewModelStore()

    override val lifecycle: Lifecycle get() = lifecycleRegistry
    override val savedStateRegistry: SavedStateRegistry
        get() = savedStateRegistryController.savedStateRegistry
    override val viewModelStore: ViewModelStore get() = store

    fun onCreate() {
        savedStateRegistryController.performRestore(Bundle())
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_START)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)
    }

    fun onDestroy() {
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_PAUSE)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_STOP)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY)
        store.clear()
    }
}
