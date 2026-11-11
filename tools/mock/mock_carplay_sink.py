#!/usr/bin/env python3
# -*- coding: utf-8 -*-
r"""
mock_carplay_sink.py — 模拟「无线 CarPlay 接收端」（假的 Head Unit）

## 目的

在没有车机、或车机不便上电时，用一台普通机器（PC / Termux 手机）扮演车机侧的
AirPlay/CarPlay 接收端，用来把 CarPlayAPK 的「Car+ → CarPlay」这段链路单独调通：

```
CarPlayAPK(手机)  --mDNS-->  mock(本脚本 :7000)
                --OPTIONS / GET /info / POST /pair-verify / POST /stream-setup-->
                --屏幕流(TCP 6030 或 RTSP 交织 $ 帧)-->
```

真实车机里 7000 是 RTSP、6030 是屏幕流；本脚本两者都实现，
并且**把每个字节都打出来**，所以它同时是一个「协议对照器」。

## 用法

PC（Windows / macOS / Linux）：

```bash
pip install zeroconf cryptography
python mock_carplay_sink.py                 # 监听 0.0.0.0:7000，并广播 _airplay._tcp
python mock_carplay_sink.py --no-mdns       # 不广播，只当服务端（自己指定 IP 时用）
python mock_carplay_sink.py --screen-port 6030 --save screen.h264
```

另一台手机（Termux）：

```bash
pkg install python
pip install zeroconf cryptography
python mock_carplay_sink.py
```

> Android 上监听 <1024 的端口需要 root，7000 没问题；6030 也没问题。

## 与 CarPlayAPK 的对接

1. 手机与模拟端必须在**同一个局域网**（家/办公室路由器即可，不必是车机热点）。
2. CarPlayAPK 里用「WLAN 连接」手动填当前 WiFi 的 SSID/密码 —— 手动模式会**跳过
   车机网段校验**，否则 192.168.5.x 的检查会把这个局域网挡掉。
3. 启动后 CarPlayAPK 会自己 mDNS 发现本脚本；若网络屏蔽组播，用 `--no-mdns` 起，
   并把 IP 直接告诉 CarPlayAPK。

## 端口

| 端口 | 协议 | 用途 |
|---|---|---|
| 7000 | TCP | RTSP 控制（OPTIONS / info / pair-verify / stream-setup / ANNOUNCE / SETUP / RECORD） |
| 6030 | TCP | 屏幕码流（车机侧原生固定端口） |
| 5001 | TCP | 事件通道（GET /event） |
| 5020 | UDP | 低功耗保活（CarPlay-kplv） |

## 输出

- 控制台：逐条请求/响应摘要，`→` 客户端发来的、`←` 本端回复的
- `mock_sink.log`：完整记录（含十六进制）
- `--save x.h264`：把 6030 收到的裸流落盘
"""

from __future__ import annotations

import argparse
import binascii
import os
import socket
import struct
import sys
import threading
import time

try:
    from cryptography.hazmat.primitives.asymmetric.x25519 import (
        X25519PrivateKey, X25519PublicKey,
    )
    from cryptography.hazmat.primitives.ciphers.aead import ChaCha20Poly1305
    from cryptography.hazmat.primitives import hashes, hmac as _hmac
except Exception as e:  # pragma: no cover
    print("需要 cryptography：pip install cryptography")
    raise

# ---------------------------------------------------------------- 常量

RTSP_PORT = 7000
SCREEN_PORT = 6030
EVENT_PORT = 5001
KEEPALIVE_PORT = 5020

SALT = b"Pair-Verify-Encrypt-Salt"
INFO = b"Pair-Verify-Encrypt-Info"

TLV_METHOD = 0x00
TLV_IDENTIFIER = 0x01
TLV_SALT = 0x02
TLV_PUBLIC_KEY = 0x03
TLV_PROOF = 0x04
TLV_ENCRYPTED_DATA = 0x05
TLV_STATE = 0x06
TLV_ERROR = 0x07

_lock = threading.Lock()
_logfile = None


def log(msg: str, echo: bool = True) -> None:
    line = f"[{time.strftime('%H:%M:%S')}] {msg}"
    with _lock:
        if echo:
            print(line, flush=True)
        if _logfile:
            _logfile.write(line + "\n")
            _logfile.flush()


def hexdump(data: bytes, limit: int = 512) -> str:
    d = data[:limit]
    out = []
    for i in range(0, len(d), 16):
        chunk = d[i:i + 16]
        hx = " ".join(f"{b:02x}" for b in chunk)
        out.append(f"    {i:04x}  {hx}")
    if len(data) > limit:
        out.append(f"    ... (共 {len(data)} 字节)")
    return "\n".join(out)


# ---------------------------------------------------------------- TLV8

def tlv_encode(items) -> bytes:
    out = bytearray()
    for t, v in items:
        # TLV8：单条最长 255，超出要拆成同 type 的连续段
        if not v:
            out += bytes([t, 0])
        for i in range(0, len(v), 255):
            seg = v[i:i + 255]
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
        items.append((t, data[i:i + ln]))
        i += ln
    return items


def tlv_get(items, want: int):
    """取第一个匹配的 TLV；TLV8 的长值可能被拆段，这里做拼接。"""
    buf = bytearray()
    found = False
    for t, v in items:
        if t == want:
            found = True
            buf += v
    return bytes(buf) if found else None


# ---------------------------------------------------------------- 密钥

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


class AccessoryKeys:
    """车机（接收端）侧的临时 Curve25519 密钥 + 派生出的对称密钥。"""

    def __init__(self):
        self.priv = X25519PrivateKey.generate()
        self.pub = self.priv.public_key().public_bytes_raw()
        self.session_key = None
        self.peer_pub = None

    def agree(self, peer_pub: bytes) -> bytes:
        self.peer_pub = peer_pub
        shared = self.priv.exchange(X25519PublicKey.from_public_bytes(peer_pub))
        self.session_key = hkdf_sha512(shared, SALT, INFO, 32)
        return shared


# ---------------------------------------------------------------- plist（够用即可）

def plist_xml(width: int, height: int) -> bytes:
    """返回一个 XML plist 能力表。

    刻意用 XML 而不是二进制 plist：CarPlayAPK 的 `/info` 解析目前是「正则找 3~4 位数字」，
    XML 里 1280/768 都是明文数字，能直接命中，省得再造一个 bin-plist 编码器。
    """
    body = f"""<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0">
<dict>
    <key>model</key><string>MockCarPlaySink1,1</string>
    <key>name</key><string>Mock Head Unit</string>
    <key>srcvers</key><string>950.7.1</string>
    <key>features</key><integer>7</integer>
    <key>displayWidth</key><integer>{width}</integer>
    <key>displayHeight</key><integer>{height}</integer>
    <key>displayRefreshRate</key><integer>30</integer>
    <key>audioFormats</key><array><string>AAC-48000-2</string></array>
</dict>
</plist>
"""
    return body.encode("utf-8")


def plist_stream_setup(screen_port: int, event_port: int) -> bytes:
    body = f"""<?xml version="1.0" encoding="UTF-8"?>
<plist version="1.0">
<dict>
    <key>streams</key>
    <array>
        <dict>
            <key>type</key><integer>110</integer>
            <key>port</key><integer>{screen_port}</integer>
            <key>width</key><integer>1280</integer>
            <key>height</key><integer>768</integer>
            <key>fps</key><integer>30</integer>
            <key>codec</key><string>h264</string>
        </dict>
        <dict>
            <key>type</key><integer>96</integer>
            <key>port</key><integer>{event_port}</integer>
        </dict>
    </array>
</dict>
</plist>
"""
    return body.encode("utf-8")


# ---------------------------------------------------------------- RTSP 服务

class RtspServer:
    def __init__(self, args):
        self.args = args
        self.screen_bytes = 0
        self.screen_frames = 0
        self.recording = open(args.save, "wb") if args.save else None
        self.last_seq = None

    # ---------------- 连接处理

    def serve_forever(self):
        srv = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
        srv.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
        srv.bind(("0.0.0.0", self.args.port))
        srv.listen(8)
        log(f"RTSP 服务端就绪 0.0.0.0:{self.args.port}")
        while True:
            conn, addr = srv.accept()
            conn.setsockopt(socket.IPPROTO_TCP, socket.TCP_NODELAY, 1)
            log(f"★ RTSP 连接建立 ← {addr[0]}:{addr[1]}")
            threading.Thread(target=self.handle, args=(conn, addr), daemon=True).start()

    def handle(self, conn: socket.socket, addr):
        buf = b""
        try:
            while True:
                data = conn.recv(65536)
                if not data:
                    log(f"RTSP 连接关闭 ← {addr[0]}:{addr[1]}")
                    return
                buf += data

                # ---- RTSP 交织帧（'$' 开头，屏幕流走这条路时）----
                while buf[:1] == b"$" and len(buf) >= 4:
                    ch, ln = buf[1], struct.unpack(">H", buf[2:4])[0]
                    if len(buf) < 4 + ln:
                        break
                    payload = buf[4:4 + ln]
                    self.on_interleaved(ch, payload)
                    buf = buf[4 + ln:]

                # ---- RTSP 文本请求 ----
                if b"\r\n\r\n" in buf:
                    head, rest = buf.split(b"\r\n\r\n", 1)
                    text = head.decode("utf-8", "replace")
                    ctype = ""
                    clen = 0
                    for line in text.split("\r\n"):
                        low = line.lower()
                        if low.startswith("content-type:"):
                            ctype = line.split(":", 1)[1].strip()
                        elif low.startswith("content-length:"):
                            try:
                                clen = int(line.split(":", 1)[1].strip())
                            except ValueError:
                                clen = 0
                    if len(rest) < clen:
                        buf = head + b"\r\n\r\n" + rest
                        continue
                    body = rest[:clen]
                    buf = rest[clen:]
                    resp = self.on_request(text, body, ctype)
                    if resp:
                        conn.sendall(resp)
                    continue
        except Exception as e:
            log(f"RTSP 连接异常: {e!r}")
        finally:
            try:
                conn.close()
            except Exception:
                pass

    # ---------------- 交织流

    def on_interleaved(self, channel: int, payload: bytes):
        self.screen_bytes += len(payload)
        self.screen_frames += 1
        if self.recording:
            self.recording.write(payload)
            self.recording.flush()
        if self.screen_frames <= 5 or self.screen_frames % 50 == 0:
            log(
                f"← 交织通道 {channel} 收到 {len(payload)}B "
                f"（累计 {self.screen_frames} 帧 / {self.screen_bytes} 字节）"
            )
            log(hexdump(payload[:64]), echo=False)

    # ---------------- 请求分发

    def on_request(self, head: str, body: bytes, ctype: str) -> bytes:
        lines = head.split("\r\n")
        first = lines[0]
        parts = first.split()
        method = parts[0] if parts else "?"
        url = parts[1] if len(parts) > 1 else "-"
        cseq = "0"
        for line in lines[1:]:
            if line.lower().startswith("cseq:"):
                cseq = line.split(":", 1)[1].strip()

        log(f"→ {method} {url}  ({len(body)}B, {ctype or '-'})  CSeq={cseq}")
        if body:
            log(hexdump(body[:160]), echo=False)

        # ---- AirPlay 私有方法：方法名与路径写在一起（"POST /pair-verify"）----
        if method in ("OPTIONS", "ANNOUNCE", "SETUP", "PLAY", "RECORD",
                      "TEARDOWN", "GET_PARAMETER", "SET_PARAMETER", "FLUSH", "PAUSE"):
            return self.rtsp_ok(cseq, extra_public=True)

        if method == "GET":
            if "/info" in url or "/stream.xml" in url:
                payload = plist_xml(self.args.width, self.args.height)
                log(f"← 200 /info 能力表 {len(payload)}B（{self.args.width}x{self.args.height}）")
                return self.reply(cseq, 200, "OK", payload, "text/x-apple-plist+xml")
            if "/event" in url:
                return self.reply(cseq, 200, "OK", b"<?xml version='1.0'?><plist/>",
                                  "text/x-apple-plist+xml")
            return self.reply(cseq, 200, "OK", b"<?xml version='1.0'?><plist/>",
                              "text/x-apple-plist+xml")

        if method == "POST":
            return self.post(url, cseq, body)

        log(f"← 200（未特别处理的方法 {method}）")
        return self.reply(cseq, 200, "OK", b"")

    def post(self, url: str, cseq: str, body: bytes) -> bytes:
        if "pair-setup" in url:
            # 真实车机在这里返回 SRP 的 M2；我们不需要 SRP，直接明确拒绝，
            # 方便和「pair-verify 通」形成对照。
            log("← 470 Connection Authorization Required（/pair-setup 本模拟端不实现，用 /pair-verify）")
            return self.reply(cseq, 470, "Connection Authorization Required", b"")

        if "pair-verify" in url:
            return self.pair_verify(cseq, body)

        if "stream-setup" in url:
            payload = plist_stream_setup(self.args.screen_port, EVENT_PORT)
            log(f"← 200 /stream-setup：屏幕流 {self.args.screen_port}，事件 {EVENT_PORT}")
            return self.reply(cseq, 200, "OK", payload, "text/x-apple-plist+xml")

        if "feedback" in url:
            return self.reply(cseq, 200, "OK", b"")

        if "command" in url:
            log(f"← 200 /command（收到控制命令 {len(body)}B）")
            return self.reply(cseq, 200, "OK", b"")

        log(f"← 200 POST {url}（未识别路径，按成功处理）")
        return self.reply(cseq, 200, "OK", b"")

    # ---------------- pair-verify

    def pair_verify(self, cseq: str, body: bytes) -> bytes:
        items = tlv_decode(body)
        state = tlv_get(items, TLV_STATE)
        st = state[0] if state else -1

        if st == 1:
            peer = tlv_get(items, TLV_PUBLIC_KEY)
            if not peer or len(peer) != 32:
                log("← 470（pair-verify M1 缺少/非法 PublicKey）")
                return self.reply(cseq, 470, "Connection Authorization Required", b"")
            keys = AccessoryKeys()
            keys.agree(peer)
            self._keys = keys

            # 明文长度对齐原生：EncryptedData 共 120B ⇒ 明文 104B（含 16B tag）
            plain = os.urandom(32) + bytes(72)
            nonce = b"\x00\x00\x00\x00" + b"PV-Msg02"
            aad = keys.pub + peer
            enc = ChaCha20Poly1305(keys.session_key).encrypt(nonce, plain, aad)
            log(
                f"pair-verify M1: 对端公钥 {peer.hex()[:16]}… → 本端公钥 "
                f"{keys.pub.hex()[:16]}…，EncryptedData={len(enc)}B（原生同为 120B）"
            )
            payload = tlv_encode([
                (TLV_ENCRYPTED_DATA, enc),
                (TLV_STATE, b"\x02"),
                (TLV_PUBLIC_KEY, keys.pub),
            ])
            log(f"← 200 pair-verify M2 {len(payload)}B")
            return self.reply(cseq, 200, "OK", payload)

        if st == 3:
            keys = getattr(self, "_keys", None)
            if not keys or not keys.session_key:
                log("← 470（未见过 M1，无法校验 M3）")
                return self.reply(cseq, 470, "Connection Authorization Required", b"")
            enc = tlv_get(items, TLV_ENCRYPTED_DATA)
            if not enc:
                log("← 470（M3 缺少 EncryptedData）")
                return self.reply(cseq, 470, "Connection Authorization Required", b"")
            nonce = b"\x00\x00\x00\x00" + b"PV-Msg03"
            aad = keys.pub + keys.peer_pub
            try:
                plain = ChaCha20Poly1305(keys.session_key).decrypt(nonce, enc, aad)
                log(f"✓ pair-verify M3 校验通过，明文 {len(plain)}B")
                log(hexdump(plain), echo=False)
            except Exception as e:
                log(f"✗ pair-verify M3 解密失败（tag 不匹配）：{e!r}")
                log("  说明两端的 KDF/nonce/AAD 有一处不一致——这条日志就是定位点")
                return self.reply(cseq, 470, "Connection Authorization Required", b"")
            payload = tlv_encode([(TLV_STATE, b"\x04")])
            log("← 200 pair-verify M4 State=4（配对成立）")
            return self.reply(cseq, 200, "OK", payload)

        log(f"← 200 pair-verify 未知 state={st}，原样回 State=4")
        return self.reply(cseq, 200, "OK", tlv_encode([(TLV_STATE, b"\x04")]))

    # ---------------- 响应拼装

    def reply(self, cseq: str, code: int, reason: str, body: bytes,
              ctype: str = "application/octet-stream") -> bytes:
        head = f"RTSP/1.0 {code} {reason}\r\nCSeq: {cseq}\r\n"
        head += "Server: AirTunes/950.7.1\r\n"
        if body:
            head += f"Content-Type: {ctype}\r\nContent-Length: {len(body)}\r\n"
        head += "\r\n"
        return head.encode("ascii") + body

    def rtsp_ok(self, cseq: str, extra_public: bool = False) -> bytes:
        head = (
            f"RTSP/1.0 200 OK\r\nCSeq: {cseq}\r\n"
            "Server: AirTunes/950.7.1\r\n"
            "Public: ANNOUNCE, SETUP, RECORD, PAUSE, FLUSH, TEARDOWN, "
            "OPTIONS, POST, GET, PUT\r\n"
            "Audio-Jack-Status: connected; type=analog\r\n"
            "\r\n"
        )
        return head.encode("ascii")


# ---------------------------------------------------------------- 屏幕流 / 事件 / 保活

def start_screen_listener(port: int, state: RtspServer):
    srv = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    srv.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    srv.bind(("0.0.0.0", port))
    srv.listen(4)
    log(f"屏幕流监听 0.0.0.0:{port}（原生车机固定端口）")

    def loop():
        while True:
            conn, addr = srv.accept()
            log(f"★ 屏幕流连接建立 ← {addr[0]}:{addr[1]}（6030 建立 = 真正「连上」）")
            threading.Thread(target=drain, args=(conn, addr), daemon=True).start()

    def drain(conn, addr):
        total = 0
        t0 = time.time()
        try:
            while True:
                data = conn.recv(65536)
                if not data:
                    log(f"屏幕流关闭 ← {addr[0]}:{addr[1]}，共 {total}B / {time.time()-t0:.1f}s")
                    return
                total += len(data)
                state.screen_bytes += len(data)
                state.screen_frames += 1
                if state.recording:
                    state.recording.write(data)
                    state.recording.flush()
                if state.screen_frames <= 3 or state.screen_frames % 50 == 0:
                    log(f"← 屏幕流 {len(data)}B（累计 {total}B）")
                    log(hexdump(data[:64]), echo=False)
        except Exception as e:
            log(f"屏幕流异常: {e!r}")
        finally:
            try:
                conn.close()
            except Exception:
                pass

    threading.Thread(target=loop, daemon=True).start()


def start_event_listener(port: int):
    srv = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    srv.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    srv.bind(("0.0.0.0", port))
    srv.listen(4)
    log(f"事件通道监听 0.0.0.0:{port}")

    def loop():
        while True:
            conn, addr = srv.accept()
            log(f"事件通道连接 ← {addr[0]}:{addr[1]}")
            threading.Thread(target=handle, args=(conn,), daemon=True).start()

    def handle(conn):
        try:
            while True:
                data = conn.recv(65536)
                if not data:
                    return
                log(f"← 事件通道 {len(data)}B: {data[:80]!r}")
                if data.startswith(b"POST") or data.startswith(b"GET"):
                    conn.sendall(b"RTSP/1.0 200 OK\r\nCSeq: 1\r\nContent-Length: 0\r\n\r\n")
        except Exception:
            pass
        finally:
            try:
                conn.close()
            except Exception:
                pass

    threading.Thread(target=loop, daemon=True).start()


def start_keepalive_udp(port: int):
    s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    s.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    s.bind(("0.0.0.0", port))
    log(f"UDP 保活监听 0.0.0.0:{port}")

    def loop():
        while True:
            data, addr = s.recvfrom(2048)
            log(f"← 保活 {addr[0]}:{addr[1]} {len(data)}B {data[:32].hex()}")

    threading.Thread(target=loop, daemon=True).start()


# ---------------------------------------------------------------- mDNS

def start_mdns(port: int, name: str):
    from zeroconf import ServiceInfo, Zeroconf

    ip = local_ip()
    info = ServiceInfo(
        "_airplay._tcp.local.",
        f"{name}._airplay._tcp.local.",
        addresses=[socket.inet_aton(ip)],
        port=port,
        properties={
            "deviceid": "AA:BB:CC:DD:EE:FF",
            "features": "0x5A7FFFF7,0x1E",
            "srcvers": "950.7.1",
            "model": "MockCarPlaySink1,1",
            "flags": "0x4",
            "pk": "0" * 64,
            "pi": "0" * 36,
            "vv": "2",
        },
        server=f"{name}.local.",
    )
    zc = Zeroconf()
    zc.register_service(info)
    log(f"mDNS 已广播 {name}._airplay._tcp.local. @ {ip}:{port}")
    return zc


def local_ip() -> str:
    s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    try:
        s.connect(("8.8.8.8", 80))
        return s.getsockname()[0]
    except Exception:
        return "127.0.0.1"
    finally:
        s.close()


# ---------------------------------------------------------------- main

def main():
    global _logfile

    ap = argparse.ArgumentParser(description="模拟无线 CarPlay 接收端（假车机）")
    ap.add_argument("--port", type=int, default=RTSP_PORT, help="RTSP 端口，默认 7000")
    ap.add_argument("--screen-port", type=int, default=SCREEN_PORT, help="屏幕流端口，默认 6030")
    ap.add_argument("--width", type=int, default=1280)
    ap.add_argument("--height", type=int, default=768)
    ap.add_argument("--name", default="CarPlay", help="mDNS 服务实例名（原生为 CarPlay）")
    ap.add_argument("--no-mdns", action="store_true", help="不广播 mDNS")
    ap.add_argument("--save", default="", help="把收到的码流写入该文件")
    ap.add_argument("--log", default="mock_sink.log")
    args = ap.parse_args()

    _logfile = open(args.log, "w", encoding="utf-8")

    ip = local_ip()
    log("=" * 62)
    log("模拟 CarPlay 接收端（假车机）")
    log(f"本机 IP        : {ip}")
    log(f"RTSP           : {ip}:{args.port}")
    log(f"屏幕流         : {ip}:{args.screen_port}")
    log(f"事件 / 保活    : {ip}:{EVENT_PORT} / udp {KEEPALIVE_PORT}")
    log(f"能力表         : {args.width}x{args.height}")
    log(f"日志           : {os.path.abspath(args.log)}")
    log("=" * 62)
    log("让 CarPlayAPK 用「WLAN 连接」手动填当前 SSID，即可跳过车机网段校验")

    state = RtspServer(args)

    threading.Thread(target=state.serve_forever, daemon=True).start()
    start_screen_listener(args.screen_port, state)
    start_event_listener(EVENT_PORT)
    start_keepalive_udp(KEEPALIVE_PORT)

    zc = None
    if not args.no_mdns:
        try:
            zc = start_mdns(args.port, args.name)
        except Exception as e:
            log(f"mDNS 广播失败: {e!r}（可用 --no-mdns 并手填 IP）")

    try:
        while True:
            time.sleep(1)
    except KeyboardInterrupt:
        log("退出")
        if zc:
            zc.close()
        if state.recording:
            state.recording.close()


if __name__ == "__main__":
    main()
