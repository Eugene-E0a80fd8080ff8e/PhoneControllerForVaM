using System;
using System.Collections.Generic;
using System.Net;
using System.Text;
using SimpleJSON;
using UnityEngine;

namespace MVRPlugin
{
    public partial class PhoneController
    {
        private Atom targetAtom;
        private string installation;
        private bool selectableTarget;
        private readonly int targetInstance = new System.Random().Next(1, int.MaxValue);
        private volatile int targetRevision = 1;
        private int catalogRevision = 1, nextAtomId = 1;
        private double nextCatalogCheck;
        private bool targetSceneLoading;
        private bool initialTargetPending;
        private readonly Dictionary<Atom, int> atomIds = new Dictionary<Atom, int>();
        private readonly List<Atom> targetCatalog = new List<Atom>();
        private string catalogSignature = "";
        private string framingNote = "";
        private class TargetRequest { public byte[] bytes; public IPEndPoint peer; }
        private readonly Queue<TargetRequest> targetRequests = new Queue<TargetRequest>();
        private bool CanControlTarget { get { return !SuperController.singleton.isLoading && targetAtom != null && atomIds.ContainsKey(targetAtom) && targetAtom.type == "Person" && targetAtom.GetStorableByID("headControl") != null; } }

        private void InitializeTargets()
        {
            // The session preset manager identifies its host even if its UID changes.
            Atom sessionHost = SuperController.singleton.sessionPresetManagerControl != null
                ? SuperController.singleton.sessionPresetManagerControl.containingAtom : null;
            string kind = containingAtom != null ? containingAtom.type : "";
            installation = containingAtom != null && (containingAtom == sessionHost || kind == "SessionPluginManager")
                ? "session" : containingAtom == null || kind == "CoreControl" || kind == "ScenePluginManager" ? "scene" : "atom";
            selectableTarget = installation != "atom";
            initialTargetPending = selectableTarget;
            targetAtom = selectableTarget ? null : containingAtom;
            RefreshTargetCatalog();
            activeTargetId = targetAtom != null && atomIds.ContainsKey(targetAtom) ? atomIds[targetAtom] : 0;
            SuperController.singleton.onSceneLoadedHandlers += TargetSceneLoaded;
            SuperController.singleton.onAtomRemovedHandlers += TargetAtomRemoved;
        }

        private void TargetSceneLoaded()
        {
            atomIds.Clear();
            targetCatalog.Clear();
            catalogRevision++;
            ChangeTarget(selectableTarget ? null : containingAtom);
            initialTargetPending = selectableTarget;
            nextCatalogCheck = 0;
        }

        private void TargetAtomRemoved(Atom atom)
        {
            if (atom == targetAtom) { ChangeTarget(null); framingNote = "Target removed. Choose a target."; }
            nextCatalogCheck = 0;
        }

        private void CleanupTargets()
        {
            if (SuperController.singleton == null) return;
            SuperController.singleton.onSceneLoadedHandlers -= TargetSceneLoaded;
            SuperController.singleton.onAtomRemovedHandlers -= TargetAtomRemoved;
        }

        private static bool HasTargetRotation(Atom atom)
        {
            return atom != null && atom.mainController != null && atom.mainController.canGrabRotation;
        }

        private bool RouteTargetDatagram(byte[] data, IPEndPoint peer)
        {
            if (data.Length < 4 || Encoding.ASCII.GetString(data, 0, 4) != "PCT1") return false;
            if (data.Length > 1200) return true;
            lock (targetRequests) {
                if (targetRequests.Count < 32) targetRequests.Enqueue(new TargetRequest { bytes = data, peer = new IPEndPoint(peer.Address, peer.Port) });
            }
            return true;
        }

        private void RefreshTargetCatalog()
        {
            List<Atom> atoms = SuperController.singleton.GetAtoms();
            foreach (Atom old in new List<Atom>(atomIds.Keys))
                if (old == null || !atoms.Contains(old)) atomIds.Remove(old);
            targetCatalog.Clear();
            foreach (Atom atom in atoms) {
                if (atom == null || atom.type == "CoreControl" || atom.type == "SessionPluginManager" || atom.type == "ScenePluginManager") continue;
                // Main controls establish spatial capability. Renderer-only atoms can also be framed.
                if (atom.type != "Person" && atom.mainController == null && atom.GetComponentsInChildren<Renderer>().Length == 0) continue;
                if (!atomIds.ContainsKey(atom)) atomIds.Add(atom, nextAtomId++);
                targetCatalog.Add(atom);
            }
            targetCatalog.Sort(delegate(Atom a, Atom b) { return CompareAtomNames(a.uid, b.uid); });
            StringBuilder signature = new StringBuilder();
            foreach (Atom atom in targetCatalog) signature.Append(atomIds[atom]).Append(':').Append(atom.uid).Append(':').Append(atom.type).Append('|');
            if (signature.ToString() != catalogSignature) { catalogSignature = signature.ToString(); catalogRevision++; }
            if (targetAtom != null && atomIds.ContainsKey(targetAtom)) activeTargetId = atomIds[targetAtom];
            if ((targetAtom != null && !atoms.Contains(targetAtom)) || (targetAtom == null && activeTargetId != 0)) ChangeTarget(null);
        }

        private static int CompareAtomNames(string left, string right)
        {
            left = left ?? "";
            right = right ?? "";
            int a = 0, b = 0;
            while (a < left.Length && b < right.Length)
            {
                if (IsAsciiDigit(left[a]) && IsAsciiDigit(right[b]))
                {
                    int endA = a, endB = b;
                    while (endA < left.Length && IsAsciiDigit(left[endA])) endA++;
                    while (endB < right.Length && IsAsciiDigit(right[endB])) endB++;
                    while (a < endA - 1 && left[a] == '0') a++;
                    while (b < endB - 1 && right[b] == '0') b++;
                    int lengthOrder = (endA - a).CompareTo(endB - b);
                    if (lengthOrder != 0) return lengthOrder;
                    int numberOrder = string.CompareOrdinal(left, a, right, b, endA - a);
                    if (numberOrder != 0) return numberOrder;
                    a = endA;
                    b = endB;
                }
                else
                {
                    int letterOrder = char.ToUpperInvariant(left[a++]).CompareTo(char.ToUpperInvariant(right[b++]));
                    if (letterOrder != 0) return letterOrder;
                }
            }
            int endOrder = (left.Length - a).CompareTo(right.Length - b);
            return endOrder != 0 ? endOrder : string.CompareOrdinal(left, right);
        }

        private static bool IsAsciiDigit(char value) { return value >= '0' && value <= '9'; }

        private int activeTargetId;
        private void ChangeTarget(Atom atom)
        {
            targetAtom = atom;
            if (atom != null) initialTargetPending = false;
            activeTargetId = atom != null && atomIds.ContainsKey(atom) ? atomIds[atom] : 0;
            targetRevision++;
            keysPressed = 0;
            prevWorldRot = null;
            prevAbsRotTime = prevMagneticAbsRotTime = 0;
            resetPositionTracking();
            positionSequence = -1;
            lock (dataChannel.SyncRoot) dataChannel.Clear();
            reference = 0;
            frameObjectRequested = atom != null;
            framingNote = atom == null ? "Choose a target" : "";
        }

        private void UpdateTargets()
        {
            bool loading = SuperController.singleton.isLoading;
            if (loading && !targetSceneLoading && selectableTarget) ChangeTarget(null);
            targetSceneLoading = loading;
            if (!loading && Now() >= nextCatalogCheck) { RefreshTargetCatalog(); nextCatalogCheck = Now() + .5; }
            for (int i = 0; i < 8; i++) {
                TargetRequest request;
                lock (targetRequests) { if (targetRequests.Count == 0) break; request = targetRequests.Dequeue(); }
                try {
                    JSONNode query = JSON.Parse(Encoding.UTF8.GetString(request.bytes, 4, request.bytes.Length - 4));
                    string error = "";
                    int requestId = query["request"].AsInt;
                    string operation = query["op"].Value;
                    if (operation == "state" && initialTargetPending && !loading)
                    {
                        initialTargetPending = false;
                        Atom firstPerson = targetCatalog.Find(delegate(Atom atom) { return atom != null && atom.type == "Person"; });
                        if (targetAtom == null && firstPerson != null) ChangeTarget(firstPerson);
                    }
                    if (operation == "select") {
                        Atom selected = targetCatalog.Find(delegate(Atom a) { return atomIds[a] == query["target"].AsInt; });
                        if (loading) error = "Scene is loading";
                        else if (!selectableTarget) error = "This plugin targets its containing atom";
                        else if (query["instance"].AsInt != targetInstance) error = "Plugin restarted; refresh targets";
                        else if (selected == null) error = "Target is no longer available";
                        // Repeated selection of the current target is idempotent.
                        else if (selected != targetAtom && query["revision"].AsInt != targetRevision) error = "Target changed; select again";
                        else if (selected != targetAtom) ChangeTarget(selected);
                    }
                    JSONClass state = new JSONClass();
                    state["instance"].AsInt = targetInstance;
                    state["revision"].AsInt = targetRevision;
                    state["catalog"].AsInt = catalogRevision;
                    state["installation"] = installation;
                    state["selectable"].AsBool = selectableTarget && !loading;
                    state["loading"].AsBool = loading;
                    state["control"].AsBool = CanControlTarget;
                    state["cameraGrab"].AsBool = true;
                    state["cameraGrabVersion"].AsInt = 4;
                    state["cameraGrabModes"].AsBool = true;
                    state["cameraGrabRotationOff"].AsBool = true;
                    state["cameraGrabHeadOnly"].AsBool = true;
                    state["position"].AsBool = targetAtom != null;
                    state["rotation"].AsBool = HasTargetRotation(targetAtom);
                    state["target"].AsInt = targetAtom != null && atomIds.ContainsKey(targetAtom) ? atomIds[targetAtom] : 0;
                    state["name"] = ShortName(targetAtom != null ? targetAtom.uid : "Choose a target");
                    state["type"] = targetAtom != null ? ShortName(targetAtom.type) : "";
                    state["request"].AsInt = requestId;
                    state["error"] = error;
                    state["framing"] = framingNote;
                    int page = query["page"].AsInt;
                    state["page"].AsInt = page;
                    state["count"].AsInt = selectableTarget && !loading ? targetCatalog.Count : 0;
                    if (!loading && selectableTarget && page >= 0 && page < targetCatalog.Count) {
                        Atom atom = targetCatalog[page];
                        state["itemId"].AsInt = atomIds[atom];
                        state["itemName"] = ShortName(atom.uid);
                        state["itemType"] = ShortName(atom.type);
                    }
                    byte[] response = Encoding.UTF8.GetBytes("PCT1" + state.ToString());
                    if (response.Length > 1200) {
                        foreach (string field in new string[] { "name", "type", "itemName", "itemType" }) {
                            string value = state[field].Value;
                            if (value.Length > 20) state[field] = value.Substring(0, 20);
                        }
                        response = Encoding.UTF8.GetBytes("PCT1" + state.ToString());
                    }
                    if (response.Length <= 1200) {
                        lock (socketLock) if (receivingSocket != null) receivingSocket.Send(response, response.Length, request.peer);
                    }
                } catch (Exception e) { receiverError = "Target request: " + e.Message; }
            }
        }

        private static string ShortName(string value) { value = value ?? ""; return value.Length > 80 ? value.Substring(0, 80) : value; }

        private void ProcessTargetMovement(byte[] packet)
        {
            // Raw legacy movement has no target identity and cannot safely cross a target change.
            if (!CanControlTarget || packet.Length < 12 || Encoding.ASCII.GetString(packet, 0, 4) != "PCM2"
                || BitConverter.ToInt32(packet, 4) != targetInstance || BitConverter.ToInt32(packet, 8) != targetRevision) return;
            byte[] payload = new byte[packet.Length - 12];
            Array.Copy(packet, 12, payload, 0, payload.Length);
            processPacket(payload);
        }
    }
}
