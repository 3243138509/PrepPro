import logging
import queue as _queue
import socket
import threading
import time
import tkinter as tk
import traceback
import uuid
from pathlib import Path
from tkinter import ttk
from typing import Any

import pystray
from PIL import Image, ImageDraw

import config
from analyzer import analyze_image_base64, analyze_text_content, detect_model_names
from capture import capture_screen_jpeg_base64, list_displays
from clipboard import read_clipboard_text, write_clipboard_text
from ocr import build_prompt_with_ocr, scan_image_base64
from protocol import recv_frame, send_frame


_log_dir = Path(__file__).with_name("log")
_log_dir.mkdir(parents=True, exist_ok=True)
_log_file = _log_dir / "server.log"
logging.basicConfig(
    level=logging.INFO,
    format="%(asctime)s %(levelname)s %(message)s",
    handlers=[
        logging.FileHandler(_log_file, encoding="utf-8"),
        logging.StreamHandler(),
    ],
)


_clipboard_suppress_lock = threading.Lock()
_clipboard_suppress_count: int = 0
_stop_event = threading.Event()


def _mark_clipboard_suppressed_once() -> None:
    global _clipboard_suppress_count
    with _clipboard_suppress_lock:
        _clipboard_suppress_count += 1


def _should_suppress_clipboard_push_once() -> bool:
    global _clipboard_suppress_count
    with _clipboard_suppress_lock:
        if _clipboard_suppress_count > 0:
            _clipboard_suppress_count -= 1
            return True
        return False


def get_local_ipv4_addresses() -> list[str]:
    addresses: set[str] = set()

    # Try resolving interfaces from hostname.
    try:
        infos = socket.getaddrinfo(socket.gethostname(), None, socket.AF_INET)
        for info in infos:
            ip = info[4][0]
            if not ip.startswith("127."):
                addresses.add(ip)
    except Exception:
        pass

    # Fallback: infer outbound interface address.
    try:
        with socket.socket(socket.AF_INET, socket.SOCK_DGRAM) as s:
            s.connect(("8.8.8.8", 80))
            ip = s.getsockname()[0]
            if ip and not ip.startswith("127."):
                addresses.add(ip)
    except Exception:
        pass

    return sorted(addresses)


def handle_client(conn: socket.socket, addr: tuple[str, int]) -> None:
    logging.info("client connected: %s:%s", addr[0], addr[1])
    authed = False

    try:
        conn.settimeout(config.SOCKET_TIMEOUT_SECONDS)
        while True:
            msg = recv_frame(conn, config.MAX_FRAME_SIZE)
            msg_type = str(msg.get("type", "")).upper()

            if not authed:
                if msg_type != "AUTH":
                    send_frame(
                        conn,
                        {
                            "type": "ERROR",
                            "code": "ERROR_AUTH_REQUIRED",
                            "message": "auth required",
                        },
                    )
                    continue

                authed = True
                send_frame(conn, {"type": "AUTH_OK"})
                continue

            if msg_type == "CAPTURE":
                request_id = msg.get("requestId")
                quality = int(msg.get("quality", config.DEFAULT_JPEG_QUALITY))
                quality = max(30, min(95, quality))
                display_id = int(msg.get("displayId", 1))
                try:
                    image_base64, width, height = capture_screen_jpeg_base64(quality, display_id)
                    send_frame(
                        conn,
                        {
                            "type": "IMAGE",
                            "requestId": request_id,
                            "displayId": display_id,
                            "format": "jpeg",
                            "width": width,
                            "height": height,
                            "imageBase64": image_base64,
                        },
                    )
                except Exception:
                    logging.exception("capture failed")
                    send_frame(
                        conn,
                        {
                            "type": "ERROR",
                            "requestId": request_id,
                            "code": "ERROR_CAPTURE",
                            "message": f"capture failed (displayId={display_id})",
                        },
                    )
                continue

            if msg_type == "LIST_DISPLAYS":
                try:
                    send_frame(conn, {"type": "DISPLAYS", "displays": list_displays()})
                except Exception:
                    logging.exception("list displays failed")
                    send_frame(
                        conn,
                        {
                            "type": "ERROR",
                            "code": "ERROR_DISPLAYS",
                            "message": "list displays failed",
                        },
                    )
                continue

            if msg_type == "ANALYZE_IMAGE":
                request_id = msg.get("requestId")
                image_base64 = str(msg.get("imageBase64", ""))
                prompt = str(
                    msg.get(
                        "prompt",
                        "你现在是一个解题专家，你的任务是根据图片中的内容写出答案。概括性的描述图片内容，提取关键信息，分析并给出答案。请先给出答案的结论，然后再概括性说出解题思路",
                    )
                )
                if not image_base64:
                    send_frame(
                        conn,
                        {
                            "type": "ERROR",
                            "requestId": request_id,
                            "code": "ERROR_ANALYZE_INPUT",
                            "message": "imageBase64 is empty",
                        },
                    )
                    continue

                ocr_text = ""
                if config.OCR_ENABLED:
                    try:
                        ocr_scan = scan_image_base64(image_base64)
                        ocr_text = ocr_scan.text
                    except Exception as exc:
                        logging.exception("ocr pre-scan failed")
                        send_frame(
                            conn,
                            {
                                "type": "ERROR",
                                "requestId": request_id,
                                "code": "ERROR_OCR",
                                "message": str(exc),
                            },
                        )
                        continue

                    if config.OCR_REQUIRED and not ocr_text:
                        send_frame(
                            conn,
                            {
                                "type": "ERROR",
                                "requestId": request_id,
                                "code": "ERROR_OCR_EMPTY",
                                "message": "ocr text is empty; image blocked before upload",
                            },
                        )
                        continue

                try:
                    if config.ANALYZE_USE_OCR_TEXT:
                        if not ocr_text:
                            raise RuntimeError("ocr text is empty; cannot run text-only analysis")
                        result = analyze_text_content(prompt, ocr_text)
                    else:
                        result = analyze_image_base64(
                            image_base64,
                            build_prompt_with_ocr(prompt, ocr_text),
                        )
                    send_frame(
                        conn,
                        {
                            "type": "ANALYZE_RESULT",
                            "requestId": request_id,
                            "text": result.text,
                            "ocrText": ocr_text,
                            "modelNotice": result.model_notice,
                        },
                    )
                except Exception as exc:
                    logging.exception("analyze image failed")
                    send_frame(
                        conn,
                        {
                            "type": "ERROR",
                            "requestId": request_id,
                            "code": "ERROR_ANALYZE",
                            "message": str(exc),
                        },
                    )
                continue

            if msg_type == "ANALYZE_TEXT":
                request_id = msg.get("requestId")
                text_content = str(msg.get("text", ""))
                prompt = str(
                    msg.get(
                        "prompt",
                        "你现在是一个解题专家，你的任务是根据文字中的内容写出答案。概括性的描述图片内容，提取关键信息，分析并给出答案。请先给出答案的结论，然后再概括性说出解题思路",
                    )
                )
                if not text_content:
                    send_frame(
                        conn,
                        {
                            "type": "ERROR",
                            "requestId": request_id,
                            "code": "ERROR_ANALYZE_TEXT_INPUT",
                            "message": "text is empty",
                        },
                    )
                    continue

                try:
                    result = analyze_text_content(prompt, text_content)
                    send_frame(
                        conn,
                        {
                            "type": "ANALYZE_RESULT",
                            "requestId": request_id,
                            "text": result.text,
                            "ocrText": "",
                            "modelNotice": result.model_notice,
                        },
                    )
                except Exception as exc:
                    logging.exception("analyze text failed")
                    send_frame(
                        conn,
                        {
                            "type": "ERROR",
                            "requestId": request_id,
                            "code": "ERROR_ANALYZE",
                            "message": str(exc),
                        },
                    )
                continue

            if msg_type == "SUBSCRIBE_CLIPBOARD":
                logging.info("clipboard subscription started: %s:%s", addr[0], addr[1])
                baseline = read_clipboard_text()
                last_text = baseline.strip() if baseline else None
                while True:
                    text = read_clipboard_text()
                    if text:
                        text = text.strip()
                    if text and text != last_text:
                        if _should_suppress_clipboard_push_once():
                            last_text = text
                            time.sleep(config.CLIPBOARD_POLL_INTERVAL_SECONDS)
                            continue
                        send_frame(
                            conn,
                            {
                                "type": "CLIPBOARD_TEXT",
                                "requestId": str(uuid.uuid4()),
                                "text": text[: config.CLIPBOARD_MAX_TEXT_CHARS],
                            },
                        )
                        last_text = text
                    time.sleep(config.CLIPBOARD_POLL_INTERVAL_SECONDS)

            if msg_type == "SET_CLIPBOARD_TEXT":
                request_id = msg.get("requestId")
                text = str(msg.get("text", ""))
                if not text.strip():
                    send_frame(
                        conn,
                        {
                            "type": "ERROR",
                            "requestId": request_id,
                            "code": "ERROR_CLIPBOARD_INPUT",
                            "message": "clipboard text is empty",
                        },
                    )
                    continue

                try:
                    normalized = text[: config.CLIPBOARD_MAX_TEXT_CHARS].strip()
                    write_clipboard_text(normalized)
                    _mark_clipboard_suppressed_once()
                    send_frame(
                        conn,
                        {
                            "type": "CLIPBOARD_SET_OK",
                            "requestId": request_id,
                        },
                    )
                except Exception as exc:
                    logging.exception("set clipboard failed")
                    send_frame(
                        conn,
                        {
                            "type": "ERROR",
                            "requestId": request_id,
                            "code": "ERROR_CLIPBOARD_SET",
                            "message": str(exc),
                        },
                    )
                continue

            if msg_type == "GET_MODEL_SETTINGS":
                send_frame(
                    conn,
                    {
                        "type": "MODEL_SETTINGS",
                        "profiles": config.get_model_profiles(),
                        "activeIndex": config.get_active_model_index(),
                    },
                )
                continue

            if msg_type == "DETECT_MODELS":
                request_id = msg.get("requestId")
                api_url = str(msg.get("modelApiUrl", "")).strip()
                api_key = str(msg.get("modelApiKey", "")).strip()
                try:
                    names = detect_model_names(api_url, api_key)
                    send_frame(
                        conn,
                        {
                            "type": "MODEL_NAMES",
                            "requestId": request_id,
                            "models": names,
                        },
                    )
                except Exception as exc:
                    send_frame(
                        conn,
                        {
                            "type": "ERROR",
                            "requestId": request_id,
                            "code": "ERROR_DETECT_MODELS",
                            "message": str(exc),
                        },
                    )
                continue

            if msg_type == "ADD_MODEL_SETTING":
                request_id = msg.get("requestId")
                api_url = str(msg.get("modelApiUrl", "")).strip()
                api_key = str(msg.get("modelApiKey", "")).strip()
                model_name = str(msg.get("modelName", "")).strip()
                set_active = bool(msg.get("setActive", True))
                try:
                    updated = config.add_model_profile(api_url, api_key, model_name, set_active=set_active)
                    send_frame(
                        conn,
                        {
                            "type": "MODEL_SETTINGS",
                            "requestId": request_id,
                            "profiles": updated["profiles"],
                            "activeIndex": updated["activeIndex"],
                        },
                    )
                except Exception as exc:
                    send_frame(
                        conn,
                        {
                            "type": "ERROR",
                            "requestId": request_id,
                            "code": "ERROR_ADD_MODEL_SETTING",
                            "message": str(exc),
                        },
                    )
                continue

            if msg_type == "SET_ACTIVE_MODEL":
                request_id = msg.get("requestId")
                try:
                    index = int(msg.get("index", -1))
                    updated = config.set_active_model(index)
                    send_frame(
                        conn,
                        {
                            "type": "MODEL_SETTINGS",
                            "requestId": request_id,
                            "profiles": updated["profiles"],
                            "activeIndex": updated["activeIndex"],
                        },
                    )
                except Exception as exc:
                    send_frame(
                        conn,
                        {
                            "type": "ERROR",
                            "requestId": request_id,
                            "code": "ERROR_SET_ACTIVE_MODEL",
                            "message": str(exc),
                        },
                    )
                continue

            if msg_type == "DELETE_MODEL_SETTING":
                request_id = msg.get("requestId")
                try:
                    index = int(msg.get("index", -1))
                    updated = config.delete_model_profile(index)
                    send_frame(
                        conn,
                        {
                            "type": "MODEL_SETTINGS",
                            "requestId": request_id,
                            "profiles": updated["profiles"],
                            "activeIndex": updated["activeIndex"],
                        },
                    )
                except Exception as exc:
                    send_frame(
                        conn,
                        {
                            "type": "ERROR",
                            "requestId": request_id,
                            "code": "ERROR_DELETE_MODEL_SETTING",
                            "message": str(exc),
                        },
                    )
                continue

            send_frame(
                conn,
                {
                    "type": "ERROR",
                    "code": "ERROR_UNKNOWN_TYPE",
                    "message": f"unknown message type: {msg_type}",
                },
            )
    except Exception as exc:
        logging.info("client disconnected: %s (%s)", addr, exc)
    finally:
        conn.close()


def start_server() -> None:
    with socket.socket(socket.AF_INET, socket.SOCK_STREAM) as server:
        server.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
        server.bind((config.HOST, config.PORT))
        server.listen(20)
        server.settimeout(1.0)
        logging.info("server listening at %s:%s", config.HOST, config.PORT)
        ips = get_local_ipv4_addresses()
        if ips:
            logging.info("local ipv4 addresses: %s", ", ".join(ips))
        else:
            logging.info("local ipv4 addresses: not found")

        while not _stop_event.is_set():
            try:
                conn, addr = server.accept()
                threading.Thread(
                    target=handle_client,
                    args=(conn, addr),
                    daemon=True,
                ).start()
            except socket.timeout:
                continue
            except Exception:
                if not _stop_event.is_set():
                    logging.error("unexpected server error\n%s", traceback.format_exc())

        logging.info("server stopped")


def _create_tray_icon_image() -> Image.Image:
    icon_path = Path(__file__).with_name("image_video") / "icon.png"
    if icon_path.exists():
        return Image.open(icon_path)
    # fallback: generated icon
    size = 64
    img = Image.new("RGBA", (size, size), (0, 0, 0, 0))
    draw = ImageDraw.Draw(img)
    draw.ellipse([0, 0, 63, 63], fill=(15, 23, 42, 255))
    draw.ellipse([14, 14, 50, 50], fill=(34, 197, 94, 255))
    return img


if __name__ == "__main__":
    server_thread = threading.Thread(target=start_server, daemon=True)
    server_thread.start()

    ips = get_local_ipv4_addresses()
    ip_str = ", ".join(ips) if ips else "localhost"

    # Thread-safe queue for pushing callbacks onto the tkinter main thread
    _gui_queue: _queue.Queue = _queue.Queue()

    # ── Tkinter info window ──────────────────────────────────────────────────
    root = tk.Tk()
    root.title("RemoteCapture Server")
    root.resizable(False, False)
    root.withdraw()  # start hidden in tray

    _icon_path = Path(__file__).with_name("image_video") / "icon.png"
    if _icon_path.exists():
        try:
            _photo = tk.PhotoImage(file=str(_icon_path))
            root.iconphoto(True, _photo)
        except Exception:
            pass

    _frame = ttk.Frame(root, padding=24)
    _frame.pack(fill=tk.BOTH, expand=True)

    ttk.Label(_frame, text="RemoteCapture Server", font=("", 14, "bold")).grid(
        row=0, column=0, columnspan=2, pady=(0, 14), sticky="w"
    )

    def _add_info_row(row: int, label: str, value: str, fg: str = "") -> None:
        ttk.Label(_frame, text=label, foreground="#888").grid(
            row=row, column=0, sticky="nw", padx=(0, 20), pady=3
        )
        lbl = ttk.Label(_frame, text=value)
        if fg:
            lbl.configure(foreground=fg)
        lbl.grid(row=row, column=1, sticky="w", pady=3)

    _add_info_row(1, "状态", "● 运行中", "green")
    _add_info_row(2, "端口", str(config.PORT))
    _addr_lines = "\n".join(f"{ip}:{config.PORT}" for ip in (ips if ips else ["localhost"]))
    _add_info_row(3, "地址", _addr_lines)
    _add_info_row(4, "日志", str(_log_file))

    ttk.Separator(_frame, orient="horizontal").grid(
        row=5, column=0, columnspan=2, sticky="ew", pady=14
    )
    ttk.Button(_frame, text="最小化到托盘", command=lambda: root.withdraw()).grid(
        row=6, column=0, columnspan=2
    )

    root.protocol("WM_DELETE_WINDOW", lambda: root.withdraw())

    def _pump_gui() -> None:
        while True:
            try:
                fn = _gui_queue.get_nowait()
                fn()
            except _queue.Empty:
                break
        root.after(50, _pump_gui)

    root.after(50, _pump_gui)

    # ── System tray ──────────────────────────────────────────────────────────
    def _show_window() -> None:
        def _do() -> None:
            root.deiconify()
            root.lift()
            root.focus_force()
        _gui_queue.put(_do)

    def _on_quit(icon: pystray.Icon, item: pystray.MenuItem) -> None:
        logging.info("tray: quit requested")
        _stop_event.set()
        icon.stop()
        _gui_queue.put(root.quit)

    tooltip = f"RemoteCapture  |  {ip_str}:{config.PORT}"
    menu = pystray.Menu(
        pystray.MenuItem("显示主界面", lambda icon, item: _show_window(), default=True),
        pystray.Menu.SEPARATOR,
        pystray.MenuItem("退出", _on_quit),
    )
    tray = pystray.Icon("RemoteCapture", _create_tray_icon_image(), tooltip, menu)
    tray.run_detached()

    root.mainloop()
