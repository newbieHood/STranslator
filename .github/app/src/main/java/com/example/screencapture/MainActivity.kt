package com.example.screencapture

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    private lateinit var translationManager: TranslationManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        translationManager = TranslationManager(applicationContext)

        setContent {
            MaterialTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    TranslatorMainScreen(
                        translationManager = translationManager,
                        modifier = Modifier.padding(innerPadding)
                    )
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        translationManager.release()
    }
}

@Composable
fun TranslatorMainScreen(
    translationManager: TranslationManager,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val coroutineScope = rememberCoroutineScope()

    var isDownloadingModel by remember { mutableStateOf(true) }
    var isModelReady by remember { mutableStateOf(false) }
    var showGlossaryDialog by remember { mutableStateOf(false) }

    var hasOverlayPermission by remember {
        mutableStateOf(Settings.canDrawOverlays(context))
    }
    var hasNotificationPermission by remember {
        mutableStateOf(checkNotificationPermission(context))
    }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                hasOverlayPermission = Settings.canDrawOverlays(context)
                hasNotificationPermission = checkNotificationPermission(context)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    LaunchedEffect(Unit) {
        isDownloadingModel = true
        isModelReady = translationManager.prepareLanguageModel()
        isDownloadingModel = false
    }

    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        hasNotificationPermission = granted
    }

    val mediaProjectionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK && result.data != null) {
            val serviceIntent = Intent(context, FloatingWindowService::class.java).apply {
                action = FloatingWindowService.ACTION_START_PROJECTION
                putExtra(FloatingWindowService.EXTRA_RESULT_CODE, result.resultCode)
                putExtra(FloatingWindowService.EXTRA_RESULT_DATA, result.data)
            }
            ContextCompat.startForegroundService(context, serviceIntent)
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = "Android Screen Translator",
            fontSize = 22.sp,
            fontWeight = FontWeight.Bold
        )
        Text(
            text = "Hybrid GTX Online + ML Kit Offline",
            fontSize = 13.sp,
            color = Color.Gray
        )

        Spacer(modifier = Modifier.height(24.dp))

        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = if (isModelReady) Color(0xFFE6F4EA) else Color(0xFFF1F3F4)
            )
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp)
            ) {
                when {
                    isDownloadingModel -> {
                        Text(
                            text = "Đang tải gói ngôn ngữ Offline (~30MB)...",
                            fontWeight = FontWeight.Medium,
                            color = Color.DarkGray
                        )
                        Spacer(modifier = Modifier.height(10.dp))
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    }
                    isModelReady -> {
                        Text(
                            text = "Sẵn sàng dịch Offline",
                            fontWeight = FontWeight.Bold,
                            color = Color(0xFF137333)
                        )
                    }
                    else -> {
                        Text(
                            text = "Chưa tải được gói ngôn ngữ Offline (~30MB). Hãy bật mạng lần đầu.",
                            color = Color(0xFFC5221F),
                            fontWeight = FontWeight.Medium
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        OutlinedButton(
                            onClick = {
                                coroutineScope.launch {
                                    isDownloadingModel = true
                                    isModelReady = translationManager.prepareLanguageModel()
                                    isDownloadingModel = false
                                }
                            }
                        ) {
                            Text("Thử tải lại")
                        }
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        OutlinedButton(
            onClick = { showGlossaryDialog = true },
            modifier = Modifier
                .fillMaxWidth()
                .height(48.dp)
        ) {
            Text(
                text = "Quản lý Từ điển & Giữ từ tiếng Anh",
                fontWeight = FontWeight.SemiBold
            )
        }

        Spacer(modifier = Modifier.height(16.dp))

        if (!hasOverlayPermission) {
            OutlinedButton(
                onClick = {
                    val intent = Intent(
                        Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:${context.packageName}")
                    )
                    context.startActivity(intent)
                },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("1. Cấp quyền Hiển thị trên ứng dụng khác (Overlay)")
            }
            Spacer(modifier = Modifier.height(12.dp))
        }

        if (!hasNotificationPermission) {
            OutlinedButton(
                onClick = {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                    }
                },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("2. Cấp quyền Thông báo (Foreground Service)")
            }
            Spacer(modifier = Modifier.height(12.dp))
        }

        Spacer(modifier = Modifier.height(8.dp))

        val canStartService = isModelReady && hasOverlayPermission && hasNotificationPermission

        Button(
            onClick = {
                val projectionManager =
                    context.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
                mediaProjectionLauncher.launch(projectionManager.createScreenCaptureIntent())
            },
            enabled = canStartService,
            modifier = Modifier
                .fillMaxWidth()
                .height(52.dp)
        ) {
            Text(
                text = "Khởi động Nút Dịch Nổi",
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold
            )
        }

        Spacer(modifier = Modifier.height(12.dp))

        // Nút dừng nhanh Dịch vụ Nút Nổi
        OutlinedButton(
            onClick = {
                context.stopService(Intent(context, FloatingWindowService::class.java))
                Toast.makeText(context, "Đã tắt Nút Dịch Nổi", Toast.LENGTH_SHORT).show()
            },
            colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFFC5221F)),
            modifier = Modifier
                .fillMaxWidth()
                .height(46.dp)
        ) {
            Text(
                text = "Tắt Nút Dịch Nổi",
                fontWeight = FontWeight.SemiBold
            )
        }
    }

    if (showGlossaryDialog) {
        GlossaryManagementDialog(
            glossaryManager = translationManager.glossaryManager,
            onSaved = {
                translationManager.clearCache()
                showGlossaryDialog = false
                Toast.makeText(context, "Đã lưu từ điển & làm mới bộ nhớ đệm", Toast.LENGTH_SHORT).show()
            },
            onDismiss = { showGlossaryDialog = false }
        )
    }
}

@Composable
fun GlossaryManagementDialog(
    glossaryManager: GlossaryManager,
    onSaved: () -> Unit,
    onDismiss: () -> Unit
) {
    var rawText by remember { mutableStateOf(glossaryManager.getRawGlossary()) }
    var keepAllCaps by remember { mutableStateOf(glossaryManager.isKeepAllCaps()) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = "Từ điển & Giữ từ tiếng Anh",
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold
            )
        },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = "• Ghi 1 từ mỗi dòng (VD: Mana, Stun) để giữ nguyên tiếng Anh.\n• Ghi có dấu '=' (VD: Deck = Bộ bài) để ép dịch nghĩa cố định.",
                    fontSize = 13.sp,
                    color = Color.DarkGray,
                    lineHeight = 18.sp
                )

                Spacer(modifier = Modifier.height(12.dp))

                OutlinedTextField(
                    value = rawText,
                    onValueChange = { rawText = it },
                    label = { Text("Danh sách thuật ngữ (mỗi dòng 1 mục)") },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(200.dp),
                    maxLines = 15
                )

                Spacer(modifier = Modifier.height(12.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Giữ từ IN HOA (keepAllCaps)",
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 14.sp
                        )
                        Text(
                            text = "Tự giữ nguyên từ viết hoa từ 2 ký tự trở lên (trừ ATK, DMG, HP... vẫn bung nghĩa)",
                            fontSize = 12.sp,
                            color = Color.Gray
                        )
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    Switch(
                        checked = keepAllCaps,
                        onCheckedChange = { keepAllCaps = it }
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    glossaryManager.saveRawGlossary(rawText)
                    glossaryManager.setKeepAllCaps(keepAllCaps)
                    onSaved()
                }
            ) {
                Text("Lưu cài đặt")
            }
        },
        dismissButton = {
            Row {
                TextButton(
                    onClick = { rawText = GlossaryManager.DEFAULT_GLOSSARY }
                ) {
                    Text("Mặc định")
                }
                TextButton(onClick = onDismiss) {
                    Text("Hủy")
                }
            }
        }
    )
}

private fun checkNotificationPermission(context: Context): Boolean {
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.POST_NOTIFICATIONS
        ) == PackageManager.PERMISSION_GRANTED
    } else {
        true
    }
}
