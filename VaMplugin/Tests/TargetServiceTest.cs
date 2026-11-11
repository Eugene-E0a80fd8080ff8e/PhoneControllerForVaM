// Standalone harness: compile this file with TargetService.cs and VaM/Unity references.
// These small doubles exercise the target service without starting Unity.
using System;
using System.Collections;
using System.Collections.Generic;
using System.Net;
using System.Net.Sockets;
using System.Text;
using SimpleJSON;

public class TestControl { public bool canGrabRotation = true; }
public class Atom
{
    public string uid, type;
    public TestControl mainController = new TestControl();
    public object GetStorableByID(string id) { return type == "Person" ? this : null; }
    public T[] GetComponentsInChildren<T>() { return new T[0]; }
}
public class TestPreset { public Atom containingAtom; }
public class SuperController
{
    public static SuperController singleton = new SuperController();
    public TestPreset sessionPresetManagerControl;
    public bool isLoading;
    public Action onSceneLoadedHandlers;
    public Action<Atom> onAtomRemovedHandlers;
    public List<Atom> atoms = new List<Atom>();
    public List<Atom> GetAtoms() { return new List<Atom>(atoms); }
}
namespace MVRPlugin
{
    public partial class PhoneController
    {
        private Atom containingAtom;
        private int keysPressed, prevAbsRotTime, prevMagneticAbsRotTime, positionSequence;
        private object prevWorldRot;
        private long reference;
        private bool frameObjectRequested;
        private readonly Queue dataChannel = new Queue();
        private readonly object socketLock = new object();
        private UdpClient receivingSocket;
        private string receiverError;
        private int acceptedMovement;
        private void resetPositionTracking() { }
        private void processPacket(byte[] bytes) { acceptedMovement++; }
        private static double Now() { return 1; }
        private static void Assert(bool ok, string message) { if (!ok) throw new Exception(message); }

        private JSONNode Request(UdpClient client, string operation, int id, int revision, int request)
        {
            JSONClass query = new JSONClass();
            query["op"] = operation;
            query["target"].AsInt = id;
            query["instance"].AsInt = targetInstance;
            query["revision"].AsInt = revision;
            query["request"].AsInt = request;
            byte[] bytes = Encoding.UTF8.GetBytes("PCT1" + query.ToString());
            RouteTargetDatagram(bytes, (IPEndPoint)client.Client.LocalEndPoint);
            UpdateTargets();
            IPEndPoint from = null;
            byte[] response = client.Receive(ref from);
            Assert(response.Length <= 1200, "Oversized target reply");
            return JSON.Parse(Encoding.UTF8.GetString(response, 4, response.Length - 4));
        }
        private byte[] Movement(int revision)
        {
            byte[] bytes = new byte[13];
            Array.Copy(Encoding.ASCII.GetBytes("PCM2"), bytes, 4);
            Array.Copy(BitConverter.GetBytes(targetInstance), 0, bytes, 4, 4);
            Array.Copy(BitConverter.GetBytes(revision), 0, bytes, 8, 4);
            return bytes;
        }
        public static void Main()
        {
            Atom host = new Atom { uid="CoreControl", type="CoreControl" };
            Atom person = new Atom { uid="person2", type="Person" };
            Atom laterPerson = new Atom { uid="Person10", type="Person" };
            Atom prop = new Atom { uid="Chair", type="Asset" };
            SuperController.singleton.atoms.AddRange(new Atom[] { host, laterPerson, prop, person });
            PhoneController plugin = new PhoneController { containingAtom=host };
            plugin.InitializeTargets();
            Assert(plugin.selectableTarget && plugin.installation == "scene", "Scene host not detected");
            Assert(!plugin.CanControlTarget, "Unselected controller enabled");
            using (plugin.receivingSocket = new UdpClient(new IPEndPoint(IPAddress.Loopback, 0)))
            using (UdpClient client = new UdpClient(new IPEndPoint(IPAddress.Loopback, 0))) {
                client.Client.ReceiveTimeout = 1000;
                int originalRevision = plugin.targetRevision;
                int personId = plugin.atomIds[person];
                JSONNode selected = plugin.Request(client, "state", 0, 0, 0);
                Assert(selected["target"].AsInt == personId && selected["control"].AsBool && plugin.frameObjectRequested,
                    "First connection did not select and frame person2 before Person10");
                int selectedRevision = plugin.targetRevision;
                plugin.frameObjectRequested = false;
                plugin.Request(client, "select", personId, originalRevision, 1);
                Assert(plugin.targetRevision == selectedRevision && !plugin.frameObjectRequested, "Retry reframed target");
                plugin.ProcessTargetMovement(plugin.Movement(originalRevision));
                plugin.ProcessTargetMovement(plugin.Movement(selectedRevision));
                Assert(plugin.acceptedMovement == 1, "Stale target movement accepted");
                plugin.keysPressed = 255;
                plugin.Request(client, "select", plugin.atomIds[prop], selectedRevision, 2);
                Assert(!plugin.CanControlTarget && plugin.keysPressed == 0, "Prop switch retained body input");
                plugin.Request(client, "state", 0, 0, 0);
                Assert(plugin.targetAtom == prop, "A state query replaced the selected prop");
                plugin.ProcessTargetMovement(plugin.Movement(plugin.targetRevision));
                Assert(plugin.acceptedMovement == 1, "Prop accepted body input");
                plugin.Request(client, "select", personId, originalRevision, 1);
                Assert(plugin.targetAtom == prop, "Delayed selection changed target");
                person.uid = "Renamed";
                plugin.RefreshTargetCatalog();
                Assert(plugin.atomIds[person] == personId, "Rename changed identity");
                SuperController.singleton.atoms.Remove(person);
                plugin.RefreshTargetCatalog();
                Atom replacement = new Atom { uid="Renamed", type="Person" };
                SuperController.singleton.atoms.Add(replacement);
                plugin.RefreshTargetCatalog();
                Assert(plugin.atomIds[replacement] != personId, "Recreated atom reused identity");
                plugin.TargetAtomRemoved(prop);
                Assert(plugin.targetAtom == null && !plugin.CanControlTarget, "Removed target retained control");
                plugin.ChangeTarget(replacement);
                plugin.TargetSceneLoaded();
                Assert(plugin.targetAtom == null && plugin.atomIds.Count == 0, "Scene load retained target IDs");
                plugin.RefreshTargetCatalog();
                replacement.uid = new string('\u0001', 500);
                plugin.ChangeTarget(replacement);
                plugin.Request(client, "state", 0, 0, 0);
            }
            plugin.CleanupTargets();
            host.type="SessionPluginManager";
            plugin.InitializeTargets();
            Assert(plugin.installation=="session", "Session host not detected");
            plugin.CleanupTargets();
            plugin.containingAtom=person;
            SuperController.singleton.atoms.Add(person);
            plugin.InitializeTargets();
            Assert(!plugin.selectableTarget && plugin.CanControlTarget, "Person attachment not detected");
            plugin.CleanupTargets();
            plugin.containingAtom=prop;
            prop.mainController.canGrabRotation=false;
            plugin.InitializeTargets();
            Assert(!plugin.selectableTarget && !plugin.CanControlTarget && !HasTargetRotation(prop), "Position-only prop capability incorrect");
            SuperController.singleton.isLoading=true;
            Assert(!plugin.CanControlTarget, "Loading scene accepted movement");
            plugin.CleanupTargets();
            Console.WriteLine("Target selection, retries, movement isolation, rename, removal, scene load and host tests passed.");
        }
    }
}
