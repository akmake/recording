package com.callstream.recorder

import android.Manifest
import android.content.Context
import android.content.Intent
import android.media.MediaPlayer
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.format.DateUtils
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.core.app.NotificationManagerCompat
import com.callstream.recorder.call.CallType
import com.callstream.recorder.call.Recording
import com.callstream.recorder.service.CallAccessibilityService
import com.callstream.recorder.service.CallRecordingService
import com.callstream.recorder.diagnostics.AppLog
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.delay

private val StreamTeal = Color(0xFF12B8A6)
private val DeepTeal = Color(0xFF073B3A)
private val NightTeal = Color(0xFF062C2C)
private val Coral = Color(0xFFFF6B5E)
private val Ink = Color(0xFF132323)
private val Muted = Color(0xFF667A79)
private val Canvas = Color(0xFFF3F7F6)
private val Divider = Color(0xFFE1EAE8)
private val SoftTeal = Color(0xFFE1F7F3)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
                MaterialTheme(
                    colorScheme = lightColorScheme(
                        primary = StreamTeal,
                        secondary = DeepTeal,
                        background = Canvas,
                        surface = Color.White,
                        onSurface = Ink
                    )
                ) { HomeScreen() }
            }
        }
    }
}

private val RUNTIME_PERMISSIONS: Array<String>
    get() = buildList {
        add(Manifest.permission.RECORD_AUDIO)
        add(Manifest.permission.READ_CONTACTS)
        add(Manifest.permission.READ_PHONE_STATE)
        add(Manifest.permission.READ_CALL_LOG)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) add(Manifest.permission.POST_NOTIFICATIONS)
    }.toTypedArray()

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun HomeScreen() {
    val context = LocalContext.current
    var recordings by remember { mutableStateOf(loadRecordings(context)) }
    var permissionsGranted by remember { mutableStateOf(hasAllPermissions(context)) }
    var query by remember { mutableStateOf("") }
    var showSetup by remember { mutableStateOf(false) }
    var autoRecord by remember { mutableStateOf(RecorderPreferences.isAutoRecordEnabled(context)) }
    var recordingMode by remember { mutableStateOf(RecorderPreferences.recordingMode(context)) }
    var showDiagnostics by remember { mutableStateOf(false) }
    var logText by remember { mutableStateOf("") }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissionsGranted = hasAllPermissions(context) }

    val overlayGranted = Settings.canDrawOverlays(context)
    val accessibilityOn = isAccessibilityEnabled(context)
    val notificationAccess = hasNotificationListenerAccess(context)
    val readyCount = listOf(permissionsGranted, overlayGranted, accessibilityOn, notificationAccess).count { it }
    val filtered = recordings.filter { it.label.contains(query, ignoreCase = true) }

    Scaffold(containerColor = Canvas) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(bottom = 32.dp)
        ) {
            item {
                Header(onRefresh = { recordings = loadRecordings(context) })
                SetupSummary(readyCount, showSetup) { showSetup = !showSetup }
                if (showSetup) {
                    SetupPanel(
                        permissionsGranted, overlayGranted, accessibilityOn,
                        notificationAccess = notificationAccess,
                        onPermissions = { permissionLauncher.launch(RUNTIME_PERMISSIONS) },
                        onOverlay = {
                            context.startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${context.packageName}")))
                        },
                        onAccessibility = { context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) },
                        onNotificationAccess = { context.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) }
                    )
                }
                RecordingPreferencesCard(
                    autoRecord = autoRecord,
                    recordingMode = recordingMode,
                    overlayGranted = overlayGranted,
                    onAutoRecordChange = {
                        autoRecord = it
                        RecorderPreferences.setAutoRecordEnabled(context, it)
                        if (it) stopManualStandby(context) else startManualStandby(context)
                    },
                    onModeChange = {
                        recordingMode = it
                        RecorderPreferences.setRecordingMode(context, it)
                    }
                )
                LaunchedEffect(Unit) {
                    if (!autoRecord) startManualStandby(context)
                }
                ManualFallbackCard(
                    onStart = { startManualRecording(context) },
                    onStop = { stopManualRecording(context) }
                )
                DiagnosticsCard(
                    expanded = showDiagnostics,
                    logs = logText,
                    onToggle = {
                        showDiagnostics = !showDiagnostics
                        if (showDiagnostics) logText = AppLog.tail(context)
                    },
                    onRefresh = { logText = AppLog.tail(context) },
                    onShare = { shareLogs(context) },
                    onClear = { AppLog.clear(context); logText = "" }
                )
                SearchField(query) { query = it }
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text("הספרייה שלי", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = Ink)
                        Text("כל השיחות, מסודרות במקום אחד", color = Muted, fontSize = 12.sp)
                    }
                    Spacer(Modifier.weight(1f))
                    if (recordings.isNotEmpty()) Text("${filtered.size} שיחות", color = Muted, fontSize = 13.sp)
                }
            }

            if (filtered.isEmpty()) {
                item { EmptyState(hasQuery = query.isNotBlank()) }
            } else {
                items(filtered, key = { it.wavPath }) { recording -> RecordingPlayerRow(recording) }
            }
        }
    }
}

@Composable
private fun RecordingPreferencesCard(
    autoRecord: Boolean,
    recordingMode: RecordingMode,
    overlayGranted: Boolean,
    onAutoRecordChange: (Boolean) -> Unit,
    onModeChange: (RecordingMode) -> Unit
) {
    Surface(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(bottom = 14.dp),
        shape = RoundedCornerShape(24.dp),
        color = Color.White
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier.size(46.dp).clip(RoundedCornerShape(15.dp)).background(SoftTeal),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(Icons.Default.Phone, null, tint = DeepTeal, modifier = Modifier.size(22.dp))
                }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text("טייס אוטומטי", fontWeight = FontWeight.Bold, color = Ink, fontSize = 16.sp)
                    Text(
                        if (autoRecord) "ההקלטה תתחיל לבד בתחילת שיחה"
                        else "כפתור הקלטה יופיע בזמן השיחה — לחיצה מתחילה להקליט",
                        color = Muted, fontSize = 13.sp
                    )
                }
                Switch(
                    checked = autoRecord,
                    onCheckedChange = onAutoRecordChange,
                    colors = SwitchDefaults.colors(checkedThumbColor = Color.White, checkedTrackColor = StreamTeal)
                )
            }

            // Manual recording leans on the floating button; without the overlay permission the
            // only way in is the notification, so say so instead of letting them hunt for it.
            AnimatedVisibility(visible = !autoRecord && !overlayGranted) {
                Row(
                    Modifier.padding(top = 12.dp).clip(RoundedCornerShape(12.dp))
                        .background(Color(0xFFFFF3E8)).padding(10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Default.Info, null, tint = Color(0xFFFFA24A), modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(
                        "בלי הרשאת \"חלון הקלטה צף\" הכפתור יופיע רק בהתראה שנפתחת בזמן השיחה",
                        color = Ink, fontSize = 12.sp, lineHeight = 16.sp
                    )
                }
            }

            AnimatedVisibility(visible = autoRecord) {
                Column {
                    HorizontalDivider(Modifier.padding(vertical = 16.dp), color = Divider)
                    Text("אילו שיחות להקליט?", color = Ink, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                    Spacer(Modifier.height(10.dp))
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        RecordingModeOption("הכול", RecordingMode.BOTH, recordingMode, onModeChange, Modifier.weight(1f))
                        RecordingModeOption("סלולרי", RecordingMode.CELLULAR, recordingMode, onModeChange, Modifier.weight(1f))
                        RecordingModeOption("WhatsApp", RecordingMode.WHATSAPP, recordingMode, onModeChange, Modifier.weight(1f))
                    }
                }
            }
        }
    }
}

@Composable
private fun RecordingModeOption(
    label: String,
    mode: RecordingMode,
    selectedMode: RecordingMode,
    onSelected: (RecordingMode) -> Unit,
    modifier: Modifier = Modifier
) {
    val selected = mode == selectedMode
    Surface(
        modifier = modifier.clickable { onSelected(mode) },
        shape = RoundedCornerShape(12.dp),
        color = if (selected) SoftTeal else Canvas,
        border = if (selected) androidx.compose.foundation.BorderStroke(1.5.dp, StreamTeal) else null
    ) {
        Text(
            label,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 11.dp),
            textAlign = TextAlign.Center,
            color = if (selected) DeepTeal else Muted,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
            fontSize = 13.sp,
            maxLines = 1
        )
    }
}

@Composable
private fun Header(onRefresh: () -> Unit) {
    Box(
        Modifier.fillMaxWidth().background(
            Brush.linearGradient(listOf(NightTeal, DeepTeal, Color(0xFF0B6661)))
        ).statusBarsPadding().padding(horizontal = 20.dp, vertical = 22.dp)
    ) {
        Column(Modifier.fillMaxWidth()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(48.dp).clip(RoundedCornerShape(16.dp)).background(StreamTeal), contentAlignment = Alignment.Center) {
                    Icon(Icons.Default.Call, null, tint = Color.White, modifier = Modifier.size(26.dp))
                }
                Spacer(Modifier.width(12.dp))
                Column {
                    Text("Reko", color = Color.White, fontWeight = FontWeight.ExtraBold, fontSize = 25.sp)
                    Text("השיחות שחשוב לזכור", color = Color.White.copy(alpha = .72f), fontSize = 13.sp)
                }
                Spacer(Modifier.weight(1f))
                FilledIconButton(
                    onClick = onRefresh,
                    colors = IconButtonDefaults.filledIconButtonColors(
                        containerColor = Color.White.copy(alpha = .12f), contentColor = Color.White
                    )
                ) { Icon(Icons.Default.Refresh, "רענון") }
            }
            Spacer(Modifier.height(24.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(10.dp).clip(CircleShape).background(Coral))
                Spacer(Modifier.width(8.dp))
                Text("מוכן להקלטה הבאה", color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
            }
        }
    }
}

@Composable
private fun SetupSummary(readyCount: Int, expanded: Boolean, onClick: () -> Unit) {
    val allReady = readyCount == 4
    Surface(
        modifier = Modifier.fillMaxWidth().padding(16.dp).clickable(onClick = onClick),
        shape = RoundedCornerShape(24.dp), color = if (allReady) SoftTeal else Color(0xFFFFF3E8)
    ) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(46.dp).clip(RoundedCornerShape(15.dp)).background(if (allReady) StreamTeal else Color(0xFFFFA24A)), contentAlignment = Alignment.Center) {
                Icon(if (allReady) Icons.Default.Check else Icons.Default.Settings, null, tint = Color.White, modifier = Modifier.size(22.dp))
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(if (allReady) "המערכת מוכנה" else "מסיימים הגדרה", fontWeight = FontWeight.Bold, color = Ink, fontSize = 16.sp)
                Text(if (allReady) "כל מנגנוני הגיבוי פעילים" else "$readyCount מתוך 4 שלבים הושלמו", color = Muted, fontSize = 13.sp)
            }
            Icon(if (expanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown, "פרטי הגדרה", tint = Muted)
        }
    }
}

@Composable
private fun SetupPanel(permissions: Boolean, overlay: Boolean, accessibility: Boolean, notificationAccess: Boolean, onPermissions: () -> Unit, onOverlay: () -> Unit, onAccessibility: () -> Unit, onNotificationAccess: () -> Unit) {
    Surface(Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(bottom = 8.dp), shape = RoundedCornerShape(18.dp), color = Color.White) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
            SetupRow("הרשאות שיחה ומיקרופון", permissions, onPermissions)
            HorizontalDivider(color = Divider)
            SetupRow("חלון הקלטה צף", overlay, onOverlay)
            HorizontalDivider(color = Divider)
            SetupRow("זיהוי שיחות WhatsApp", accessibility, onAccessibility)
            HorizontalDivider(color = Divider)
            SetupRow("גיבוי WhatsApp דרך התראות", notificationAccess, onNotificationAccess)
        }
    }
}

@Composable
private fun SetupRow(label: String, done: Boolean, onFix: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(enabled = !done, onClick = onFix).padding(vertical = 13.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(if (done) Icons.Default.CheckCircle else Icons.Default.Settings, null, tint = if (done) StreamTeal else Color(0xFFFFA24A), modifier = Modifier.size(22.dp))
        Spacer(Modifier.width(12.dp))
        Text(label, Modifier.weight(1f), color = Ink, fontSize = 14.sp)
        if (!done) Text("הגדרה", color = DeepTeal, fontWeight = FontWeight.Bold, fontSize = 13.sp)
    }
}

@Composable
private fun ManualFallbackCard(onStart: () -> Unit, onStop: () -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(bottom = 14.dp),
        shape = RoundedCornerShape(20.dp), color = Color.White
    ) {
        Column(Modifier.padding(16.dp)) {
            Text("שליטה ידנית — תמיד זמינה", fontWeight = FontWeight.Bold, color = Ink, fontSize = 16.sp)
            Text("עובד גם אם זיהוי השיחה, WhatsApp או החלון הצף נכשלו.", color = Muted, fontSize = 12.sp)
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Button(onClick = onStart, modifier = Modifier.weight(1f)) {
                    Icon(Icons.Default.Mic, null)
                    Spacer(Modifier.width(6.dp))
                    Text("הקלט עכשיו")
                }
                OutlinedButton(onClick = onStop, modifier = Modifier.weight(1f)) {
                    Icon(Icons.Default.Stop, null)
                    Spacer(Modifier.width(6.dp))
                    Text("עצור ושמור")
                }
            }
        }
    }
}

@Composable
private fun DiagnosticsCard(
    expanded: Boolean,
    logs: String,
    onToggle: () -> Unit,
    onRefresh: () -> Unit,
    onShare: () -> Unit,
    onClear: () -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(bottom = 14.dp),
        shape = RoundedCornerShape(20.dp), color = Color.White
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(Modifier.fillMaxWidth().clickable(onClick = onToggle), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.BugReport, null, tint = DeepTeal)
                Spacer(Modifier.width(10.dp))
                Text("אבחון ולוגים", Modifier.weight(1f), fontWeight = FontWeight.Bold, color = Ink)
                Icon(if (expanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown, null, tint = Muted)
            }
            AnimatedVisibility(expanded) {
                Column {
                    Spacer(Modifier.height(12.dp))
                    Surface(color = Canvas, shape = RoundedCornerShape(12.dp)) {
                        Text(
                            if (logs.isBlank()) "עדיין אין אירועים שמורים" else logs,
                            Modifier.fillMaxWidth().padding(10.dp), color = Ink, fontSize = 10.sp,
                            maxLines = 18
                        )
                    }
                    Spacer(Modifier.height(10.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(onClick = onRefresh) { Text("רענון") }
                        TextButton(onClick = onShare) { Text("שיתוף") }
                        TextButton(onClick = onClear) { Text("ניקוי") }
                    }
                }
            }
        }
    }
}

@Composable
private fun SearchField(query: String, onChange: (String) -> Unit) {
    TextField(
        value = query, onValueChange = onChange,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
        placeholder = { Text("חיפוש בהקלטות", color = Muted) },
        leadingIcon = { Icon(Icons.Default.Search, null, tint = Muted) },
        trailingIcon = if (query.isNotEmpty()) {{ IconButton(onClick = { onChange("") }) { Icon(Icons.Default.Close, "נקה", tint = Muted) } }} else null,
        singleLine = true, shape = RoundedCornerShape(20.dp),
        colors = TextFieldDefaults.colors(
            focusedContainerColor = Color.White, unfocusedContainerColor = Color.White,
            focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent
        )
    )
}

@Composable
private fun RecordingPlayerRow(rec: Recording) {
    var player by remember { mutableStateOf<MediaPlayer?>(null) }
    var playing by remember { mutableStateOf(false) }
    var positionMs by remember { mutableStateOf(0) }
    var durationMs by remember { mutableStateOf(rec.durationMillis.toInt().coerceAtLeast(0)) }
    var speed by remember { mutableStateOf(1f) }
    var dragging by remember { mutableStateOf(false) }

    DisposableEffect(Unit) {
        onDispose {
            player?.release()
            player = null
        }
    }

    LaunchedEffect(player, playing, dragging) {
        while (player != null && playing && !dragging) {
            positionMs = player?.currentPosition ?: positionMs
            delay(250)
        }
    }

    fun applySpeed(mediaPlayer: MediaPlayer, playbackSpeed: Float) {
        runCatching {
            mediaPlayer.playbackParams = mediaPlayer.playbackParams.setSpeed(playbackSpeed)
        }
    }

    fun ensurePlayer(): MediaPlayer {
        player?.let { return it }
        return MediaPlayer().apply {
            setDataSource(rec.wavPath)
            setOnCompletionListener {
                playing = false
                positionMs = 0
                seekTo(0)
            }
            prepare()
            durationMs = duration.coerceAtLeast(rec.durationMillis.toInt())
            if (positionMs > 0) seekTo(positionMs.coerceAtMost(durationMs))
            applySpeed(this, speed)
            player = this
        }
    }

    Surface(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 5.dp),
        shape = RoundedCornerShape(22.dp), color = Color.White,
        shadowElevation = 1.dp
    ) {
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(50.dp).clip(RoundedCornerShape(17.dp)).background(avatarColor(rec.label)), contentAlignment = Alignment.Center) {
                    Text(rec.label.trim().firstOrNull()?.uppercase() ?: "?", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                }
                Spacer(Modifier.width(13.dp))
                Column(Modifier.weight(1f)) {
                    Text(rec.label, color = Ink, fontWeight = FontWeight.Bold, fontSize = 16.sp, maxLines = 1)
                    Spacer(Modifier.height(5.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Call, null, tint = StreamTeal, modifier = Modifier.size(15.dp))
                        Spacer(Modifier.width(5.dp))
                        Text("${typeLabel(rec.type)} - ${DateUtils.formatElapsedTime(rec.durationMillis / 1000)} - ${formatTime(rec.startedAtMillis)}", color = Muted, fontSize = 12.sp)
                    }
                }
                val ctx = LocalContext.current
                IconButton(onClick = { shareMetrics(ctx, rec) }, modifier = Modifier.size(38.dp)) {
                    Icon(Icons.Default.Share, "share processing data", tint = Muted, modifier = Modifier.size(20.dp))
                }
            }

            Spacer(Modifier.height(12.dp))
            Slider(
                value = positionMs.coerceIn(0, durationMs).toFloat(),
                onValueChange = {
                    dragging = true
                    positionMs = it.toInt()
                },
                onValueChangeFinished = {
                    player?.seekTo(positionMs.coerceIn(0, durationMs))
                    dragging = false
                },
                valueRange = 0f..durationMs.coerceAtLeast(1).toFloat(),
                colors = SliderDefaults.colors(
                    thumbColor = DeepTeal,
                    activeTrackColor = StreamTeal,
                    inactiveTrackColor = Divider
                )
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(formatPlaybackTime(positionMs), color = Muted, fontSize = 12.sp)
                Spacer(Modifier.weight(1f))
                Text(formatPlaybackTime(durationMs), color = Muted, fontSize = 12.sp)
            }

            Spacer(Modifier.height(10.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                FilledIconButton(
                    onClick = {
                        val mediaPlayer = ensurePlayer()
                        if (playing) {
                            mediaPlayer.pause()
                            positionMs = mediaPlayer.currentPosition
                            playing = false
                        } else {
                            applySpeed(mediaPlayer, speed)
                            mediaPlayer.start()
                            playing = true
                        }
                    },
                    modifier = Modifier.size(42.dp),
                    colors = IconButtonDefaults.filledIconButtonColors(containerColor = if (playing) Coral else DeepTeal, contentColor = Color.White)
                ) {
                    if (playing) Text("II", fontWeight = FontWeight.Bold, fontSize = 16.sp) else Icon(Icons.Default.PlayArrow, "play")
                }

                Spacer(Modifier.width(8.dp))
                IconButton(
                    onClick = {
                        player?.pause()
                        player?.seekTo(0)
                        positionMs = 0
                        playing = false
                    },
                    modifier = Modifier.size(38.dp)
                ) {
                    Text("■", color = Muted, fontWeight = FontWeight.Bold, fontSize = 17.sp)
                }

                Spacer(Modifier.width(8.dp))
                Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.End)) {
                    listOf(0.75f, 1f, 1.5f, 2f).forEach { option ->
                        SpeedOption(
                            label = speedLabel(option),
                            selected = speed == option,
                            onClick = {
                                speed = option
                                player?.let { applySpeed(it, option) }
                            }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SpeedOption(label: String, selected: Boolean, onClick: () -> Unit) {
    Surface(
        modifier = Modifier.height(32.dp).clip(RoundedCornerShape(10.dp)).clickable(onClick = onClick),
        shape = RoundedCornerShape(10.dp),
        color = if (selected) SoftTeal else Canvas,
        border = if (selected) androidx.compose.foundation.BorderStroke(1.dp, StreamTeal) else null
    ) {
        Text(
            label,
            modifier = Modifier.padding(horizontal = 9.dp, vertical = 7.dp),
            color = if (selected) DeepTeal else Muted,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
            fontSize = 12.sp,
            maxLines = 1
        )
    }
}

@Composable
private fun RecordingRow(rec: Recording) {
    var player by remember { mutableStateOf<MediaPlayer?>(null) }
    var playing by remember { mutableStateOf(false) }
    DisposableEffect(Unit) { onDispose { player?.release() } }

    Surface(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 5.dp),
        shape = RoundedCornerShape(22.dp), color = Color.White,
        shadowElevation = 1.dp
    ) {
        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(50.dp).clip(RoundedCornerShape(17.dp)).background(avatarColor(rec.label)), contentAlignment = Alignment.Center) {
                Text(rec.label.trim().firstOrNull()?.uppercase() ?: "?", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold)
            }
            Spacer(Modifier.width(13.dp))
            Column(Modifier.weight(1f)) {
                Text(rec.label, color = Ink, fontWeight = FontWeight.Bold, fontSize = 16.sp, maxLines = 1)
                Spacer(Modifier.height(5.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Call, null, tint = StreamTeal, modifier = Modifier.size(15.dp))
                    Spacer(Modifier.width(5.dp))
                    Text("${typeLabel(rec.type)} · ${DateUtils.formatElapsedTime(rec.durationMillis / 1000)} · ${formatTime(rec.startedAtMillis)}", color = Muted, fontSize = 12.sp)
                }
            }
            val ctx = LocalContext.current
            IconButton(onClick = { shareMetrics(ctx, rec) }, modifier = Modifier.size(38.dp)) {
                Icon(Icons.Default.Share, "ייצוא נתוני עיבוד", tint = Muted, modifier = Modifier.size(20.dp))
            }
            Spacer(Modifier.width(4.dp))
            FilledIconButton(
                onClick = {
                    if (playing) { player?.stop(); player?.release(); player = null; playing = false }
                    else player = MediaPlayer().apply {
                        setDataSource(rec.wavPath)
                        setOnCompletionListener { playing = false; release(); player = null }
                        prepare(); start(); playing = true
                    }
                },
                modifier = Modifier.size(42.dp),
                colors = IconButtonDefaults.filledIconButtonColors(containerColor = if (playing) Coral else DeepTeal, contentColor = Color.White)
            ) { Icon(if (playing) Icons.Default.Close else Icons.Default.PlayArrow, if (playing) "עצירה" else "ניגון") }
        }
    }
}

@Composable
private fun EmptyState(hasQuery: Boolean) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 36.dp, vertical = 52.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.size(92.dp).clip(RoundedCornerShape(30.dp)).background(SoftTeal), contentAlignment = Alignment.Center) {
            Icon(if (hasQuery) Icons.Default.Search else Icons.Default.Phone, null, tint = DeepTeal, modifier = Modifier.size(42.dp))
        }
        Spacer(Modifier.height(18.dp))
        Text(if (hasQuery) "לא מצאנו הקלטות" else "עדיין אין כאן הקלטות", fontWeight = FontWeight.Bold, fontSize = 20.sp, color = Ink)
        Spacer(Modifier.height(7.dp))
        Text(if (hasQuery) "אפשר לנסות שם אחר" else "לאחר השלמת ההגדרה, השיחות החדשות יופיעו כאן באופן אוטומטי.", color = Muted, textAlign = TextAlign.Center, lineHeight = 20.sp)
    }
}

private fun avatarColor(label: String): Color = listOf(Color(0xFF6750A4), Color(0xFF087F8C), Coral, Color(0xFF3F6B9C), DeepTeal)[kotlin.math.abs(label.hashCode() % 5)]
private fun hasAllPermissions(context: Context) = RUNTIME_PERMISSIONS.all { ContextCompat.checkSelfPermission(context, it) == android.content.pm.PackageManager.PERMISSION_GRANTED }
private fun isAccessibilityEnabled(context: Context): Boolean {
    val enabled = Settings.Secure.getString(context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: return false
    val service = "${context.packageName}/${CallAccessibilityService::class.java.name}"
    return enabled.split(':').any { it.equals(service, ignoreCase = true) }
}

private fun hasNotificationListenerAccess(context: Context): Boolean =
    NotificationManagerCompat.getEnabledListenerPackages(context).contains(context.packageName)

private fun startManualStandby(context: Context) {
    val intent = Intent(context, CallRecordingService::class.java).apply { action = CallRecordingService.ACTION_ARM }
    AppLog.i(context, "manual_standby_requested")
    runCatching {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) context.startForegroundService(intent)
        else context.startService(intent)
    }.onFailure { AppLog.e(context, "manual_standby_failed", error = it) }
}

private fun stopManualStandby(context: Context) {
    runCatching { context.startService(Intent(context, CallRecordingService::class.java).apply { action = CallRecordingService.ACTION_STOP }) }
}

private fun startManualRecording(context: Context) {
    val intent = Intent(context, CallRecordingService::class.java).apply {
        action = CallRecordingService.ACTION_START
        putExtra(CallRecordingService.EXTRA_MANUAL, true)
        putExtra(CallRecordingService.EXTRA_TYPE, CallType.UNKNOWN.name)
    }
    AppLog.i(context, "manual_app_start_pressed")
    runCatching {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) context.startForegroundService(intent)
        else context.startService(intent)
    }.onFailure { AppLog.e(context, "manual_app_start_failed", error = it) }
}

private fun stopManualRecording(context: Context) {
    AppLog.i(context, "manual_app_stop_pressed")
    runCatching {
        context.startService(Intent(context, CallRecordingService::class.java).apply {
            action = CallRecordingService.ACTION_STOP
            putExtra(CallRecordingService.EXTRA_REARM, true)
        })
    }.onFailure { AppLog.e(context, "manual_app_stop_failed", error = it) }
}

private fun shareLogs(context: Context) {
    val file = AppLog.file(context)
    if (!file.exists()) return
    val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
    context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_STREAM, uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }, "שיתוף לוגים של Reko"))
}
/**
 * Shares everything needed to analyze a recording offline: the raw (un-normalized) WAV, the
 * processed WAV, and the two per-second metrics CSVs. With the raw WAV the processing can be
 * re-tuned on the real captured signal, not just its metrics.
 */
private fun shareMetrics(context: Context, rec: Recording) {
    val files = listOf(
        rec.rawWavFile(),        // un-normalized capture
        rec.file,                // processed WAV (what you hear in the app)
        rec.rawMetricsFile(),
        rec.processedMetricsFile(),
    ).filter { it.exists() }
    if (files.isEmpty()) {
        Toast.makeText(context, "אין קובצי נתונים להקלטה הזו (הוקלטה לפני העדכון)", Toast.LENGTH_LONG).show()
        return
    }
    val uris = ArrayList(files.map {
        FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", it)
    })
    val intent = Intent(Intent.ACTION_SEND_MULTIPLE).apply {
        type = "*/*"  // mixed: audio/wav + text/csv
        putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    context.startActivity(Intent.createChooser(intent, "ייצוא הקלטה + נתונים (${files.size} קבצים)"))
}

private fun loadRecordings(context: Context): List<Recording> {
    val dir = File(context.getExternalFilesDir(null), "recordings")
    return dir.listFiles { file -> file.extension == "json" }?.mapNotNull(Recording::fromSidecar)?.filter { File(it.wavPath).exists() }?.sortedByDescending { it.startedAtMillis } ?: emptyList()
}
private fun typeLabel(type: CallType) = when (type) { CallType.CELLULAR -> "שיחה סלולרית"; CallType.WHATSAPP -> "WhatsApp"; CallType.UNKNOWN -> "שיחה" }
private fun formatPlaybackTime(millis: Int): String = DateUtils.formatElapsedTime((millis / 1000).toLong())
private fun speedLabel(speed: Float): String = if (speed % 1f == 0f) "${speed.toInt()}x" else "${speed}x"

private fun formatTime(millis: Long): String {
    val date = Date(millis)
    val today = SimpleDateFormat("yyyyMMdd", Locale.getDefault()).format(Date()) == SimpleDateFormat("yyyyMMdd", Locale.getDefault()).format(date)
    return SimpleDateFormat(if (today) "HH:mm" else "dd/MM", Locale.getDefault()).format(date)
}
