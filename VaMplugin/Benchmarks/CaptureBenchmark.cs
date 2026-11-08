// Standalone VaM diagnostic: add this .cs file directly as a plugin.
using System;
using System.Collections;
using System.Collections.Generic;
using System.Diagnostics;
using UnityEngine;

namespace MVRPlugin
{
    public class CaptureBenchmark : MVRScript
    {
        private JSONStorableString report;
        private bool running, cancelled;
        private string stage = "idle";
        private GameObject cameraObject;
        private Camera capture;
        private RenderTexture target;
        private Texture2D texture;
        public override void Init()
        {
            report = new JSONStorableString("Report", "Ready. Run only one benchmark at a time.");
            CreateTextField(report).height = 950f;
            CreateButton("Run benchmark").button.onClick.AddListener(Begin);
            CreateButton("Stop").button.onClick.AddListener(delegate { cancelled = true; });
            CreateButton("Copy report").button.onClick.AddListener(delegate { GUIUtility.systemCopyBuffer = report.val; });
        }
        private void Add(string text) { report.val += text + "\n"; }
        private void Begin()
        {
            if (running) return;
            running = true;
            cancelled = false;
            report.val = "CaptureBenchmark v1\n";
            Add("UTC: " + DateTime.UtcNow.ToString("u"));
            Add("Unity: " + Application.unityVersion + "; OS: " + SystemInfo.operatingSystem);
            Add("CPU: " + SystemInfo.processorType + "; GPU: " + SystemInfo.graphicsDeviceName);
            Add("Graphics: " + SystemInfo.graphicsDeviceVersion);
            Add("Screen: " + Screen.width + "x" + Screen.height + "; vSync: " + QualitySettings.vSyncCount + "; targetFPS: " + Application.targetFrameRate);
            StartCoroutine(Run());
        }
        // Catch failures from the iterator without putting yield inside a try/catch.
        private IEnumerator Run()
        {
            IEnumerator steps = Bench();
            while (!cancelled)
            {
                bool more = false;
                try { more = steps.MoveNext(); }
                catch (Exception e) { Add("FAIL at " + stage + ": " + e.ToString()); break; }
                if (!more) break;
                yield return steps.Current;
            }
            IDisposable disposable = steps as IDisposable;
            if (disposable != null) disposable.Dispose();
            Cleanup();
            Add(cancelled ? "STOPPED" : "FINISHED (check above for FAIL)");
            running = false;
            SuperController.LogMessage(report.val);
        }
        private string Stats(List<double> values)
        {
            double sum = 0;
            foreach (double v in values) sum += v;
            values.Sort();
            int p95 = Math.Max(0, (int)Math.Ceiling(values.Count * 0.95) - 1);
            return "avg=" + (sum / values.Count).ToString("F3") + "ms p95=" + values[p95].ToString("F3") + "ms max=" + values[values.Count - 1].ToString("F3") + "ms";
        }
        private void OnDestroy() { cancelled = true; StopAllCoroutines(); Cleanup(); }
        private IEnumerator Bench()
        {
            Add("2 warmups + 20 samples/resolution. Main-thread wall times; GPU readback can include GPU waits. Camera has no copied postprocessing components.");
            int[] widths = { 640, 960, 1280 };
            foreach (int width in widths)
            {
                int height = width * 9 / 16;
                stage = "create camera and render target";
                Camera source = SuperController.singleton.lookCamera;
                if (source == null) throw new Exception("No lookCamera available");
                cameraObject = new GameObject("PhoneControllerCaptureBenchmark");
                capture = cameraObject.AddComponent<Camera>();
                capture.enabled = false;
                capture.CopyFrom(source);
                capture.enabled = false;
                capture.stereoTargetEye = StereoTargetEyeMask.None;
                capture.aspect = (float)width / height;
                target = new RenderTexture(width, height, 24, RenderTextureFormat.ARGB32);
                target.Create();
                capture.targetTexture = target;
                texture = new Texture2D(width, height, TextureFormat.RGB24, false);
                List<double> renderTimes = new List<double>();
                List<double> readTimes = new List<double>();
                List<double> totalTimes = new List<double>();
                for (int i = -2; i < 20; i++)
                {
                    capture.transform.position = source.transform.position;
                    capture.transform.rotation = source.transform.rotation;
                    RenderTexture previous = RenderTexture.active;
                    double renderMs, readMs;
                    try
                    {
                        stage = "Camera.Render " + width;
                        Stopwatch sw = Stopwatch.StartNew();
                        capture.Render();
                        sw.Stop(); renderMs = sw.Elapsed.TotalMilliseconds;
                        stage = "ReadPixels " + width;
                        RenderTexture.active = target;
                        sw.Reset(); sw.Start();
                        texture.ReadPixels(new Rect(0, 0, width, height), 0, 0, false);
                        sw.Stop(); readMs = sw.Elapsed.TotalMilliseconds;
                    }
                    finally { RenderTexture.active = previous; }
                    if (i >= 0) { renderTimes.Add(renderMs); readTimes.Add(readMs); totalTimes.Add(renderMs + readMs); }
                    yield return null;
                }
                Color32[] pixels = texture.GetPixels32();
                int min = 255, max = 0;
                long brightness = 0;
                foreach (Color32 p in pixels)
                {
                    int b = (p.r + p.g + p.b) / 3;
                    min = Math.Min(min, b); max = Math.Max(max, b); brightness += b;
                }
                Add(width + "x" + height + ": render " + Stats(renderTimes) + "; readback " + Stats(readTimes) + "; combined " + Stats(totalTimes));
                Add("Pixel brightness min/mean/max: " + min + "/" + (brightness / (double)pixels.Length).ToString("F1") + "/" + max + (max == min ? " (UNIFORM IMAGE: inspect scene/camera)" : ""));
                Cleanup();
                yield return null;
            }
            Add("Capture only: excludes JPEG/network. ReadPixels updates CPU data; no unnecessary GPU re-upload via Apply.");
        }
        private void Cleanup()
        {
            if (capture != null) capture.targetTexture = null;
            if (target != null) { target.Release(); UnityEngine.Object.Destroy(target); target = null; }
            if (texture != null) { UnityEngine.Object.Destroy(texture); texture = null; }
            if (cameraObject != null) { UnityEngine.Object.Destroy(cameraObject); cameraObject = null; capture = null; }
        }

    }
}
