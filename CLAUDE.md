# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

Android Camera Server - HTTP API server for Android devices enabling remote camera control via web interface. Designed for photo booth and automated imaging systems.

- **Language**: Kotlin
- **Min SDK**: API 24 (Android 7.0), Target SDK: 34
- **Package**: com.cameraserver.usb

## Build Commands

```bash
# Debug build
./gradlew assembleDebug

# Release build (requires release-key.jks)
./gradlew assembleRelease

# Clean build
./gradlew clean assembleRelease

# Install via ADB
adb install app/build/outputs/apk/release/app-release.apk

# USB port forwarding for testing
adb forward tcp:8080 tcp:8080
```

## Testing

```bash
# Selenium tests (requires server running with port forwarded)
python test_selenium.py
```

## Architecture

```
app/src/main/java/com/cameraserver/usb/
├── CameraServerApplication.kt      # App entry point
├── MainActivity.kt                 # UI activity (Start/Stop controls)
├── CameraService.kt                # Foreground service - main orchestrator
├── admin/
│   ├── DeviceAdminReceiver.kt      # Device Admin handler
│   └── DeviceOwnerManager.kt       # Device Owner mode utilities
├── boot/
│   └── BootReceiver.kt             # Auto-start on boot
├── camera/
│   └── CameraController.kt         # Camera2 API wrapper
├── config/
│   └── CameraConfig.kt             # Runtime configuration
├── server/
│   └── CameraHttpServer.kt         # NanoHTTPD HTTP server with Web UI
└── reliability/
    ├── ServiceGuard.kt             # Crash protection & recovery
    ├── SystemWatchdog.kt           # Health monitoring
    ├── CameraRecoveryManager.kt    # Soft recovery strategies
    ├── DeviceHealthMonitor.kt      # Temperature/battery monitoring
    └── LogReporter.kt              # Centralized logging
```

### Key Components

**CameraService** - Foreground service managing camera and HTTP server lifecycle. Uses wake locks, START_STICKY for auto-restart.

**CameraController** - Camera2 API abstraction with thread management. Uses dedicated handler threads for Camera2 callbacks and image processing. Supports 5 focus modes (AUTO, CONTINUOUS, MANUAL, FIXED, MACRO).

**CameraHttpServer** - NanoHTTPD-based server on port 8080. Built-in HTML5 web UI. Per-client frame queues for MJPEG streaming.

**Reliability subsystem** - Watchdog detects stuck streams (>10s) and servers (>30s). Recovery uses exponential backoff. ServiceGuard handles crashes with escalating strategies.

## API Endpoints

| Method | Endpoint | Description |
|--------|----------|-------------|
| GET | `/health` | Health check |
| GET | `/status` | Full camera/server status |
| POST | `/stream/start` | Start MJPEG stream |
| POST | `/stream/stop` | Stop stream |
| GET | `/stream/mjpeg` | MJPEG video stream |
| GET | `/stream/frame` | Single JPEG frame |
| GET/POST | `/stream/config` | Get/set resolution, FPS, quality |
| POST | `/photo` | Full resolution photo |
| POST | `/photo/quick` | Quick photo from stream |
| GET/POST | `/focus/mode` | Get/set focus mode |

## Key Patterns

- **Thread synchronization**: Semaphores protect camera open/close. Atomic types for concurrent flags.
- **Coroutines**: Used in CameraRecoveryManager (CoroutineScope + SupervisorJob).
- **Queue-based streaming**: ArrayBlockingQueue for per-client frame distribution.
- **Exponential backoff**: Recovery retries at 500ms, 1s, 2s, 5s intervals.

## Android Version Considerations

- **API 24-33**: Traditional foreground service startup from BroadcastReceiver
- **API 34+**: Camera foreground service requires Activity context; ServiceGuard forces full app restart

## Device Owner Mode

For kiosk deployments, Device Owner mode enables auto-start and background foreground service:

```bash
# Set (device must be factory reset or have no Google accounts)
adb shell dpm set-device-owner com.cameraserver.usb/.admin.DeviceAdminReceiver

# Remove
adb shell dpm remove-active-admin com.cameraserver.usb/.admin.DeviceAdminReceiver
```

## Known Limitations

- Dual output (stream + quick photo) fails on some devices - handled gracefully with fallback
- Focus modes vary by device; macro mode optional
- Photo capture temporarily stops stream
