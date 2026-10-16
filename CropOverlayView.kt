package com.example.screencapture

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Bitmap
import android.widget.Toast
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
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
import com.google.mlkit.vision.text.Text as MlText
import kotlinx.coroutines.launch
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

@Composable
fun CropOverlayScreen(
    screenshotBitmap: Bitmap,
    translationManager: TranslationManager,
    initialCropRect: Rect? = null,
    onSaveCropRect: (Rect) -> Unit = {},
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val density = LocalDensity.current
    val coroutineScope = rememberCoroutineScope()

    val screenWidthPx = screenshotBitmap.width
    val screenHeightPx = screenshotBitmap.height

    var dragStart by remember { mutableStateOf<Offset?>(null) }
    var dragEnd by remember { mutableStateOf<Offset?>(null) }
    var isLoading by remember { mutableStateOf(false) }
    var translatedText by remember { mutableStateOf<String?>(null) }
    
    // TÍNH NĂNG 1: Lưu trữ kết quả VisionText để vẽ đè tọa độ (Lens Mode)
    var visionTextResult by remember { mutableStateOf<MlText?>(null) }
    var isOverlayLensMode by remember { mutableStateOf(false) }

    // Dialog thêm nhanh từ vào từ điển (Tính năng 3)
    var showAddGlossaryDialog by remember { mutableStateOf(false) }
    var quickWordToAdd by remember { mutableStateOf("") }

    var cardDragOffset by remember { mutableStateOf(Offset.Zero) }
    var cardSizePx by remember { mutableStateOf(IntSize.Zero) }

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

    fun executeCropAndTranslate(targetRect: Rect, saveAsLastRect: Boolean) {
        if (targetRect.width < 40f || targetRect.height < 40f) {
            dragStart = null
            dragEnd = null
            onDismiss()
            return
        }

        if (saveAsLastRect) {
            onSaveCropRect(targetRect)
        }

        isLoading = true
        coroutineScope.launch {
            val croppedBitmap = cropBitmapSafely(screenshotBitmap, targetRect)
            if (croppedBitmap != null) {
                val (vText, result) = try {
                    translationManager.recognizeAndGetVisionText(croppedBitmap)
                } finally {
                    if (croppedBitmap !== screenshotBitmap && !croppedBitmap.isRecycled) {
                        croppedBitmap.recycle()
                    }
                }
                isLoading = false
                visionTextResult = vText
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

    LaunchedEffect(initialCropRect) {
        if (initialCropRect != null) {
            val clampedRect = Rect(
                left = initialCropRect.left.coerceIn(0f, screenWidthPx.toFloat()),
                top = initialCropRect.top.coerceIn(0f, screenHeightPx.toFloat()),
                right = initialCropRect.right.coerceIn(0f, screenWidthPx.toFloat()),
                bottom = initialCropRect.bottom.coerceIn(0f, screenHeightPx.toFloat())
            )
            if (clampedRect.width >= 40f && clampedRect.height >= 40f) {
                dragStart = Offset(clampedRect.left, clampedRect.top)
                dragEnd = Offset(clampedRect.right, clampedRect.bottom)
                executeCropAndTranslate(clampedRect, saveAsLastRect = false)
            }
        }
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
            .pointerInput(isLoading) {
                if (!isLoading) {
                    detectDragGestures(
                        onDragStart = { offset ->
                            translatedText = null
                            visionTextResult = null
                            isOverlayLensMode = false
                            cardDragOffset = Offset.Zero
                            dragStart = offset
                            dragEnd = offset
                        },
                        onDrag = { change, _ ->
                            change.consume()
                            dragEnd = change.position
                        },
                        onDragEnd = {
                            val rect = cropRect
                            if (rect != null) {
                                executeCropAndTranslate(rect, saveAsLastRect = true)
                            } else {
                                onDismiss()
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

        // Canvas vẽ khung khoanh vùng hoặc vẽ đè nhãn dịch trực tiếp lên chữ gốc (Lens Mode)
        Canvas(
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer(compositingStrategy = CompositingStrategy.Offscreen)
        ) {
            if (isOverlayLensMode && visionTextResult != null && cropRect != null) {
                // TÍNH NĂNG 1: Chế độ Lens Mode - Vẽ các nhãn dịch đè lên vị trí boundingBox gốc
                drawRect(color = Color.Black.copy(alpha = 0.45f))
                
                visionTextResult?.textBlocks?.forEach { block ->
                    val box = block.boundingBox
                    if (box != null) {
                        val absLeft = cropRect.left + box.left
                        val absTop = cropRect.top + box.top
                        
                        // Vẽ khung nền vàng nhạt cho nhãn dịch đè lên chữ gốc
                        drawRect(
                            color = Color.Black.copy(alpha = 0.85f),
                            topLeft = Offset(absLeft, absTop),
                            size = Size(box.width().toFloat(), box.height().toFloat())
                        )
                        drawRect(
                            color = Color(0xFFFDD663),
                            topLeft = Offset(absLeft, absTop),
                            size = Size(box.width().toFloat(), box.height().toFloat()),
                            style = Stroke(width = 1.dp.toPx())
                        )
                    }
                }
            } else {
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
            val bottomNavSafePx = 150
            val maxCardHeightPx = with(density) { 250.dp.roundToPx() }
            val estimatedCardHeightPx = if (cardSizePx.height > 0) cardSizePx.height else maxCardHeightPx
            val cardWidthPx = if (cardSizePx.width > 0) cardSizePx.width else (screenWidthPx - horizontalMarginPx * 2)

            val spaceBelowPx = screenHeightPx - cropRect.bottom
            val baseYPx = if (spaceBelowPx >= 700f) {
                (cropRect.bottom + 16f).roundToInt()
            } else {
                val aboveYPx = (cropRect.top - estimatedCardHeightPx - 16f).roundToInt()
                if (aboveYPx >= 64) {
                    aboveYPx
                } else {
                    ((screenHeightPx - 200 - estimatedCardHeightPx) / 2).coerceAtLeast(64)
                }
            }

            val baseXPx = cropRect.left.roundToInt().coerceIn(
                horizontalMarginPx,
                max(horizontalMarginPx, screenWidthPx - cardWidthPx - horizontalMarginPx)
            )

            val maxAllowedXPx = max(16, screenWidthPx - cardWidthPx - 16)
            val maxAllowedYPx = max(48, screenHeightPx - estimatedCardHeightPx - bottomNavSafePx)

            val finalXPx = (baseXPx + cardDragOffset.x.roundToInt()).coerceIn(16, maxAllowedXPx)
            val finalYPx = (baseYPx + cardDragOffset.y.roundToInt()).coerceIn(48, maxAllowedYPx)

            Card(
                shape = RoundedCornerShape(12.dp),
                colors = CardDefaults.cardColors(
                    containerColor = Color.Black.copy(alpha = 0.88f)
                ),
                modifier = Modifier
                    .offset { IntOffset(finalXPx, finalYPx) }
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp)
                    .onSizeChanged { cardSizePx = it }
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null
                    ) { }
                    .pointerInput(Unit) {
                        detectDragGestures { change, dragAmount ->
                            change.consume()
                            cardDragOffset += dragAmount
                        }
                    }
            ) {
                Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {

                    // Thanh kéo & nút chuyển chế độ Lens Mode (Tính năng 1)
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier
                                .width(40.dp)
                                .height(4.dp)
                                .clip(RoundedCornerShape(2.dp))
                                .background(Color.White.copy(alpha = 0.35f))
                        )

                        // Nút bật/tắt chế độ đè chữ gốc
                        TextButton(
                            onClick = { isOverlayLensMode = !isOverlayLensMode },
                            modifier = Modifier.height(28.dp)
                        ) {
                            Text(
                                text = if (isOverlayLensMode) "Xem Card" else "Đè chữ gốc",
                                color = Color(0xFF8AB4F8),
                                fontSize = 11.5.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(4.dp))

                    // Nội dung bản dịch
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

                    // Thanh công cụ: Khoanh lại | + Từ điển | 🔊 Loa | Copy | Đóng
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            TextButton(
                                onClick = {
                                    translatedText = null
                                    visionTextResult = null
                                    isOverlayLensMode = false
                                    dragStart = null
                                    dragEnd = null
                                    cardDragOffset = Offset.Zero
                                }
                            ) {
                                Text(
                                    text = "Khoanh lại",
                                    color = Color(0xFFFDD663),
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.SemiBold
                                )
                            }

                            // TÍNH NĂNG 3: Nút thêm nhanh từ vào Từ điển
                            TextButton(
                                onClick = { showAddGlossaryDialog = true }
                            ) {
                                Text(
                                    text = "+ Từ điển",
                                    color = Color(0xFF81C995),
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.SemiBold
                                )
                            }
                        }

                        Row(verticalAlignment = Alignment.CenterVertically) {
                            // TÍNH NĂNG 5: Phát âm bằng Text-to-Speech
                            TextButton(
                                onClick = {
                                    translationManager.ttsManager.speak(currentResult, isEnglish = false)
                                }
                            ) {
                                Text(
                                    text = "🔊",
                                    fontSize = 14.sp
                                )
                            }

                            TextButton(
                                onClick = {
                                    val clipboard =
                                        context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                    val clip = ClipData.newPlainText("Translated Text", currentResult)
                                    clipboard.setPrimaryClip(clip)
                                    Toast.makeText(context, "Đã sao chép", Toast.LENGTH_SHORT).show()
                                }
                            ) {
                                Text(
                                    text = "Copy",
                                    color = Color(0xFF8AB4F8),
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.SemiBold
                                )
                            }

                            TextButton(onClick = onDismiss) {
                                Text(
                                    text = "Đóng",
                                    color = Color(0xFFF28B82),
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.SemiBold
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    // Dialog thêm nhanh từ vào từ điển (Tính năng 3)
    if (showAddGlossaryDialog) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { showAddGlossaryDialog = false },
            title = { Text("Thêm nhanh vào Từ điển", fontSize = 16.sp) },
            text = {
                Column {
                    Text("Nhập từ tiếng Anh hoặc định dạng (Từ = Nghĩa):", fontSize = 12.sp)
                    Spacer(modifier = Modifier.height(8.dp))
                    androidx.compose.material3.OutlinedTextField(
                        value = quickWordToAdd,
                        onValueChange = { quickWordToAdd = it },
                        placeholder = { Text("VD: Boss hoặc Mana = Năng lượng") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true
                    )
                }
            },
            confirmButton = {
                androidx.compose.material3.Button(
                    onClick = {
                        if (quickWordToAdd.isNotBlank()) {
                            val currentRaw = translationManager.glossaryManager.getRawGlossary()
                            val updatedRaw = "$currentRaw\n${quickWordToAdd.trim()}"
                            translationManager.glossaryManager.saveRawGlossary(updatedRaw)
                            translationManager.clearCache()
                            showAddGlossaryDialog = false
                            quickWordToAdd = ""
                            Toast.makeText(context, "Đã thêm vào từ điển", Toast.LENGTH_SHORT).show()
                        }
                    }
                ) {
                    Text("Thêm")
                }
            },
            dismissButton = {
                androidx.compose.material3.TextButton(onClick = { showAddGlossaryDialog = false }) {
                    Text("Hủy")
                }
            }
        )
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
