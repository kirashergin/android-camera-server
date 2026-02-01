@echo off
echo ========================================
echo Device Owner Status Check
echo ========================================
echo.

echo Checking via dumpsys...
adb shell dumpsys device_policy | findstr /C:"Device Owner" /C:"admin=" /C:"package="

echo.
echo ========================================

echo.
echo Checking camera permission...
adb shell dumpsys package com.cameraserver.usb | findstr "CAMERA: granted=true"

echo.
echo Checking via app endpoint...
curl http://localhost:8080/device-owner 2>nul
if errorlevel 1 (
    echo.
    echo Note: App may not be running or port not forwarded
    echo Run: adb forward tcp:8080 tcp:8080
)

echo.
echo.
pause
