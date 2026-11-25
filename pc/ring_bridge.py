#!/usr/bin/env python3
"""Smart Ring Repacker PC Bridge (Linux; developed on KDE Plasma Wayland).

Connects to the Smart Ring Repacker phone API WebSocket (/v1/events) and synthesizes
keyboard shortcuts / inputs triggered by ring gestures (the PcKeys action).

Supports python-evdev UInput (primary) with transparent fallback to ydotool.
Automatically reconnects on connection loss.
"""

import argparse
import base64
import json
import os
import re
import socket
import ssl
import sys
import time
import urllib.parse

# Optional python-evdev support
try:
    import evdev
    from evdev import UInput, ecodes as e
    HAVE_EVDEV = True
except ImportError:
    HAVE_EVDEV = False

# Fallback: check if ydotool is present in PATH
import shutil
HAVE_YDOTOOL = shutil.which("ydotool") is not None


class InputSynthesizer:
    """Dispatches key combos using evdev UInput or ydotool."""

    def __init__(self, verbose=False):
        self.verbose = verbose
        self.ui = None
        self.use_evdev = False

        if HAVE_EVDEV:
            try:
                # Open UInput device
                self.ui = UInput(name="SmartRingRepacker-Virtual-Keyboard")
                self.use_evdev = True
                if self.verbose:
                    print("[bridge] initialized evdev UInput device")
            except Exception as err:
                if self.verbose:
                    print(f"[bridge] failed to initialize evdev UInput ({err}), falling back to ydotool")

        if not self.use_evdev:
            if HAVE_YDOTOOL:
                if self.verbose:
                    print("[bridge] using ydotool for input synthesis")
            else:
                print("[bridge] WARNING: neither evdev nor ydotool is functional. Key combos will only be logged.")

    def _resolve_evdev_key(self, name: str):
        k = name.strip().lower()
        alias = {
            "ctrl": "KEY_LEFTCTRL",
            "control": "KEY_LEFTCTRL",
            "lctrl": "KEY_LEFTCTRL",
            "rctrl": "KEY_RIGHTCTRL",
            "alt": "KEY_LEFTALT",
            "lalt": "KEY_LEFTALT",
            "ralt": "KEY_RIGHTALT",
            "shift": "KEY_LEFTSHIFT",
            "lshift": "KEY_LEFTSHIFT",
            "rshift": "KEY_RIGHTSHIFT",
            "meta": "KEY_LEFTMETA",
            "super": "KEY_LEFTMETA",
            "win": "KEY_LEFTMETA",
            "cmd": "KEY_LEFTMETA",
            "enter": "KEY_ENTER",
            "return": "KEY_ENTER",
            "esc": "KEY_ESC",
            "escape": "KEY_ESC",
            "tab": "KEY_TAB",
            "space": "KEY_SPACE",
            "backspace": "KEY_BACKSPACE",
            "delete": "KEY_DELETE",
            "up": "KEY_UP",
            "down": "KEY_DOWN",
            "left": "KEY_LEFT",
            "right": "KEY_RIGHT",
            "home": "KEY_HOME",
            "end": "KEY_END",
            "pageup": "KEY_PAGEUP",
            "pagedown": "KEY_PAGEDOWN",
            "volumeup": "KEY_VOLUMEUP",
            "volumedown": "KEY_VOLUMEDOWN",
            "mute": "KEY_MUTE",
            "playpause": "KEY_PLAYPAUSE",
            "next": "KEY_NEXTSONG",
            "prev": "KEY_PREVIOUSSONG",
        }
        if k in alias:
            return getattr(e, alias[k], None)
        # Check standard key e.g. 'c' -> KEY_C
        if len(k) == 1 and k.isalnum():
            return getattr(e, f"KEY_{k.upper()}", None)
        # Check direct name e.g. F1..F12
        return getattr(e, f"KEY_{k.upper()}", None)

    def trigger_combo(self, combo: str):
        combo = combo.strip()
        if not combo:
            return

        if self.verbose:
            print(f"[bridge] executing key combo: {combo}")

        # Split combo like "ctrl+c" or "ctrl+alt+t"
        parts = [p.strip() for p in combo.split("+") if p.strip()]

        if self.use_evdev and self.ui:
            key_codes = [self._resolve_evdev_key(p) for p in parts]
            if any(code is None for code in key_codes):
                print(f"[bridge] error: could not map one or more keys in combo '{combo}'")
                return

            try:
                # Press all keys in order
                for code in key_codes:
                    self.ui.write(e.EV_KEY, code, 1)
                self.ui.syn()
                time.sleep(0.04)
                # Release in reverse order
                for code in reversed(key_codes):
                    self.ui.write(e.EV_KEY, code, 0)
                self.ui.syn()
            except Exception as err:
                print(f"[bridge] evdev write error: {err}")
            return

        if HAVE_YDOTOOL:
            # ydotool key format e.g. 29:1 46:1 46:0 29:0
            import subprocess
            try:
                subprocess.run(["ydotool", "type", combo], check=False)
            except Exception as err:
                print(f"[bridge] ydotool execution error: {err}")
            return

        print(f"[bridge] (simulation) combo pressed: {combo}")

    def close(self):
        if self.ui:
            try:
                self.ui.close()
            except Exception:
                pass


class RawWebSocketClient:
    """Zero-dependency RFC 6455 WebSocket client using Python standard library sockets."""

    def __init__(self, url: str, token: str, verbose: bool = False):
        self.url = url
        self.token = token
        self.verbose = verbose
        self.sock = None

    def connect(self):
        parsed = urllib.parse.urlparse(self.url)
        scheme = parsed.scheme.lower()
        if scheme not in ("ws", "wss", "http", "https"):
            raise ValueError(f"Unsupported scheme: {scheme}")

        is_ssl = scheme in ("wss", "https")
        host = parsed.hostname or "127.0.0.1"
        port = parsed.port or (443 if is_ssl else 80)
        path = parsed.path or "/v1/events"
        if parsed.query:
            path += f"?{parsed.query}"

        raw_sock = socket.create_connection((host, port), timeout=10)
        if is_ssl:
            ctx = ssl.create_default_context()
            self.sock = ctx.wrap_socket(raw_sock, server_hostname=host)
        else:
            self.sock = raw_sock

        # WebSocket handshake
        sec_key = base64.b64encode(os.urandom(16)).decode("ascii")
        headers = [
            f"GET {path} HTTP/1.1",
            f"Host: {host}:{port}",
            "Upgrade: websocket",
            "Connection: Upgrade",
            f"Sec-WebSocket-Key: {sec_key}",
            "Sec-WebSocket-Version: 13",
        ]
        if self.token:
            headers.append(f"Authorization: Bearer {self.token}")
        headers.append("\r\n")

        req = "\r\n".join(headers).encode("utf-8")
        self.sock.sendall(req)

        # Read HTTP response headers
        resp_data = b""
        while b"\r\n\r\n" not in resp_data:
            chunk = self.sock.recv(4096)
            if not chunk:
                raise ConnectionError("Server closed connection during handshake")
            resp_data += chunk

        header_part, rest = resp_data.split(b"\r\n\r\n", 1)
        status_line = header_part.split(b"\r\n")[0].decode("utf-8", "ignore")
        if "101" not in status_line:
            raise ConnectionError(f"Handshake failed: {status_line}")

        if self.verbose:
            print(f"[bridge] connected to {self.url}")

    def _recv_exact(self, n: int) -> bytes:
        buf = bytearray()
        while len(buf) < n:
            chunk = self.sock.recv(n - len(buf))
            if not chunk:
                raise ConnectionResetError("Connection closed by remote host")
            buf.extend(chunk)
        return bytes(buf)

    def read_message(self) -> str:
        """Read a single text frame from the WebSocket."""
        while True:
            head = self._recv_exact(2)
            b1, b2 = head[0], head[1]
            opcode = b1 & 0x0F
            masked = (b2 & 0x80) != 0
            payload_len = b2 & 0x7F

            if payload_len == 126:
                payload_len = int.from_bytes(self._recv_exact(2), "big")
            elif payload_len == 127:
                payload_len = int.from_bytes(self._recv_exact(8), "big")

            mask_key = self._recv_exact(4) if masked else b""
            data = self._recv_exact(payload_len)

            if masked:
                data = bytes(b ^ mask_key[i % 4] for i, b in enumerate(data))

            if opcode == 0x01:  # Text frame
                return data.decode("utf-8")
            elif opcode == 0x08:  # Close frame
                raise ConnectionResetError("Server sent close frame")
            elif opcode == 0x09:  # Ping frame -> send pong
                pong = bytearray([0x8A, len(data)]) + data
                self.sock.sendall(pong)
            # Other opcodes (binary, pong, etc.) continue to next frame

    def close(self):
        if self.sock:
            try:
                self.sock.close()
            except Exception:
                pass
            self.sock = None


def run_bridge(url: str, token: str, verbose: bool = False):
    synth = InputSynthesizer(verbose=verbose)
    backoff = 1.0

    print(f"[bridge] starting Smart Ring Repacker bridge -> {url}")
    try:
        while True:
            client = RawWebSocketClient(url, token, verbose=verbose)
            try:
                client.connect()
                backoff = 1.0  # Reset backoff on successful connect
                while True:
                    msg_text = client.read_message()
                    if verbose:
                        print(f"[bridge] recv: {msg_text}")
                    try:
                        event = json.loads(msg_text)
                    except Exception:
                        continue

                    evt_type = event.get("event")
                    if evt_type == "pc_keys":
                        keys = event.get("keys", "")
                        synth.trigger_combo(keys)
                    elif evt_type == "gesture_hit":
                        # If gesture hit action was PcKeys or configured for keys
                        pass
            except (ConnectionError, ConnectionResetError, socket.error) as err:
                print(f"[bridge] connection error ({err}), reconnecting in {backoff:.1f}s...")
                time.sleep(backoff)
                backoff = min(backoff * 1.5, 15.0)
            finally:
                client.close()
    except KeyboardInterrupt:
        print("\n[bridge] exiting on user interrupt")
    finally:
        synth.close()


def main():
    parser = argparse.ArgumentParser(description="Smart Ring Repacker PC Input Bridge")
    parser.add_argument(
        "--url",
        default=os.environ.get("REPACKER_URL", "ws://127.0.0.1:8765/v1/events"),
        help="WebSocket URL (default: ws://127.0.0.1:8765/v1/events or REPACKER_URL)",
    )
    parser.add_argument(
        "--token",
        default=os.environ.get("REPACKER_TOKEN", ""),
        help="Bearer token (or REPACKER_TOKEN env var)",
    )
    parser.add_argument(
        "-v", "--verbose",
        action="store_true",
        help="Enable verbose event logging",
    )
    args = parser.parse_args()

    # Ensure URL is WebSocket format
    url = args.url
    if url.startswith("http://"):
        url = "ws://" + url[7:]
    elif url.startswith("https://"):
        url = "wss://" + url[8:]
    if not url.endswith("/v1/events"):
        url = url.rstrip("/") + "/v1/events"

    run_bridge(url, args.token, verbose=args.verbose)


if __name__ == "__main__":
    main()
