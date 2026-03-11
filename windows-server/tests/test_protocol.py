import json
import struct

from protocol import recv_frame


class FakeSocket:
    def __init__(self, chunks: list[bytes]):
        self._chunks = chunks

    def recv(self, n: int) -> bytes:
        if not self._chunks:
            return b""
        chunk = self._chunks[0]
        if len(chunk) <= n:
            self._chunks.pop(0)
            return chunk
        self._chunks[0] = chunk[n:]
        return chunk[:n]


def test_recv_frame_with_fragmented_packets():
    payload = json.dumps({"type": "AUTH", "password": "x"}).encode("utf-8")
    data = struct.pack(">I", len(payload)) + payload
    sock = FakeSocket([data[:2], data[2:7], data[7:]])
    msg = recv_frame(sock, 1024)
    assert msg["type"] == "AUTH"
