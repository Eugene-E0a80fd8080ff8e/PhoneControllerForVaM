using System;
using System.Collections;
using System.Collections.Generic;
using System.Diagnostics;
using System.Net;
using System.Net.Sockets;
using System.Text;
using System.Threading;
using UnityEngine;
using UnityEngine.Experimental.Rendering;
using FivelSystems.LiveStreamConnectorToOBS;

namespace MVRPlugin
{
    public partial class PhoneController : MVRScript
    {
        private JSONStorableString report;
        private JSONStorableStringChooser resolution, rate;
        private bool frameObjectRequested;
        private int alignFrameWhenTrackingRevision;
        private long reference; private Vector3 basePosition; private Quaternion baseRotation;
        private JSONStorableFloat quality;
        private volatile Worker worker;
        private volatile UdpClient sharedCameraSocket;
        protected void SetCameraSocket(UdpClient socket) { sharedCameraSocket=socket; }
        public static bool IsCameraPacket(byte[] data) { return data!=null && (data.Length>=4 && data[0]==80 &&
                ((data[1]==67 && ((data[2]==67 && data[3]==72) || (data[2]==80 && (data[3]==49 || data[3]==50 || data[3]==51 || data[3]==52)))) ||
                 (data[1]==66 && data[2]==65 && data[3]==49))); }
        protected bool RouteCameraDatagram(byte[] data,IPEndPoint from)
        {
            // Reserve only exact camera protocol families; legacy records retain their parser.
            if(!IsCameraPacket(data))return false;
            Worker active=worker;
            if(active!=null) active.Enqueue(data,new IPEndPoint(from.Address,from.Port));
            return true;
        }
        private Camera capture;
        private GameObject cameraObject;
        private RenderTexture target;
        private readonly Queue<Readback> requests = new Queue<Readback>();
        private bool running, cancelled;
        private double captureStarted, captureScheduled;
        private int captureTargetRevision;
        private PoseData capturePose;
        private GrabMarker captureGrabMarker;
        private bool awaitingCapture, captureRendered;
        private int pixelChecks, blackPixelChecks;
        private string captureError;
        private int width, height, fps, jpegQuality, submitted, gpuErrors, skipped;
        private readonly List<double> renderMs = new List<double>();
        private readonly List<double> copyMs = new List<double>();
        private readonly List<double> readbackMs = new List<double>();
        private readonly List<double> frameMs = new List<double>();
        private readonly List<double> baselineMs = new List<double>();
        private static double Now() { return Stopwatch.GetTimestamp() / (double)Stopwatch.Frequency; }
        private class Readback { public AsyncGPUReadbackRequest request; public double started; public int revision; public GrabMarker grab; }
        private class Frame { public byte[] pixels; public double started; public int revision; public GrabMarker grab; }

        private void InitializeCameraService()
        {
            resolution = new JSONStorableStringChooser("Resolution", new List<string> { "640x360", "960x540", "1280x720" }, "640x360", "Resolution");
            rate = new JSONStorableStringChooser("Stream FPS", new List<string> { "15", "30" }, "30", "Stream FPS");
            quality = new JSONStorableFloat("JPEG quality", 75f, 30f, 95f, true);

            CreateButton("Start camera service").button.onClick.AddListener(Begin);
            CreateButton("Frame target").button.onClick.AddListener(delegate { frameObjectRequested=true; });
            CreateButton("Stop").button.onClick.AddListener(delegate { cancelled = true; });
            CreateButton("Copy report").button.onClick.AddListener(delegate { GUIUtility.systemCopyBuffer = report.val; });
            report = new JSONStorableString("Report", "Start receiver on the other computer, then Run. UDP port 62701. Load the .cslist, not individual .cs files.");
            CreateTextField(report).height = 1100f;
            Begin(); // Listen immediately on plugin load; capture starts when a phone connects.
        }
        private void Add(string s) { report.val = (report.val.Length > 6000 ? "" : report.val) + s + "\n"; }
        private void Begin()
        {
            if (running) return;
            if (worker != null && !worker.done) { Add("Previous worker still finishing; retry shortly."); return; }
            width = int.Parse(resolution.val.Split('x')[0]); height = width * 9 / 16;
            fps = int.Parse(rate.val); jpegQuality = (int)quality.val;
            renderMs.Clear(); copyMs.Clear(); readbackMs.Clear(); frameMs.Clear(); baselineMs.Clear();
            captureError = null; awaitingCapture = captureRendered = false;
            pixelChecks = blackPixelChecks = 0;
            submitted = gpuErrors = skipped = 0; cancelled = false; running = true;
            report.val = "PhoneCamera v3\nUTC: " + DateTime.UtcNow.ToString("u") + "\n";
            Add("Unity " + Application.unityVersion + "; " + SystemInfo.operatingSystem);
            Add("CPU " + SystemInfo.processorType + "; GPU " + SystemInfo.graphicsDeviceName + "; " + SystemInfo.graphicsDeviceVersion);
            Add(width + "x" + height + " q" + jpegQuality + " requestedFPS=" + fps + "; vSync=" + QualitySettings.vSyncCount + "; targetFPS=" + Application.targetFrameRate);
            StartCoroutine(GuardedRun());
        }
        private IEnumerator GuardedRun()
        {
            IEnumerator steps = Run();
            while (!cancelled)
            {
                bool more = false;
                try { more = steps.MoveNext(); }
                catch (Exception e) { Add("FAIL: " + e); break; }
                if (!more) break;
                yield return steps.Current;
            }
            ReleaseCameraGrab();
            DisableCapture();
            if (worker != null) worker.Stop(); // no Join on Unity thread
            // Normal completion/Stop drains only this plugin's outstanding GPU requests.
            while (requests.Count > 0)
            {
                if (!requests.Peek().request.done) { yield return null; continue; }
                requests.Dequeue();
            }
            CleanupCamera();
            if (worker != null)
            {
                double deadline = Now() + 3;
                while (!worker.done && Now() < deadline) yield return null;
                Add(worker.Summary());
                if (!worker.done) Add("Worker still finishing; no blocking Join was used.");
            }
            Add(cancelled ? "STOPPED" : "Camera service stopped");
            running = false;
            SuperController.LogMessage(report.val);
            if (!cancelled && worker != null && worker.expired) Begin();
        }
        private IEnumerator Run()
        {
            if (!BitConverter.IsLittleEndian) throw new Exception("Camera protocol v1 requires a little-endian sender");
            if (!SystemInfo.supportsAsyncGPUReadback) throw new Exception("Async GPU readback unsupported on this graphics device");
            worker = new Worker(width, height, jpegQuality);
            worker.owner=this;
            worker.Start();
            Add("Listening for phone on UDP 62701...");
            double deadline = double.MaxValue;
            while (!worker.connected && Now() < deadline)
            {
                if (worker.error != null) throw new Exception(worker.error);
                yield return null;
            }
            if (!worker.connected) throw new Exception("No receiver. Check --host and inbound UDP 62701 on the VaM host (including the Ubuntu firewall for Wine/Proton).");
            width=worker.width; height=worker.height;
            Add("Client connected: "+width+"x"+height+", 30 fps, quality 75.");
            double start = Now(), last = start;
            Camera source = SuperController.singleton.lookCamera;
            if (source == null) throw new Exception("No lookCamera");
            cameraObject = new GameObject("PhoneControllerPhoneCamera");
            capture = cameraObject.AddComponent<Camera>(); capture.enabled = false;
            capture.CopyFrom(source); capture.enabled = false; capture.stereoTargetEye = StereoTargetEyeMask.None;
            capture.rect = new Rect(0f, 0f, 1f, 1f);
            capture.aspect = (float)width / height;
            capture.allowMSAA = false;
            capture.ResetProjectionMatrix();
            capture.ResetWorldToCameraMatrix();
            Add("Source camera=" + source.name + "; source mask=0x" + source.cullingMask.ToString("X8") + "; rendering path=" + source.actualRenderingPath);
            capture.depth = source.depth + 1;
            // VaM submits skin meshes with Graphics.DrawMesh in DAZSkinV2.LateUpdate.
            // Let Unity render this enabled camera normally, after all LateUpdates.
            // Camera.Render from the coroutine ran before those submissions.
            ConfigureUiMask();
            Camera.onPreCull += CapturePreCull;
            Camera.onPostRender += CapturePostRender;
            // Explicit RGBA readback below prevents BGRA channel confusion on D3D11.
            target = new RenderTexture(width, height, 24, RenderTextureFormat.ARGB32, RenderTextureReadWrite.sRGB);
            if (!target.Create()) throw new Exception("RenderTexture.Create failed");
            capture.transform.position = source.transform.position; capture.transform.rotation = source.transform.rotation;
            reference=0;
            alignFrameWhenTrackingRevision = 0;
            capture.targetTexture = target;
            Add("Capture mask=0x" + capture.cullingMask.ToString("X8") + "; viewport=" + capture.rect + "; clear=" + capture.clearFlags + "; MSAA=" + target.antiAliasing);
            if (capture.cullingMask == 0) throw new Exception("Capture mask is empty after UI exclusion");
            worker.linear = !target.sRGB && QualitySettings.activeColorSpace == ColorSpace.Linear;
            Add("Streaming. RT sRGB=" + target.sRGB + "; CPU gamma conversion=" + worker.linear + "; run=" + worker.run);
            start = Now(); last = start;
            double next = start;
            while (!worker.expired)
            {
                if (worker.error != null) throw new Exception(worker.error);
                if (captureError != null) throw new Exception(captureError);
                FinishCapture();
                while (requests.Count > 0 && requests.Peek().request.done)
                {
                    Readback item = requests.Dequeue();
                    if (item.request.hasError) { gpuErrors++; continue; }
                    readbackMs.Add((Now()-item.started)*1000);
                    double copyStart = Now();
                    byte[] pixels = worker.Rent();
                    if (pixels == null) { skipped++; continue; }
                    try
                    {
                        var data = item.request.GetData<byte>();
                        if (data.Length != pixels.Length) throw new Exception("Unexpected RGBA readback byte count " + data.Length);
                        data.CopyTo(pixels);
                        // First three frames only; included in copy/queue timing.
                        if (pixelChecks < 3) CheckPixels(pixels);
                        worker.Submit(new Frame { pixels = pixels, started = item.started, revision = item.revision, grab = item.grab });
                    }
                    catch { worker.Return(pixels); throw; }
                    copyMs.Add((Now()-copyStart)*1000);
                }
                double now = Now();
                if (now >= next)
                {
                    // Skip missed cadence slots rather than catching up with bursts.
                    int slots = 1 + (int)((now-next)*fps); skipped += slots-1; next += slots/(double)fps;
                    if (requests.Count >= 3 || awaitingCapture) skipped++;
                    else
                    {
                        capturePose = ApplyPose();
                        UpdateCameraGrab(worker, capturePose);
                        captureTargetRevision = targetRevision;
                        captureScheduled = Now(); captureRendered = false; awaitingCapture = true;
                        capture.enabled = true; // Keep enabled until the next frame, after final target resolve.
                    }
                }
                yield return null;
                now = Now(); frameMs.Add((now-last)*1000); last = now;
                if (frameMs.Count>300) { frameMs.Clear(); renderMs.Clear(); copyMs.Clear(); readbackMs.Clear(); }
            }
            FinishCapture();
            DisableCapture();
            if (captureError != null) throw new Exception(captureError);
            // Stop capture; drain GPU data without handing old frames to the encoder.
            Add("Stream observed FPS=" + Fps(frameMs) + "; frame intervals " + Stats(frameMs));
            Add("Capture requests=" + submitted + "; skipped cadence/pool slots=" + skipped + "; GPU errors=" + gpuErrors);
            Add("Camera callback interval (pre-cull to post-render; excludes final resolve/readback submission): " + Stats(renderMs));
            Add("Capture start to readback observed ready: " + Stats(readbackMs));
            Add("CPU copy + queue: " + Stats(copyMs));
            Add("Raw pixel checks=" + pixelChecks + "; all-black checks=" + blackPixelChecks);
            Add("GPU requests discarded at end of capture window=" + requests.Count);
            Add("Draining encoder/ACKs for 2s...");
            deadline = Now() + 2;
            while (Now() < deadline) yield return null;
            Add("Camera copies basic look-camera settings, not VaM postprocessing/scripts. Inspect receiver preview for fidelity.");
        }
        private PoseData ApplyPose()
        {
            if (targetAtom == null || SuperController.singleton.isLoading) return null;
            int requestedRevision = Interlocked.Exchange(ref worker.frameRequest, 0);
            if (requestedRevision == targetRevision) frameObjectRequested = true;

            PoseData pose = worker.Latest();
            if (pose != null && (pose.revision != targetRevision || Now() - pose.received > .25)) pose = null;

            Quaternion phoneRotation = pose == null
                ? Quaternion.identity
                : new Quaternion(-pose.qx, -pose.qy, pose.qz, pose.qw);
            bool alignHorizons = pose != null && pose.alignHorizons;
            if (frameObjectRequested || (alignHorizons && alignFrameWhenTrackingRevision == targetRevision))
            {
                frameObjectRequested = false;
                ResetGrabView();
                FrameContainingObject(alignHorizons, phoneRotation);
                // Give an immediate fit without tracking, then fit the aligned view when tracking returns.
                alignFrameWhenTrackingRevision = pose == null ? targetRevision : 0;
                worker.origin = null;
            }
            if (pose == null || pose.hold)
            {
                worker.origin = null;
                return null;
            }
            alignFrameWhenTrackingRevision = 0;

            PoseData origin = worker.origin;
            if (origin == null || origin.revision != pose.revision || reference != pose.reference || origin.scale != pose.scale
                || origin.alignHorizons != pose.alignHorizons)
            {
                ResetGrabView();
                reference = pose.reference;
                basePosition = capture.transform.position;
                baseRotation = pose.alignHorizons
                    ? AlignHeading(phoneRotation, capture.transform.rotation)
                    : capture.transform.rotation;
                worker.origin = pose;
                if (pose.alignHorizons) capture.transform.rotation = baseRotation * phoneRotation;
                return pose;
            }

            Vector3 delta = new Vector3(pose.x - origin.x, pose.y - origin.y, origin.z - pose.z);
            Quaternion relative = phoneRotation;
            if (!pose.alignHorizons)
            {
                Quaternion inverseOrigin = Quaternion.Inverse(new Quaternion(-origin.qx, -origin.qy, origin.qz, origin.qw));
                delta = inverseOrigin * delta;
                relative = inverseOrigin * phoneRotation;
            }
            Vector3 position = basePosition + baseRotation * delta * pose.scale;
            capture.transform.rotation = baseRotation * relative;
            if (!FiniteGrabPosition(position)) return null;
            capture.transform.position = position;
            return pose;
        }

        private static Quaternion AlignHeading(Quaternion phone, Quaternion view)
        {
            return Quaternion.AngleAxis(Heading(view) - Heading(phone), Vector3.up);
        }

        private static float Heading(Quaternion rotation)
        {
            Vector3 forward = rotation * Vector3.forward;
            if (forward.x * forward.x + forward.z * forward.z < 1e-6f)
            {
                // At the poles the forward vector has no heading. Use the screen's right axis.
                Vector3 right = rotation * Vector3.right;
                return Mathf.Atan2(-right.z, right.x) * Mathf.Rad2Deg;
            }
            return Mathf.Atan2(forward.x, forward.z) * Mathf.Rad2Deg;
        }

        private void FrameContainingObject(bool alignHorizons, Quaternion phoneRotation)
        {
            bool found=false; Bounds bounds=new Bounds();
            // Five control landmarks give a cheap rough silhouette; no skin/GPU readback.
            string[] landmarks={"headControl","lFootControl","rFootControl","lHandControl","rHandControl"};
            int landmarkCount=0;
            foreach(string id in landmarks) {
                FreeControllerV3 controller=targetAtom.GetStorableByID(id) as FreeControllerV3;
                if(controller==null) continue;
                Vector3 point=controller.control!=null ? controller.control.position : controller.transform.position;
                if(float.IsNaN(point.x)||float.IsInfinity(point.x)||float.IsNaN(point.y)||float.IsInfinity(point.y)||float.IsNaN(point.z)||float.IsInfinity(point.z))continue;
                if(!found){bounds=new Bounds(point,Vector3.zero);found=true;}else bounds.Encapsulate(point);
                landmarkCount++;
            }
            if(landmarkCount>0) {
                // Landmark centers omit the crown, fingers and soles. Pad each side
                // by 6% of the landmark diagonal, scaling with the person's size.
                float padding=Mathf.Max(.02f,bounds.size.magnitude*.06f);
                bounds.Expand(padding*2);
            }
            if(!found)
            foreach(Renderer renderer in targetAtom.GetComponentsInChildren<Renderer>()) {
                if(!renderer.enabled || !renderer.gameObject.activeInHierarchy || renderer is ParticleSystemRenderer || renderer is LineRenderer) continue;
                if((capture.cullingMask & (1<<renderer.gameObject.layer))==0)continue;
                Bounds rb=renderer.bounds;if(rb.size.magnitude>1000 || rb.size.sqrMagnitude<.000001f)continue;
                if(!found){bounds=rb;found=true;}else bounds.Encapsulate(rb);
            }
            if(!found) {
                Vector3 center = targetAtom.mainController != null ? targetAtom.mainController.transform.position : targetAtom.transform.position;
                bounds = new Bounds(center, Vector3.one);
                framingNote = "Approximate framing: target has no measurable geometry";
            } else framingNote = "";
            Transform front=targetAtom.mainController!=null ? targetAtom.mainController.transform : targetAtom.transform;
            Quaternion rotation=HasTargetRotation(targetAtom) ? Quaternion.LookRotation(-front.forward,front.up) : capture.transform.rotation;
            if (alignHorizons) rotation = AlignHeading(phoneRotation, rotation) * phoneRotation;
            float distance=FrameDistance(bounds,rotation,capture.fieldOfView,capture.aspect,capture.nearClipPlane);
            capture.transform.rotation=rotation;capture.transform.position=bounds.center-(rotation*Vector3.forward)*distance;
            Add("Framed target at 90% fit"+(landmarkCount>0 ? " using "+landmarkCount+" body landmarks" : " using renderer bounds")+"; current phone pose becomes the new reference.");
        }
        public static float FrameDistance(Bounds bounds,Quaternion rotation,float verticalFov,float aspect,float near)
        {
            float tanV=Mathf.Tan(verticalFov*Mathf.Deg2Rad*.5f)*.9f;
            float tanH=tanV*aspect, distance=near;
            Quaternion inverse=Quaternion.Inverse(rotation);
            for(int x=-1;x<=1;x+=2)for(int y=-1;y<=1;y+=2)for(int z=-1;z<=1;z+=2){
                Vector3 p=inverse*Vector3.Scale(bounds.extents,new Vector3(x,y,z));
                distance=Mathf.Max(distance,Mathf.Abs(p.x)/tanH-p.z,Mathf.Abs(p.y)/tanV-p.z,near-p.z+.01f);
            }
            return distance;
        }
        private class PoseData { public long reference; public int revision; public double received; public float x,y,z,qx,qy,qz,qw,scale; public bool hold, alignHorizons, grabEnabled, showGrabHints; public int frameCommand, grabGesture; public uint grabFrame; public float grabRadius = DefaultGrabRadius; public int grabMode = GrabPosition; }
        private static string Stats(List<double> source, string unit = "ms")
        {
            if (source.Count == 0) return "n=0";
            List<double> values = new List<double>(source); values.Sort(); double sum = 0;
            foreach (double v in values) sum += v;
            return "n=" + values.Count + " avg=" + (sum/values.Count).ToString("F3") + " p95=" + values[(int)Math.Ceiling(values.Count*.95)-1].ToString("F3") + " max=" + values[values.Count-1].ToString("F3") + " " + unit;
        }
        private static string Fps(List<double> values)
        { double sum=0; foreach (double v in values) sum+=v; return sum>0 ? (1000*values.Count/sum).ToString("F1") : "n/a"; }
        private void ConfigureUiMask()
        {
            int uiMask = LayerMask.GetMask("UI");
            // Discover VaM-specific UI layers rather than guessing serialized layer numbers.
            // Default is never excluded: it also contains scene geometry.
            SuperController sc = SuperController.singleton;
            Transform[] roots = { sc.worldUI, sc.topWorldUI, sc.mainMenuUI, sc.sceneControlUI };
            int defaultLayerGraphics = 0;
            foreach (Transform root in roots)
            {
                if (root == null) continue;
                foreach (CanvasRenderer graphic in root.GetComponentsInChildren<CanvasRenderer>(true))
                {
                    int layer = graphic.gameObject.layer;
                    if (layer == 0) defaultLayerGraphics++;
                    else uiMask |= 1 << layer;
                }
            }
            capture.cullingMask &= ~uiMask;
            string excluded = "";
            for (int layer = 0; layer < 32; layer++)
                if ((uiMask & (1 << layer)) != 0) excluded += " " + layer + ":" + LayerMask.LayerToName(layer);
            Add("Stream camera excludes UI layers:" + excluded);
            if (defaultLayerGraphics > 0)
                Add("UI diagnostics: " + defaultLayerGraphics + " graphics on shared Default layer; report if any menu remains visible.");
        }
        private void CapturePreCull(Camera camera)
        {
            if (camera != capture) return;
            captureStarted = Now();
            Worker active = worker;
            captureGrabMarker = active != null && active.grabProtocol
                ? CaptureGrabMarker(active, capturePose) : null;
        }
        private void CapturePostRender(Camera camera)
        {
            if (camera != capture || !awaitingCapture) return;
            // This event precedes final image effects/target resolve. Do not request
            // readback or disable the camera here (v2 could read an unfinished target).
            captureRendered = true;
            renderMs.Add((Now()-captureStarted)*1000);
        }
        private void FinishCapture()
        {
            if (!awaitingCapture) return;
            if (!captureRendered)
            {
                if (Now()-captureScheduled > 2) throw new Exception("Capture camera did not render within two seconds");
                return;
            }
            // Coroutine resumes on the next frame, after the previous camera's full
            // render/resolve. Queue readback before reusing this target for another frame.
            capture.enabled = false;
            awaitingCapture = false;
            Readback item = new Readback(); item.started = captureStarted; item.revision = captureTargetRevision; item.grab = captureGrabMarker;
            item.request = AsyncGPUReadback.Request(target, 0, TextureFormat.RGBA32);
            requests.Enqueue(item); submitted++;
        }
        private void CheckPixels(byte[] pixels)
        {
            int min = 255, max = 0; long sum = 0;
            for (int i = 0; i < pixels.Length; i += 4)
            {
                int value = Math.Max(pixels[i], Math.Max(pixels[i+1], pixels[i+2]));
                min = Math.Min(min, value); max = Math.Max(max, value); sum += value;
            }
            pixelChecks++;
            if (max == 0) blackPixelChecks++;
            Add("Raw RGBA frame " + pixelChecks + " RGB-max min/mean/max=" + min + "/" + (sum/(double)(pixels.Length/4)).ToString("F2") + "/" + max
                + (max == 0 ? " BLACK BEFORE JPEG ENCODING" : ""));
            if (max == 0 && pixelChecks == 1) CheckTargetSynchronously();
        }
        private void CheckTargetSynchronously()
        {
            // Only on a black first frame: distinguish async readback failure from
            // a black render target. This one-off check may stall and affects timing.
            RenderTexture previous = RenderTexture.active;
            Texture2D probe = null;
            try
            {
                probe = new Texture2D(width, height, TextureFormat.RGB24, false);
                RenderTexture.active = target;
                probe.ReadPixels(new Rect(0, 0, width, height), 0, 0, false);
                int max = 0;
                foreach (Color32 p in probe.GetPixels32()) max = Math.Max(max, Math.Max(p.r, Math.Max(p.g, p.b)));
                Add("One-off synchronous target probe RGB max=" + max + "; included in timings. "
                    + (max == 0 ? "Target also black: investigate camera/scene rendering." : "Target has color: async readback or frame timing differs."));
            }
            finally
            {
                RenderTexture.active = previous;
                if (probe != null) UnityEngine.Object.Destroy(probe);
            }
        }
        private void DisableCapture()
        {
            Camera.onPreCull -= CapturePreCull;
            Camera.onPostRender -= CapturePostRender;
            if (capture != null) capture.enabled = false;
        }
        private void CleanupCamera()
        {
            DisableCapture();
            if (capture != null) capture.targetTexture = null;
            if (target != null) { target.Release(); UnityEngine.Object.Destroy(target); target=null; }
            if (cameraObject != null) { UnityEngine.Object.Destroy(cameraObject); cameraObject=null; capture=null; }
        }
        protected void StopCameraService()
        {
            cancelled=true; ReleaseCameraGrab(); DisableCapture(); StopAllCoroutines(); if (worker != null) worker.Stop();
            // Avoid freeing a texture still referenced by the GPU, without blocking reload.
            // Exceptional mid-readback removal retains one target until scene/application cleanup.
            if ((requests.Count > 0 || awaitingCapture) && target != null)
            {
                RenderTexture retired=target; target=null;
                CameraRetirement retirement=new GameObject("Phone camera GPU cleanup").AddComponent<CameraRetirement>();
                retirement.texture=retired;
                retirement.request=AsyncGPUReadback.Request(retired,0);
            }
            CleanupCamera();
        }
        private class Worker
        {
            public volatile bool connected, done, linear, expired;
            private double heartbeat; private PoseData latest; public PoseData origin;
            public PoseData Latest() { lock(gate) return latest; }
            public volatile string error;
            public readonly uint run = (uint)new System.Random().Next(1, int.MaxValue);
            private volatile bool stop; private long lastPose=-1;
            public int frameRequest; private int lastFrameRequest;
            private volatile int protocolVersion = 2;
            public bool grabProtocol { get { return protocolVersion >= 3; } }
            public int lastGrabGesture; // Unity main thread only.
            private const int GrabHistorySize = 64;
            private struct GrabOfferFrame
            {
                public uint id;
                public int revision;
                public double started;
                public GrabMarker marker;
            }
            private readonly GrabOfferFrame[] grabOffers = new GrabOfferFrame[GrabHistorySize];

            public GrabMarker FindGrabOffer(uint id, int revision)
            {
                lock (gate)
                {
                    GrabOfferFrame offer = grabOffers[id % GrabHistorySize];
                    return offer.id == id && offer.revision == revision
                        && Now() - offer.started <= GrabOfferTimeout ? offer.marker : null;
                }
            }
            public int width, height; private readonly int quality;
            private readonly object gate = new object();
            private readonly Stack<byte[]> pool = new Stack<byte[]>();
            private Frame pending;
            private UdpClient socket;
            public PhoneController owner;
            private class Datagram { public byte[] data; public IPEndPoint from; public double received; }
            private readonly Queue<Datagram> inbox=new Queue<Datagram>();
            public void Enqueue(byte[] data,IPEndPoint from) { lock(gate) { if(inbox.Count>=128)inbox.Dequeue(); inbox.Enqueue(new Datagram { data=data, from=from, received=Now() }); } }
            private IPEndPoint peer;
            private int encoded, sent, packets, replaced, acked;
            private long bytes;
            private readonly List<double> jpegSizes = new List<double>();
            private readonly List<double> encodeTimes = new List<double>(), sendTimes = new List<double>(), ageTimes = new List<double>(), ackTimes = new List<double>();
            private readonly Dictionary<uint,double> awaiting = new Dictionary<uint,double>();
            public Worker(int w,int h,int q) { width=w; height=h; quality=q;  }
            public void Start() { Thread t=new Thread(Loop); t.IsBackground=true; t.Priority=System.Threading.ThreadPriority.BelowNormal; t.Start(); }
            public byte[] Rent() { lock(gate) return pool.Count>0 ? pool.Pop() : null; }
            public void Return(byte[] data) { lock(gate) pool.Push(data); }
            public void Submit(Frame frame) { lock(gate) { if(pending!=null) { pool.Push(pending.pixels); replaced++; } pending=frame; } }
            public void Stop() { stop=true; } // all socket I/O nonblocking; no joins or Unity APIs
            private void Poll()
            {
                for(int i=0;i<128;i++)
                {
                    Datagram packet; lock(gate) { if(inbox.Count==0)break; packet=inbox.Dequeue(); }
                    IPEndPoint from=packet.from; byte[] data=packet.data;
                    string greeting = data.Length == 13 ? Encoding.ASCII.GetString(data, 0, 9) : "";
                    if (greeting == "PCCHELLO2" || greeting == "PCCHELLO3" || greeting == "PCCHELLO4")
                    {
                        int requestedVersion = greeting[8] - '0';
                        int w=BitConverter.ToUInt16(data,9), h=BitConverter.ToUInt16(data,11);
                        if(w<128 || h<128 || w>2048 || h>2048 || w*h>1000000) continue;
                        if(peer==null) {
                            width=w; height=h;
                            lock(gate) for(int bufferIndex=0;bufferIndex<3;bufferIndex++) pool.Push(new byte[w*h*4]);
                            peer=from;
                            protocolVersion = requestedVersion;
                            connected=true;
                        }
                        if(peer.Equals(from) && width==w && height==h && requestedVersion==protocolVersion) {
                            heartbeat=Now();
                            byte[] accept=new byte[12]; accept[0]=80;accept[1]=67;accept[2]=65;accept[3]=(byte)('0' + protocolVersion);
                            Put(accept,4,BitConverter.GetBytes(run)); Put(accept,8,BitConverter.GetBytes((ushort)width));Put(accept,10,BitConverter.GetBytes((ushort)height));
                            socket.Send(accept,accept.Length,peer);
                        }
                        continue;
                    }
                    if(peer==null || !from.Equals(peer)) continue;
                    int poseSize = protocolVersion == 4 ? 84 : grabProtocol ? 80 : 72;
                    if(data.Length==poseSize && data[0]==80 && data[1]==67 && data[2]==80 && data[3]=='0'+protocolVersion && BitConverter.ToUInt32(data,4)==run)
                    {
                        if(BitConverter.ToInt32(data,64)!=owner.targetInstance || BitConverter.ToInt32(data,68)!=owner.targetRevision) continue;
                        PoseData p=new PoseData(); p.revision=BitConverter.ToInt32(data,68); p.reference=BitConverter.ToInt64(data,16); p.received=packet.received;
                        float[] v=new float[8]; bool valid=true;
                        for(int n=0;n<8;n++) { v[n]=BitConverter.ToSingle(data,24+n*4); if(float.IsNaN(v[n])||float.IsInfinity(v[n])) valid=false; }
                        float norm=v[3]*v[3]+v[4]*v[4]+v[5]*v[5]+v[6]*v[6];
                        long seq=BitConverter.ToInt64(data,8);
                        if (valid && norm > .9f && norm < 1.1f && v[7] >= .02f && v[7] <= 25f && seq > lastPose)
                        {
                            lastPose = seq;
                            p.frameCommand = BitConverter.ToInt32(data, 60);
                            p.x = v[0]; p.y = v[1]; p.z = v[2];
                            p.qx = v[3]; p.qy = v[4]; p.qz = v[5]; p.qw = v[6]; p.scale = v[7];
                            p.hold = data[56] != 0;
                            p.alignHorizons = data[57] == 1;
                            if (grabProtocol)
                            {
                                p.grabEnabled = data[58] == 1;
                                p.grabGesture = BitConverter.ToInt32(data, 72);
                                p.grabFrame = BitConverter.ToUInt32(data, 76);
                                if (protocolVersion == 4)
                                {
                                    p.showGrabHints = (data[59] & 1) != 0;
                                    int mode = data[59] & GrabAxes;
                                    if (mode != 0) p.grabMode = mode;
                                    if (mode == GrabPosition && (data[59] & GrabRotationOff) != 0)
                                        p.grabMode |= GrabRotationOff;
                                    if (mode == GrabAxes && (data[59] & GrabHeadOnly) != 0)
                                        p.grabMode |= GrabHeadOnly;
                                    float radius = BitConverter.ToSingle(data, 80);
                                    if (radius >= .01f && radius <= .5f) p.grabRadius = radius;
                                }
                            }
                            lock (gate) latest = p;
                            if (p.frameCommand != lastFrameRequest)
                            {
                                lastFrameRequest = p.frameCommand;
                                Interlocked.Exchange(ref frameRequest, p.revision);
                            }
                            heartbeat = Now();
                        }
                        continue;
                    }
                    if(data.Length==12 && data[0]==80 && data[1]==66 && data[2]==65 && data[3]==49 && BitConverter.ToUInt32(data,4)==run)
                    {
                        uint seq=BitConverter.ToUInt32(data,8); double started;
                        lock(gate) if(awaiting.TryGetValue(seq,out started)) { ackTimes.Add((Now()-started)*1000); awaiting.Remove(seq); acked++; }
                    }
                }
            }
            private void Loop()
            {
                try
                {
                    while(!stop && owner.sharedCameraSocket==null) Thread.Sleep(5);
                    if(stop)return; socket=owner.sharedCameraSocket;
                    JpegEncoder encoder=new JpegEncoder();
                    byte[] gamma=new byte[256]; for(int i=0;i<256;i++) { double v=i/255.0; gamma[i]=(byte)Math.Min(255,Math.Round(255*(v<=.0031308 ? 12.92*v : 1.055*Math.Pow(v,1/2.4)-.055))); }
                    while(!stop)
                    {
                        Poll(); if(connected && Now()-heartbeat>2) { expired=true; break; } Frame frame=null;
                        lock(gate) { frame=pending; pending=null; }
                        if(frame==null) { Thread.Sleep(1); continue; }
                        try
                        {
                            double began=Now(); int count=encoder.Encode(frame.pixels,width,height,quality,true,linear ? gamma : null);
                            double encodedAt=Now();
                            if(count<=0) throw new Exception("JPEG encoder returned no bytes");
                            uint seq;
                            lock(gate) { seq=(uint)encoded++; jpegSizes.Add(count); encodeTimes.Add((encodedAt-began)*1000); ageTimes.Add((encodedAt-frame.started)*1000); awaiting[seq]=frame.started; if(awaiting.Count>128) awaiting.Clear(); if(encodeTimes.Count>300) { encodeTimes.Clear(); jpegSizes.Clear(); ageTimes.Clear(); sendTimes.Clear(); ackTimes.Clear(); } }
                            if(frame.revision != owner.targetRevision) continue;
                            int headerSize = grabProtocol ? 48 : 36;
                            int chunk = 1200 - headerSize;
                            GrabMarker marker = grabProtocol ? frame.grab : null;
                            int hintCount = protocolVersion == 4 && marker != null && marker.hints != null
                                ? marker.hints.Length : 0;
                            int hintBytes = hintCount * 4;
                            int fragments = (count + hintBytes + chunk - 1) / chunk;
                            byte[] packet=new byte[1200]; packet[0]=80;packet[1]=67;packet[2]=70;
                            packet[3]=(byte)('0' + protocolVersion);
                            Put(packet,4,BitConverter.GetBytes(run)); Put(packet,8,BitConverter.GetBytes(seq));
                            Put(packet,14,BitConverter.GetBytes((ushort)fragments)); Put(packet,16,BitConverter.GetBytes(count)); Put(packet,20,BitConverter.GetBytes(frame.started));
                            Put(packet,28,BitConverter.GetBytes(owner.targetInstance)); Put(packet,32,BitConverter.GetBytes(frame.revision));
                            if (marker != null)
                            {
                                if (marker.state == GrabOffer)
                                {
                                    lock (gate) grabOffers[seq % GrabHistorySize] = new GrabOfferFrame
                                    {
                                        id = seq, revision = frame.revision,
                                        started = frame.started, marker = marker
                                    };
                                }
                                Put(packet, 36, BitConverter.GetBytes(marker.x));
                                Put(packet, 38, BitConverter.GetBytes(marker.y));
                                packet[40] = marker.state;
                                packet[41] = (byte)hintCount;
                                Put(packet, 44, BitConverter.GetBytes(marker.gesture));
                                for (int i = 0; i < hintCount; i++)
                                {
                                    Put(packet, headerSize + i * 4, BitConverter.GetBytes(marker.hints[i].x));
                                    Put(packet, headerSize + i * 4 + 2, BitConverter.GetBytes(marker.hints[i].y));
                                }
                            }
                            for(int n=0;n<fragments && !stop;n++)
                            {
                                int payloadOffset = headerSize + (n == 0 ? hintBytes : 0);
                                int jpegOffset = n == 0 ? 0 : n * chunk - hintBytes;
                                int length = Math.Min(1200 - payloadOffset, count - jpegOffset);
                                Put(packet,12,BitConverter.GetBytes((ushort)n));
                                Array.Copy(encoder.OutputBuffer, jpegOffset, packet, payloadOffset, length);
                                socket.Send(packet, payloadOffset + length, peer);
                                lock(gate) packets++;
                                if(n%32==31) Poll();
                            }
                            if(!stop) lock(gate) { sent++; bytes+=count; sendTimes.Add((Now()-encodedAt)*1000); }
                        }
                        finally { Return(frame.pixels); }
                    }
                    if(peer!=null)
                    {
                        // Redundant summary datagrams; never assumed reliable by receiver.
                        byte[] end=new byte[12]; end[0]=80;end[1]=66;end[2]=69;end[3]=49;
                        Put(end,4,BitConverter.GetBytes(run)); Put(end,8,BitConverter.GetBytes(encoded));
                        for(int i=0;i<3;i++) socket.Send(end,end.Length,peer);
                    }
                }
                catch(Exception e) { error=e.ToString(); }
                finally { done=true; } // Shared socket belongs to the controller receiver.
            }
            private static void Put(byte[] dest,int offset,byte[] data) { Array.Copy(data,0,dest,offset,data.Length); }
            public string Summary()
            {
                lock(gate) return "Worker run="+run+" encoded="+encoded+" fully sent="+sent+" datagrams="+packets+" pending replacements="+replaced+" decoded ACKs="+acked
                    +"\nJPEG bytes total="+bytes+"; mean per fully sent frame="+(sent>0 ? bytes/sent : 0)
                    +"\nJPEG size: "+Stats(jpegSizes,"bytes")+"\nEncode: "+Stats(encodeTimes)+"\nFragment/send: "+Stats(sendTimes)+"\nCapture to encoded: "+Stats(ageTimes)
                    +"\nCapture to decoded ACK (ROUND TRIP, includes receiver decode/queue): "+Stats(ackTimes)
                    +(error!=null ? "\nFAIL worker: "+error : "");
            }
        }
    }
    // Independent lifetime: plugin removal must not destroy a GPU-referenced target.
    public class CameraRetirement : MonoBehaviour
    {
        public RenderTexture texture;
        public AsyncGPUReadbackRequest request;
        private void Update()
        {
            if(!request.done) return;
            texture.Release(); UnityEngine.Object.Destroy(texture);
            UnityEngine.Object.Destroy(gameObject);
        }
    }

}
