#!/usr/bin/env python3
# -*- coding: utf-8 -*-
r"""
smoke_test.py — 给 mock_carplay_sink.py 做端到端自检

## 为什么需要它

`mock_carplay_sink.py` 自己也是「一份实现」，它同样可能写错。如果它的 pair-verify
KDF / nonce / AAD 与真机不一致，我们的 App 连上去必然握手失败，而结论会被误读成
「我方发送端坏了」——那就白查一轮。

这个脚本以**客户端**身份，按真实 iPhone 的顺序走一遍：

```
OPTIONS → GET /info → POST /pair-verify M1/M3 → ANNOUNCE → SETUP → RECORD
→ 连 6030 推若干假 NALU
```

只要它能把 pair-verify 走到 State=4 并让 6030 收到字节，就说明 mock 这一侧是
可信的接收端：之后 App 连不上，就真的是 App 的问题。

## 用法

```bash
python mock_carplay_sink.py            # 另开一个终端
python smoke_test.py                   # 默认 127.0.0.1:7000
python smoke_test.py 192.168.1.11 7000
```

退出码 0 = 全链路通；非 0 = 卡在哪一步（控制台会指出）。
"""

from __future__ import annotations

import os
import socket
import sys
import time

from cryptography.hazmat.primitives import hashes
from cryptography.hazmat.primitives import hmac as _hmac
from cryptography.hazmat.primitives.asymmetric.x25519 import (
    X25519PrivateKey,
    X25519PublicKey,
)
from cryptography.hazmat.primitives.ciphers.aead import ChaCha20Poly1305

SALT = b"Pair-Verify-Encrypt-Salt"
INFO = b"Pair-Verify-Encrypt-Info"

TLV_METHOD = 0x00
TLV_IDENTIFIER = 0x01
TLV_PUBLIC_KEY = 0x03
TLV_PROOF = 0x04
TLV_ENCRYPTED_DATA = 0x05
TLV_STATE = 0x06


def tlv_encode(items) -> bytes:
    out = bytearray()
    for t, v in items:
        if not v:
            out += bytes([t, 0])
        for i in range(0, len(v), 255):
            seg = v[i : i + 255]
            out += bytes([t, len(seg)]) + seg
    return bytes(out)


def tlv_decode(data: bytes):
    items = []
    i = 0
    while i + 1 < len(data):
        t, ln = data[i], data[i + 1]
        i += 2
        if i + ln > len(data):
            break
        items.append((t, data[i : i + ln]))
        i += ln
    return items


def tlv_get(items, want: int):
    buf = bytearray()
    found = False
    for t, v in items:
        if t == want:
            found = True
            buf += v
    return bytes(buf) if found else None


def hkdf_sha512(ikm: bytes, salt: bytes, info: bytes, length: int) -> bytes:
    prk = _hmac.HMAC(salt if salt else b"\x00" * 64, hashes.SHA512())
    prk.update(ikm)
    prk = prk.finalize()
    okm = b""
    t = b""
    counter = 1
    while len(okm) < length:
        h = _hmac.HMAC(prk, hashes.SHA512())
        h.update(t + info + bytes([counter]))
        t = h.finalize()
        okm += t
        counter += 1
    return okm[:length]


class Rtsp:
    """极简 RTSP 客户端：按 Content-Length 读完整应答，其余字节留在缓冲里。"""

    def __init__(self, host: str, port: int):
        self.sock = socket.create_connection((host, port), 5)
        self.sock.settimeout(5)
        self.cseq = 0
        self.buf = b""

    def request(self, method: str, url: str, headers=None, body: bytes = b""):
        self.cseq += 1
        lines = [f"{method} {url} RTSP/1.0", f"CSeq: {self.cseq}", "User-Agent: AirPlay/540.31"]
        for k, v in (headers or {}).items():
            lines.append(f"{k}: {v}")
        if body:
            lines.append("Content-Length: %d" % len(body))
        raw = ("\r\n".join(lines) + "\r\n\r\n").encode() + body
        self.sock.sendall(raw)

        while b"\r\n\r\n" not in self.buf:
            chunk = self.sock.recv(65536)
            if not chunk:
                raise RuntimeError("连接被对端关闭")
            self.buf += chunk
        head, rest = self.buf.split(b"\r\n\r\n", 1)
        text = head.decode("utf-8", "replace")
        clen = 0
        for line in text.split("\r\n"):
            if line.lower().startswith("content-length:"):
                clen = int(line.split(":", 1)[1].strip())
        while len(rest) < clen:
            chunk = self.sock.recv(65536)
            if not chunk:
                raise RuntimeError("应答体被截断")
            rest += chunk
        self.buf = rest[clen:]
        status = text.split("\r\n")[0]
        return status, rest[:clen]

    def close(self):
        try:
            self.sock.close()
        except Exception:
            pass


def main() -> int:
    host = sys.argv[1] if len(sys.argv) > 1 else "127.0.0.1"
    port = int(sys.argv[2]) if len(sys.argv) > 2 else 7000

    print(f"== 目标 {host}:{port}")
    rtsp = Rtsp(host, port)

    status, _ = rtsp.request("OPTIONS", "*")
    print(f"[1] OPTIONS           → {status}")
    if "200" not in status:
        print("✗ OPTIONS 未通过")
        return 1

    status, body = rtsp.request("GET", "/info", {"X-Apple-ProtocolVersion": "1"})
    print(f"[2] GET /info         → {status}，{len(body)}B")
    if "200" not in status:
        print("✗ /info 未通过")
        return 1

    # ---- pair-verify M1：我方临时密钥 + PublicKey
    priv = X25519PrivateKey.generate()
    pub = priv.public_key().public_bytes_raw()
    status, body = rtsp.request(
        "POST",
        "/pair-verify",
        {"Content-Type": "application/octet-stream"},
        tlv_encode([(TLV_STATE, b"\x01"), (TLV_PUBLIC_KEY, pub)]),
    )
    print(f"[3] pair-verify M1    → {status}，{len(body)}B")
    if "200" not in status:
        print("✗ pair-verify M1 未通过")
        return 1

    items = tlv_decode(body)
    peer_pub = tlv_get(items, TLV_PUBLIC_KEY)
    enc = tlv_get(items, TLV_ENCRYPTED_DATA)
    state = tlv_get(items, TLV_STATE)
    if not peer_pub or len(peer_pub) != 32 or not enc:
        print("✗ M2 缺少 PublicKey / EncryptedData")
        return 1
    print(f"    M2: State={state.hex() if state else '-'}，PublicKey={peer_pub.hex()[:16]}…，Enc={len(enc)}B")

    shared = priv.exchange(X25519PublicKey.from_public_bytes(peer_pub))
    session_key = hkdf_sha512(shared, SALT, INFO, 32)
    aad = peer_pub + pub
    try:
        plain = ChaCha20Poly1305(session_key).decrypt(
            b"\x00\x00\x00\x00" + b"PV-Msg02", enc, aad
        )
        print(f"[4] 解密 M2           → 通过（明文 {len(plain)}B）")
    except Exception as e:
        print(f"✗ 解密 M2 失败：{e!r}")
        print("  → mock 的 KDF/nonce/AAD 与真实 iPhone 不一致，先修它")
        return 1

    # ---- M3：回一段用同 key 加密的验证数据
    m3_plain = os.urandom(32) + bytes(16)
    m3_enc = ChaCha20Poly1305(session_key).encrypt(
        b"\x00\x00\x00\x00" + b"PV-Msg03", m3_plain, aad
    )
    status, body = rtsp.request(
        "POST",
        "/pair-verify",
        {"Content-Type": "application/octet-stream"},
        tlv_encode([(TLV_STATE, b"\x03"), (TLV_ENCRYPTED_DATA, m3_enc)]),
    )
    m4_state = tlv_get(tlv_decode(body), TLV_STATE)
    print(f"[5] pair-verify M3    → {status}，M4 State={m4_state.hex() if m4_state else '-'}")
    if "200" not in status or m4_state != b"\x04":
        print("✗ pair-verify 未走到 State=4")
        return 1

    # ---- 媒体协商
    sdp = (
        "v=0\r\no=- 0 0 IN IP4 0.0.0.0\r\ns=AirPlay\r\n"
        "m=video 0 RTP/AVP 96\r\na=rtpmap:96 H264/90000\r\n"
    ).encode()
    status, _ = rtsp.request("ANNOUNCE", "/stream", {"Content-Type": "application/sdp"}, sdp)
    print(f"[6] ANNOUNCE          → {status}")

    status, body = rtsp.request(
        "POST",
        "/stream-setup",
        {"Content-Type": "application/x-apple-binary-plist"},
        b"",
    )
    print(f"[7] /stream-setup     → {status}，{len(body)}B")
    screen_port = 6030
    if "200" in status and b"<key>port</key>" in body:
        import re

        nums = [int(x) for x in re.findall(rb"<key>port</key>\s*<integer>(\d+)</integer>", body)]
        if nums:
            screen_port = nums[0]
    print(f"    → 屏幕流端口 {screen_port}")

    status, _ = rtsp.request("SETUP", "/stream", {"Transport": "RTP/AVP/TCP;interleaved=0-1"})
    print(f"[8] SETUP             → {status}")
    status, _ = rtsp.request("RECORD", "/stream", {"Range": "npt=0.000000-"})
    print(f"[9] RECORD            → {status}")
    if "200" not in status:
        print("✗ RECORD 未通过")
        return 1

    # 交织流：在 RTSP 连接上按 $ 帧发两帧（真实接收端应能收下）
    frame = b"\x00\x00\x00\x01\x09\x10" + bytes(600)
    for _ in range(2):
        rtsp.sock.sendall(b"$" + bytes([0]) + len(frame).to_bytes(2, "big") + frame)

    # 裸流：6030 推若干假 NALU
    sent = 0
    try:
        s = socket.create_connection((host, screen_port), 5)
        for i in range(5):
            nalu = b"\x00\x00\x00\x01\x09\x10" + bytes(500)
            s.sendall(nalu)
            sent += len(nalu)
            time.sleep(0.05)
        time.sleep(0.3)
        s.close()
        print(f"[10] 6030 推流        → 已发 {sent}B")
    except Exception as e:
        print(f"⚠ 6030 推流失败：{e!r}（mock 端可能未监听，检查 --screen-port）")
        return 1

    rtsp.close()
    print("\n✓ 全链路通：OPTIONS → /info → pair-verify State=4 → SETUP/RECORD → 6030")
    print("  下一步可以让 App 的「测试台接收端」填这个地址，对比两边日志。")
    return 0


if __name__ == "__main__":
    sys.exit(main())
