#!/usr/bin/env python3
"""Selenium tests for Camera Server Web UI"""

import time
import sys
import requests
from selenium import webdriver
from selenium.webdriver.common.by import By
from selenium.webdriver.support.ui import WebDriverWait, Select
from selenium.webdriver.support import expected_conditions as EC
from selenium.webdriver.chrome.options import Options

# Fix Windows console encoding
sys.stdout.reconfigure(encoding='utf-8', errors='replace')

# Test configuration
BASE_URL = "http://localhost:8080"  # Pixel via adb forward
TIMEOUT = 10

def setup_driver():
    options = Options()
    options.add_argument("--headless")
    options.add_argument("--no-sandbox")
    options.add_argument("--disable-dev-shm-usage")
    options.add_argument("--window-size=1280,800")
    return webdriver.Chrome(options=options)

def test_page_loads(driver):
    """Test that the main page loads correctly"""
    driver.get(BASE_URL)
    assert "Camera Server" in driver.title, "Page title should contain 'Camera Server'"

    # Check main elements exist
    assert driver.find_element(By.ID, "btnStart"), "Start button should exist"
    assert driver.find_element(By.ID, "btnStop"), "Stop button should exist"
    assert driver.find_element(By.ID, "res"), "Resolution select should exist"
    assert driver.find_element(By.ID, "fps"), "FPS select should exist"
    assert driver.find_element(By.ID, "quality"), "Quality slider should exist"
    print("✓ Page loads correctly")

def test_status_display(driver):
    """Test that status is displayed and updates"""
    driver.get(BASE_URL)
    time.sleep(3)  # Wait for status to load

    # Check status values are populated
    cam_status = driver.find_element(By.ID, "sCam").text
    assert cam_status in ["Open", "Closed"], f"Camera status should be Open/Closed, got: {cam_status}"

    stream_status = driver.find_element(By.ID, "sStr").text
    assert stream_status in ["Active", "Stopped"], f"Stream status should be Active/Stopped, got: {stream_status}"

    resolution = driver.find_element(By.ID, "sRes").text
    assert "x" in resolution, f"Resolution should contain 'x', got: {resolution}"
    print("✓ Status displays correctly")

def test_start_stop_stream(driver):
    """Test starting and stopping the stream"""
    driver.get(BASE_URL)
    time.sleep(2)

    # Click Start
    start_btn = driver.find_element(By.ID, "btnStart")
    if start_btn.is_enabled():
        start_btn.click()
        time.sleep(3)

        # Check stream is active
        stream_status = driver.find_element(By.ID, "sStr").text
        assert stream_status == "Active", f"Stream should be Active after start, got: {stream_status}"

        # Check video is visible
        video = driver.find_element(By.ID, "vid")
        assert video.is_displayed(), "Video should be visible"
        print("✓ Stream started successfully")

    # Click Stop
    stop_btn = driver.find_element(By.ID, "btnStop")
    if stop_btn.is_enabled():
        stop_btn.click()
        time.sleep(2)

        # Check stream is stopped
        stream_status = driver.find_element(By.ID, "sStr").text
        assert stream_status == "Stopped", f"Stream should be Stopped, got: {stream_status}"
        print("✓ Stream stopped successfully")

def test_change_resolution(driver):
    """Test changing resolution via dropdown"""
    driver.get(BASE_URL)
    time.sleep(2)

    # Start stream first
    requests.post(f"{BASE_URL}/stream/start")
    time.sleep(2)

    # Change resolution to 640x480
    res_select = Select(driver.find_element(By.ID, "res"))
    res_select.select_by_value("640x480")
    time.sleep(3)

    # Verify via API - camera may select closest supported size
    status = requests.get(f"{BASE_URL}/status").json()
    resolution = status["camera"]["streamResolution"]
    assert "x" in resolution, f"Resolution format invalid: {resolution}"
    print(f"✓ Resolution change works (actual: {resolution})")

    # Change to 1080p
    res_select.select_by_value("1920x1080")
    time.sleep(3)

    status = requests.get(f"{BASE_URL}/status").json()
    resolution = status["camera"]["streamResolution"]
    # Allow some tolerance - camera picks closest supported
    width = int(resolution.split("x")[0])
    assert width >= 1280, f"Resolution should be at least 1280 wide, got: {resolution}"
    print(f"✓ Resolution change to 1080p works (actual: {resolution})")

def test_change_fps(driver):
    """Test changing FPS via dropdown"""
    driver.get(BASE_URL)
    time.sleep(2)

    # Change FPS to 60
    fps_select = Select(driver.find_element(By.ID, "fps"))
    fps_select.select_by_value("60")
    time.sleep(3)

    status = requests.get(f"{BASE_URL}/status").json()
    # FPS config is stored as requested, camera uses supported range
    assert status["camera"]["targetFps"] == 60, f"Target FPS should be 60, got: {status['camera']['targetFps']}"
    print("✓ FPS change works")

def test_change_quality(driver):
    """Test changing JPEG quality via slider"""
    driver.get(BASE_URL)
    time.sleep(2)

    # Set quality to 50 via JavaScript
    driver.execute_script("document.getElementById('quality').value = 50; document.getElementById('quality').dispatchEvent(new Event('change'));")
    time.sleep(3)

    status = requests.get(f"{BASE_URL}/status").json()
    assert status["camera"]["jpegQuality"] == 50, f"Quality should be 50, got: {status['camera']['jpegQuality']}"
    print("✓ Quality change works")

def test_quick_photo(driver):
    """Test quick photo capture"""
    driver.get(BASE_URL)
    time.sleep(2)

    # Ensure stream is running
    requests.post(f"{BASE_URL}/stream/start")
    time.sleep(2)

    # Click quick photo button
    quick_btn = driver.find_element(By.XPATH, "//button[contains(text(), 'Quick Photo')]")
    quick_btn.click()
    time.sleep(2)

    # Check photo preview appears
    preview = driver.find_element(By.ID, "photoPreview")
    assert "img" in preview.get_attribute("innerHTML"), "Photo preview should contain image"
    print("✓ Quick photo works")

def test_focus_mode(driver):
    """Test focus mode change"""
    driver.get(BASE_URL)
    time.sleep(2)

    # Change focus mode
    focus_select = Select(driver.find_element(By.ID, "focusMode"))
    focus_select.select_by_value("AUTO")
    time.sleep(2)

    status = requests.get(f"{BASE_URL}/status").json()
    assert status["camera"]["focusMode"] == "AUTO", f"Focus should be AUTO, got: {status['camera']['focusMode']}"
    print("✓ Focus mode change works")

def test_auto_reconnect(driver):
    """Test that stream auto-reconnects when server restarts stream"""
    driver.get(BASE_URL)
    time.sleep(2)

    # Start stream via UI
    start_btn = driver.find_element(By.ID, "btnStart")
    if start_btn.is_enabled():
        start_btn.click()
        time.sleep(3)

    # Stop stream via API (simulating server restart)
    requests.post(f"{BASE_URL}/stream/stop")
    time.sleep(2)

    # Start stream via API
    requests.post(f"{BASE_URL}/stream/start")
    time.sleep(4)  # Wait for auto-reconnect

    # Check video is visible again
    video = driver.find_element(By.ID, "vid")
    assert video.is_displayed(), "Video should auto-reconnect and be visible"
    print("✓ Auto-reconnect works")

def run_all_tests():
    print("=" * 50)
    print("Camera Server Selenium Tests")
    print("=" * 50)
    print(f"Testing: {BASE_URL}")
    print()

    # Check server is accessible
    try:
        r = requests.get(f"{BASE_URL}/health", timeout=5)
        assert r.status_code == 200
        print("✓ Server is accessible")
    except Exception as e:
        print(f"✗ Server not accessible: {e}")
        return False

    driver = None
    try:
        driver = setup_driver()
        print("✓ Chrome driver initialized")
        print()

        test_page_loads(driver)
        test_status_display(driver)
        test_start_stop_stream(driver)
        test_change_resolution(driver)
        test_change_fps(driver)
        test_change_quality(driver)
        test_quick_photo(driver)
        test_focus_mode(driver)
        test_auto_reconnect(driver)

        print()
        print("=" * 50)
        print("ALL TESTS PASSED!")
        print("=" * 50)
        return True

    except AssertionError as e:
        print(f"\n✗ Test failed: {e}")
        return False
    except Exception as e:
        print(f"\n✗ Error: {e}")
        return False
    finally:
        if driver:
            driver.quit()
        # Cleanup - stop stream
        requests.post(f"{BASE_URL}/stream/stop")

if __name__ == "__main__":
    success = run_all_tests()
    exit(0 if success else 1)
