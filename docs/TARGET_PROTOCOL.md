# Atom targets over UDP

Both app and plugin must be updated together. All traffic uses UDP 62701 and
little-endian binary fields. One active plugin instance owns this port.

## State and target list

`PCT1` followed by UTF-8 JSON, at most 1200 bytes, requests and returns target
information. Requests have `op` (`state` or `select`) and `page` (zero-based).
A selection also supplies `instance`, `revision`, `target` and `request` integers.
The phone retries a selection for up to five seconds. Repeated selection of the
current target does not reframe it. A request with an obsolete revision cannot
change the target. Responses echo the request ID and contain an `error` string.

Every response contains the authoritative state and one requested catalog entry.
Fields are `instance`, `revision`, `catalog`, `installation` (`session`, `scene`,
`atom`), `selectable`, `loading`, `control`, `cameraGrab`, `cameraGrabVersion`, `position`, `rotation`, `target`, `name`, `type`,
`framing`, `request`, `error`, `page`, and `count`. An existing page also contains
`itemId`, `itemName`, and `itemType`. Target ID zero means no selection. Display
names may be shortened to keep the datagram bounded. Full atom identity uses IDs.

The client queries state every 500 ms and retrieves missing catalog pages faster.
Lost replies are recovered by repeating the query. Catalog revisions prevent mixed
lists when atoms change. IDs remain stable across renames and are never reused
within a plugin instance. Scene load invalidates IDs and session/scene selection.
Plugin reload changes `instance`. Responses from retired instances or with older
state/catalog revisions are discarded. State expires after 1.5 seconds without a reply.

Target queries are sent to the configured host or broadcast and the last known host.
The first valid target response selects the endpoint. Both phone pages share this
state. The older 0xff beacon and 0xfe discovery reply remain available, but body
control requires successful target negotiation.

## Movement

`PCM2` wraps the original controller sensor records. The header is magic at offset
0, plugin instance int32 at 4, and target revision int32 at 8. Sensor records follow
at 12. Raw old packets, wrong instances/revisions and non-Person movement are rejected.
The app waits for its Controller UI to clear held buttons before sending a new
revision. Target changes also clear the plugin's movement queue and baselines.

Camera poses use `PCP2`, 72 bytes. Offsets 4–63 retain the existing camera pose
layout, including the frame command at 60. Byte 57 is 1 for a gravity-aligned
anchor and 0 for the original camera-relative anchor. Instance int32 at 64 and revision int32
at 68 identify the target. The plugin checks the revision both on reception and
when applying the pose. Camera discovery remains `PCCHELLO2` / `PCA2`.

## Video

`PCF2` extends the previous fragment header to 36 bytes. Offsets 4–27 retain the
session, frame number, fragment index/count, JPEG length and timestamp. Instance
int32 at 28 and target revision int32 at 32 identify the captured target. JPEG
payload starts at 36, with up to 1164 bytes per 1200-byte datagram.

Revision is recorded when capture is scheduled and carried through GPU readback,
encoding and transmission. The phone checks it before reassembly and display.
A target switch clears pending images. This permits retaining the camera stream
session while discarding frames from the previous target. Feedback remains `PBA1`.

## Framing and availability

Person framing uses head, feet and hands. Other targets use enabled renderer bounds.
Targets without visible bounds use a one-unit cube around their position and report
approximate framing. A main controller with rotation grabbing enabled supplies the
front/up axes. Otherwise framing retains the current viewing direction. Framing
fits 90% of both image dimensions and rebases the next phone pose, preserving Hold.
With horizon alignment enabled, framing uses the target's heading and the phone's
current pitch and roll. The fit is calculated for that orientation. If tracking is
unavailable, the camera first frames using the target orientation and refits when
an aligned pose arrives.

Atom enumeration, selection, framing and body control run on Unity's main thread.
Network threads use bounded request queues and revision checks. Target deletion
immediately invalidates control. Scene loading blocks body control and selection.

## Camera Grab

A phone with **Show Grab button** enabled requests `PCCHELLO3` if the target
service advertises `cameraGrab`. The greeting and `PCA3` reply have the same
size and fields as version 2. Version 2 remains available to older clients and
when the Grab setting is off.

`PCP3` extends the pose packet to 80 bytes. Byte 58 enables Grab, offset 72
holds the int32 gesture ID, and offset 76 holds the uint32 frame ID the phone
showed on press. Gesture ID zero releases. The phone repeats its current
intent in each pose, and the server accepts pose sequence numbers in order.
A gesture ID is processed at most once in each session. A 250 ms pose timeout
also releases the link.

`PCF3` extends the video fragment header to 48 bytes. Offsets 36 and 38 carry
uint16 image coordinates with the origin at the top left. Values 1–65535
represent the full image extent and zero hides the marker. Byte 40 indicates
no marker (0), an eligible point (1), or a held point (2). Offset 44 carries
the last processed gesture ID, including rejected attempts. The payload is
up to 1152 bytes. The phone displays the marker alongside its decoded frame.
VaM keeps up to 64 offered points per stream, accepts only offers less than a
second old from the same target and camera reference, and checks eligibility
again when the user presses Grab.

A Person offers its enabled body controls in **On** or **Comply** position
state. Other atoms offer a movable main controller. VaM acquires a temporary
link for the selected axes and preserves the point's initial camera-relative
offset and orientation as applicable. When
released, VaM restores the controller state through its normal grab API.

Version 4 adds point hints and the catch radius. If the target service advertises
`cameraGrabVersion` 4, an app with Grab enabled uses `PCCHELLO4` and `PCA4`.
`PCP4` is 84 bytes. Byte 59 requests visible eligible-point hints and offset 80
carries the catch radius as a float32 fraction of the image's shorter edge.
The allowed range is .01–.50 and the default is .05.

`PCF4` uses the same 48-byte base header as `PCF3`. Byte 41 contains the
uint8 count of visible eligible points in every fragment. Fragment zero includes
that many pairs of uint16 coordinates at offset 48 before its JPEG payload.
Later fragments use the full 1152-byte JPEG payload capacity. If the hints
occupy B bytes, the first fragment carries up to 1152 − B JPEG bytes and fragment
N (N > 0) begins at JPEG offset N × 1152 − B. The JPEG length field excludes the
hint bytes. The phone draws the hints only with the JPEG from the same frame.

When the target service advertises `cameraGrabModes`, byte 59 of `PCP4`
also carries the mode. Bit 0 still requests point hints. Bit 1 enables position
and bit 2 enables rotation. If neither axis bit is set, the server uses position
for compatibility with older clients. Offers are valid only for their mode,
and changing the mode releases an active grip.

If the service also advertises `cameraGrabRotationOff`, bit 3 with bit 1 and
without bit 2 selects Pos&Relax. The grip links position and temporarily sets
the rotation state to Off. The prior state is restored on release if the grip
still owns the link and rotation is still Off.

If the service advertises `cameraGrabHeadOnly`, bit 4 with both axis bits
selects Head Only. Only a Person’s eligible head controller can be offered.
The server links both head axes and saves and turns off the other editable
body controls. Release restores their saved states on axes that are still Off.
