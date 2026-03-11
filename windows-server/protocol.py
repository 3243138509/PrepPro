import json
import socket
import struct
from typing import Any


def recv_exact(conn: socket.socket, size: int) -> bytes:
    chunks = []
    remaining = size
    while remaining > 0:
        chunk = conn.recv(remaining)
        if not chunk:
            raise ConnectionError("socket closed")
        chunks.append(chunk)
        remaining -= len(chunk)
    return b"".join(chunks)


def recv_frame(conn: socket.socket, max_frame_size: int) -> dict[str, Any]:
    header = recv_exact(conn, 4)
    (length,) = struct.unpack(">I", header)
    if length <= 0 or length > max_frame_size:
        raise ValueError(f"invalid frame length: {length}")
    payload = recv_exact(conn, length)
    return json.loads(payload.decode("utf-8"))


def send_frame(conn: socket.socket, msg: dict[str, Any]) -> None:
    payload = json.dumps(msg, ensure_ascii=False).encode("utf-8")
    header = struct.pack(">I", len(payload))
    conn.sendall(header + payload)
