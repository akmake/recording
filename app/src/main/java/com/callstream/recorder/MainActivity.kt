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
import com.callstream.recorder.call.CallType
import com.callstream.recorder.call.Recording
import com.callstream.recorder.service.CallAccessibilityService
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

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

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissionsGranted = hasAllPermissions(context) }

    val overlayGranted = Settings.canDrawOverlays(context)
    val accessibilityOn = isAccessibilityEnabled(context)
    val readyCount = listOf(permissionsGranted, overlayGranted, accessibilityOn).count { it }
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
                        onPermissions = { permissionLauncher.launch(RUNTIME_PERMISSIONS) },
                        onOverlay = {
                            context.startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${context.packageName}")))
                        },
                        onAccessibility = { context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
                    )
                }
                RecordingPreferencesCard(
                    autoRecord = autoRecord,
                    recordingMode = recordingMode,
                    onAutoRecordChange = {
                        autoRecord = it
                        RecorderPreferences.setAutoRecordEnabled(context, it)
                    },
                    onModeChange = {
                        recordingMode = it
                        RecorderPreferences.setRecordingMode(context, it)
                    }
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
                items(filtered, key = { it.wavPath }) { recording -> RecordingRow(recording) }
            }
        }
    }
}

@Composable
private fun RecordingPreferencesCard(
    autoRecord: Boolean,
    recordingMode: RecordingMode,
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
                        if (autoRecord) "ההקלטה תתחיל לבד בתחילת שיחה" else "שיחות לא יוקלטו אוטומטית",
                        color = Muted, fontSize = 13.sp
                    )
                }
                Switch(
                    checked = autoRecord,
                    onCheckedChange = onAutoRecordChange,
                    colors = SwitchDefaults.colors(checkedThumbColor = Color.White, checkedTrackColor = StreamTeal)
                )
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
    val allReady = readyCount == 3
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
                Text(if (allReady) "כל ההרשאות פעילות" else "$readyCount מתוך 3 שלבים הושלמו", color = Muted, fontSize = 13.sp)
            }
            Icon(if (expanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown, "פרטי הגדרה", tint = Muted)
        }
    }
}

@Composable
private fun SetupPanel(permissions: Boolean, overlay: Boolean, accessibility: Boolean, onPermissions: () -> Unit, onOverlay: () -> Unit, onAccessibility: () -> Unit) {
    Surface(Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(bottom = 8.dp), shape = RoundedCornerShape(18.dp), color = Color.White) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
            SetupRow("הרשאות שיחה ומיקרופון", permissions, onPermissions)
            HorizontalDivider(color = Divider)
            SetupRow("חלון הקלטה צף", overlay, onOverlay)
            HorizontalDivider(color = Divider)
            SetupRow("זיהוי שיחות WhatsApp", accessibility, onAccessibility)
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
private fun formatTime(millis: Long): String {
    val date = Date(millis)
    val today = SimpleDateFormat("yyyyMMdd", Locale.getDefault()).format(Date()) == SimpleDateFormat("yyyyMMdd", Locale.getDefault()).format(date)
    return SimpleDateFormat(if (today) "HH:mm" else "dd/MM", Locale.getDefault()).format(date)
}
