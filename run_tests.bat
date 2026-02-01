@echo off
echo ========================================
echo Camera Server Stress Tests
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
echo Setting up port forwarding...
adb forward tcp:8080 tcp:8080

echo.
echo Starting tests...
echo.
python test_stress.py > test_results.txt 2>&1

echo.
echo ========================================
echo Tests completed
echo Results saved to test_results.txt
echo ========================================
type test_results.txt
pause
