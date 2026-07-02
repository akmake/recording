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
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.core.content.ContextCompat
import com.callstream.recorder.call.CallType
import com.callstream.recorder.call.Recording
import com.callstream.recorder.service.CallAccessibilityService
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val WhatsAppGreen = Color(0xFF00A884)
private val DeepGreen = Color(0xFF008069)
private val Ink = Color(0xFF111B21)
private val Muted = Color(0xFF667781)
private val Canvas = Color(0xFFF7F9F9)
private val Divider = Color(0xFFE9EDEF)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
                MaterialTheme(
                    colorScheme = lightColorScheme(
                        primary = WhatsAppGreen,
                        secondary = DeepGreen,
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
                SearchField(query) { query = it }
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("הקלטות אחרונות", fontSize = 19.sp, fontWeight = FontWeight.Bold, color = Ink)
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
private fun Header(onRefresh: () -> Unit) {
    Box(Modifier.fillMaxWidth().background(DeepGreen).statusBarsPadding().padding(20.dp)) {
        Column {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(44.dp).clip(RoundedCornerShape(14.dp)).background(Color.White.copy(alpha = .16f)), contentAlignment = Alignment.Center) {
                    Icon(Icons.Default.Phone, null, tint = Color.White)
                }
                Spacer(Modifier.width(12.dp))
                Column {
                    Text("CallStream", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 24.sp)
                    Text("כל השיחות שלך, במקום אחד", color = Color.White.copy(alpha = .76f), fontSize = 13.sp)
                }
                Spacer(Modifier.weight(1f))
                IconButton(onClick = onRefresh) { Icon(Icons.Default.Refresh, "רענון", tint = Color.White) }
            }
        }
    }
}

@Composable
private fun SetupSummary(readyCount: Int, expanded: Boolean, onClick: () -> Unit) {
    val allReady = readyCount == 3
    Surface(
        modifier = Modifier.fillMaxWidth().padding(16.dp).clickable(onClick = onClick),
        shape = RoundedCornerShape(18.dp), color = if (allReady) Color(0xFFE7F8F3) else Color(0xFFFFF6E5)
    ) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(42.dp).clip(CircleShape).background(if (allReady) WhatsAppGreen else Color(0xFFFFB020)), contentAlignment = Alignment.Center) {
                Icon(if (allReady) Icons.Default.Check else Icons.Default.Settings, null, tint = Color.White, modifier = Modifier.size(22.dp))
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(if (allReady) "ההקלטה האוטומטית פעילה" else "נשאר להשלים את ההגדרה", fontWeight = FontWeight.Bold, color = Ink)
                Text(if (allReady) "אנחנו מוכנים לשיחה הבאה" else "$readyCount מתוך 3 שלבים הושלמו", color = Muted, fontSize = 13.sp)
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
        Icon(if (done) Icons.Default.CheckCircle else Icons.Default.Settings, null, tint = if (done) WhatsAppGreen else Color(0xFFFFB020), modifier = Modifier.size(22.dp))
        Spacer(Modifier.width(12.dp))
        Text(label, Modifier.weight(1f), color = Ink, fontSize = 14.sp)
        if (!done) Text("הגדרה", color = DeepGreen, fontWeight = FontWeight.Bold, fontSize = 13.sp)
    }
}

@Composable
private fun SearchField(query: String, onChange: (String) -> Unit) {
    TextField(
        value = query, onValueChange = onChange,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
        placeholder = { Text("חיפוש לפי איש קשר", color = Muted) },
        leadingIcon = { Icon(Icons.Default.Search, null, tint = Muted) },
        trailingIcon = if (query.isNotEmpty()) {{ IconButton(onClick = { onChange("") }) { Icon(Icons.Default.Close, "נקה", tint = Muted) } }} else null,
        singleLine = true, shape = RoundedCornerShape(16.dp),
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

    Row(Modifier.fillMaxWidth().background(Color.White).padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(52.dp).clip(CircleShape).background(avatarColor(rec.label)), contentAlignment = Alignment.Center) {
            Text(rec.label.trim().firstOrNull()?.uppercase() ?: "?", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold)
        }
        Spacer(Modifier.width(13.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(rec.label, Modifier.weight(1f), color = Ink, fontWeight = FontWeight.SemiBold, fontSize = 16.sp, maxLines = 1)
                Text(formatTime(rec.startedAtMillis), color = Muted, fontSize = 12.sp)
            }
            Spacer(Modifier.height(4.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(if (rec.type == CallType.WHATSAPP) Icons.Default.Call else Icons.Default.Phone, null, tint = WhatsAppGreen, modifier = Modifier.size(15.dp))
                Spacer(Modifier.width(5.dp))
                Text("${typeLabel(rec.type)} · ${DateUtils.formatElapsedTime(rec.durationMillis / 1000)}", color = Muted, fontSize = 13.sp)
            }
        }
        Spacer(Modifier.width(8.dp))
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
            colors = IconButtonDefaults.filledIconButtonColors(containerColor = Color(0xFFE7F8F3), contentColor = DeepGreen)
        ) { Icon(if (playing) Icons.Default.Close else Icons.Default.PlayArrow, if (playing) "עצירה" else "ניגון") }
    }
    HorizontalDivider(Modifier.padding(start = 81.dp), color = Divider)
}

@Composable
private fun EmptyState(hasQuery: Boolean) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 36.dp, vertical = 52.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.size(92.dp).clip(CircleShape).background(Color(0xFFE7F8F3)), contentAlignment = Alignment.Center) {
            Icon(if (hasQuery) Icons.Default.Search else Icons.Default.Phone, null, tint = WhatsAppGreen, modifier = Modifier.size(42.dp))
        }
        Spacer(Modifier.height(18.dp))
        Text(if (hasQuery) "לא מצאנו הקלטות" else "עדיין אין כאן הקלטות", fontWeight = FontWeight.Bold, fontSize = 20.sp, color = Ink)
        Spacer(Modifier.height(7.dp))
        Text(if (hasQuery) "אפשר לנסות שם אחר" else "לאחר השלמת ההגדרה, השיחות החדשות יופיעו כאן באופן אוטומטי.", color = Muted, textAlign = TextAlign.Center, lineHeight = 20.sp)
    }
}

private fun avatarColor(label: String): Color = listOf(Color(0xFF7E57C2), Color(0xFF0097A7), Color(0xFFE57373), Color(0xFF5C6BC0), DeepGreen)[kotlin.math.abs(label.hashCode() % 5)]
private fun hasAllPermissions(context: Context) = RUNTIME_PERMISSIONS.all { ContextCompat.checkSelfPermission(context, it) == android.content.pm.PackageManager.PERMISSION_GRANTED }
private fun isAccessibilityEnabled(context: Context): Boolean {
    val enabled = Settings.Secure.getString(context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: return false
    val service = "${context.packageName}/${CallAccessibilityService::class.java.name}"
    return enabled.split(':').any { it.equals(service, ignoreCase = true) }
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
