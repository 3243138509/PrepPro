import base64
import io

from PIL import Image

import ocr


def _sample_image_base64() -> str:
    image = Image.new("RGB", (32, 16), "white")
    buf = io.BytesIO()
    image.save(buf, format="JPEG")
    return base64.b64encode(buf.getvalue()).decode("ascii")


def test_build_prompt_with_ocr_includes_scanned_text():
    prompt = "请描述图片"
    result = ocr.build_prompt_with_ocr(prompt, "第一行 文字")
    assert "请描述图片" in result
    assert "第一行 文字" in result
    assert "OCR" in result


def test_scan_image_base64_normalizes_text(monkeypatch):
    monkeypatch.setattr(ocr, "_run_rapidocr", lambda _img: "  A\n\nB\tC  ")
    result = ocr.scan_image_base64(_sample_image_base64())
    assert result.text == "A B C"


def test_scan_image_base64_rejects_invalid_payload():
    try:
        ocr.scan_image_base64("not-base64")
        assert False, "expected ValueError"
    except ValueError as exc:
        assert "invalid imageBase64" in str(exc)
