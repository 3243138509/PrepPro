from PIL import Image

import capture


def test_capture_windows_keeps_mss_path(monkeypatch):
    called = {"fallback": False}

    monkeypatch.setattr(capture.platform, "system", lambda: "Windows")
    monkeypatch.setattr(
        capture,
        "_capture_with_mss_jpeg_base64",
        lambda quality, display_id: ("ok", 100, 200),
    )

    def _fallback() -> Image.Image:
        called["fallback"] = True
        return Image.new("RGB", (1, 1), "white")

    monkeypatch.setattr(capture, "_capture_with_ubuntu_command", _fallback)

    encoded, width, height = capture.capture_screen_jpeg_base64(quality=80, display_id=1)

    assert encoded == "ok"
    assert width == 100
    assert height == 200
    assert called["fallback"] is False


def test_capture_ubuntu_fallback_when_mss_unavailable(monkeypatch):
    monkeypatch.setattr(capture.platform, "system", lambda: "Linux")
    monkeypatch.setattr(capture, "_is_ubuntu_linux", lambda: True)

    def _raise_mss(_quality: int, _display_id: int):
        raise RuntimeError("mss failed")

    monkeypatch.setattr(capture, "_capture_with_mss_jpeg_base64", _raise_mss)
    monkeypatch.setattr(
        capture,
        "_capture_with_ubuntu_command",
        lambda: Image.new("RGB", (10, 20), "white"),
    )

    encoded, width, height = capture.capture_screen_jpeg_base64(quality=70, display_id=1)

    assert isinstance(encoded, str)
    assert encoded
    assert width == 10
    assert height == 20


def test_list_displays_ubuntu_fallback(monkeypatch):
    monkeypatch.setattr(capture, "_is_ubuntu_linux", lambda: True)

    class _BrokenMSS:
        def __enter__(self):
            raise RuntimeError("mss init failed")

        def __exit__(self, exc_type, exc, tb):
            return False

    monkeypatch.setattr(capture.mss, "mss", lambda: _BrokenMSS())
    monkeypatch.setattr(
        capture,
        "_capture_with_ubuntu_command",
        lambda: Image.new("RGB", (1366, 768), "white"),
    )

    displays = capture.list_displays()

    assert displays == [{"id": 1, "left": 0, "top": 0, "width": 1366, "height": 768}]
