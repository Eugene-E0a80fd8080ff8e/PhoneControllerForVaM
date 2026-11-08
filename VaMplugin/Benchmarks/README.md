# VaM camera streaming feasibility benchmarks

These are standalone diagnostic plugins, not the streaming feature. They do not modify PhoneController or use its ports. No TCP is used.

## Run in VaM

1. Copy this folder to `VaM/Custom/Scripts/PhoneControllerBenchmarks/`.
2. Open a representative scene (ideally the scene you intend to stream). Use a visible, reasonably well-lit view. Record whether you are using desktop or VR mode.
3. Enable VaM's **Allow Plugins Network Access** for the UDP probe.
4. On an Empty atom, add **one** of the `.cs` files as a plugin. Open its UI and click **Run benchmark**. Do not run probes simultaneously or stream using OBS during the baseline.
5. Wait for `FINISHED`, then click **Copy report** and paste the full text back. The report is also sent to VaM's log. If loading fails, return the compiler/security error instead.
6. Remove that probe and repeat for the other two files. Run each twice to reveal warm-up variability. Keep the scene/view unchanged between runs.

Return all reports, scene description, desktop/VR mode, approximate normal scene FPS, and any visible problems or errors. No filesystem writes are required. Stop takes effect between samples; an individual Unity render/encode call cannot be interrupted.

## What the probes measure

- **JpegBenchmark.cs**: Unity `Texture2D.EncodeToJPG`, RGB24, qualities 50/75, 640×360 / 960×540 / 1280×720. Two warmups and 20 measured samples per case. Smooth gradient and deterministic random noise bracket different image complexities; neither predicts a real scene's exact compression. Reports mean/p95/max wall time, JPEG bytes, and hypothetical payload bandwidth at 15/30 fps. Encoding runs on Unity's main thread and includes allocation of the returned JPEG array. Texture setup is excluded.
- **CaptureBenchmark.cs**: a temporary camera copies the current look camera's settings and transform, renders to a dedicated target, then performs synchronous `ReadPixels`. Same three resolutions and sample counts. Reports render/readback/combined times and a brightness sanity check. No JPEG encoding. Camera scripts, postprocessing components and command buffers are not copied, so fidelity and costs may differ from the final camera. Render timing alone is not GPU execution time; GPU waiting can appear in readback. Texture data stays on the CPU without an unnecessary `Apply` upload.
- **UdpBenchmark.cs**: IPv4 loopback on OS-assigned ports, 200 datagrams each of 256/1200/1400 bytes. Checks sequence/payload integrity, duplicates and missing packets, and measures the `Send` call. Nonblocking sockets, bounded bursts and receives per frame, one-second final drain deadline. Loopback only: this checks APIs and basic delivery, not Wi-Fi bandwidth, phone decoding, latency, or image reassembly. Total duration includes deliberate frame yields.

Twenty samples are a quick feasibility probe, not statistically rigorous tail-latency testing. Timings include runtime scheduling and possible garbage collection. Do not add p95 values from separate probes to estimate end-to-end p95.

## Interpreting results and next tests

A 30 fps stream allows about 33.3 ms per stream frame, but work on Unity's main thread also consumes the game's frame budget. JPEG bytes × fps × 8 estimates payload bit rate before packet headers, framing and losses. A successful loopback probe does not establish that the Wi-Fi link can sustain that rate.

The downloaded FivelSystems LiveStreamConnectorToOBS source uses a custom managed JPEG encoder on a worker thread and `UnityEngine.Experimental.Rendering.AsyncGPUReadback`. These probes intentionally establish Unity JPEG and synchronous-readback baselines; they do not benchmark that plugin's optimized pipeline or establish its performance. Next, compare async readback and worker encoding on actual scene frames, then test fragmented UDP delivery and decoding on the Pixel 6. No third-party source has been copied into these probes.

## Validation status

Source reviewed against the supplied decompiled VaM APIs. No C# compiler or running VaM is available in the development environment, so compilation, runtime sandbox acceptance and performance must be verified by the runs above. Each probe is separate so a rejected API does not prevent the other probes from loading. A failure is useful diagnostic output.

## Real-scene streaming follow-up

The next benchmark is in [Streaming/README.md](Streaming/README.md). It combines
async GPU readback, background managed JPEG encoding and fragmented UDP delivery
to a Python receiver, including decode acknowledgements and saved JSON reports.
Load its `.cslist` entry point; the three baseline probes above remain independent.
