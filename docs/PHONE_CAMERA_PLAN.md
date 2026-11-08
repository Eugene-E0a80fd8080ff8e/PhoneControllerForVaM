# Phone-controlled VaM camera: implementation plan

Status: proposed implementation; no feature code is authorized by this document.
Date: 2026-11-08. Baseline: StreamingBenchmark v3 visually confirmed by user to
render the human and exclude the menu. Existing controller remains the launch default.

## 1. Scope and defaults

Add a separate camera page to the existing Android app. The phone's ARCore pose
moves a dedicated virtual camera in VaM; VaM sends its view to the phone as JPEG
frames fragmented over UDP. The phone displays the VaM view, not its physical
camera feed. ARCore still uses the physical rear camera for tracking.

Initial production defaults:

- 640×360 landscape stream, quality 75, requested 30 fps.
- VaM UI excluded. No menu-visibility setting in the first release.
- One active phone per camera-plugin instance; one camera-plugin instance initially.
- Position scale 1:1 (physical meters to Unity scene units); no prediction initially.
- Existing controller, rotation defaults, optional body-position control and protocol
  continue unchanged. Camera mode never moves body controllers.
- UDP only for discovery, configuration, pose, JPEG fragments, feedback and teardown.
- No audio, recording, H.264, WebRTC, remote desktop controls or simultaneous body
  control and camera control in this first feature.

Keep internal stream configuration explicit so resolution/FPS/quality can become
phone settings later. Do not expose incomplete controls in the initial UI.

## 2. Android navigation and screen behavior

Keep MainActivity as the launcher and existing controller page. Add CameraActivity
rather than moving the existing controller into a new navigation framework.

The top bar provides a visible, labeled two-state mode selector: Controller / Camera.
It must remain directly accessible on Pixel 6 in portrait and landscape; reduce the
long app title before hiding the selector in overflow. Accessibility descriptions
must announce the current mode and the destination. Existing controller settings and
azimuth setup remain accessible in Controller mode. Camera mode replaces azimuth
setup with its own actions.

- Cold launch always opens Controller, even if Camera was last used.
- Entering Camera launches one CameraActivity above MainActivity, not a chain of
  activities. Selecting Controller or pressing Android Back returns to MainActivity.
- Configuration recreation of an already open CameraActivity stays in Camera mode.
- Remember camera settings separately from the last active page.
- The toolbar gear is contextual: existing SettingsActivity from Controller;
  new CameraSettingsActivity from Camera.
- Opening settings, leaving the app, locking the phone, or switching modes pauses
  capture/pose transmission and releases tracking resources. Returning to the camera
  page renegotiates the stream and establishes a safe pose reference.

Camera page layout:

1. Toolbar with mode selector and settings.
2. VaM video viewport preserving 16:9 aspect ratio. Letterbox in portrait; do not
   stretch, crop by default, or allow the toolbar/system bars to cover the image.
3. Compact connection and tracking status, with distinct states: finding server,
   connecting, tracking initializing, tracking, tracking lost, stream stalled.
4. Start/Stop, Recenter and Hold controls. Hold freezes the VaM camera while the
   video continues; releasing Hold re-bases from the current physical phone pose to
   the held virtual camera pose, avoiding a jump.
5. Optional diagnostics text enabled from settings: decoded/displayed FPS, dropped
   frames and frame age in the local receive pipeline. Do not label an unsynchronized
   sender timestamp as one-way latency.

Entering the page may discover servers automatically, but streaming/motion requires
Start. Leaving the page stops both. Keep the screen awake only while active.
A stale last image must be visibly marked; it must not look like a live view.

## 3. Settings boundaries

Use a separate preference namespace/file for camera mode; do not repurpose existing
body-controller sensitivity, azimuth, ports or position preferences.

Initial Camera Settings sections:

- Connection: automatic discovery, manual host override, camera service port,
  selected server identity. Default camera port 62711; existing controller 62701
  and diagnostic benchmark 62710 remain separate.
- Camera movement: position scale (bounded positive value, default 1), reference
  behavior/help and reset-to-defaults. Keep smoothing off initially to measure real
  latency; if added, explicitly define independent rotation/translation controls.
- Diagnostics: show metrics, copy a bounded diagnostic report.

Future sections, explicitly deferred: resolution, desired FPS, JPEG quality,
menu visibility, FOV and adaptive quality. The protocol negotiates effective stream
settings from day one, but first release accepts only supported 360p/30/q75 defaults.
Do not claim a requested FPS was achieved merely because negotiation succeeded.

## 4. Android implementation structure

Current app uses Java, AppCompat support libraries, minSdk 24 and ARCore 1.56.0.
No SDK/library migration is part of this feature. Check the existing build before
changing dependencies; this work does not require increasing the minimum Android
version on the evidence available.

Proposed components:

- CameraActivity: page state, lifecycle, viewport and user actions.
- CameraSettingsActivity and camera preference resources.
- CameraModeCoordinator: serialized ownership/state transitions between tracking,
  transport and presentation. No network or JPEG decode on the UI thread.
- CameraPoseTracker: ARCore Session and offscreen GL ownership, full position plus
  quaternion, tracking/reference generation and monotonic sample timestamps.
- CameraUdpClient: discovery, connection/lease, control retries, latest-pose mailbox,
  fragment receive and receiver feedback.
- FrameAssembler: bounded, reorder-tolerant frame assembly with strict validation.
- JpegDecodeWorker: bounded decode work and bitmap ownership.
- CameraFrameView: latest-frame presentation with safe bitmap/surface lifecycle.
- CameraProtocol / CameraPreferences: explicit wire structures and settings storage.

Reuse the proven offscreen EGL/ARCore lifecycle approach from PositionTracker,
but do not change its position-only Sample contract to carry camera-mode state.
Start with an independent CameraPoseTracker; extract shared implementation only
where doing so does not change controller behavior. There must never be two owners
of the Android camera/ARCore session at once.

Audit MainActivity.onStop/onPause, held button state, sensor queues and socket shutdown
before adding the switch. Release held controls, send a best-effort neutral legacy
state, clear stale queued sensor data and stop controller workers when leaving.
Do not rely on a UDP release packet arriving: retain the receiver's stale-input
safeguards. Do not silently engage previously held controls when returning.

Session stop/close must be serialized with Session.update and EGL cleanup on its
owner thread. Avoid unbounded Thread.join on the Android UI thread (the current
PositionTracker.stop contains one); change shared lifecycle code only as needed,
with controller regression checks. Ignore callbacks from old page/session generations.

## 5. ARCore pose and virtual camera semantics

Camera mode needs all six degrees of freedom. Use one ARCore pose for translation
and orientation; do not mix ARCore translation with legacy magnetometer rotation.
Select the rear-camera pose as the optical reference and explicitly account for
Android display orientation when constructing the camera-mode coordinate adapter.

Coordinate contract, to finalize and unit-test before networking:

- Translation is meters; quaternion order x,y,z,w; normalized finite quaternions.
- Define Android/ARCore camera axes, Unity camera axes and screen orientation in
  the protocol document. Convert handedness through a documented basis transform,
  including camera forward direction; avoid ad-hoc axis sign changes.
- At acquisition/recenter, record physical reference pose T_phone0 and current
  virtual camera pose T_vam0. Apply the converted relative transform
  inverse(T_phone0) * T_phone to T_vam0. Translation scale affects translation only.
- Reference identity changes on recenter, resumed tracking or ARCore session restart.
  Re-base against the current virtual pose before accepting motion under a new
  reference. A resumed session must not jump the camera back to the original view.
- Hold and tracking loss freeze the last valid virtual pose. Tracking failure/status
  messages remain distinct from disconnect. Do not send invalid poses as identity.
- Recenter resets physical reference without moving the current virtual view.
  Reset-to-starting-view, if needed, is a separate deliberate action.
- Default virtual pose is copied once from VaM's current look camera at stream start.
  After that, it follows phone pose, not the user's local desktop/VR head camera.
- Use a fixed initial FOV derived from the VaM starting view, not automatic ARCore
  camera-intrinsics matching. Optical matching can be a later calibration feature.

Validate translation along each axis, positive/negative yaw/pitch/roll, quaternion
sign equivalence, screen rotation, recenter, loss/reacquisition and scaling using
pure math tests and a simple visual scene before connecting motion to live rendering.

## 6. VaM plugin structure and camera lifecycle

Add a separate PhoneCamera.cslist plugin, loadable on an Empty atom, alongside
PhoneController.cs. This isolates camera/stream lifecycle and camera service sockets
from the proven body-controller plugin. No user must install or enable the camera
plugin merely to use the old controller.

Suggested source split: PhoneCamera (MVRScript UI/coordinator), CameraCapture,
CameraUdpService, CameraStreamWorker, CameraProtocol and the attributed JPEG encoder.
Preserve the encoder's FivelSystems/MahiroOyama CC BY attribution. Port relevant
benchmark behavior into production classes; do not turn the diagnostic UI/report loop
into the production service.

- Dedicated camera and render target, with the v3 settings confirmed by the user:
  full viewport, reset view/projection matrices, mono target, MSAA disabled.
- Normal render phase after LateUpdate so VaM skin draw submissions exist.
- Defer readback until the following frame, after full rendering/target resolution.
- Exclude known/discovered VaM UI layers on this camera only. Preserve local menus.
  Default-layer/custom world-space UI remains a documented edge case to verify;
  do not hide all Default-layer scene geometry to suppress a menu.
- Capture at the negotiated cadence only when a client lease is active.
- All Unity object access, camera transforms, render requests and GPU readback data
  access on Unity's main thread. Worker handles managed JPEG and sockets only.
- Record the applied pose sequence with each captured frame so future latency/debug
  tools can correlate movement and images without clock synchronization.
- No main-thread joins. Stop accepts no new work, invalidates the session generation,
  and allows in-flight GPU operations to finish before releasing their targets.
- Production cleanup must improve on the benchmark's deliberate mid-readback texture
  retention. Implement a bounded deferred-retirement owner/callback path compatible
  with VaM removal/reload; verify repeated reloads don't leak textures, threads,
  callbacks or sockets. Do not free GPU-referenced textures to avoid a leak.

VaM UI: enable/disable service, port, active client, tracking state, effective stream
settings, counters/FPS and disconnect/release-client. No native DLL, file stream API,
or TCP dependency. Limit configurable values and reject unsupported network commands.

## 7. Production UDP protocol

Write a versioned CAMERA_PROTOCOL.md and cross-language golden fixtures before
implementing transport. Benchmark PBF1/HELLO messages remain diagnostic-only; production
uses its own magic and version, and does not accidentally join a benchmark run.

Common header: magic, version, message type, validated payload length, unpredictable
session ID and message/frame sequence as applicable. Specify exact little-endian
field offsets and maximum sizes. Sequence and reference comparisons are per-session;
use wide counters with a defined rollover policy rather than signed subtraction tricks.

Message families:

- DISCOVER / OFFER: small, infrequent IPv4 LAN broadcasts plus unicast replies;
  server name/identity, service version and supported capabilities. Manual IP fallback
  for hotspot broadcast restrictions. If multiple servers respond, show selection.
- HELLO / ACCEPT: requested supported settings, endpoint/session binding, negotiated
  stream configuration, keepalive timing and initial state. Handle incompatible versions.
- CONFIG / CONFIG_ACK: idempotent, request-ID-based changes; reserved for future settings.
- POSE / TRACKING: full pose, reference generation, sequence, tracking status and
  opaque monotonic sample time. Send latest state (initially up to 60 Hz subject to
  fresh ARCore samples), not a queue of old motion. Never apply stale/reordered poses.
- HOLD / RECENTER / STOP and acknowledgements: bounded retries for discrete commands;
  duplicate-safe execution. Loss of STOP is covered by lease expiry.
- FRAME fragments: session, frame ID, applied pose ID/reference, dimensions/config
  generation, total JPEG bytes, fragment index/count and payload.
- FEEDBACK / KEEPALIVE: completed/decoded/displayed frames, drops, reassembly/decode
  statistics and optional round-trip probes. Feedback at a modest cadence (e.g. 2 Hz),
  not a per-fragment acknowledgement storm.

UDP payload ceiling 1200 bytes including production headers, adjusting fragment
payload size accordingly. No frame retransmission initially; late video is less useful
than the next frame. Small control messages retry with capped backoff; continuous pose
and video are replaced by newer data. Initial liveness defaults: keepalive every 500 ms,
2-second client lease; freeze motion after 250 ms without a fresh valid pose. Make
thresholds named constants/configuration and validate behavior on the actual network.

Pin the negotiated client endpoint/session; reject packets from others while leased.
Session IDs prevent stale/cross-run packets, not malicious LAN interception. First
release is a trusted-LAN protocol, not an encrypted/authenticated Internet service.
New clients cannot silently steal an active session; explicit disconnect or lease
expiry releases it. Keep discovery/config parsing bounded and rate-limited.

## 8. Buffering, receive, decode and display

Sender: at most three readbacks/CPU buffers; latest pending encode; no unbounded
frame queue. Encoding/sending may initially share one worker as in the benchmark.
Receive/drain pose/control independently enough that a JPEG encode cannot hold up
camera motion for its duration. Use a latest-pose mailbox consumed by Unity.

Receiver: dedicated UDP receive loop, validation before allocation, bounded frame
count and total-byte budget, roughly 200–500 ms assembly expiry tuned from real tests.
Reject excessive dimensions, sizes, fragment counts, inconsistent headers and malformed
JPEGs. Track completed/expired IDs with a bounded sliding window, not ever-growing sets
(the 30-second benchmark's collections are unsuitable for a long-running feature).

Decode off the UI thread with one active decode and one replaceable latest pending
frame. Discard completed frames older than the last displayed frame; do not display
reordered old images. Recycle buffers/bitmaps only after rendering no longer owns them.
Apply decoded-image dimension checks even if header dimensions looked valid.
Report received, completed, decoded and displayed FPS separately. Phone decode and
presentation must be measured directly; MacBook timings do not predict the Pixel 6.

## 9. State transitions and failure handling

Android: Controller -> Camera idle -> discovering -> connected/tracking initializing
-> streaming. Streaming may enter held, tracking-lost or stream-stalled states.
Stop/back/background/settings -> deterministic teardown -> idle/controller.

VaM: disabled -> listening -> negotiated -> streaming/held -> lease expired/listening.
Reload/removal always tears down safely; a phone must discover a new session afterward.

Handle permission denial, missing/outdated ARCore services, camera unavailable, Android
activity recreation, server reload, Wi-Fi changes, receiver overload and phone lock.
Do not start ARCore or a stream on ordinary controller launch. When video stalls while
tracking still works, freeze motion after a documented short stale-view threshold
(initial proposal 1 second) and require a safe re-base when resuming, so the user does
not move a camera while looking at an apparently current but frozen view.

## 10. Delivery sequence and acceptance gates

1. **Protocol/math specification and lifecycle audit.** Finalize wire fixtures,
   coordinate conversion, reference/hold semantics and resource ownership. Record
   current controller build/tests and behavior. No visual redesign prerequisite.
2. **Navigation and isolated settings.** Add CameraActivity placeholder, toolbar mode
   selector, contextual settings and safe mode switching. Gate: ordinary launch and
   controller operations remain unchanged; no ARCore owner or socket survives exit.
3. **Production VaM static camera stream.** Extract v3 capture with correct body/UI
   rendering, bounded workers and reload-safe retirement. Validate using a protocol-
   updated Python diagnostic receiver before implementing phone decoding. Preserve
   benchmark receiver compatibility by leaving its existing diagnostic protocol alone.
4. **Android video receive/display.** Stream a stationary VaM camera at 360p/30/q75
   to Pixel 6. Gate: correct image, stable memory, responsive toolbar, bounded queues,
   useful reconnect/error states; record phone-specific decode/display metrics.
5. **ARCore full-pose camera control.** Integrate tested coordinate adapter, initial
   pose, hold/recenter and tracking loss. Gate: physical axis tests and no jumps on
   mode changes, tracking reacquisition or network reconnect.
6. **Fault/reload testing and polish.** Settings/lifecycle recovery, portrait/landscape
   insets, diagnostics, documentation and install instructions. Repeat prolonged use
   and VR coexistence tests before declaring the feature complete.

Do not combine all six steps into one unreviewable change. Build and validate after
each useful increment; commit only when requested. Do not merge or push implicitly.

## 11. Verification matrix

Automated:

- Protocol fixtures between Java/C#/Python; truncated/oversized/nonfinite inputs,
  duplicate/reordered fragments and controls, expired leases/session rejection.
- Pure coordinate transforms, reference changes and hold/rebase behavior.
- Bounded assembler/decode queues, sequence windows and old-frame suppression.
- Android build/unit tests and C# compilation against supplied Unity assemblies.
  VaM UI stubs don't prove sandbox acceptance; real VaM validation is mandatory.
- UDP integration with deliberate loss/reordering and decode faults.

On user hardware (Ubuntu Wine/Proton host, Pixel 6 on its hotspot):

- Cold-launch old controller; rotation and optional position still work with only
  PhoneController loaded. Switch modes repeatedly with a body button held.
- Camera visual correctness: human, hair, environment, lighting, no main menu;
  local VaM menu remains usable. Compare with the current v3 result.
- Translation/rotation axis tests; recenter/hold; obscure rear camera and reacquire.
- Background, settings, screen lock, denied permission, rotation and process restart.
- Disconnect hotspot/reconnect; reload/remove VaM camera plugin during capture.
- At least 10-minute steady stream and repeated switches/reloads; check memory,
  threads/sockets, thermal degradation and capture-to-feedback latency distributions.
- Repeat with WiVRn/xrizer active: headset streaming shares the hotspot and host GPU,
  so earlier desktop/MacBook results are not a VR concurrency guarantee.

Performance objectives, not promises: approach 30 displayed fps in the simple test
scene, no growing latency, no multi-second stalls or queue growth, and approximately
under 100 ms p95 capture-to-decoded feedback on the user's local setup. Measure full
phone-motion-to-visible-response separately; transport feedback excludes display scanout
and does not by itself establish that end-to-end motion latency.

## 12. Completion definition

User can launch the unchanged controller, select Camera in the top bar, start a
360p UDP stream, move the virtual camera with Pixel 6 ARCore, hold/recenter it, open
its separate settings, and return to body control. Correct body rendering and menu
exclusion persist. Loss/reload/background behavior is bounded and recoverable.
Resolution/FPS/menu options are left for subsequent work, with explicit protocol and
settings boundaries ready for them.
