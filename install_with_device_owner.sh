#!/bin/bash

# Цвета
GREEN='\033[92m'
RED='\033[91m'
YELLOW='\033[93m'
BLUE='\033[94m'
RESET='\033[0m'

echo ""
echo "========================================"
echo "Camera Server - Установка с Device Owner"
echo "========================================"
echo ""

# ========================================
# ВАЖНОЕ ПРЕДУПРЕЖДЕНИЕ
# ========================================
echo -e "${YELLOW}ВНИМАНИЕ: Device Owner режим${RESET}"
echo ""
echo "Требования:"
echo "  1. Устройство должно быть сброшено до заводских настроек"
echo "  2. ИЛИ все Google аккаунты должны быть удалены"
echo "  3. На устройстве не должно быть других Device Owner"
echo ""
echo -e "${YELLOW}Если требования не выполнены - установка Device Owner не удастся${RESET}"
echo ""
echo "Продолжить установку? (Ctrl+C для отмены)"
read -p "Нажмите Enter..."
echo ""

# ========================================
# 1. Проверка ADB
# ========================================
echo -e "${BLUE}[1/9] Проверка ADB соединения...${RESET}"
if ! command -v adb &> /dev/null; then
    echo -e "${RED}✗ ADB не найден${RESET}"
    echo ""
    echo "Установите ADB:"
    echo "  Ubuntu/Debian: sudo apt install adb"
    echo "  macOS: brew install android-platform-tools"
    exit 1
fi

DEVICE=$(adb devices | grep -w "device" | head -1 | awk '{print $1}')
if [ -z "$DEVICE" ]; then
    echo -e "${RED}✗ Устройство не найдено${RESET}"
    echo ""
    echo "Убедитесь что:"
    echo "  - Устройство подключено по USB"
    echo "  - USB отладка включена"
    echo "  - ADB разрешен на устройстве"
    exit 1
fi

echo -e "${GREEN}✓ Устройство найдено: $DEVICE${RESET}"
echo ""

# ========================================
# 2. Получение информации об устройстве
# ========================================
echo -e "${BLUE}[2/9] Информация об устройстве...${RESET}"
MODEL=$(adb shell getprop ro.product.model 2>/dev/null | tr -d '\r')
ANDROID=$(adb shell getprop ro.build.version.release 2>/dev/null | tr -d '\r')
API=$(adb shell getprop ro.build.version.sdk 2>/dev/null | tr -d '\r')

echo "  Модель: $MODEL"
echo "  Android: $ANDROID (API $API)"
echo -e "${GREEN}✓ Информация получена${RESET}"
echo ""

# ========================================
# 3. Проверка Google аккаунтов
# ========================================
echo -e "${BLUE}[3/9] Проверка Google аккаунтов...${RESET}"
if adb shell dumpsys account 2>/dev/null | grep -q "com.google"; then
    echo -e "${RED}✗ Обнаружены Google аккаунты${RESET}"
    echo ""
    echo "Device Owner можно установить только:"
    echo "  - После factory reset"
    echo "  - ИЛИ после удаления всех Google аккаунтов"
    echo ""
    echo "Удалите аккаунты в: Настройки → Аккаунты → Google"
    echo ""
    exit 1
fi
echo -e "${GREEN}✓ Google аккаунты не найдены${RESET}"
echo ""

# ========================================
# 4. Поиск APK файла
# ========================================
echo -e "${BLUE}[4/9] Поиск APK файла...${RESET}"
APK_PATH=""
if [ -f "app/build/outputs/apk/debug/app-debug.apk" ]; then
    APK_PATH="app/build/outputs/apk/debug/app-debug.apk"
    echo "  Найден: app-debug.apk"
elif [ -f "app/build/outputs/apk/release/app-release.apk" ]; then
    APK_PATH="app/build/outputs/apk/release/app-release.apk"
    echo "  Найден: app-release.apk"
else
    echo -e "${RED}✗ APK не найден${RESET}"
    echo ""
    echo "Соберите приложение:"
    echo "  ./gradlew assembleDebug"
    exit 1
fi
echo -e "${GREEN}✓ APK найден: $APK_PATH${RESET}"
echo ""

# ========================================
# 5. Удаление старой версии
# ========================================
echo -e "${BLUE}[5/9] Проверка установленной версии...${RESET}"
if adb shell pm list packages | grep -q "com.cameraserver.usb"; then
    echo "  Найдена старая версия"
    echo "  Удаление..."

    # Проверяем Device Owner
    if adb shell dumpsys device_policy | grep -q "com.cameraserver.usb"; then
        echo -e "${YELLOW}  ⚠ Обнаружен Device Owner, удаляем...${RESET}"
        adb shell dpm remove-active-admin com.cameraserver.usb/.admin.CameraDeviceAdminReceiver 2>/dev/null
    fi

    if ! adb uninstall com.cameraserver.usb 2>/dev/null; then
        echo -e "${RED}✗ Не удалось удалить старую версию${RESET}"
        echo "  Удалите приложение вручную в настройках"
        exit 1
    fi
    echo -e "${GREEN}✓ Старая версия удалена${RESET}"
else
    echo "  Старая версия не найдена"
    echo -e "${GREEN}✓ Готов к установке${RESET}"
fi
echo ""

# ========================================
# 6. Установка APK
# ========================================
echo -e "${BLUE}[6/9] Установка приложения...${RESET}"
if ! adb install "$APK_PATH" 2>&1 | grep -q "Success"; then
    echo -e "${RED}✗ Ошибка установки${RESET}"
    echo ""
    echo "Попробуйте вручную:"
    echo "  adb install -r \"$APK_PATH\""
    exit 1
fi
echo -e "${GREEN}✓ Приложение установлено${RESET}"
echo ""

# ========================================
# 7. Выдача разрешений
# ========================================
echo -e "${BLUE}[7/9] Выдача разрешений...${RESET}"

echo "  - CAMERA..."
if adb shell pm grant com.cameraserver.usb android.permission.CAMERA 2>/dev/null; then
    echo -e "${GREEN}    ✓ Выдано${RESET}"
else
    echo -e "${YELLOW}    ⚠ Не удалось выдать автоматически${RESET}"
    echo ""
    echo -e "${RED}КРИТИЧНО: Запустите приложение вручную и дайте разрешение CAMERA${RESET}"
    echo -e "${RED}Без разрешения CAMERA установка Device Owner не сработает${RESET}"
    echo ""
    read -p "Нажмите Enter после выдачи разрешения..."
fi

if [ "$API" -ge 33 ]; then
    echo "  - POST_NOTIFICATIONS (Android 13+)..."
    if adb shell pm grant com.cameraserver.usb android.permission.POST_NOTIFICATIONS 2>/dev/null; then
        echo -e "${GREEN}    ✓ Выдано${RESET}"
    else
        echo -e "${YELLOW}    ⚠ Не удалось выдать автоматически${RESET}"
    fi
fi

echo -e "${GREEN}✓ Разрешения обработаны${RESET}"
echo ""

# ========================================
# 8. Установка Device Owner
# ========================================
echo -e "${BLUE}[8/9] Установка Device Owner...${RESET}"
if ! adb shell dpm set-device-owner com.cameraserver.usb/.admin.CameraDeviceAdminReceiver 2>&1 | grep -q "Success"; then
    echo -e "${RED}✗ Не удалось установить Device Owner${RESET}"
    echo ""
    echo "Возможные причины:"
    echo "  - На устройстве есть Google аккаунт"
    echo "  - Уже установлен другой Device Owner"
    echo "  - Требуется factory reset"
    echo ""
    echo "Попробуйте:"
    echo "  1. Удалить все аккаунты в настройках"
    echo "  2. ИЛИ сделать factory reset"
    echo "  3. Запустить скрипт снова"
    exit 1
fi
echo -e "${GREEN}✓ Device Owner установлен${RESET}"
echo ""

# ========================================
# 9. Настройка батареи
# ========================================
echo -e "${BLUE}[9/9] Отключение оптимизации батареи...${RESET}"
if [ "$API" -ge 23 ]; then
    if adb shell dumpsys deviceidle whitelist +com.cameraserver.usb 2>/dev/null; then
        echo -e "${GREEN}✓ Добавлено в белый список${RESET}"
    else
        echo -e "${YELLOW}  ⚠ Требуется вручную в настройках${RESET}"
    fi
else
    echo "  Не требуется (Android < 6.0)"
    echo -e "${GREEN}✓ Пропущено${RESET}"
fi
echo ""

# ========================================
# Проверка установки
# ========================================
echo -e "${BLUE}Проверка установки...${RESET}"

# Проверка пакета
if ! adb shell pm list packages | grep -q "com.cameraserver.usb"; then
    echo -e "${RED}✗ Пакет не найден${RESET}"
    exit 1
fi
echo -e "${GREEN}✓ Пакет установлен${RESET}"

# Получение версии
VERSION=$(adb shell dumpsys package com.cameraserver.usb | grep "versionName" | head -1 | cut -d'=' -f2 | tr -d '\r')

# Проверка Device Owner
echo ""
echo -e "${BLUE}Статус Device Owner:${RESET}"
if adb shell dumpsys device_policy | grep -q "Device Owner"; then
    echo -e "${GREEN}✓ Device Owner активен${RESET}"
    adb shell dumpsys device_policy | grep "admin=" | grep "com.cameraserver.usb"
else
    echo -e "${RED}✗ Device Owner НЕ установлен${RESET}"
fi

# Проверка разрешений
echo ""
echo -e "${BLUE}Статус разрешений:${RESET}"
if adb shell dumpsys package com.cameraserver.usb | grep -q "android.permission.CAMERA: granted=true"; then
    echo -e "  CAMERA: ${GREEN}✓ Выдано${RESET}"
else
    echo -e "  CAMERA: ${RED}НЕ ВЫДАНО${RESET}"
fi

if [ "$API" -ge 33 ]; then
    if adb shell dumpsys package com.cameraserver.usb | grep -q "android.permission.POST_NOTIFICATIONS: granted=true"; then
        echo -e "  POST_NOTIFICATIONS: ${GREEN}✓ Выдано${RESET}"
    else
        echo -e "  POST_NOTIFICATIONS: ${YELLOW}НЕ ВЫДАНО${RESET}"
    fi
fi

# Проверка оптимизации батареи
echo ""
echo -e "${BLUE}Оптимизация батареи:${RESET}"
if adb shell dumpsys deviceidle | grep -q "com.cameraserver.usb"; then
    echo -e "  ${GREEN}✓ Отключена${RESET}"
else
    echo -e "  ${YELLOW}⚠ Включена (рекомендуется отключить)${RESET}"
fi

echo ""
echo "========================================"
echo -e "${GREEN}УСТАНОВКА ЗАВЕРШЕНА${RESET}"
echo "========================================"
echo ""
echo "Информация:"
echo "  Пакет: com.cameraserver.usb"
[ -n "$VERSION" ] && echo "  Версия: $VERSION"
echo "  Устройство: $MODEL"
echo "  Android: $ANDROID (API $API)"
echo -e "  Device Owner: ${GREEN}АКТИВЕН${RESET}"
echo ""
echo -e "${BLUE}Следующие шаги:${RESET}"
echo ""
echo "1. Перезагрузите устройство для теста auto-start:"
echo "   adb reboot"
echo ""
echo "2. После перезагрузки сервис запустится автоматически"
echo ""
echo "3. Для подключения по USB:"
echo "   adb forward tcp:8080 tcp:8080"
echo "   Откройте http://localhost:8080"
echo ""
echo "4. Для проверки статуса Device Owner:"
echo "   ./check_device_owner.sh"
echo ""
