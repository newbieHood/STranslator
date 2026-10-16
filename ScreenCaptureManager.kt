package com.example.screencapture

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.WindowManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

class ScreenCaptureManager(
    private val context: Context,
    private val onProjectionStopped: (() -> Unit)? = null
) {
    companion object {
        private const val TAG = "ScreenCaptureManager"
        private const val VIRTUAL_DISPLAY_NAME = "OneUI8_ScreenCapture"
        private const val MAX_IMAGES = 2
        private const val MAX_RETRIES = 3
        private const val RETRY_DELAY_MS = 45L
    }

    private val mediaProjectionManager =
        context.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
    private val windowManager =
        context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val mainHandler = Handler(Looper.getMainLooper())

    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null

    private var screenWidth: Int = 0
    private var screenHeight: Int = 0
    private var screenDensity: Int = 0

    val isReady: Boolean
        get() = mediaProjection != null && virtualDisplay != null && imageReader != null

    private val projectionCallback = object : MediaProjection.Callback() {
        override fun onStop() {
            super.onStop()
            Log.d(TAG, "MediaProjection đã dừng từ hệ thống. Giải phóng tài nguyên...")
            cleanupVirtualDisplayAndReader()
            mediaProjection = null
            onProjectionStopped?.invoke()
        }
    }

    fun initialize(resultCode: Int, resultData: Intent) {
        // Nếu khởi tạo lại từ đầu, dọn dẹp phiên cũ trước để tránh rò rỉ
        if (isReady) {
            releaseAll()
        }

        val maxBounds = windowManager.maximumWindowMetrics.bounds
        screenWidth = maxBounds.width()
        screenHeight = maxBounds.height()
        screenDensity = context.resources.configuration.densityDpi

        val projection = mediaProjectionManager.getMediaProjection(resultCode, resultData)
            ?: throw IllegalStateException("Không thể lấy MediaProjection từ resultData.")

        mediaProjection = projection

        // BẮT BUỘC trên Android 14 - 16: Đăng ký Callback TRƯỚC KHI gọi createVirtualDisplay
        projection.registerCallback(projectionCallback, mainHandler)

        val reader = ImageReader.newInstance(
            screenWidth,
            screenHeight,
            PixelFormat.RGBA_8888,
            MAX_IMAGES
        )
        imageReader = reader

        virtualDisplay = projection.createVirtualDisplay(
            VIRTUAL_DISPLAY_NAME,
            screenWidth,
            screenHeight,
            screenDensity,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            reader.surface,
            null,
            mainHandler
        )
    }

    /**
     * Tự động kiểm tra và cập nhật độ phân giải nếu người dùng xoay màn hình (Dọc <-> Ngang khi vào Game)
     * Sử dụng virtualDisplay.resize() hợp chuẩn Android 14-16 mà không cần tạo VirtualDisplay mới.
     */
    private suspend fun syncDisplaySizeIfRotated(): Boolean = withContext(Dispatchers.Main) {
        val vDisplay = virtualDisplay ?: return@withContext false
        val maxBounds = windowManager.maximumWindowMetrics.bounds
        val newWidth = maxBounds.width()
        val newHeight = maxBounds.height()
        val newDensity = context.resources.configuration.densityDpi

        if (newWidth == screenWidth && newHeight == screenHeight && imageReader != null) {
            return@withContext false
        }

        Log.d(TAG, "Phát hiện xoay màn hình: ${screenWidth}x${screenHeight} -> ${newWidth}x${newHeight}")
        screenWidth = newWidth
        screenHeight = newHeight
        screenDensity = newDensity

        val oldReader = imageReader
        val newReader = ImageReader.newInstance(
            screenWidth,
            screenHeight,
            PixelFormat.RGBA_8888,
            MAX_IMAGES
        )
        imageReader = newReader

        // Cập nhật kích thước và Surface mới cho VirtualDisplay hiện tại
        vDisplay.resize(screenWidth, screenHeight, screenDensity)
        vDisplay.surface = newReader.surface

        try {
            oldReader?.close()
        } catch (_: Exception) {
        }
        return@withContext true
    }

    suspend fun captureScreen(): Bitmap? = withContext(Dispatchers.Default) {
        if (!isReady) return@withContext null

        // Kiểm tra nếu vừa xoay màn hình sang ngang/dọc thì chờ thêm 60ms để Surface mới nhận frame
        val wasRotated = syncDisplaySizeIfRotated()
        if (wasRotated) {
            delay(60L)
        }

        val reader = imageReader ?: return@withContext null
        var image: Image? = null
        var attempt = 0

        while (attempt <= MAX_RETRIES) {
            image = try {
                reader.acquireLatestImage()
            } catch (e: Exception) {
                null
            }

            if (image != null) break
            if (attempt < MAX_RETRIES) {
                delay(RETRY_DELAY_MS)
            }
            attempt++
        }

        if (image == null) return@withContext null

        try {
            val width = image.width
            val height = image.height
            val plane = image.planes[0]
            val buffer = plane.buffer

            val pixelStride = plane.pixelStride
            val rowStride = plane.rowStride
            val rowPadding = rowStride - pixelStride * width

            val tempWidth = width + rowPadding / pixelStride
            val tempBitmap = Bitmap.createBitmap(tempWidth, height, Bitmap.Config.ARGB_8888)

            buffer.rewind()
            tempBitmap.copyPixelsFromBuffer(buffer)

            val finalBitmap = Bitmap.createBitmap(tempBitmap, 0, 0, width, height)
            if (finalBitmap !== tempBitmap) {
                tempBitmap.recycle()
            }

            return@withContext finalBitmap
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Lỗi chuyển đổi buffer sang Bitmap", e)
            return@withContext null
        } finally {
            image.close()
        }
    }

    private fun cleanupVirtualDisplayAndReader() {
        try {
            virtualDisplay?.release()
        } catch (_: Exception) {
        } finally {
            virtualDisplay = null
        }

        try {
            imageReader?.close()
        } catch (_: Exception) {
        } finally {
            imageReader = null
        }
    }

    fun releaseAll() {
        cleanupVirtualDisplayAndReader()
        mediaProjection?.let { projection ->
            try {
                projection.unregisterCallback(projectionCallback)
                projection.stop()
            } catch (_: Exception) {
            }
        }
        mediaProjection = null
    }
}
