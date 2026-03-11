import base64
import io
import os
import platform
import re
import shutil
import subprocess
import tempfile
from typing import Any

from PIL import Image
import mss


def _encode_image_to_jpeg_base64(image: Image.Image, quality: int) -> tuple[str, int, int]:
    output = io.BytesIO()
    image.save(output, format="JPEG", quality=quality, optimize=True)
    data = output.getvalue()
    encoded = base64.b64encode(data).decode("ascii")
    return encoded, image.width, image.height


def _capture_with_mss_image(display_id: int) -> Image.Image:
    with mss.mss() as sct:
        monitors = sct.monitors
        if display_id < 1 or display_id >= len(monitors):
            raise ValueError(f"invalid display_id: {display_id}")

        monitor = monitors[display_id]
        shot = sct.grab(monitor)
        return Image.frombytes("RGB", shot.size, shot.rgb)


def _capture_with_mss_jpeg_base64(quality: int, display_id: int) -> tuple[str, int, int]:
    image = _capture_with_mss_image(display_id)
    return _encode_image_to_jpeg_base64(image, quality)


def _is_ubuntu_linux() -> bool:
    if platform.system() != "Linux":
        return False

    os_release_path = "/etc/os-release"
    if not os.path.exists(os_release_path):
        return False

    try:
        content = ""
        with open(os_release_path, "r", encoding="utf-8") as f:
            content = f.read().lower()
        return "id=ubuntu" in content or "id_like=ubuntu" in content
    except OSError:
        return False


def _capture_with_ubuntu_command(monitor_bounds: dict[str, int] | None = None) -> Image.Image:
    temp_file = tempfile.NamedTemporaryFile(suffix=".png", delete=False)
    temp_file.close()
    screenshot_path = temp_file.name

    try:
        commands: list[tuple[str, list[str]]] = []
        if shutil.which("grim"):
            commands.append(("grim", ["grim", screenshot_path]))
        if shutil.which("gnome-screenshot"):
            commands.append(("gnome-screenshot", ["gnome-screenshot", "-f", screenshot_path]))
        if shutil.which("scrot"):
            commands.append(("scrot", ["scrot", screenshot_path]))
        if shutil.which("import"):
            commands.append(("import", ["import", "-window", "root", screenshot_path]))

        ffmpeg_cmd = _build_ffmpeg_x11_screenshot_command(screenshot_path, monitor_bounds)
        if ffmpeg_cmd is not None:
            commands.append(("ffmpeg", ffmpeg_cmd))

        if not commands:
            raise RuntimeError(
                "no screenshot command found (need grim/gnome-screenshot/scrot/import/ffmpeg). "
                "On Ubuntu, install one with: sudo apt install gnome-screenshot or sudo apt install ffmpeg"
            )

        errors: list[str] = []
        for tool_name, command in commands:
            try:
                subprocess.run(command, check=True, capture_output=True)
                with Image.open(screenshot_path) as image:
                    rgb_image = image.convert("RGB")
                    if monitor_bounds is not None and tool_name != "ffmpeg":
                        return rgb_image.crop((
                            monitor_bounds["left"],
                            monitor_bounds["top"],
                            monitor_bounds["left"] + monitor_bounds["width"],
                            monitor_bounds["top"] + monitor_bounds["height"],
                        ))
                    return rgb_image
            except Exception as exc:
                if isinstance(exc, subprocess.CalledProcessError):
                    stderr_text = ""
                    if exc.stderr:
                        try:
                            stderr_text = exc.stderr.decode("utf-8", errors="ignore").strip()
                        except Exception:
                            stderr_text = str(exc.stderr)
                    errors.append(f"{tool_name} failed (exit={exc.returncode}): {stderr_text}")
                else:
                    errors.append(f"{tool_name} failed: {exc}")

        raise RuntimeError("all screenshot backends failed: " + " | ".join(errors))
    finally:
        if os.path.exists(screenshot_path):
            os.remove(screenshot_path)


def _build_ffmpeg_x11_screenshot_command(
    screenshot_path: str,
    monitor_bounds: dict[str, int] | None = None,
) -> list[str] | None:
    if shutil.which("ffmpeg") is None:
        return None

    display = os.environ.get("DISPLAY", ":0")
    video_size = "1920x1080"
    offset_x = 0
    offset_y = 0

    if monitor_bounds is not None:
        video_size = f"{monitor_bounds['width']}x{monitor_bounds['height']}"
        offset_x = monitor_bounds["left"]
        offset_y = monitor_bounds["top"]
    elif shutil.which("xrandr"):
        try:
            result = subprocess.run(
                ["xrandr", "--current"],
                check=True,
                capture_output=True,
                text=True,
            )
            match = re.search(r"current\s+(\d+)\s+x\s+(\d+)", result.stdout)
            if match:
                video_size = f"{match.group(1)}x{match.group(2)}"
        except Exception:
            pass

    display_input = f"{display}.0+{offset_x},{offset_y}"

    return [
        "ffmpeg",
        "-hide_banner",
        "-loglevel",
        "error",
        "-f",
        "x11grab",
        "-video_size",
        video_size,
        "-i",
        display_input,
        "-frames:v",
        "1",
        "-y",
        screenshot_path,
    ]


def list_displays() -> list[dict[str, Any]]:
    try:
        with mss.mss() as sct:
            displays: list[dict[str, Any]] = []
            # mss.monitors[0] is the virtual full screen; real displays start at 1.
            for idx, mon in enumerate(sct.monitors[1:], start=1):
                displays.append(
                    {
                        "id": idx,
                        "left": int(mon["left"]),
                        "top": int(mon["top"]),
                        "width": int(mon["width"]),
                        "height": int(mon["height"]),
                    }
                )
            return displays
    except Exception:
        if not _is_ubuntu_linux():
            raise

        # Ubuntu fallback tools generally return the full desktop image only.
        image = _capture_with_ubuntu_command()
        return [{"id": 1, "left": 0, "top": 0, "width": image.width, "height": image.height}]


def capture_screen_jpeg_base64(quality: int = 75, display_id: int = 1) -> tuple[str, int, int]:
    if platform.system() == "Windows":
        # Keep the existing Windows path unchanged.
        return _capture_with_mss_jpeg_base64(quality, display_id)

    monitor_bounds: dict[str, int] | None = None
    try:
        return _capture_with_mss_jpeg_base64(quality, display_id)
    except Exception:
        if not _is_ubuntu_linux():
            raise
        
        # Try to get monitor bounds even if mss grab failed
        try:
            with mss.mss() as sct:
                monitors = sct.monitors
                if 1 <= display_id < len(monitors):
                    mon = monitors[display_id]
                    monitor_bounds = {
                        "left": int(mon["left"]),
                        "top": int(mon["top"]),
                        "width": int(mon["width"]),
                        "height": int(mon["height"]),
                    }
        except Exception:
            pass
        
        image = _capture_with_ubuntu_command(monitor_bounds)
        return _encode_image_to_jpeg_base64(image, quality)
