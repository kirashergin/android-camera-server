@echo off
chcp 65001 >nul
setlocal enabledelayedexpansion

echo.
echo ========================================
echo Camera Server - Полная установка
echo ========================================
echo.

REM Цвета для вывода (работает в Windows 10+)
set "GREEN=[92m"
set "RED=[91m"
set "YELLOW=[93m"
set "BLUE=[94m"
set "RESET=[0m"

REM ========================================
REM 1. Проверка ADB
REM ========================================
echo %BLUE%[1/8] Проверка ADB соединения...%RESET%
adb devices >nul 2>&1
if errorlevel 1 (
    echo %RED%✗ ADB не найден или устройство не подключено%RESET%
    echo.
    echo Убедитесь что:
    echo   - ADB установлен и в PATH
    echo   - Устройство подключено по USB
    echo   - USB отладка включена
    pause
    exit /b 1
)

for /f "skip=1 tokens=1,2" %%a in ('adb devices 2^>nul') do (
    if not "%%a"=="" if not "%%b"=="offline" (
        set DEVICE=%%a
        goto device_found
    )
)

echo %RED%✗ Устройство не найдено%RESET%
pause
exit /b 1

:device_found
echo %GREEN%✓ Устройство найдено: %DEVICE%%RESET%
echo.

REM ========================================
REM 2. Получение информации об устройстве
REM ========================================
echo %BLUE%[2/8] Информация об устройстве...%RESET%
for /f "tokens=*" %%a in ('adb shell getprop ro.product.model 2^>nul') do set MODEL=%%a
for /f "tokens=*" %%a in ('adb shell getprop ro.build.version.release 2^>nul') do set ANDROID=%%a
for /f "tokens=*" %%a in ('adb shell getprop ro.build.version.sdk 2^>nul') do set API=%%a

echo   Модель: %MODEL%
echo   Android: %ANDROID% (API %API%)
echo %GREEN%✓ Информация получена%RESET%
echo.

REM ========================================
REM 3. Поиск APK файла
REM ========================================
echo %BLUE%[3/8] Поиск APK файла...%RESET%
set "APK_PATH="
if exist "app\build\outputs\apk\debug\app-debug.apk" (
    set "APK_PATH=app\build\outputs\apk\debug\app-debug.apk"
    echo   Найден: app-debug.apk
) else if exist "app\build\outputs\apk\release\app-release.apk" (
    set "APK_PATH=app\build\outputs\apk\release\app-release.apk"
    echo   Найден: app-release.apk
) else (
    echo %RED%✗ APK не найден%RESET%
    echo.
    echo Соберите приложение:
    echo   gradlew assembleDebug
    pause
    exit /b 1
)
echo %GREEN%✓ APK найден: %APK_PATH%%RESET%
echo.

REM ========================================
REM 4. Удаление старой версии
REM ========================================
echo %BLUE%[4/8] Проверка установленной версии...%RESET%
adb shell pm list packages | findstr "com.cameraserver.usb" >nul 2>&1
if not errorlevel 1 (
    echo   Найдена старая версия
    echo   Удаление...

    REM Проверяем Device Owner
    adb shell dumpsys device_policy | findstr "com.cameraserver.usb" >nul 2>&1
    if not errorlevel 1 (
        echo %YELLOW%  ⚠ Обнаружен Device Owner, удаляем...%RESET%
        adb shell dpm remove-active-admin com.cameraserver.usb/.admin.CameraDeviceAdminReceiver >nul 2>&1
    )

    adb uninstall com.cameraserver.usb >nul 2>&1
    if errorlevel 1 (
        echo %RED%✗ Не удалось удалить старую версию%RESET%
        echo   Удалите приложение вручную в настройках
        pause
        exit /b 1
    )
    echo %GREEN%✓ Старая версия удалена%RESET%
) else (
    echo   Старая версия не найдена
    echo %GREEN%✓ Готов к установке%RESET%
)
echo.

REM ========================================
REM 5. Установка APK
REM ========================================
echo %BLUE%[5/8] Установка приложения...%RESET%
adb install "%APK_PATH%" >nul 2>&1
if errorlevel 1 (
    echo %RED%✗ Ошибка установки%RESET%
    echo.
    echo Попробуйте вручную:
    echo   adb install -r "%APK_PATH%"
    pause
    exit /b 1
)
echo %GREEN%✓ Приложение установлено%RESET%
echo.

REM ========================================
REM 6. Выдача разрешений
REM ========================================
echo %BLUE%[6/8] Выдача разрешений...%RESET%

echo   - CAMERA...
adb shell pm grant com.cameraserver.usb android.permission.CAMERA 2>nul
if errorlevel 1 (
    echo %YELLOW%    ⚠ Не удалось выдать автоматически (требуется вручную)%RESET%
) else (
    echo %GREEN%    ✓ Выдано%RESET%
)

if %API% GEQ 33 (
    echo   - POST_NOTIFICATIONS (Android 13+^)...
    adb shell pm grant com.cameraserver.usb android.permission.POST_NOTIFICATIONS 2>nul
    if errorlevel 1 (
        echo %YELLOW%    ⚠ Не удалось выдать автоматически%RESET%
    ) else (
        echo %GREEN%    ✓ Выдано%RESET%
    )
)

echo %GREEN%✓ Разрешения обработаны%RESET%
echo.

REM ========================================
REM 7. Настройка батареи
REM ========================================
echo %BLUE%[7/8] Отключение оптимизации батареи...%RESET%
if %API% GEQ 23 (
    adb shell dumpsys deviceidle whitelist +com.cameraserver.usb >nul 2>&1
    if errorlevel 1 (
        echo %YELLOW%  ⚠ Требуется вручную в настройках%RESET%
        echo     Настройки → Батарея → Оптимизация → Camera Server → Не оптимизировать
    ) else (
        echo %GREEN%✓ Добавлено в белый список%RESET%
    )
) else (
    echo   Не требуется (Android ^< 6.0^)
    echo %GREEN%✓ Пропущено%RESET%
)
echo.

REM ========================================
REM 8. Проверка установки
REM ========================================
echo %BLUE%[8/8] Проверка установки...%RESET%

REM Проверка пакета
adb shell pm list packages | findstr "com.cameraserver.usb" >nul 2>&1
if errorlevel 1 (
    echo %RED%✗ Пакет не найден%RESET%
    goto end
)
echo %GREEN%✓ Пакет установлен%RESET%

REM Получение версии
for /f "tokens=2 delims==" %%a in ('adb shell dumpsys package com.cameraserver.usb ^| findstr "versionName" 2^>nul') do (
    set VERSION=%%a
    goto version_found
)
:version_found

REM Проверка разрешений
echo.
echo %BLUE%Статус разрешений:%RESET%
adb shell dumpsys package com.cameraserver.usb | findstr "android.permission.CAMERA: granted=true" >nul 2>&1
if errorlevel 1 (
    echo   CAMERA: %RED%НЕ ВЫДАНО%RESET% - откройте приложение для запроса
) else (
    echo   CAMERA: %GREEN%✓ Выдано%RESET%
)

if %API% GEQ 33 (
    adb shell dumpsys package com.cameraserver.usb | findstr "android.permission.POST_NOTIFICATIONS: granted=true" >nul 2>&1
    if errorlevel 1 (
        echo   POST_NOTIFICATIONS: %YELLOW%НЕ ВЫДАНО%RESET%
    ) else (
        echo   POST_NOTIFICATIONS: %GREEN%✓ Выдано%RESET%
    )
)

REM Проверка оптимизации батареи
echo.
echo %BLUE%Оптимизация батареи:%RESET%
adb shell dumpsys deviceidle | findstr "com.cameraserver.usb" >nul 2>&1
if errorlevel 1 (
    echo   %YELLOW%⚠ Включена (рекомендуется отключить)%RESET%
) else (
    echo   %GREEN%✓ Отключена%RESET%
)

echo.
echo ========================================
echo %GREEN%УСТАНОВКА ЗАВЕРШЕНА%RESET%
echo ========================================
echo.
echo Информация:
echo   Пакет: com.cameraserver.usb
if defined VERSION echo   Версия: %VERSION%
echo   Устройство: %MODEL%
echo   Android: %ANDROID% (API %API%)
echo.
echo %BLUE%Следующие шаги:%RESET%
echo.
echo 1. Запустите приложение на устройстве
echo    (если разрешения не выданы автоматически)
echo.
echo 2. Для Device Owner режима:
echo    setup_device_owner.bat
echo.
echo 3. Для проверки статуса:
echo    check_device_owner.bat
echo.
echo 4. Для подключения по USB:
echo    adb forward tcp:8080 tcp:8080
echo    Откройте http://localhost:8080
echo.

:end
pause
