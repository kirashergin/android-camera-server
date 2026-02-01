@echo off
chcp 65001 >nul
setlocal enabledelayedexpansion

echo.
echo ========================================
echo Camera Server - Установка с Device Owner
echo ========================================
echo.

REM Цвета для вывода (работает в Windows 10+)
set "GREEN=[92m"
set "RED=[91m"
set "YELLOW=[93m"
set "BLUE=[94m"
set "RESET=[0m"

REM ========================================
REM ВАЖНОЕ ПРЕДУПРЕЖДЕНИЕ
REM ========================================
echo %YELLOW%ВНИМАНИЕ: Device Owner режим%RESET%
echo.
echo Требования:
echo   1. Устройство должно быть сброшено до заводских настроек
echo   2. ИЛИ все Google аккаунты должны быть удалены
echo   3. На устройстве не должно быть других Device Owner
echo.
echo %YELLOW%Если требования не выполнены - установка Device Owner не удастся%RESET%
echo.
echo Продолжить установку?
echo Нажмите Ctrl+C для отмены или
pause
echo.

REM ========================================
REM 1. Проверка ADB
REM ========================================
echo %BLUE%[1/9] Проверка ADB соединения...%RESET%
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
echo %BLUE%[2/9] Информация об устройстве...%RESET%
for /f "tokens=*" %%a in ('adb shell getprop ro.product.model 2^>nul') do set MODEL=%%a
for /f "tokens=*" %%a in ('adb shell getprop ro.build.version.release 2^>nul') do set ANDROID=%%a
for /f "tokens=*" %%a in ('adb shell getprop ro.build.version.sdk 2^>nul') do set API=%%a

echo   Модель: %MODEL%
echo   Android: %ANDROID% (API %API%)
echo %GREEN%✓ Информация получена%RESET%
echo.

REM ========================================
REM 3. Проверка Google аккаунтов
REM ========================================
echo %BLUE%[3/9] Проверка Google аккаунтов...%RESET%
adb shell dumpsys account | findstr "com.google" >nul 2>&1
if not errorlevel 1 (
    echo %RED%✗ Обнаружены Google аккаунты%RESET%
    echo.
    echo Device Owner можно установить только:
    echo   - После factory reset
    echo   - ИЛИ после удаления всех Google аккаунтов
    echo.
    echo Удалите аккаунты в: Настройки → Аккаунты → Google
    echo.
    pause
    exit /b 1
)
echo %GREEN%✓ Google аккаунты не найдены%RESET%
echo.

REM ========================================
REM 4. Поиск APK файла
REM ========================================
echo %BLUE%[4/9] Поиск APK файла...%RESET%
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
REM 5. Удаление старой версии
REM ========================================
echo %BLUE%[5/9] Проверка установленной версии...%RESET%
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
REM 6. Установка APK
REM ========================================
echo %BLUE%[6/9] Установка приложения...%RESET%
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
REM 7. Выдача разрешений
REM ========================================
echo %BLUE%[7/9] Выдача разрешений...%RESET%

echo   - CAMERA...
adb shell pm grant com.cameraserver.usb android.permission.CAMERA 2>nul
if errorlevel 1 (
    echo %YELLOW%    ⚠ Не удалось выдать автоматически%RESET%
    echo.
    echo %RED%КРИТИЧНО: Запустите приложение вручную и дайте разрешение CAMERA%RESET%
    echo %RED%Без разрешения CAMERA установка Device Owner не сработает%RESET%
    echo.
    pause
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
REM 8. Установка Device Owner
REM ========================================
echo %BLUE%[8/9] Установка Device Owner...%RESET%
adb shell dpm set-device-owner com.cameraserver.usb/.admin.CameraDeviceAdminReceiver 2>nul
if errorlevel 1 (
    echo %RED%✗ Не удалось установить Device Owner%RESET%
    echo.
    echo Возможные причины:
    echo   - На устройстве есть Google аккаунт
    echo   - Уже установлен другой Device Owner
    echo   - Требуется factory reset
    echo.
    echo Попробуйте:
    echo   1. Удалить все аккаунты в настройках
    echo   2. ИЛИ сделать factory reset
    echo   3. Запустить скрипт снова
    pause
    exit /b 1
)
echo %GREEN%✓ Device Owner установлен%RESET%
echo.

REM ========================================
REM 9. Настройка батареи
REM ========================================
echo %BLUE%[9/9] Отключение оптимизации батареи...%RESET%
if %API% GEQ 23 (
    adb shell dumpsys deviceidle whitelist +com.cameraserver.usb >nul 2>&1
    if errorlevel 1 (
        echo %YELLOW%  ⚠ Требуется вручную в настройках%RESET%
    ) else (
        echo %GREEN%✓ Добавлено в белый список%RESET%
    )
) else (
    echo   Не требуется (Android ^< 6.0^)
    echo %GREEN%✓ Пропущено%RESET%
)
echo.

REM ========================================
REM Проверка установки
REM ========================================
echo %BLUE%Проверка установки...%RESET%

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

REM Проверка Device Owner
echo.
echo %BLUE%Статус Device Owner:%RESET%
adb shell dumpsys device_policy | findstr "Device Owner" >nul 2>&1
if errorlevel 1 (
    echo %RED%✗ Device Owner НЕ установлен%RESET%
) else (
    echo %GREEN%✓ Device Owner активен%RESET%
    adb shell dumpsys device_policy | findstr "admin=" | findstr "com.cameraserver.usb"
)

REM Проверка разрешений
echo.
echo %BLUE%Статус разрешений:%RESET%
adb shell dumpsys package com.cameraserver.usb | findstr "android.permission.CAMERA: granted=true" >nul 2>&1
if errorlevel 1 (
    echo   CAMERA: %RED%НЕ ВЫДАНО%RESET%
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
echo   Device Owner: %GREEN%АКТИВЕН%RESET%
echo.
echo %BLUE%Следующие шаги:%RESET%
echo.
echo 1. Перезагрузите устройство для теста auto-start:
echo    adb reboot
echo.
echo 2. После перезагрузки сервис запустится автоматически
echo.
echo 3. Для подключения по USB:
echo    adb forward tcp:8080 tcp:8080
echo    Откройте http://localhost:8080
echo.
echo 4. Для проверки статуса Device Owner:
echo    check_device_owner.bat
echo.

:end
pause
