#!/usr/bin/env python3
"""Minimal Minecraft RCON client (Python stdlib only).

Sends a single console command to the local Paper server and prints the reply.
Used by Claude (via the Bash tool) to operate the server, because the server
runs inside a Pelican/Wings Docker container with no tmux/screen console.

Usage:
    python3 rcon.py "list"
    python3 rcon.py "say Hello from Claude"

Connection is configured via environment variables (no secrets in this file):
    RCON_HOST       default 127.0.0.1
    RCON_PORT       default 25575   (matches rcon.port in server.properties)
    RCON_PASSWORD   required        (matches rcon.password in server.properties)

Set RCON_PASSWORD in the environment the server/plugin runs under, e.g. via the
Pelican startup/egg variables or a sourced ~/.rcon.env, NOT in version control.
"""
import os
import select
import socket
import struct
import sys

HOST = os.environ.get("RCON_HOST", "127.0.0.1")
PORT = int(os.environ.get("RCON_PORT", "25575"))
PASSWORD = os.environ.get("RCON_PASSWORD")

TYPE_AUTH = 3
TYPE_COMMAND = 2
TYPE_RESPONSE = 0


def _send(sock, req_id, req_type, payload):
    body = struct.pack("<ii", req_id, req_type) + payload.encode("utf-8") + b"\x00\x00"
    sock.sendall(struct.pack("<i", len(body)) + body)


def _recv(sock):
    raw_len = _read_exact(sock, 4)
    (length,) = struct.unpack("<i", raw_len)
    data = _read_exact(sock, length)
    resp_id, resp_type = struct.unpack("<ii", data[:8])
    payload = data[8:-2].decode("utf-8", errors="replace")
    return resp_id, resp_type, payload


def _read_exact(sock, n):
    buf = b""
    while len(buf) < n:
        chunk = sock.recv(n - len(buf))
        if not chunk:
            raise ConnectionError("RCON connection closed unexpectedly")
        buf += chunk
    return buf


def main():
    if len(sys.argv) < 2:
        print("usage: python3 rcon.py \"<minecraft console command>\"", file=sys.stderr)
        return 2
    if not PASSWORD:
        print("RCON_PASSWORD environment variable is not set", file=sys.stderr)
        return 2

    command = " ".join(sys.argv[1:])
    with socket.create_connection((HOST, PORT), timeout=10) as sock:
        _send(sock, 1, TYPE_AUTH, PASSWORD)
        resp_id, _, _ = _recv(sock)
        if resp_id == -1:
            print("RCON authentication failed (bad password?)", file=sys.stderr)
            return 1

        _send(sock, 2, TYPE_COMMAND, command)
        # Drain any reply fragments (large outputs can span packets).
        out = []
        while True:
            _, resp_type, payload = _recv(sock)
            if resp_type == TYPE_RESPONSE:
                out.append(payload)
            if not select.select([sock], [], [], 0.3)[0]:
                break
        print("".join(out).strip())
    return 0


if __name__ == "__main__":
    sys.exit(main())
