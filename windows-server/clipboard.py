import ctypes
import ctypes.wintypes
from dataclasses import dataclass


CF_UNICODETEXT = 13
GMEM_MOVEABLE = 0x0002
GMEM_ZEROINIT = 0x0040

user32 = ctypes.WinDLL("user32", use_last_error=True)
kernel32 = ctypes.WinDLL("kernel32", use_last_error=True)

user32.OpenClipboard.argtypes = [ctypes.wintypes.HWND]
user32.OpenClipboard.restype = ctypes.wintypes.BOOL

user32.CloseClipboard.argtypes = []
user32.CloseClipboard.restype = ctypes.wintypes.BOOL

user32.GetClipboardData.argtypes = [ctypes.wintypes.UINT]
user32.GetClipboardData.restype = ctypes.wintypes.HANDLE

user32.EmptyClipboard.argtypes = []
user32.EmptyClipboard.restype = ctypes.wintypes.BOOL

user32.SetClipboardData.argtypes = [ctypes.wintypes.UINT, ctypes.wintypes.HANDLE]
user32.SetClipboardData.restype = ctypes.wintypes.HANDLE

kernel32.GlobalAlloc.argtypes = [ctypes.wintypes.UINT, ctypes.c_size_t]
kernel32.GlobalAlloc.restype = ctypes.wintypes.HGLOBAL

kernel32.GlobalLock.argtypes = [ctypes.wintypes.HGLOBAL]
kernel32.GlobalLock.restype = ctypes.c_void_p

kernel32.GlobalUnlock.argtypes = [ctypes.wintypes.HGLOBAL]
kernel32.GlobalUnlock.restype = ctypes.wintypes.BOOL

kernel32.GlobalFree.argtypes = [ctypes.wintypes.HGLOBAL]
kernel32.GlobalFree.restype = ctypes.wintypes.HGLOBAL


@dataclass
class ClipboardSnapshot:
    text: str


def read_clipboard_text() -> str | None:
    if not user32.OpenClipboard(None):
        return None

    try:
        handle = user32.GetClipboardData(CF_UNICODETEXT)
        if not handle:
            return None

        ptr = kernel32.GlobalLock(handle)
        if not ptr:
            return None

        try:
            text = ctypes.wstring_at(ptr)
            return text
        finally:
            kernel32.GlobalUnlock(handle)
    finally:
        user32.CloseClipboard()


def write_clipboard_text(text: str) -> None:
    if text is None:
        raise ValueError("clipboard text is None")

    data = str(text)
    size_in_bytes = (len(data) + 1) * ctypes.sizeof(ctypes.c_wchar)
    hglobal = kernel32.GlobalAlloc(GMEM_MOVEABLE | GMEM_ZEROINIT, size_in_bytes)
    if not hglobal:
        raise RuntimeError("GlobalAlloc failed")

    ptr = kernel32.GlobalLock(hglobal)
    if not ptr:
        kernel32.GlobalFree(hglobal)
        raise RuntimeError("GlobalLock failed")

    try:
        buffer = ctypes.create_unicode_buffer(data)
        ctypes.memmove(ptr, buffer, size_in_bytes)
    finally:
        kernel32.GlobalUnlock(hglobal)

    if not user32.OpenClipboard(None):
        kernel32.GlobalFree(hglobal)
        raise RuntimeError("OpenClipboard failed")

    try:
        if not user32.EmptyClipboard():
            kernel32.GlobalFree(hglobal)
            raise RuntimeError("EmptyClipboard failed")

        handle = user32.SetClipboardData(CF_UNICODETEXT, hglobal)
        if not handle:
            kernel32.GlobalFree(hglobal)
            raise RuntimeError("SetClipboardData failed")

        # Ownership is transferred to the OS on success.
        hglobal = None
    finally:
        user32.CloseClipboard()
