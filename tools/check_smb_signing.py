#!/usr/bin/env python3
"""Report whether an SMB server requires message signing.

Sends one unauthenticated SMB2 NEGOTIATE request. No session setup, tree
connect, or file access. Never prints the host address.

SMBJ rejects Guest sessions with SMB2GuestSigningRequiredException when the
server reports signing as required, so the TV app needs SIGNING_REQUIRED=False.

Exit codes: 0 signing not required, 1 signing required, 2 probe failed.
"""

import argparse
import os
import socket
import struct
import sys

DIALECTS = (0x0202, 0x0210, 0x0300, 0x0302)
SIGNING_ENABLED = 0x01
SIGNING_REQUIRED = 0x02


def negotiate_request() -> bytes:
    header = (
        b"\xfeSMB"
        + struct.pack("<HHIHHIIQIIQ", 64, 0, 0, 0, 1, 0, 0, 0, 0xFEFF, 0, 0)
        + b"\x00" * 16
    )
    body = struct.pack("<HHHHI", 36, len(DIALECTS), SIGNING_ENABLED, 0, 0)
    body += os.urandom(16) + b"\x00" * 8
    body += b"".join(struct.pack("<H", dialect) for dialect in DIALECTS)
    packet = header + body
    return b"\x00" + len(packet).to_bytes(3, "big") + packet


def read_frame(sock: socket.socket) -> bytes:
    header = read_exact(sock, 4)
    if header[0] != 0:
        raise ValueError("unexpected SMB transport frame")
    length = int.from_bytes(header[1:], "big")
    if length < 64 or length > 1024 * 1024:
        raise ValueError("invalid SMB frame length")
    return read_exact(sock, length)


def read_exact(sock: socket.socket, length: int) -> bytes:
    data = bytearray()
    while len(data) < length:
        chunk = sock.recv(length - len(data))
        if not chunk:
            raise ConnectionError("incomplete SMB response")
        data.extend(chunk)
    return bytes(data)


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("host", help="SMB server address (not printed)")
    args = parser.parse_args()

    try:
        with socket.create_connection((args.host, 445), timeout=5) as sock:
            sock.sendall(negotiate_request())
            response = read_frame(sock)
    except (OSError, ValueError) as error:
        print(f"SMB negotiate failed ({type(error).__name__})")
        return 2

    if len(response) < 70 or response[:4] != b"\xfeSMB":
        print("SMB negotiate failed (unexpected response)")
        return 2
    status = struct.unpack_from("<I", response, 8)[0]
    if status != 0:
        print(f"SMB negotiate failed (status=0x{status:08x})")
        return 2
    security_mode, dialect = struct.unpack_from("<HH", response, 66)
    required = bool(security_mode & SIGNING_REQUIRED)
    print(f"status=0x{status:08x} dialect=0x{dialect:04x}")
    print(f"SIGNING_ENABLED={bool(security_mode & SIGNING_ENABLED)} SIGNING_REQUIRED={required}")
    return 1 if required else 0


if __name__ == "__main__":
    sys.exit(main())
