# Keep AccessibilityService subclass (referenced by system via manifest)
-keep class com.callstream.recorder.service.CallAccessibilityService { *; }

# Keep Service subclass
-keep class com.callstream.recorder.service.CallRecordingService { *; }

# Keep data model (used with reflection-free code, but safe to keep)
-keep class com.callstream.recorder.model.** { *; }

# Compose internals
-dontwarn androidx.compose.**
