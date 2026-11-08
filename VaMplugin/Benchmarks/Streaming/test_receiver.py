# /// script
# requires-python = ">=3.10"
# dependencies = ["pillow>=10,<13"]
# ///
"""Run: uv run test_receiver.py. Uses real UDP loopback plus malformed/lost packet cases."""
import json
from io import BytesIO
from pathlib import Path
import socket
import subprocess
import sys
import tempfile
import time
import unittest

from PIL import Image
from receiver import CHUNK, CONTROL, HEADER, MAX_PENDING, Reassembler


def packets(data, seq=0, run=123):
    count = (len(data) + CHUNK - 1) // CHUNK
    return [HEADER.pack(b"PBF1", run, seq, n, count, len(data), 12.5)
            + data[n*CHUNK:(n+1)*CHUNK] for n in range(count)]


class ReassemblyTests(unittest.TestCase):
    def test_reordered_duplicate_and_exact_payload(self):
        original = bytes(range(256)) * 20
        parts = packets(original)
        asm = Reassembler()
        self.assertIsNone(asm.feed(parts[-1], 0))
        self.assertIsNone(asm.feed(parts[-1], .01))
        result = None
        for part in reversed(parts[:-1]):
            result = asm.feed(part, .02)
        self.assertEqual(result, (0, original))
        self.assertEqual(asm.counts['duplicate_fragments'], 1)
        self.assertIsNone(asm.feed(parts[0], .03))
        self.assertEqual(asm.counts['complete'], 1)

    def test_missing_fragment_expires_without_resurrection(self):
        parts = packets(b'a'*3000)
        asm = Reassembler()
        asm.feed(parts[0], 0)
        asm.expire(.6)
        self.assertFalse(asm.pending)
        self.assertIsNone(asm.feed(parts[1], .7))
        self.assertEqual(asm.counts['expired_incomplete'], 1)

    def test_invalid_metadata_and_truncated_payload(self):
        asm = Reassembler()
        for packet in [b'x', HEADER.pack(b'PBF1', 123, 0, 0, 1, 2**31-1, 1),
                       packets(b'a'*2000)[0][:-1],
                       HEADER.pack(b'PBF1', 123, 0, 3, 2, 2000, 1) + b'a'*828]:
            self.assertIsNone(asm.feed(packet, 0))
        self.assertEqual(asm.counts['invalid'], 4)
        self.assertFalse(asm.pending)

    def test_pending_memory_bounded(self):
        asm = Reassembler()
        for seq in range(100):
            asm.feed(packets(b'a'*3000, seq)[0], 0)
        self.assertEqual(len(asm.pending), MAX_PENDING)
        self.assertEqual(asm.counts['evicted_incomplete'], 100-MAX_PENDING)

    def test_conflicting_fragment_metadata(self):
        asm = Reassembler()
        asm.feed(packets(b'a'*3000)[0], 0)
        self.assertIsNone(asm.feed(packets(b'a'*3100)[1], .01))
        self.assertEqual(asm.counts['inconsistent'], 1)

    def test_real_udp_receiver_decode_ack_and_report(self):
        with tempfile.TemporaryDirectory() as temp, socket.socket(socket.AF_INET, socket.SOCK_DGRAM) as sock:
            sock.bind(('127.0.0.1', 0)); sock.settimeout(5)
            process = subprocess.Popen([sys.executable, str(Path(__file__).with_name('receiver.py')),
                '--host', '127.0.0.1', '--port', str(sock.getsockname()[1]),
                '--idle-exit', '1', '--output', temp], stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True)
            try:
                hello, receiver = sock.recvfrom(65535)
                self.assertEqual(hello, b'PCBHELLO1')
                buffer = BytesIO()
                Image.new('RGB', (96, 64), (220, 30, 10)).save(buffer, format='JPEG')
                image = buffer.getvalue()
                # Complete JPEG, deliberately incomplete JPEG, then invalid JPEG.
                for packet in reversed(packets(image)):
                    sock.sendto(packet, receiver)
                sock.sendto(packets(image*5, 1)[0], receiver)
                for packet in packets(b'not a jpeg', 2):
                    sock.sendto(packet, receiver)
                # Sequence 3 entirely lost. END permits counting it.
                sock.sendto(CONTROL.pack(b'PBE1', 123, 4), receiver)
                ack = None
                deadline = time.monotonic()+5
                while time.monotonic()<deadline:
                    packet, _ = sock.recvfrom(65535)
                    if packet[:4] == b'PBA1':
                        ack = CONTROL.unpack(packet); break
                self.assertEqual(ack, (b'PBA1', 123, 0))
                stdout, stderr = process.communicate(timeout=8)
                self.assertEqual(process.returncode, 0, stderr)
                report = json.loads((Path(temp)/'run-123.json').read_text())
                self.assertEqual(report['counts']['complete'], 2)
                self.assertEqual(report['frames_not_completed'], 2)
                self.assertEqual(report['frames_never_seen'], 1)
                self.assertEqual(report['decoder']['decoded'], 1)
                self.assertEqual(report['decoder']['decode_errors'], 1)
                self.assertEqual((Path(temp)/'run-123.jpg').read_bytes(), image)
            finally:
                if process.poll() is None:
                    process.kill(); process.communicate()


if __name__ == '__main__':
    unittest.main(verbosity=2)
