# Phone camera (initial integration)

Load the combined **PhoneController.cslist** entry described in `../README.md`.
This directory contains supporting code for that one plugin, not a separate entry.
Both body control and camera streaming share UDP 62701; the phone page selects
which traffic to send. Remove old standalone instances when upgrading.

Install the Android debug APK, open **Camera** in the toolbar, and press Start.
The app requests camera permission/ARCore services as needed. Leave Camera settings
host blank for LAN broadcast discovery, or set it to the VaM host address
(e.g. 192.168.12.1 on the Ubuntu hotspot). Settings are separate from controller
preferences. Position scale accepts 0.02–10, default 1. The camera UI stays in portrait. On connection the phone requests an even-sized
image matching its video viewport, at approximately the previous 640x360 pixel
budget. VaM renders at that aspect ratio rather than cropping a landscape image.
JPEG quality 75 and requested 30 fps remain fixed, with UI layers excluded.
Physical roll remains tracked, independently of the locked Android UI.

Hold freezes motion, Resume rebases at the held view, Recenter changes the phone
reference without moving the current view. Tracking loss freezes pose. Leaving the
page releases tracking and the socket. The server's two-second lease expires and
returns to listening. Cold launch still opens the original controller.

## Wire format, initial version

All integers/floats little-endian. Session is server-assigned uint32, nonzero.

- Client greeting: 13 bytes: nine ASCII bytes `PCCHELLO2`, then uint16 width
  and uint16 height, repeated every 500 ms, broadcast
  initially or unicast to the configured address. First client owns the lease.
- Accept: 12 bytes: `PCA2`, uint32 session, uint16 width, uint16 height.
  Both sides must be updated; older greetings are deliberately rejected.
  Size bounds: 128–1024 per axis and at most 300000 pixels. Negotiation is fixed
  for the session; changing the viewport size takes effect on the next Start.
- Pose: 64 bytes: `PCP1`, session at 4, int64 increasing sequence at 8, int64
  reference at 16, eight float32 values at 24 (x,y,z,qx,qy,qz,qw,scale), held byte
  at 56, reserved zero bytes through 63. Position is meters in the ARCore anchor
  frame; orientation is relative to that same anchor. The server computes relative
  motion against the first received pose for each reference and maps handedness
  using basis diag(1,1,-1). Pose packets expire after 250 ms.
- JPEG fragment: 28-byte header `PCF1`, session uint32, frame uint32, fragment
  index/count uint16, total bytes int32, opaque sender timestamp double. Payload
  is up to 1172 bytes. Max datagram payload 1200 bytes.
- Decoded feedback: `PBA1`, session uint32, frame uint32 (12 bytes).

This initial protocol has fixed settings, no encryption, no Internet exposure,
and no retransmission of video. The benchmark protocol remains separate on 62710.

## Validation and remaining plan work

Android assembleDebug and existing unit tests pass. C# compiles against supplied
Unity assemblies with VaM UI stubs. Actual Pixel 6 tracking, coordinate mapping,
stream decoding, normal/VR view correctness, reload cleanup and long-running memory
behavior require on-device validation. These compilation checks do not establish
VaM sandbox acceptance.

This is an initial integration, not completion of PHONE_CAMERA_PLAN.md. Remaining
work includes protocol/math fixture tests, independent latest-frame decode queue,
server selection when multiple offers arrive, configurable port and richer settings,
tracking/video status separation and diagnostics, explicit stop/control acknowledgments,
and prolonged fault/reload testing. The current Android receive loop also decodes
frames, which can delay receiving during a decode stall. Landscape display orientation
calibration has not yet been verified. Do not interpret the initial integration as
meeting all acceptance gates in the plan.

## Decode buffering, framing and live scale

JPEG decoding now runs independently of UDP reception: one active decode, one
replaceable latest pending JPEG, and one replaceable pending image for the UI.
Stopping closes the pending-work queue without waiting on the Android UI thread.
Session/generation checks discard results from an old connection.

The phone's **Frame object** button (and VaM's **Frame containing object**) places
the virtual camera on the containing atom's front side, aimed at its bounds center.
Its perspective fit reserves 5% on each edge, fitting within 90% of both width and
height; whichever dimension limits the fit wins. It uses the current stream aspect
ratio/FOV. Put the plugin on the Person or visible object to frame. An Empty atom
has no visible bounds: a diagnostic is logged and the camera stays put.
For people, framing uses head, left/right foot and left/right hand controller
positions, with padding for the crown, fingers and soles. It does not read skin
vertices or GPU buffers. This is a rough fit: large hairstyles/accessories or
extreme poses may extend outside the estimate. Objects without these landmarks
use ordinary enabled Renderer bounds. Animated poses can move outside the initial
framing afterward.

**World scale** on the phone adjusts translation from 0.02 to 10 VaM units per
physical meter (default 1). Rotation is unaffected. Scale is saved and applied live;
the server rebases on changes so the current view does not jump. Framing likewise
rebases against the next phone pose, including while Hold is active.

The pose's formerly reserved uint32 at byte offset 60 now carries a frame-object
command counter. It repeats with pose packets and is applied once per new value,
so losing a single UDP packet does not lose or repeat the action. Update both sides.

## Automatic start

The VaM service starts listening as soon as the plugin loads. Opening the Android
camera page starts tracking and connection automatically, after its layout is ready.
Start and Stop remain available. An explicit phone Stop stays stopped when returning
from settings/background; Start re-enables automatic resume. Leaving the page pauses
tracking and network resources. The original controller is still the app launch page.
