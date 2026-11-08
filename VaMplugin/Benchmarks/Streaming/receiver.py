# /// script
# requires-python = ">=3.10"
# dependencies = ["pillow>=10,<13"]
# ///
"""UDP-only VaM benchmark receiver. Run with uv run receiver.py --host WINDOWS_IP."""
from __future__ import annotations

import argparse
from collections import Counter
from dataclasses import dataclass, field
from io import BytesIO
import json
import math
import platform
from pathlib import Path
import queue
import socket
import struct
import threading
import time

HEADER = struct.Struct("<4sIIHHId")
CONTROL = struct.Struct("<4sII")
CHUNK = 1172
MAX_FRAME = 4 * 1024 * 1024
MAX_PENDING = 8


def stats(values):
    if not values:
        return {"n": 0}
    values = sorted(values)
    return {"n": len(values), "avg_ms": sum(values) / len(values),
            "p95_ms": values[math.ceil(len(values) * .95) - 1], "max_ms": values[-1]}


@dataclass
class Assembly:
    total: int
    count: int
    stamp: float
    first: float
    parts: dict = field(default_factory=dict)


class Reassembler:
    """Bounded, reorder-tolerant reassembly; malformed packets never allocate total bytes."""
    def __init__(self, timeout=.5):
        self.pending = {}
        self.finished = set()
        self.timeout = timeout
        self.counts = Counter({key: 0 for key in (
            "datagrams", "wire_payload_bytes", "complete", "jpeg_bytes",
            "expired_incomplete", "evicted_incomplete", "late_or_duplicate",
            "duplicate_fragments", "invalid", "inconsistent")})
        self.seen = set()
        self.first = None
        self.last = None
        self.reassembly_ms = []

    def expire(self, now):
        for seq, frame in list(self.pending.items()):
            if now - frame.first >= self.timeout:
                del self.pending[seq]
                self.finished.add(seq)
                self.counts["expired_incomplete"] += 1

    def feed(self, packet, now):
        self.expire(now)
        if len(packet) < HEADER.size:
            self.counts["invalid"] += 1
            return None
        magic, run, seq, part, count, total, stamp = HEADER.unpack_from(packet)
        payload = packet[HEADER.size:]
        if (magic != b"PBF1" or seq >= 10000 or not 0 < total <= MAX_FRAME
                or count != (total + CHUNK - 1) // CHUNK or part >= count
                or not math.isfinite(stamp)
                or len(payload) != min(CHUNK, total - part * CHUNK)):
            self.counts["invalid"] += 1
            return None
        self.counts["datagrams"] += 1
        self.counts["wire_payload_bytes"] += len(packet)
        self.seen.add(seq)
        self.first = now if self.first is None else self.first
        self.last = now
        if seq in self.finished:
            self.counts["late_or_duplicate"] += 1
            return None
        frame = self.pending.get(seq)
        if frame is None:
            if len(self.pending) >= MAX_PENDING:
                oldest = min(self.pending, key=lambda k: self.pending[k].first)
                del self.pending[oldest]
                self.finished.add(oldest)
                self.counts["evicted_incomplete"] += 1
            frame = self.pending[seq] = Assembly(total, count, stamp, now)
        if (total, count, stamp) != (frame.total, frame.count, frame.stamp):
            self.counts["inconsistent"] += 1
            return None
        if part in frame.parts:
            self.counts["duplicate_fragments"] += 1
            return None
        frame.parts[part] = payload
        if len(frame.parts) != count:
            return None
        jpeg = b"".join(frame.parts[n] for n in range(count))
        del self.pending[seq]
        self.finished.add(seq)
        self.counts["complete"] += 1
        self.counts["jpeg_bytes"] += len(jpeg)
        self.reassembly_ms.append((now - frame.first) * 1000)
        return seq, jpeg


class Decoder:
    def __init__(self, sock, peer):
        self.sock, self.peer = sock, peer
        self.jobs = queue.Queue(maxsize=2)
        self.lock = threading.Lock()
        self.runs = {}
        self.thread = threading.Thread(target=self.work, daemon=True)
        self.thread.start()

    def state(self, run):
        return self.runs.setdefault(run, {"decoded": 0, "decode_errors": 0,
            "decode_queue_drops": 0, "ack_errors": 0, "decode_ms": [],
            "queue_ms": [], "last_jpeg": None, "dimensions": None})

    def submit(self, run, seq, jpeg):
        try:
            self.jobs.put_nowait((run, seq, jpeg, time.monotonic()))
        except queue.Full:
            with self.lock:
                self.state(run)["decode_queue_drops"] += 1

    def work(self):
        from PIL import Image
        while True:
            job = self.jobs.get()
            if job is None:
                self.jobs.task_done()
                return
            run, seq, jpeg, queued = job
            began = time.monotonic()
            try:
                with Image.open(BytesIO(jpeg)) as image:
                    if image.format != "JPEG" or image.width > 2048 or image.height > 2048:
                        raise ValueError("Unexpected image type or dimensions")
                    image.load()  # full decode, not just header parsing
                    dimensions = image.size
                elapsed = (time.monotonic() - began) * 1000
                with self.lock:
                    state = self.state(run)
                    state["decoded"] += 1
                    state["decode_ms"].append(elapsed)
                    state["queue_ms"].append((began - queued) * 1000)
                    state["last_jpeg"] = jpeg
                    state["dimensions"] = dimensions
                try:
                    self.sock.sendto(CONTROL.pack(b"PBA1", run, seq), self.peer)
                except OSError:
                    with self.lock:
                        self.state(run)["ack_errors"] += 1
            except Exception:
                with self.lock:
                    self.state(run)["decode_errors"] += 1
            finally:
                self.jobs.task_done()

    def close(self):
        self.jobs.join()
        self.jobs.put(None)
        self.thread.join(timeout=3)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--host", required=True, help="VaM Windows machine IPv4 address")
    parser.add_argument("--port", type=int, default=62710)
    parser.add_argument("--output", type=Path, default=Path("benchmark-results"))
    parser.add_argument("--idle-exit", type=float, default=8,
                        help="Exit this many seconds after last frame; 0 waits for Ctrl+C")
    args = parser.parse_args()
    peer = (socket.gethostbyname(args.host), args.port)
    args.output.mkdir(parents=True, exist_ok=True)
    sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    sock.setsockopt(socket.SOL_SOCKET, socket.SO_RCVBUF, 4 * 1024 * 1024)
    sock.bind(("0.0.0.0", 0))
    sock.settimeout(.1)
    from PIL import __version__ as pillow_version
    environment = {"os": platform.platform(), "python": platform.python_version(),
                   "pillow": pillow_version, "socket_receive_buffer_bytes": sock.getsockopt(socket.SOL_SOCKET, socket.SO_RCVBUF)}
    decoder = Decoder(sock, peer)
    runs, totals = {}, {}
    last_frame = None
    hello_at = 0
    print(f"Connecting via UDP to {peer[0]}:{peer[1]}; receive buffer={sock.getsockopt(socket.SOL_SOCKET, socket.SO_RCVBUF)} bytes", flush=True)
    print("Click Run in VaM. Waiting for frames; Ctrl+C saves reports.", flush=True)
    try:
        while True:
            now = time.monotonic()
            if now >= hello_at:
                sock.sendto(b"PCBHELLO1", peer)
                hello_at = now + 1
            if last_frame is not None and args.idle_exit > 0 and now - last_frame >= args.idle_exit:
                break
            try:
                packet, sender = sock.recvfrom(65535)
            except socket.timeout:
                for assembler in runs.values():
                    assembler.expire(time.monotonic())
                continue
            if sender != peer:
                continue
            now = time.monotonic()
            if len(packet) == CONTROL.size and packet[:4] == b"PBE1":
                _, run, count = CONTROL.unpack(packet)
                if count <= 10000 and (run in runs or len(runs) < 16):
                    totals[run] = count
                    runs.setdefault(run, Reassembler())
                    if last_frame is None:
                        last_frame = now
                continue
            if len(packet) < HEADER.size or packet[:4] != b"PBF1":
                continue
            run = HEADER.unpack_from(packet)[1]
            if run not in runs:
                if len(runs) >= 16:
                    continue
                runs[run] = Reassembler()
                print(f"Receiving run {run}", flush=True)
            assembler = runs[run]
            result = assembler.feed(packet, now)
            last_frame = now
            if result is not None:
                seq, jpeg = result
                decoder.submit(run, seq, jpeg)
                if assembler.counts["complete"] % 100 == 0:
                    print(f"run {run}: {assembler.counts['complete']} complete frames", flush=True)
    except KeyboardInterrupt:
        pass
    finally:
        decoder.close()
        sock.close()
        for run, assembler in runs.items():
            assembler.expire(float("inf"))
            span = (assembler.last - assembler.first) if assembler.first is not None else 0
            with decoder.lock:
                decoded = dict(decoder.state(run))
            preview = decoded.pop("last_jpeg")
            decoded["decode_ms"] = stats(decoded["decode_ms"])
            decoded["queue_ms"] = stats(decoded["queue_ms"])
            total = totals.get(run)
            report = {"version": 1, "run": run, "peer": peer, "environment": environment,
                "counts": dict(assembler.counts), "sender_encoded_frames": total,
                "frames_never_seen": None if total is None else max(0, total - len(assembler.seen)),
                "frames_not_completed": None if total is None else max(0, total - assembler.counts["complete"]),
                "receive_span_seconds": span,
                "completed_fps_over_receive_span": assembler.counts["complete"] / span if span > 0 else None,
                "udp_payload_mbps_over_receive_span": assembler.counts["wire_payload_bytes"] * 8 / span / 1e6 if span > 0 else None,
                "reassembly_ms": stats(assembler.reassembly_ms), "decoder": decoded,
                "notes": "Receive-span rates are approximate and exclude pre-first/post-last-frame time. No one-way latency measured. Missing sender END leaves loss totals unknown. Linux decode is not Pixel 6 decode."}
            path = args.output / f"run-{run}.json"
            path.write_text(json.dumps(report, indent=2) + "\n")
            if preview is not None:
                (args.output / f"run-{run}.jpg").write_bytes(preview)
            print(json.dumps(report, indent=2))
            print(f"Saved {path}")
        if not runs:
            print("No frames received. Check VaM report and network/firewall settings.")


if __name__ == "__main__":
    main()
