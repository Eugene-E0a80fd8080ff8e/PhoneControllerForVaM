# Real-scene async capture / JPEG / UDP benchmark (v3)

This is a diagnostic plugin and Ubuntu receiver, not the Android streaming feature.
It captures the current VaM look-camera view using a temporary camera, asynchronously
reads RGBA pixels, encodes on a background worker using the supplied OBS plugin's
managed JPEG encoder, and sends fragmented JPEG frames over UDP. No TCP is used.

## 1. Copy to VaM (including Wine/Proton on Ubuntu)

Copy this entire `Streaming` directory into:

```
VaM/Custom/Scripts/PhoneControllerBenchmarks/Streaming/
```

Open your representative scene. On an Empty atom, add **StreamingBenchmark.cslist**
(not either individual `.cs`). Enable **Allow Plugins Network Access**.
If the host firewall blocks it, allow inbound **UDP port 62710** from the receiver.
With Wine/Proton on Ubuntu, this is the Ubuntu host/network firewall. The existing controller's
62701 port is not used.

## 2. Run receiver on Ubuntu or macOS

Replace `192.168.1.100` with the VaM host's LAN/hotspot IPv4 address (for your Ubuntu hotspot: `192.168.12.1`):

```bash
uv run /home/eugene/prj26/pcvam/PhoneControllerForVaM/VaMplugin/Benchmarks/Streaming/receiver.py \
  --host 192.168.1.100 \
  --output "$HOME/pcvam-benchmark-results"
```

`uv` automatically installs Pillow in an isolated script environment. No system
Python package installation is needed. The receiver sends a UDP greeting once a
second; VaM uses its source address and port for replies. The Windows listener pins
the first receiver for each run. Use one receiver and one benchmark plugin at a time.

## 3. Run the measurement

In the plugin UI:

1. Leave the defaults: **640×360, quality 75, 30 fps**.
2. Click **Run**. It waits up to 60 seconds for the receiver.
3. Keep the scene and view consistent: it measures **5 seconds of baseline**, then
   **30 seconds streaming**, then allows **2 seconds for encoding/ACKs to drain**.
4. Click **Copy report** after `FINISHED`. Check for `FAIL` lines.

The receiver automatically exits eight seconds after its last frame, prints a JSON
report, and saves `run-<id>.json` and `run-<id>.jpg` in the output folder. The JPEG is
the last successfully decoded frame. Open it to check the view, orientation, colors,
lighting and missing effects. Receiver decode is measured without a live display.
If no frames arrive, it keeps waiting: use Ctrl+C and send the VaM error/report.

For now, use 360p. Resolution/FPS controls remain available for diagnostic comparisons;
phone-side settings are deferred. Restart the receiver for each run. Two runs will
help distinguish stable costs from transient stalls. Changing
controls during a run affects only the next run.

Return:

- VaM's full copied report for each run;
- matching `run-<id>.json` receiver reports;
- one saved JPEG, or your assessment of its visual correctness;
- desktop/VR mode, scene description, and whether each computer uses Wi-Fi or Ethernet.

## What the numbers mean

**VaM:** baseline/stream observed FPS and frame-interval mean/p95/max; camera render
plus async-request submission cost; capture-to-readback-ready delay (includes frame
polling granularity); CPU buffer-copy/queue cost; encoder time; JPEG size; fragment
send time; capture-to-encoded delay; capture-to-decoded-ACK round-trip delay.

The ACK is sent after Pillow actually decodes the full JPEG. Its time includes
capture, readback, encoder queue, encoding, outbound network, receiver reassembly,
decode queue, decode and the ACK return path. It is **not one-way latency** or
phone display latency. No clock synchronization is needed. Dropped/unacknowledged
frames have no latency sample, so read latency together with delivery counts.

**Receiver:** received datagrams, completed/incomplete frames, completely unseen frame
IDs (when the sender's final count arrives), JPEG decode errors and times, decode
queue drops, reassembly time, approximate receive-span FPS and UDP payload bitrate.
The desktop Python decoder does not predict Pixel 6 decoding or display performance.

The sender sends its final frame count three times over UDP. If all three are lost,
total loss remains unknown (`null`), rather than being reported as zero. Counts refer
to encoded frame IDs, not requested capture slots: compare GPU errors, skipped slots,
pending replacements, encoded, fully sent, receiver complete and receiver decoded.
The sender's final few outstanding GPU readbacks are deliberately discarded when the
30-second capture window closes and are reported separately. Sender byte totals count
fully sent JPEGs; packet counts include any partial send at interruption.

The first encode and startup allocations are included. There is no warm-up exclusion.
A 30 fps target is a request, not a guarantee; queue/cadence limits deliberately drop
work instead of accumulating seconds of delay. Capture/frame timing can be affected by
scene animation, thermals and unrelated load, so baseline/stream FPS changes are not
perfectly controlled measurements. Camera components/postprocessing are not cloned.

## Bounded queues and lifecycle

- At most 3 GPU readbacks in flight; 3 reusable RGBA CPU buffers.
- One pending encode frame, replaced by the latest completed readback if necessary.
- Encoding and sending share one worker; send cost can therefore limit encoding rate.
- Nonblocking UDP; a socket send error is reported as a failed benchmark, not hidden.
- No retransmission or per-packet pacing: this measures a simple burst-per-frame
  transport. Congestion or loss will inform a later pacing/recovery design.
- Receiver holds at most 8 partial frames, expiring after 500 ms; 2 queued decode jobs.
- Stop is observed between individual encode/send operations; no main-thread Join.
  Normal Stop/finish asynchronously drains this plugin's GPU requests before releasing
  its target. Removing/reloading the plugin mid-readback retains one render target
  until scene/application cleanup to avoid freeing GPU-referenced memory; prefer Stop
  and wait for completion before removing it. There is no blocking GPU wait on reload.

## UDP protocol v1

All numeric fields are little-endian. Windows x64 is the sender platform tested for
wire compatibility. UDP port 62710; greetings and ACKs use the receiver's same socket.

- Greeting: nine ASCII bytes `PCBHELLO1`.
- Frame: Python struct `<4sIIHHId` (28 bytes): magic `PBF1`, run ID uint32,
  encoded frame ID uint32 starting at 0, fragment index uint16 starting at 0,
  fragment count uint16, total JPEG bytes uint32, opaque sender monotonic timestamp
  double. Then up to 1172 bytes of JPEG, for **1200 bytes maximum UDP payload**.
  The timestamp is not compared with the receiver's clock.
- Decoded ACK: `<4sII`, magic `PBA1`, run ID, frame ID.
- Final summary: `<4sII`, magic `PBE1`, run ID, number of encoded frame IDs allocated.

No authentication, retransmission, forward error correction, clock sync, or ARCore
pose traffic is included in this LAN diagnostic protocol.

## Validation

Run receiver tests:

```bash
uv run /home/eugene/prj26/pcvam/PhoneControllerForVaM/VaMplugin/Benchmarks/Streaming/test_receiver.py
```

Tests cover reordered/duplicate fragments, loss/expiry, malformed/inconsistent
headers, bounded partial-frame storage, and real UDP reception, full JPEG decoding,
ACKs, end counts and saved reports.

The C# plugin was compiled against the supplied Unity assemblies with small VaM UI
stubs; this verifies C# syntax and Unity API availability, not VaM's sandbox or UI.
The actual C# encoder/UDP worker was also exercised against the Python receiver over
loopback using a synthetic RGBA frame source. GPU capture and the complete VaM plugin
still require execution in your VaM installation.

The copied encoder's credits and license are in [ATTRIBUTION.md](ATTRIBUTION.md).

## v2 rendering correction

VaM's `DAZSkinV2.LateUpdate` submits body geometry using `Graphics.DrawMesh`.
Version 1 called `Camera.Render` from a coroutine before that submission, which is
consistent with the received floating-hair/missing-body images. Version 2 enables
the temporary camera only on capture frames and lets Unity render it in its normal
rendering phase, after LateUpdate. Its post-render callback requests async readback
and disables the camera again. Camera callbacks are removed on stop, failure and
plugin removal. See Unity's [execution order documentation](https://docs.unity3d.com/cn/2018.3/Manual/ExecutionOrder.html).

The stream camera excludes the `UI` layer plus non-Default layers discovered on
CanvasRenderer objects under VaM's main UI roots. Other cameras and menu visibility
are not changed. The report lists excluded layers; if UI graphics use the shared
Default layer, it reports that rather than hiding all Default-layer scene geometry.
Send that diagnostic if a menu remains visible. Arbitrary in-scene custom interfaces
outside the main UI roots are not automatically excluded.

Please replace the old plugin files, reload the `.cslist`, run at 360p and inspect
the new JPEG with the VaM menu open. It should show the human without the menu.
The body fix is source-based and compiled, but still needs this in-VaM visual check.
Compare new timings with v1 cautiously: the rendered content changes, and the camera
render metric now spans pre-cull to post-render plus async request submission.

## v3 black-frame investigation

The v2 run delivered 900 valid but black JPEGs. This is a regression in capture,
not evidence of improved streaming performance. V3 keeps the camera enabled until
the frame after its post-render callback, then requests readback after the previous
frame's complete render/resolve. It also explicitly sets a full viewport, resets
copied projection/view matrices, disables camera MSAA, and logs camera masks/path.
The existing after-LateUpdate capture and UI exclusions remain in place.

First-three-frame raw RGB diagnostics identify black data before JPEG encoding.
If the first frame is black, a one-off synchronous target read distinguishes an
also-black render target from an async-path discrepancy. That probe can stall and
is included in timings; a black-image run is diagnostic, not a performance result.
The camera interval metric now excludes final resolve and readback submission.
This is a source-based correction pending validation in the user's Wine/Proton VaM.
