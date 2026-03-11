import base64
import json
import io
import os
import re
import tempfile
import threading
import atexit
import subprocess
import importlib
from pathlib import Path
from dataclasses import dataclass

from PIL import Image

import config

try:
    _ExternalOcrAPI = getattr(importlib.import_module("RapidOCR_api"), "OcrAPI", None)
except Exception:
    _ExternalOcrAPI = None


@dataclass
class OCRScanResult:
    text: str


_ocr_instance = None
_ocr_lock = threading.Lock()


class _LocalRapidOcrAPI:
    def __init__(self, exe_path: str) -> None:
        self.exe_path = exe_path

    def run(self, image_path: str):
        cmd = [self.exe_path]
        models_path = config.OCR_RAPID_MODELS_PATH.strip()
        if models_path:
            cmd.append(f"--models={models_path}")
        cmd.append(f"--image={image_path}")
        run_kwargs = {
            "capture_output": True,
            "text": False,
            "timeout": config.OCR_RAPID_TIMEOUT_SECONDS,
        }
        if os.name == "nt":
            startupinfo = subprocess.STARTUPINFO()
            startupinfo.dwFlags |= subprocess.STARTF_USESHOWWINDOW
            run_kwargs["startupinfo"] = startupinfo
            run_kwargs["creationflags"] = subprocess.CREATE_NO_WINDOW
        try:
            proc = subprocess.run(cmd, **run_kwargs)
        except FileNotFoundError as exc:
            raise RuntimeError(
                "无法启动 RapidOCR-json.exe，请确认可执行文件已随封装发布。"
                f" path={self.exe_path} cwd={Path.cwd()}"
            ) from exc
        stdout = _decode_subprocess_output(proc.stdout)
        stderr = _decode_subprocess_output(proc.stderr)
        if proc.returncode != 0:
            err = (stderr or stdout or "").strip()
            raise RuntimeError(f"RapidOCR-json 执行失败: {err}")
        return _parse_rapidocr_stdout(stdout)

    def stop(self) -> None:
        return


def _decode_image_base64(image_base64: str) -> Image.Image:
    try:
        raw = base64.b64decode(image_base64, validate=True)
    except Exception as exc:
        raise ValueError("invalid imageBase64 payload") from exc

    try:
        with Image.open(io.BytesIO(raw)) as image:
            return image.convert("RGB")
    except Exception as exc:
        raise ValueError("imageBase64 is not a valid image") from exc


def _normalize_text(text: str) -> str:
    compact = re.sub(r"\s+", " ", text).strip()
    if len(compact) > config.OCR_MAX_TEXT_CHARS:
        return compact[: config.OCR_MAX_TEXT_CHARS]
    return compact


def _get_ocr_instance():
    global _ocr_instance
    if _ocr_instance is not None:
        return _ocr_instance

    if not config.OCR_RAPID_EXE_PATH:
        raise RuntimeError("未配置 RapidOCR 引擎路径，请设置 RC_OCR_RAPID_EXE_PATH")

    exe_path = str(Path(config.OCR_RAPID_EXE_PATH).expanduser())
    exe_file = Path(exe_path)
    if not exe_file.exists():
        raise RuntimeError(
            "RapidOCR-json.exe 不存在，请检查 RC_OCR_RAPID_EXE_PATH。"
            f" current={exe_file} cwd={Path.cwd()}"
        )

    models_path = Path(config.OCR_RAPID_MODELS_PATH).expanduser()
    if not models_path.exists():
        raise RuntimeError(
            "RapidOCR models 目录不存在，请检查 RC_OCR_RAPID_MODELS_PATH。"
            f" current={models_path}"
        )

    if _ExternalOcrAPI is not None and os.name != "nt":
        _ocr_instance = _ExternalOcrAPI(exe_path)
    else:
        _ocr_instance = _LocalRapidOcrAPI(exe_path)
    return _ocr_instance


def stop_ocr_engine() -> None:
    global _ocr_instance
    with _ocr_lock:
        if _ocr_instance is None:
            return
        try:
            _ocr_instance.stop()
        finally:
            _ocr_instance = None


atexit.register(stop_ocr_engine)


def _extract_text_from_result(result) -> str:
    payload = result
    if isinstance(result, str):
        try:
            payload = json.loads(result)
        except Exception:
            payload = result

    if isinstance(payload, dict):
        data = payload.get("data")
        if isinstance(data, list):
            parts: list[str] = []
            for item in data:
                if isinstance(item, dict):
                    text = str(item.get("text", "")).strip()
                    if text:
                        parts.append(text)
            return "\n".join(parts)
        return str(payload.get("text", "")).strip()

    if isinstance(payload, list):
        parts = []
        for item in payload:
            if isinstance(item, dict):
                text = str(item.get("text", "")).strip()
            else:
                text = str(item).strip()
            if text:
                parts.append(text)
        return "\n".join(parts)

    return str(payload).strip()


def _parse_rapidocr_stdout(stdout: str):
    text = (stdout or "").strip()
    if not text:
        return {}

    # RapidOCR-json prints banner lines before final JSON payload.
    lines = [line.strip() for line in text.splitlines() if line.strip()]
    for line in reversed(lines):
        if line.startswith("{") and line.endswith("}"):
            try:
                return json.loads(line)
            except Exception:
                pass

    # Fallback: try parsing from the last opening brace.
    idx = text.rfind("{")
    if idx >= 0:
        tail = text[idx:]
        try:
            return json.loads(tail)
        except Exception:
            pass

    return text


def _decode_subprocess_output(raw: bytes | str | None) -> str:
    if raw is None:
        return ""
    if isinstance(raw, str):
        return raw
    for enc in ("utf-8", "gbk"):
        try:
            return raw.decode(enc)
        except Exception:
            continue
    return raw.decode("utf-8", errors="replace")


def _run_rapidocr(image: Image.Image) -> str:
    ocr = _get_ocr_instance()
    with tempfile.NamedTemporaryFile(suffix=".png", delete=False) as tmp:
        tmp_path = tmp.name
    try:
        image.save(tmp_path, format="PNG")
        with _ocr_lock:
            result = ocr.run(tmp_path)
        return _extract_text_from_result(result)
    finally:
        try:
            os.remove(tmp_path)
        except Exception:
            pass


def scan_image_base64(image_base64: str) -> OCRScanResult:
    image = _decode_image_base64(image_base64)
    text = _run_rapidocr(image)
    return OCRScanResult(text=_normalize_text(text))


def build_prompt_with_ocr(user_prompt: str, ocr_text: str) -> str:
    if not ocr_text:
        return user_prompt

    return (
        f"{user_prompt}\n\n"
        "以下是上传前 OCR 扫描提取的文本（可能有误，请结合图片内容核对）：\n"
        f"{ocr_text}\n\n"
        "请优先基于图片内容作答，并在可用时参考 OCR 文本。"
    )
