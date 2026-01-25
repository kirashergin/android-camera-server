# Android Camera Server

HTTP-сервер для управления камерой Android-устройства. Разработан для использования в фотобудках и автоматизированных системах съёмки.

## Возможности

- **MJPEG стриминг** - живое видео через HTTP
- **Управление настройками на лету** - разрешение, FPS, качество JPEG
- **Режимы фокуса** - Auto, Continuous, Manual, Macro, Fixed
- **Фото** - быстрое (из стрима) и полноразмерное
- **Web UI** - управление через браузер
- **Foreground Service** - стабильная работа в фоне
- **Auto-recovery** - автоматическое восстановление при сбоях

## Установка

1. Скачайте APK из [Releases](https://github.com/kirashergin/android-camera-server/releases)
2. Установите на устройство
3. Дайте разрешение на камеру
4. Отключите оптимизацию батареи для приложения

## Подключение

### Через USB (ADB)
```bash
adb forward tcp:8080 tcp:8080
# Открыть http://localhost:8080
```

### Через Wi-Fi
Устройство и компьютер должны быть в одной сети:
```
http://<IP-устройства>:8080
```

## API Endpoints

### Статус
| Метод | Endpoint | Описание |
|-------|----------|----------|
| GET | `/health` | Проверка доступности сервера |
| GET | `/status` | Полный статус камеры и сервера |

### Стрим
| Метод | Endpoint | Описание |
|-------|----------|----------|
| POST | `/stream/start` | Запустить стрим |
| POST | `/stream/stop` | Остановить стрим |
| GET | `/stream/mjpeg` | MJPEG видеопоток |
| GET | `/stream/frame` | Один кадр (JPEG) |
| GET | `/stream/config` | Текущие настройки |
| POST | `/stream/config` | Изменить настройки |

### Фото
| Метод | Endpoint | Описание |
|-------|----------|----------|
| POST | `/photo` | Полноразмерное фото |
| POST | `/photo/quick` | Быстрое фото из стрима |

### Фокус
| Метод | Endpoint | Описание |
|-------|----------|----------|
| GET | `/focus/mode` | Текущий режим фокуса |
| POST | `/focus/mode` | Установить режим фокуса |
| POST | `/focus/auto` | Триггер автофокуса |
| GET | `/focus/modes` | Поддерживаемые режимы |

### Device Owner
| Метод | Endpoint | Описание |
|-------|----------|----------|
| GET | `/device-owner` | Статус Device Owner |
| POST | `/device-owner/reboot` | Перезагрузка устройства |

## Device Owner Mode

Device Owner — специальный режим Android, дающий приложению расширенные права для работы в режиме киоска/фотобудки.

### Преимущества
- Автозапуск сервиса при загрузке устройства
- Запуск Foreground Service из фона (без ограничений Android 12+)
- Возможность перезагрузки устройства через API
- Защита от остановки пользователем

### Установка Device Owner

**Важно:** Устройство должно быть сброшено до заводских настроек или не иметь привязанных аккаунтов Google.

```bash
# Установить приложение
adb install app-release.apk

# Назначить Device Owner
adb shell dpm set-device-owner com.cameraserver.usb/.admin.DeviceAdminReceiver
```

### Проверка статуса
```bash
curl http://localhost:8080/device-owner
```

Ответ:
```json
{
  "isDeviceOwner": true,
  "isDeviceAdmin": true,
  "canStartFgsFromBackground": true
}
```

### Удаление Device Owner
```bash
adb shell dpm remove-active-admin com.cameraserver.usb/.admin.DeviceAdminReceiver
```

## Примеры использования

### Запуск стрима
```bash
curl -X POST http://localhost:8080/stream/start
```

### Изменение настроек
```bash
curl -X POST http://localhost:8080/stream/config \
  -H "Content-Type: application/json" \
  -d '{"width": 1920, "height": 1080, "fps": 30, "quality": 80}'
```

### Установка режима фокуса
```bash
curl -X POST http://localhost:8080/focus/mode \
  -H "Content-Type: application/json" \
  -d '{"mode": "CONTINUOUS"}'
```

### Сделать фото
```bash
# Быстрое фото (из текущего стрима)
curl -X POST http://localhost:8080/photo/quick -o photo.jpg

# Полноразмерное фото
curl -X POST http://localhost:8080/photo -o photo_full.jpg
```

### Просмотр стрима в VLC
```
vlc http://localhost:8080/stream/mjpeg
```

## Режимы фокуса

| Режим | Описание |
|-------|----------|
| `CONTINUOUS` | Постоянный автофокус (по умолчанию) |
| `AUTO` | Фокус по запросу (Trigger Focus) |
| `MACRO` | Для близких объектов (~10-15 см) |
| `MANUAL` | Ручная установка дистанции |
| `FIXED` | Фиксированный фокус |

## Web UI

Откройте `http://localhost:8080` в браузере для доступа к веб-интерфейсу:

- Просмотр живого видео
- Управление стримом (Start/Stop)
- Настройка разрешения, FPS, качества
- Смена режима фокуса
- Съёмка фото

## Конфигурация

Настройки по умолчанию в `CameraConfig.kt`:

```kotlin
const val SERVER_PORT = 8080
var streamWidth = 1280
var streamHeight = 720
var targetFps = 30
var jpegQuality = 80
val DEFAULT_FOCUS_MODE = FocusMode.CONTINUOUS
```

## Сборка

```bash
# Debug
./gradlew assembleDebug

# Release
./gradlew assembleRelease
```

## Тестирование

```bash
# Selenium тесты
python test_selenium.py
```

## Требования

- Android 7.0+ (API 24+)
- Разрешение на камеру
- Рекомендуется отключить оптимизацию батареи

## Архитектура

```
├── CameraService       # Foreground Service
├── CameraController    # Управление Camera2 API
├── CameraHttpServer    # HTTP сервер (NanoHTTPD)
├── CameraConfig        # Конфигурация
└── Reliability         # Watchdog, Recovery, Health Monitor
```

## Лицензия

MIT License
