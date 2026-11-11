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
`atom`), `selectable`, `loading`, `control`, `position`, `rotation`, `target`, `name`, `type`,
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
