@echo off
echo ========================================
echo Camera Server - Device Owner Setup
echo ========================================
echo.

echo Checking ADB connection...
adb devices
if errorlevel 1 (
    echo ERROR: ADB not found or device not connected
    pause
    exit /b 1
)

echo.
echo WARNING: This will set Camera Server as Device Owner
echo.
echo Requirements:
echo  1. All Google accounts must be removed
echo  2. Device must not have other Device Owner
echo  3. Factory reset recommended
echo.
echo Press Ctrl+C to cancel, or
pause

echo.
echo Installing app...
adb install -r app\build\outputs\apk\debug\app-debug.apk
if errorlevel 1 (
    echo ERROR: Failed to install app
    pause
    exit /b 1
)

echo.
echo Setting Device Owner...
adb shell dpm set-device-owner com.cameraserver.usb/.admin.DeviceAdminReceiver

echo.
echo Checking status...
adb shell dumpsys device_policy | findstr /C:"Device Owner"

echo.
echo ========================================
echo Setup completed!
echo ========================================
echo.
echo To verify in app:
echo  1. Open Camera Server app
echo  2. Check "Device Owner Status" section
echo.
echo To remove Device Owner:
echo  adb shell dpm remove-active-admin com.cameraserver.usb/.admin.DeviceAdminReceiver
echo.
pause
