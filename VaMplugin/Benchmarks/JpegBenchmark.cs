// Standalone VaM diagnostic: add this .cs file directly as a plugin.
using System;
using System.Collections;
using System.Collections.Generic;
using System.Diagnostics;
using UnityEngine;

namespace MVRPlugin
{
    public class JpegBenchmark : MVRScript
    {
        private JSONStorableString report;
        private bool running, cancelled;
        private string stage = "idle";
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
            report.val = "JpegBenchmark v1\n";
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
            Add("Synthetic images; 2 warmups + 20 samples per case, one encode/frame. JPEG time includes returned byte[] allocation.");
            int[] widths = { 640, 960, 1280 };
            int[] qualities = { 50, 75 };
            for (int pattern = 0; pattern < 2; pattern++)
            foreach (int width in widths)
            {
                int height = width * 9 / 16;
                stage = "create texture";
                texture = new Texture2D(width, height, TextureFormat.RGB24, false);
                Color32[] pixels = new Color32[width * height];
                System.Random random = new System.Random(12345);
                for (int y = 0; y < height; y++)
                for (int x = 0; x < width; x++)
                    pixels[y * width + x] = pattern == 0
                        ? new Color32((byte)(x * 255 / width), (byte)(y * 255 / height), 100, 255)
                        : new Color32((byte)random.Next(256), (byte)random.Next(256), (byte)random.Next(256), 255);
                texture.SetPixels32(pixels);
                texture.Apply(false, false);
                foreach (int quality in qualities)
                {
                    stage = "EncodeToJPG " + width + " q" + quality;
                    List<double> times = new List<double>();
                    long size = 0;
                    for (int i = -2; i < 20; i++)
                    {
                        Stopwatch sw = Stopwatch.StartNew();
                        byte[] bytes = texture.EncodeToJPG(quality);
                        sw.Stop();
                        if (bytes == null || bytes.Length < 4 || bytes[0] != 255 || bytes[1] != 216 || bytes[bytes.Length-2] != 255 || bytes[bytes.Length-1] != 217)
                            throw new Exception("Invalid JPEG markers");
                        if (i >= 0) { times.Add(sw.Elapsed.TotalMilliseconds); size += bytes.Length; }
                        yield return null;
                    }
                    double average = size / 20.0;
                    Add((pattern == 0 ? "gradient " : "noise ") + width + "x" + height + " q" + quality + ": " + Stats(times)
                        + "; bytes/frame=" + average.ToString("F0") + "; JPEG payload Mbps at 15/30fps="
                        + (average * 8 * 15 / 1000000).ToString("F2") + "/" + (average * 8 * 30 / 1000000).ToString("F2"));
                }
                Cleanup();
                yield return null;
            }
            Add("Noise is a stress case, not a representative VaM scene. Mbps excludes UDP/IP and framing overhead.");
        }
        private void Cleanup() { if (texture != null) { UnityEngine.Object.Destroy(texture); texture = null; } }

    }
}
