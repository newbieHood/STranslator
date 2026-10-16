package com.example.screencapture

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Bitmap
import android.widget.Toast
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

@Composable
fun CropOverlayScreen(
    screenshotBitmap: Bitmap,
    translationManager: TranslationManager,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val density = LocalDensity.current
    val coroutineScope = rememberCoroutineScope()

    var dragStart by remember { mutableStateOf<Offset?>(null) }
    var dragEnd by remember { mutableStateOf<Offset?>(null) }
    var isLoading by remember { mutableStateOf(false) }
    var translatedText by remember { mutableStateOf<String?>(null) }

    var cardSizePx by remember { mutableStateOf(IntSize.Zero) }

    val screenWidthPx = screenshotBitmap.width
    val screenHeightPx = screenshotBitmap.height

    val cropRect: Rect? = remember(dragStart, dragEnd) {
        val start = dragStart
        val end = dragEnd
        if (start != null && end != null) {
            Rect(
                left = min(start.x, end.x).coerceIn(0f, screenWidthPx.toFloat()),
                top = min(start.y, end.y).coerceIn(0f, screenHeightPx.toFloat()),
                right = max(start.x, end.x).coerceIn(0f, screenWidthPx.toFloat()),
                bottom = max(start.y, end.y).coerceIn(0f, screenHeightPx.toFloat())
            )
        } else null
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null
            ) {
                if (!isLoading) {
                    onDismiss()
                }
            }
            .pointerInput(isLoading, translatedText) {
                if (!isLoading && translatedText == null) {
                    detectDragGestures(
                        onDragStart = { offset ->
                            dragStart = offset
                            dragEnd = offset
                        },
                        onDrag = { change, _ ->
                            change.consume()
                            dragEnd = change.position
                        },
                        onDragEnd = {
                            val rect = cropRect
                            if (rect == null || rect.width < 40f || rect.height < 40f) {
                                dragStart = null
                                dragEnd = null
                                onDismiss()
                                return@detectDragGestures
                            }

                            isLoading = true
                            coroutineScope.launch {
                                val croppedBitmap = cropBitmapSafely(screenshotBitmap, rect)
                                if (croppedBitmap != null) {
                                    val result = translationManager.recognizeAndTranslate(croppedBitmap)
                                    if (croppedBitmap !== screenshotBitmap && !croppedBitmap.isRecycled) {
                                        croppedBitmap.recycle()
                                    }
                                    isLoading = false
                                    if (result.isBlank()) {
                                        onDismiss()
                                    } else {
                                        translatedText = result
                                    }
                                } else {
                                    isLoading = false
                                    onDismiss()
                                }
                            }
                        }
                    )
                }
            }
    ) {
        Image(
            bitmap = screenshotBitmap.asImageBitmap(),
            contentDescription = "Screenshot Background",
            contentScale = ContentScale.None,
            alignment = Alignment.TopStart,
            modifier = Modifier.fillMaxSize()
        )

        Canvas(
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer(compositingStrategy = CompositingStrategy.Offscreen)
        ) {
            drawRect(color = Color.Black.copy(alpha = 0.55f))

            cropRect?.let { rect ->
                drawRect(
                    color = Color.Transparent,
                    topLeft = Offset(rect.left, rect.top),
                    size = Size(rect.width, rect.height),
                    blendMode = BlendMode.Clear
                )
                drawRect(
                    color = Color.White,
                    topLeft = Offset(rect.left, rect.top),
                    size = Size(rect.width, rect.height),
                    style = Stroke(width = 2.dp.toPx())
                )
            }
        }

        if (isLoading && cropRect != null) {
            val indicatorSizeDp = 42.dp
            val indicatorSizePx = with(density) { indicatorSizeDp.roundToPx() }
            val centerX = (cropRect.center.x - indicatorSizePx / 2f).roundToInt()
            val centerY = (cropRect.center.y - indicatorSizePx / 2f).roundToInt()

            CircularProgressIndicator(
                color = Color.White,
                strokeWidth = 3.5.dp,
                modifier = Modifier
                    .offset { IntOffset(centerX, centerY) }
                    .size(indicatorSizeDp)
            )
        }

        val currentResult = translatedText
        if (!isLoading && currentResult != null && cropRect != null) {
            val horizontalMarginPx = 32
            val maxCardHeightPx = with(density) { 220.dp.roundToPx() }
            val estimatedCardHeightPx = if (cardSizePx.height > 0) cardSizePx.height else maxCardHeightPx

            val spaceBelowPx = screenHeightPx - cropRect.bottom
            val targetYPx = if (spaceBelowPx >= 700f) {
                (cropRect.bottom + 16f).roundToInt()
            } else {
                val aboveYPx = (cropRect.top - estimatedCardHeightPx - 16f).roundToInt()
                if (aboveYPx >= 64) {
                    aboveYPx
                } else {
                    ((screenHeightPx - 200 - estimatedCardHeightPx) / 2).coerceAtLeast(64)
                }
            }

            val cardWidthPx = if (cardSizePx.width > 0) cardSizePx.width else (screenWidthPx - horizontalMarginPx * 2)
            val targetXPx = cropRect.left.roundToInt().coerceIn(
                horizontalMarginPx,
                max(horizontalMarginPx, screenWidthPx - cardWidthPx - horizontalMarginPx)
            )

            Card(
                shape = RoundedCornerShape(12.dp),
                colors = CardDefaults.cardColors(
                    containerColor = Color.Black.copy(alpha = 0.85f)
                ),
                modifier = Modifier
                    .offset { IntOffset(targetXPx, targetYPx) }
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp)
                    .onSizeChanged { cardSizePx = it }
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null
                    ) { /* Chặn click-through xuống nền */ }
            ) {
                Column(modifier = Modifier.padding(14.dp)) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 220.dp)
                            .verticalScroll(rememberScrollState())
                    ) {
                        Text(
                            text = currentResult,
                            color = Color.White,
                            fontSize = 15.sp,
                            lineHeight = 22.sp
                        )
                    }

                    Spacer(modifier = Modifier.height(8.dp))
                    HorizontalDivider(color = Color.White.copy(alpha = 0.2f))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        TextButton(
                            onClick = {
                                val clipboard =
                                    context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                val clip = ClipData.newPlainText("Translated Text", currentResult)
                                clipboard.setPrimaryClip(clip)
                                Toast.makeText(context, "Đã sao chép bản dịch", Toast.LENGTH_SHORT).show()
                            }
                        ) {
                            Text(
                                text = "Copy",
                                color = Color(0xFF8AB4F8),
                                fontSize = 13.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                        }

                        Spacer(modifier = Modifier.width(4.dp))

                        TextButton(onClick = onDismiss) {
                            Text(
                                text = "Đóng",
                                color = Color(0xFFF28B82),
                                fontSize = 13.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                    }
                }
            }
        }
    }
}

private fun cropBitmapSafely(source: Bitmap, rect: Rect): Bitmap? {
    return try {
        val x = rect.left.roundToInt().coerceIn(0, source.width - 1)
        val y = rect.top.roundToInt().coerceIn(0, source.height - 1)
        val width = rect.width.roundToInt().coerceAtMost(source.width - x)
        val height = rect.height.roundToInt().coerceAtMost(source.height - y)

        if (width < 32 || height < 32) return null
        Bitmap.createBitmap(source, x, y, width, height)
    } catch (e: Exception) {
        null
    }
}
