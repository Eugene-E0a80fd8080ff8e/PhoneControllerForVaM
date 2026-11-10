# Phone Controller for VaM

One plugin handles both the original body controller and the ARCore camera page,
using **UDP 62701**. The Android page determines the traffic it sends; there is
no VaM plugin-mode selection. Body control works without an active camera stream.
Camera capture begins when a phone requests it and stops after its lease expires.

## Installation / upgrade

1. Copy `PhoneController.cs`, `PhoneController.cslist`, and the `PhoneCamera` folder
   together into `VaM/Custom/Scripts/PhoneControllerForVaM/`.
2. Remove the previously loaded standalone PhoneController and PhoneCamera plugins
   from the atom. Both would compete for resources with the combined version.
3. Add **PhoneController.cslist** on the Person/object you want to control or frame.
   The `.cs` is now one part of the plugin; load the `.cslist` entry instead.
4. Enable plugin network access. Allow UDP 62701 through the host firewall.
5. Install the matching updated Android APK. Controller remains the launch page;
   switch to Camera in its toolbar for the camera view. No port 62711 is needed.

The shared socket retains the original server beacon and movement parser. Camera
message families are routed to a bounded worker inbox and never enter the body
parser. The camera worker sends through the same socket and never closes it;
receiver startup/shutdown belongs to the combined plugin. Camera Stop only stops
streaming service, while the legacy body receiver continues to run.

See `PhoneCamera/README.md` for camera controls, framing, scaling and protocol fields.
`PhoneCamera` contains implementation/support files, not another plugin to select.
`Benchmarks` remains a separate diagnostic toolset.

Validation: Android APK build/unit tests, combined C# compilation against supplied
Unity assemblies with VaM API stubs, packet-family routing tests, and a reflection
check for exactly one MVRScript entry point. Reload and both modes should also be
verified in the actual VaM runtime; compilation does not prove sandbox acceptance.

## Discovery

The original server beacon remains one byte `0xff`, broadcast every ~300 ms to
UDP 62701. The original controller now also sends one-byte `0xfe` discovery probes
at most twice per second. These go to broadcast and, when configured/known, directly
to the camera host. VaM replies to the probe's source endpoint with `0xff`. This
avoids depending solely on broadcast delivery through hotspot/Wine networking.
The controller accepts only one-byte `0xff` announcements and expires a server
1.5 seconds after its last announcement. Movement remains unicast.

Camera negotiation still uses `PCCHELLO2` / `PCA2`; it is separate from the small
controller discovery probe. The phone remembers the accepted camera server address
for subsequent controller discovery. An explicit host setting takes precedence.
All of this uses the same receiver on UDP 62701.
