# UDP position record, version 1

The phone appends this record to the existing little-endian UDP packet, after all legacy records. Old plugins process the preceding rotation data and stop at the unknown record type. Position tracking requires the updated plugin.

| Offset | Size | Value |
| --- | ---: | --- |
| 0 | 1 | Record type 33 |
| 1 | 1 | Version 1 |
| 2 | 1 | 1 = tracking; 0 = unavailable |
| 3 | 8 | Signed reference identifier (random per ARCore anchor) |
| 11 | 8 | Android elapsed realtime in nanoseconds, used as packet sequence |
| 19 | 12 | Three little-endian IEEE 754 floats: x, y, z in meters, in the anchor's Android sensor axes |

The anchor is created at the phone's sensor pose when tracking starts. The plugin converts the displacement from Android's coordinate system and maps it to VaM's scene using its existing phone orientation at reference acquisition. It snapshots each selected control's position and the phone position when that control becomes active. Each new pose is applied relative to those snapshots, with hand and foot reach limits. Tracking loss, stale packets, a changed reference, or a 500 ms timeout clears the snapshots; any buttons held at that point must be released before position movement resumes. Rotation continues using the original sensor records.

A status of 0 carries zero reference and position. The plugin discards position records with an older or equal sequence and validates length and finite coordinates. Packets are unencrypted and unauthenticated, like the existing rotation protocol.
