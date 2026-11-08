// Standalone VaM diagnostic: add this .cs file directly as a plugin.
using System;
using System.Collections;
using System.Collections.Generic;
using System.Diagnostics;
using UnityEngine;
using System.Net;
using System.Net.Sockets;
namespace MVRPlugin
{
    public class UdpBenchmark : MVRScript
    {
        private JSONStorableString report;
        private bool running, cancelled;
        private string stage = "idle";
        private UdpClient sender, receiver;
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
            report.val = "UdpBenchmark v1\n";
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
            Add("UDP IPv4 loopback only, ephemeral ports; 200 packets/size, bursts of 8/frame. No TCP. Does not measure Wi-Fi throughput or RTT.");
            int[] sizes = { 256, 1200, 1400 };
            foreach (int size in sizes)
            {
                stage = "open UDP loopback sockets";
                receiver = new UdpClient(new IPEndPoint(IPAddress.Loopback, 0));
                receiver.Client.Blocking = false;
                sender = new UdpClient(new IPEndPoint(IPAddress.Loopback, 0));
                sender.Client.Blocking = false;
                IPEndPoint destination = (IPEndPoint)receiver.Client.LocalEndPoint;
                IPEndPoint remote = new IPEndPoint(IPAddress.Any, 0);
                byte[] payload = new byte[size];
                for (int j = 4; j < size; j++) payload[j] = (byte)(j % 251);
                bool[] seen = new bool[200];
                int sent = 0, received = 0, corrupt = 0, duplicates = 0;
                List<double> times = new List<double>();
                Stopwatch elapsed = Stopwatch.StartNew();
                double drainUntil = double.MaxValue;
                while (sent < 200 || (received < 200 && elapsed.Elapsed.TotalSeconds < drainUntil))
                {
                    stage = "send UDP " + size;
                    for (int batch = 0; batch < 8 && sent < 200; batch++)
                    {
                        byte[] sequence = BitConverter.GetBytes(sent);
                        Array.Copy(sequence, payload, 4);
                        Stopwatch sw = Stopwatch.StartNew();
                        int count = sender.Send(payload, payload.Length, destination);
                        sw.Stop();
                        if (count != payload.Length) throw new Exception("Short UDP send");
                        times.Add(sw.Elapsed.TotalMilliseconds);
                        sent++;
                        if (sent == 200) drainUntil = elapsed.Elapsed.TotalSeconds + 1.0;
                    }
                    stage = "receive UDP " + size;
                    for (int n = 0; n < 64 && receiver.Available > 0; n++)
                    {
                        byte[] data = receiver.Receive(ref remote);
                        bool valid = data.Length == size;
                        int sequence = valid ? BitConverter.ToInt32(data, 0) : -1;
                        valid = valid && sequence >= 0 && sequence < 200;
                        for (int j = 4; valid && j < data.Length; j++) if (data[j] != (byte)(j % 251)) valid = false;
                        if (!valid) corrupt++;
                        else if (seen[sequence]) duplicates++;
                        else { seen[sequence] = true; received++; }
                    }
                    yield return null;
                }
                elapsed.Stop();
                Add(size + " bytes: sent=" + sent + " received=" + received + " missing=" + (sent-received) + " corrupt=" + corrupt + " duplicates=" + duplicates
                    + "; Send cost " + Stats(times) + "; elapsed including frame yields=" + elapsed.Elapsed.TotalSeconds.ToString("F3") + "s");
                Cleanup();
            }
            Add("Send cost measures OS enqueue, not delivery. 1200-byte datagrams are the initial candidate for later image fragmentation tests.");
        }
        private void Cleanup()
        {
            if (sender != null) { sender.Close(); sender = null; }
            if (receiver != null) { receiver.Close(); receiver = null; }
        }

    }
}
