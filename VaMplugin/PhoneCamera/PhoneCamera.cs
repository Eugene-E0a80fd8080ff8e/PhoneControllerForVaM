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
    public class PhoneCamera : MVRScript
    {
        private JSONStorableString report;
        private JSONStorableStringChooser resolution, rate;
        private bool frameObjectRequested;
        private long reference; private Vector3 basePosition; private Quaternion baseRotation;
        private JSONStorableFloat quality;
        private Worker worker;
        private Camera capture;
        private GameObject cameraObject;
        private RenderTexture target;
        private readonly Queue<Readback> requests = new Queue<Readback>();
        private bool running, cancelled;
        private double captureStarted, captureScheduled;
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
        private class Readback { public AsyncGPUReadbackRequest request; public double started; }
        private class Frame { public byte[] pixels; public double started; }

        public override void Init()
        {
            resolution = new JSONStorableStringChooser("Resolution", new List<string> { "640x360", "960x540", "1280x720" }, "640x360", "Resolution");
            rate = new JSONStorableStringChooser("Stream FPS", new List<string> { "15", "30" }, "30", "Stream FPS");
            quality = new JSONStorableFloat("JPEG quality", 75f, 30f, 95f, true);

            CreateButton("Start camera service").button.onClick.AddListener(Begin);
            CreateButton("Frame containing object").button.onClick.AddListener(delegate { frameObjectRequested=true; });
            CreateButton("Stop").button.onClick.AddListener(delegate { cancelled = true; });
            CreateButton("Copy report").button.onClick.AddListener(delegate { GUIUtility.systemCopyBuffer = report.val; });
            report = new JSONStorableString("Report", "Start receiver on the other computer, then Run. UDP port 62711. Load the .cslist, not individual .cs files.");
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
            worker.Start();
            Add("Listening for phone on UDP 62711...");
            double deadline = double.MaxValue;
            while (!worker.connected && Now() < deadline)
            {
                if (worker.error != null) throw new Exception(worker.error);
                yield return null;
            }
            if (!worker.connected) throw new Exception("No receiver. Check --host and inbound UDP 62711 on the VaM host (including the Ubuntu firewall for Wine/Proton).");
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
                        worker.Submit(new Frame { pixels = pixels, started = item.started });
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
                        ApplyPose();
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
        private void ApplyPose()
        {
            if (frameObjectRequested || Interlocked.Exchange(ref worker.frameRequest,0)!=0) {
                frameObjectRequested=false;
                FrameContainingObject(); reference=0;
            }
            PoseData pose=worker.Latest();
            if(pose==null || Now()-pose.received>.25 || pose.hold) { reference=0; return; }
            if(reference!=pose.reference || (worker.origin!=null && worker.origin.scale!=pose.scale)) { reference=pose.reference; basePosition=capture.transform.position; baseRotation=capture.transform.rotation; worker.origin=pose; return; }
            PoseData origin=worker.origin;
            Quaternion oq=new Quaternion(origin.qx,origin.qy,origin.qz,origin.qw);
            Quaternion q=new Quaternion(pose.qx,pose.qy,pose.qz,pose.qw);
            Vector3 delta=Quaternion.Inverse(oq)*(new Vector3(pose.x-origin.x,pose.y-origin.y,pose.z-origin.z));
            Quaternion relative=Quaternion.Inverse(oq)*q;
            capture.transform.position=basePosition+baseRotation*new Vector3(delta.x,delta.y,-delta.z)*pose.scale;
            capture.transform.rotation=baseRotation*new Quaternion(-relative.x,-relative.y,relative.z,relative.w);
        }
        private void FrameContainingObject()
        {
            if(capture==null || containingAtom==null) { Add("Start camera service before framing an object."); return; }
            bool found=false; Bounds bounds=new Bounds();
            // Five control landmarks give a cheap rough silhouette; no skin/GPU readback.
            string[] landmarks={"headControl","lFootControl","rFootControl","lHandControl","rHandControl"};
            int landmarkCount=0;
            foreach(string id in landmarks) {
                FreeControllerV3 controller=containingAtom.GetStorableByID(id) as FreeControllerV3;
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
            foreach(Renderer renderer in containingAtom.GetComponentsInChildren<Renderer>()) {
                if(!renderer.enabled || !renderer.gameObject.activeInHierarchy || renderer is ParticleSystemRenderer || renderer is LineRenderer) continue;
                if((capture.cullingMask & (1<<renderer.gameObject.layer))==0)continue;
                Bounds rb=renderer.bounds;if(rb.size.magnitude>1000 || rb.size.sqrMagnitude<.000001f)continue;
                if(!found){bounds=rb;found=true;}else bounds.Encapsulate(rb);
            }
            if(!found){Add("Frame object: containing atom has no visible geometry. Add PhoneCamera to the object you want to frame.");return;}
            Transform front=containingAtom.mainController!=null ? containingAtom.mainController.transform : containingAtom.transform;
            Quaternion rotation=Quaternion.LookRotation(-front.forward,front.up);
            float distance=FrameDistance(bounds,rotation,capture.fieldOfView,capture.aspect,capture.nearClipPlane);
            capture.transform.rotation=rotation;capture.transform.position=bounds.center-(rotation*Vector3.forward)*distance;
            Add("Framed containing object at 90% fit"+(landmarkCount>0 ? " using "+landmarkCount+" body landmarks" : " using renderer bounds")+"; current phone pose becomes the new reference.");
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
        private class PoseData { public long reference; public double received; public float x,y,z,qx,qy,qz,qw,scale; public bool hold; }
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
            Readback item = new Readback(); item.started = captureStarted;
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
        private void OnDestroy()
        {
            cancelled=true; DisableCapture(); StopAllCoroutines(); if (worker != null) worker.Stop();
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
            public int width, height; private readonly int quality;
            private readonly object gate = new object();
            private readonly Stack<byte[]> pool = new Stack<byte[]>();
            private Frame pending;
            private UdpClient socket;
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
                for(int i=0;i<128 && socket.Available>0;i++)
                {
                    IPEndPoint from=new IPEndPoint(IPAddress.Any,0);
                    byte[] data=socket.Receive(ref from);
                    if(data.Length==13 && Encoding.ASCII.GetString(data,0,9)=="PCCHELLO2")
                    {
                        int w=BitConverter.ToUInt16(data,9), h=BitConverter.ToUInt16(data,11);
                        if(w<128 || h<128 || w>1024 || h>1024 || w*h>300000) continue;
                        if(peer==null) {
                            width=w; height=h;
                            lock(gate) for(int bufferIndex=0;bufferIndex<3;bufferIndex++) pool.Push(new byte[w*h*4]);
                            peer=from;
                            connected=true;
                        }
                        if(peer.Equals(from) && width==w && height==h) {
                            heartbeat=Now();
                            byte[] accept=new byte[12]; accept[0]=80;accept[1]=67;accept[2]=65;accept[3]=50;
                            Put(accept,4,BitConverter.GetBytes(run)); Put(accept,8,BitConverter.GetBytes((ushort)width));Put(accept,10,BitConverter.GetBytes((ushort)height));
                            socket.Send(accept,accept.Length,peer);
                        }
                        continue;
                    }
                    if(peer==null || !from.Equals(peer)) continue;
                    if(data.Length==64 && data[0]==80 && data[1]==67 && data[2]==80 && data[3]==49 && BitConverter.ToUInt32(data,4)==run)
                    {
                        PoseData p=new PoseData(); p.reference=BitConverter.ToInt64(data,16); p.received=Now();
                        float[] v=new float[8]; bool valid=true;
                        for(int n=0;n<8;n++) { v[n]=BitConverter.ToSingle(data,24+n*4); if(float.IsNaN(v[n])||float.IsInfinity(v[n])) valid=false; }
                        float norm=v[3]*v[3]+v[4]*v[4]+v[5]*v[5]+v[6]*v[6];
                        long seq=BitConverter.ToInt64(data,8);
                        if(valid && norm>.9f && norm<1.1f && v[7]>.01f && v[7]<=10 && seq>lastPose) { lastPose=seq; int command=BitConverter.ToInt32(data,60); if(command!=lastFrameRequest) { lastFrameRequest=command; Interlocked.Exchange(ref frameRequest,1); } p.x=v[0];p.y=v[1];p.z=v[2];p.qx=v[3];p.qy=v[4];p.qz=v[5];p.qw=v[6];p.scale=v[7];p.hold=data[56]!=0; lock(gate) latest=p; heartbeat=Now(); }
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
                    socket=new UdpClient(new IPEndPoint(IPAddress.Any,62711)); socket.Client.Blocking=false;
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
                            const int chunk=1172;
                            int fragments=(count+chunk-1)/chunk;
                            byte[] packet=new byte[1200]; packet[0]=80;packet[1]=67;packet[2]=70;packet[3]=49;
                            Put(packet,4,BitConverter.GetBytes(run)); Put(packet,8,BitConverter.GetBytes(seq));
                            Put(packet,14,BitConverter.GetBytes((ushort)fragments)); Put(packet,16,BitConverter.GetBytes(count)); Put(packet,20,BitConverter.GetBytes(frame.started));
                            for(int n=0;n<fragments && !stop;n++)
                            {
                                int length=Math.Min(chunk,count-n*chunk);
                                Put(packet,12,BitConverter.GetBytes((ushort)n)); Array.Copy(encoder.OutputBuffer,n*chunk,packet,28,length);
                                socket.Send(packet,28+length,peer);
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
                finally { if(socket!=null) socket.Close(); done=true; }
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
