package com.callstream.recorder.service

import android.content.Intent
import android.os.Build
import android.service.quicksettings.TileService
import com.callstream.recorder.call.CallType
import com.callstream.recorder.diagnostics.AppLog

/** Always-available manual fallback from Android Quick Settings. */
class ManualRecordTileService : TileService() {
    override fun onClick() {
        super.onClick()
        AppLog.i(this, "manual_tile_pressed")
        val intent = Intent(this, CallRecordingService::class.java).apply {
            action = CallRecordingService.ACTION_START
            putExtra(CallRecordingService.EXTRA_TYPE, CallType.UNKNOWN.name)
            putExtra(CallRecordingService.EXTRA_MANUAL, true)
        }
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(intent)
            else startService(intent)
        }.onFailure { AppLog.e(this, "manual_tile_start_failed", error = it) }
    }
}
