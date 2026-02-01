#!/usr/bin/env python3
"""
Стресс-тестирование Camera Server
Проверяет множественные параллельные запросы и стабильность стрима

Требования:
1. Установленное приложение на телефоне
2. Запущенный сервис камеры
3. adb forward tcp:8080 tcp:8080

Запуск:
    python test_stress.py
"""

import requests
import threading
import time
import sys
from concurrent.futures import ThreadPoolExecutor, as_completed
from collections import defaultdict
import json

BASE_URL = "http://localhost:8080"
TIMEOUT = 10  # секунды

class Colors:
    GREEN = '\033[92m'
    RED = '\033[91m'
    YELLOW = '\033[93m'
    BLUE = '\033[94m'
    RESET = '\033[0m'

class TestStats:
    def __init__(self):
        self.lock = threading.Lock()
        self.total = 0
        self.success = 0
        self.failed = 0
        self.timeouts = 0
        self.errors = defaultdict(int)
        self.response_times = []

    def record_success(self, duration):
        with self.lock:
            self.total += 1
            self.success += 1
            self.response_times.append(duration)

    def record_failure(self, error_type):
        with self.lock:
            self.total += 1
            self.failed += 1
            self.errors[error_type] += 1

    def record_timeout(self):
        with self.lock:
            self.total += 1
            self.timeouts += 1

    def print_summary(self):
        with self.lock:
            print(f"\n{'='*60}")
            print(f"{Colors.BLUE}СТАТИСТИКА ТЕСТИРОВАНИЯ{Colors.RESET}")
            print(f"{'='*60}")
            print(f"Всего запросов: {self.total}")
            print(f"{Colors.GREEN}Успешных: {self.success} ({self.success/max(self.total,1)*100:.1f}%){Colors.RESET}")
            print(f"{Colors.RED}Ошибок: {self.failed} ({self.failed/max(self.total,1)*100:.1f}%){Colors.RESET}")
            print(f"{Colors.YELLOW}Таймаутов: {self.timeouts}{Colors.RESET}")

            if self.response_times:
                avg_time = sum(self.response_times) / len(self.response_times)
                max_time = max(self.response_times)
                min_time = min(self.response_times)
                print(f"\nВремя ответа:")
                print(f"  Среднее: {avg_time:.2f}s")
                print(f"  Минимум: {min_time:.2f}s")
                print(f"  Максимум: {max_time:.2f}s")

            if self.errors:
                print(f"\nТипы ошибок:")
                for error_type, count in self.errors.items():
                    print(f"  {error_type}: {count}")
            print(f"{'='*60}\n")

stats = TestStats()

def test_health():
    """Проверка /health"""
    try:
        start = time.time()
        r = requests.get(f"{BASE_URL}/health", timeout=TIMEOUT)
        duration = time.time() - start

        if r.status_code == 200 and r.json().get('status') == 'ok':
            stats.record_success(duration)
            return True
        else:
            stats.record_failure(f"HTTP {r.status_code}")
            return False
    except requests.Timeout:
        stats.record_timeout()
        return False
    except Exception as e:
        stats.record_failure(str(type(e).__name__))
        return False

def test_status():
    """Проверка /status"""
    try:
        start = time.time()
        r = requests.get(f"{BASE_URL}/status", timeout=TIMEOUT)
        duration = time.time() - start

        if r.status_code == 200:
            data = r.json()
            if 'camera' in data and 'server' in data:
                stats.record_success(duration)
                return True
        stats.record_failure(f"HTTP {r.status_code}")
        return False
    except requests.Timeout:
        stats.record_timeout()
        return False
    except Exception as e:
        stats.record_failure(str(type(e).__name__))
        return False

def test_stream_config_get():
    """Проверка GET /stream/config"""
    try:
        start = time.time()
        r = requests.get(f"{BASE_URL}/stream/config", timeout=TIMEOUT)
        duration = time.time() - start

        if r.status_code == 200:
            data = r.json()
            if 'width' in data and 'height' in data and 'fps' in data:
                stats.record_success(duration)
                return True
        stats.record_failure(f"HTTP {r.status_code}")
        return False
    except requests.Timeout:
        stats.record_timeout()
        return False
    except Exception as e:
        stats.record_failure(str(type(e).__name__))
        return False

def test_stream_config_post():
    """Проверка POST /stream/config"""
    try:
        start = time.time()
        payload = {"width": 1280, "height": 720, "fps": 30, "quality": 80}
        r = requests.post(f"{BASE_URL}/stream/config", json=payload, timeout=TIMEOUT)
        duration = time.time() - start

        if r.status_code == 200:
            stats.record_success(duration)
            return True
        stats.record_failure(f"HTTP {r.status_code}")
        return False
    except requests.Timeout:
        stats.record_timeout()
        return False
    except Exception as e:
        stats.record_failure(str(type(e).__name__))
        return False

def test_stream_start():
    """Проверка POST /stream/start"""
    try:
        start = time.time()
        r = requests.post(f"{BASE_URL}/stream/start", timeout=TIMEOUT)
        duration = time.time() - start

        if r.status_code == 200:
            stats.record_success(duration)
            return True
        stats.record_failure(f"HTTP {r.status_code}")
        return False
    except requests.Timeout:
        stats.record_timeout()
        return False
    except Exception as e:
        stats.record_failure(str(type(e).__name__))
        return False

def test_quick_photo():
    """Проверка POST /photo/quick"""
    try:
        start = time.time()
        r = requests.post(f"{BASE_URL}/photo/quick", timeout=TIMEOUT)
        duration = time.time() - start

        if r.status_code == 200 and r.headers.get('content-type') == 'image/jpeg':
            stats.record_success(duration)
            return True
        elif r.status_code == 429:
            # 429 это нормально при параллельных запросах
            stats.record_success(duration)
            return True
        stats.record_failure(f"HTTP {r.status_code}")
        return False
    except requests.Timeout:
        stats.record_timeout()
        return False
    except Exception as e:
        stats.record_failure(str(type(e).__name__))
        return False

def test_full_photo():
    """Проверка POST /photo"""
    try:
        start = time.time()
        r = requests.post(f"{BASE_URL}/photo", timeout=TIMEOUT)
        duration = time.time() - start

        if r.status_code == 200 and r.headers.get('content-type') == 'image/jpeg':
            stats.record_success(duration)
            return True
        elif r.status_code == 429:
            # 429 это нормально при параллельных запросах
            stats.record_success(duration)
            return True
        stats.record_failure(f"HTTP {r.status_code}")
        return False
    except requests.Timeout:
        stats.record_timeout()
        return False
    except Exception as e:
        stats.record_failure(str(type(e).__name__))
        return False

def check_stream_alive():
    """Проверяет что стрим активен"""
    try:
        r = requests.get(f"{BASE_URL}/status", timeout=5)
        if r.status_code == 200:
            data = r.json()
            return data.get('camera', {}).get('isStreaming', False)
        return False
    except:
        return False

def run_parallel_test(test_name, test_func, num_requests, num_workers):
    """Запускает параллельные запросы"""
    print(f"\n{Colors.BLUE}▶ {test_name}{Colors.RESET}")
    print(f"  Запросов: {num_requests}, Потоков: {num_workers}")

    start_time = time.time()

    with ThreadPoolExecutor(max_workers=num_workers) as executor:
        futures = [executor.submit(test_func) for _ in range(num_requests)]

        success_count = 0
        for future in as_completed(futures):
            if future.result():
                success_count += 1

    duration = time.time() - start_time

    print(f"  {Colors.GREEN}✓{Colors.RESET} Завершено за {duration:.2f}s")
    print(f"  Успешно: {success_count}/{num_requests}")

def main():
    print(f"{Colors.BLUE}{'='*60}{Colors.RESET}")
    print(f"{Colors.BLUE}СТРЕСС-ТЕСТИРОВАНИЕ CAMERA SERVER{Colors.RESET}")
    print(f"{Colors.BLUE}{'='*60}{Colors.RESET}")

    # Проверка доступности сервера
    print(f"\n{Colors.YELLOW}Проверка доступности сервера...{Colors.RESET}")
    try:
        r = requests.get(f"{BASE_URL}/health", timeout=5)
        if r.status_code == 200:
            print(f"{Colors.GREEN}✓ Сервер доступен{Colors.RESET}")
        else:
            print(f"{Colors.RED}✗ Сервер вернул код {r.status_code}{Colors.RESET}")
            sys.exit(1)
    except Exception as e:
        print(f"{Colors.RED}✗ Не удалось подключиться к серверу: {e}{Colors.RESET}")
        print(f"{Colors.YELLOW}Убедитесь что:{Colors.RESET}")
        print(f"  1. Приложение запущено на телефоне")
        print(f"  2. Выполнена команда: adb forward tcp:8080 tcp:8080")
        sys.exit(1)

    # Запуск стрима
    print(f"\n{Colors.YELLOW}Запуск стрима...{Colors.RESET}")
    test_stream_start()
    time.sleep(2)  # Даем стриму время на старт

    if check_stream_alive():
        print(f"{Colors.GREEN}✓ Стрим запущен{Colors.RESET}")
    else:
        print(f"{Colors.YELLOW}⚠ Стрим не активен, но продолжаем тесты{Colors.RESET}")

    # Тест 1: Множественные GET /status
    run_parallel_test(
        "Тест 1: Множественные /status (легкие запросы)",
        test_status,
        num_requests=50,
        num_workers=10
    )

    time.sleep(1)

    # Тест 2: Смешанные GET запросы
    print(f"\n{Colors.BLUE}▶ Тест 2: Смешанные GET запросы{Colors.RESET}")
    with ThreadPoolExecutor(max_workers=15) as executor:
        futures = []
        for _ in range(10):
            futures.append(executor.submit(test_status))
            futures.append(executor.submit(test_health))
            futures.append(executor.submit(test_stream_config_get))

        for future in as_completed(futures):
            future.result()

    print(f"  {Colors.GREEN}✓ Завершено{Colors.RESET}")

    time.sleep(1)

    # Тест 3: POST запросы (изменение конфига)
    run_parallel_test(
        "Тест 3: POST /stream/config (изменение настроек)",
        test_stream_config_post,
        num_requests=10,
        num_workers=5
    )

    time.sleep(1)

    # Тест 4: Quick фото при активном стриме
    print(f"\n{Colors.BLUE}▶ Тест 4: Quick фото при активном стриме{Colors.RESET}")
    stream_alive_before = check_stream_alive()
    print(f"  Стрим перед тестом: {'активен' if stream_alive_before else 'НЕ активен'}")

    run_parallel_test(
        "  Параллельные quick photo",
        test_quick_photo,
        num_requests=20,
        num_workers=5
    )

    time.sleep(2)
    stream_alive_after = check_stream_alive()
    print(f"  Стрим после теста: {'активен' if stream_alive_after else 'НЕ активен'}")

    if stream_alive_before and stream_alive_after:
        print(f"  {Colors.GREEN}✓ Стрим остался активным{Colors.RESET}")
    elif stream_alive_before and not stream_alive_after:
        print(f"  {Colors.RED}✗ СТРИМ ОБОРВАЛСЯ!{Colors.RESET}")

    time.sleep(1)

    # Тест 5: Full resolution фото
    print(f"\n{Colors.BLUE}▶ Тест 5: Full resolution фото{Colors.RESET}")
    stream_alive_before = check_stream_alive()
    print(f"  Стрим перед тестом: {'активен' if stream_alive_before else 'НЕ активен'}")

    run_parallel_test(
        "  Параллельные full photo (должны обрабатываться с 429)",
        test_full_photo,
        num_requests=10,
        num_workers=5
    )

    time.sleep(3)  # Даем время стриму восстановиться
    stream_alive_after = check_stream_alive()
    print(f"  Стрим после теста: {'активен' if stream_alive_after else 'НЕ активен'}")

    if stream_alive_before and stream_alive_after:
        print(f"  {Colors.GREEN}✓ Стрим остался активным{Colors.RESET}")
    elif stream_alive_before and not stream_alive_after:
        print(f"  {Colors.RED}✗ СТРИМ ОБОРВАЛСЯ!{Colors.RESET}")

    time.sleep(1)

    # Тест 6: Экстремальная нагрузка
    print(f"\n{Colors.BLUE}▶ Тест 6: Экстремальная нагрузка{Colors.RESET}")
    print(f"  {Colors.YELLOW}100 параллельных запросов, 20 потоков{Colors.RESET}")

    stream_alive_before = check_stream_alive()

    with ThreadPoolExecutor(max_workers=20) as executor:
        futures = []
        # Смешиваем разные типы запросов
        for i in range(100):
            if i % 5 == 0:
                futures.append(executor.submit(test_quick_photo))
            elif i % 4 == 0:
                futures.append(executor.submit(test_stream_config_get))
            elif i % 3 == 0:
                futures.append(executor.submit(test_stream_config_post))
            else:
                futures.append(executor.submit(test_status))

        for future in as_completed(futures):
            future.result()

    time.sleep(2)
    stream_alive_after = check_stream_alive()

    if stream_alive_before and stream_alive_after:
        print(f"  {Colors.GREEN}✓ Стрим выдержал экстремальную нагрузку!{Colors.RESET}")
    elif stream_alive_before and not stream_alive_after:
        print(f"  {Colors.RED}✗ СТРИМ УПАЛ ПОД НАГРУЗКОЙ!{Colors.RESET}")

    # Итоговая статистика
    stats.print_summary()

    # Финальная проверка стрима
    print(f"{Colors.BLUE}Финальная проверка стрима...{Colors.RESET}")
    if check_stream_alive():
        print(f"{Colors.GREEN}✓ Стрим работает корректно{Colors.RESET}")
    else:
        print(f"{Colors.YELLOW}⚠ Стрим не активен{Colors.RESET}")

    print(f"\n{Colors.BLUE}{'='*60}{Colors.RESET}")
    print(f"{Colors.GREEN}ТЕСТИРОВАНИЕ ЗАВЕРШЕНО{Colors.RESET}")
    print(f"{Colors.BLUE}{'='*60}{Colors.RESET}\n")

if __name__ == "__main__":
    try:
        main()
    except KeyboardInterrupt:
        print(f"\n{Colors.YELLOW}Тестирование прервано пользователем{Colors.RESET}")
        stats.print_summary()
        sys.exit(0)
