using System;
using UnityEngine;
using System.Collections;
using System.Collections.Generic;
using SimpleJSON;

using System.Net.Sockets;
using System.Threading;
// :( using System.Collections.Concurrent; 
using System.Collections;
using System.Net;
using System.Diagnostics;
using System.IO;
using Leap;
using System.Text;

namespace MVRPlugin
{


    public class PhoneController : MVRScript
    {

        private JSONStorableFloat _azimuth;
        private float NORTH_DIRECTION = 215;

        private volatile int udpCounter = 0;
        private int udpCounterLastReported = 0;

        private Queue dataChannel = new Queue();
        private Thread thread;
        private volatile bool terminateFlag = false;
        private readonly object socketLock = new object();
        private UdpClient receivingSocket;
        private volatile string receiverError;

        private int keysPressed = 0; // bitmask: 0-head, 8-lHand, 9-rHand, 16-hip, 24-lFoot, 25-rFoot
        private Vector3 magneticField = Vector3.zero;
        private Vector3 gravity = Vector3.zero;

        private Quaternion prevMagneticAbsRot = Quaternion.identity;
        private long prevMagneticAbsRotTime = 0; // 0 here means both prevMagneticAbsRot and prevMagneticAbsRotLastTime are not initialized

        private Quaternion prevAbsRot = Quaternion.identity;
        private long prevAbsRotTime = 0; // 0 here means both prevAbsRot and prevAbsRotLastTime are not initialized

        private Quaternion? prevWorldRot = null;

        // Never wait for the receiver from Unity's UI thread, or from the
        // receiver itself. Closing its socket wakes a pending Receive call.
        private void StopReceiver()
        {
            terminateFlag = true;
            UdpClient socket;
            lock (socketLock)
            {
                socket = receivingSocket;
                receivingSocket = null;
            }
            if (socket != null) socket.Close();
        }

        public void TrFunc()
        {
            UdpClient udpClient = null;
            bool bindErrorReported = false;
            try
            {
                while (!terminateFlag && udpClient == null)
                {
                    try
                    {
                        UdpClient candidate = new UdpClient(62701);
                        lock (socketLock)
                        {
                            if (terminateFlag) candidate.Close();
                            else
                            {
                                udpClient = candidate;
                                receivingSocket = candidate;
                            }
                        }
                    }
                    catch (SocketException e)
                    {
                        // Another instance may still be releasing the port.
                        // Retry without keeping a reload blocked.
                        if (!bindErrorReported && !terminateFlag)
                        {
                            receiverError = "Phone controller: waiting for UDP port 62701: " + e.Message;
                            bindErrorReported = true;
                        }
                        Thread.Sleep(200);
                    }
                }
                if (udpClient == null || terminateFlag) return;

                IPEndPoint remoteIP = new IPEndPoint(IPAddress.Any, 0);
                IPEndPoint broadcastIP = new IPEndPoint(IPAddress.Broadcast, 62701);
                udpClient.Client.ReceiveTimeout = 100;
                int lastAnnounceTime = 0;
                byte[] bAnnounce = new byte[] { 0xff };

                while (!terminateFlag)
                {
                    byte[] data = null;
                    try { data = udpClient.Receive(ref remoteIP); }
                    catch (SocketException) { if (terminateFlag) break; }
                    catch (ObjectDisposedException) { break; }

                    if (terminateFlag) break;
                    if (data != null)
                    {
                        lock (dataChannel.SyncRoot)
                        {
                            if (dataChannel.Count >= 5) dataChannel.Dequeue();
                            dataChannel.Enqueue(data);
                            ++udpCounter;
                        }
                    }
                    if (lastAnnounceTime + 300 < TickCount)
                    {
                        try { udpClient.Send(bAnnounce, 1, broadcastIP); }
                        catch (SocketException) { if (terminateFlag) break; }
                        catch (ObjectDisposedException) { break; }
                        lastAnnounceTime = TickCount;
                    }
                }
            }
            catch (Exception e)
            {
                if (!terminateFlag) receiverError = "Phone controller receiver stopped: " + e;
            }
            finally
            {
                lock (socketLock)
                {
                    if (receivingSocket == udpClient) receivingSocket = null;
                }
                if (udpClient != null) udpClient.Close();
            }
        }

        // IMPORTANT - DO NOT make custom enums. The dynamic C# complier crashes Unity when it encounters these for
        // some reason

        // IMPORTANT - DO NOT OVERRIDE Awake() as it is used internally by MVRScript - instead use Init() function which
        // is called right after creation
        public override void Init()
        {
            try
            {

                /*
                {
                    _azimuth = new JSONStorableFloat("Azimuth of Monitor", 215f, (angle) => { this.NORTH_DIRECTION = angle; } , 0f, 360f );
                    RegisterFloat(_azimuth);
                    CreateSlider(_azimuth, true);
                }
                */

                if (thread != null && thread.IsAlive) return;
                terminateFlag = false;
                thread = new Thread(new ThreadStart(TrFunc));
                thread.IsBackground = true;
                thread.Start();


            }
            catch (Exception e)
            {
                SuperController.LogError("Exception caught: " + e);
            }
        }

        Int64 gyroPrevTimestamp = 0;
        private void processGyro(Int64 timestamp, byte accuracy, float XrotRate, float YrotRate, float ZrotRate)
        {
            /*
			if( timestamp - gyroPrevTimestamp > 100000000) // 100ms
            {
				// time gap too long -- igronig data
				gyroPrevTimestamp = timestamp;
				return;
			}
			*/

            // gravity plane is a plane orthogonal to gravity vector
            Vector3 northOnGravityPlane = Vector3.ProjectOnPlane(magneticField, gravity);  // Projection of north to the gravity plane (phone's coordinates)

            // I assume that phone is right in front of the monitor
            Vector3 phoneToMonitorDirection = Quaternion.AngleAxis(-NORTH_DIRECTION, gravity) * northOnGravityPlane; // phone's coordinates

            Vector3 phoneToMonitorDirectionR = new Vector3(phoneToMonitorDirection.x, phoneToMonitorDirection.y, -phoneToMonitorDirection.z);
            Vector3 gravityR = new Vector3(gravity.x, gravity.y, -gravity.z);

            Quaternion magneticAbsRot = Quaternion.LookRotation(phoneToMonitorDirectionR, gravityR);
            magneticAbsRot = Quaternion.Inverse(magneticAbsRot);

            // now magneticAbsRot is absolute rotation of the phone in scene camera coordinates

            ///////////////////////////////////////

            // Need to apply low-pass filter to it. I chose the time constant to be 3 sec. 
            Quaternion absRotSmoothed;
            {
                if (0 == prevMagneticAbsRotTime)
                {
                    prevMagneticAbsRotTime = timestamp; // time in nanoseconds
                    prevMagneticAbsRot = magneticAbsRot;
                }

                float tce = (float)Math.Exp(-(timestamp - prevMagneticAbsRotTime) / 3e9);
                //SuperController.LogError($"cc: {cc} ; dt={timestamp-prevMagneticAbsRotLastTime} ");
                absRotSmoothed = Quaternion.Slerp(magneticAbsRot, prevMagneticAbsRot, tce);

                prevMagneticAbsRot = magneticAbsRot;
                prevMagneticAbsRotTime = timestamp;
            }

            ///////////////////////////////////////

            // now mixing in the gyroscope data
            Quaternion absRot;
            {
                if (0 == prevAbsRotTime)
                {
                    prevAbsRotTime = timestamp;
                    prevAbsRot = magneticAbsRot;
                }

                float dt_sec = ((float)(timestamp - prevAbsRotTime)) / 1000000000.0f; // in seconds
                float dt_sec_dtr = dt_sec * 180f / 3.14159265f;

                absRot = prevAbsRot * Quaternion.Euler(-XrotRate * dt_sec_dtr, -YrotRate * dt_sec_dtr, ZrotRate * dt_sec_dtr);

                // to compensate gyro's drift, absRot shoudl slowly gravitate towards magnetic-obtained absolute rotation
                float tce = (float)Math.Exp(-(timestamp - prevAbsRotTime) / 3e9);
                absRot = Quaternion.Slerp(prevMagneticAbsRot, absRot, tce);
            }


            {   // debugging
                //	Atom a = SuperController.singleton.GetAtomByUid("Empty");
                //	a.transform.rotation = absRot;
            }


            Quaternion camRot = SuperController.singleton.lookCamera.transform.rotation;

            Quaternion worldRot = camRot * absRot;
            

            {   // debugging
                //Atom a = SuperController.singleton.GetAtomByUid("Empty");
                //a.transform.rotation = worldRot;
            }

            if (false)
            {  // debug
                if (null != prevWorldRot)
                    if (0 != (keysPressed & 1))
                    {
                        //Quaternion q = Quaternion.Inverse((Quaternion)prevWorldRot) * worldRot;

                        Atom a = SuperController.singleton.GetAtomByUid("Empty");
                        //a.transform.rotation *= worldRot;
                        //a.transform.rotation *= Quaternion.Inverse((Quaternion)prevWorldRot);

                        a.transform.rotation = Quaternion.Inverse((Quaternion)prevWorldRot) * a.transform.rotation;
                        a.transform.rotation = worldRot * a.transform.rotation;

                    }
            }

            //float dt = ((float)(timestamp - gyroPrevTimestamp)) / 1000000000.0f; // 1 second
            //const float DTR = 180f / 3.14159265f;

            if (null != prevWorldRot)
            {
                if (0 != (keysPressed & 1)) // head
                {
                    FreeControllerV3 control = (FreeControllerV3)containingAtom.GetStorableByID("headControl");
                    control.RotateTo(worldRot * (Quaternion.Inverse((Quaternion)prevWorldRot) * control.transform.rotation));

                }

                if (0 != (keysPressed & 2)) // neck
                {
                    FreeControllerV3 head = (FreeControllerV3)containingAtom.GetStorableByID("headControl");
                    FreeControllerV3 neck = (FreeControllerV3)containingAtom.GetStorableByID("neckControl");
                    neck.RotateTo(worldRot * (Quaternion.Inverse((Quaternion)prevWorldRot) * neck.transform.rotation));
                    head.transform.position = neck.transform.position + worldRot * (Quaternion.Inverse((Quaternion)prevWorldRot) * (head.transform.position - neck.transform.position));
                    head.RotateTo(worldRot * (Quaternion.Inverse((Quaternion)prevWorldRot) * head.transform.rotation));
                }

                if (0 != (keysPressed & (1 << 8))) // lHand
                {
                    FreeControllerV3 control = (FreeControllerV3)containingAtom.GetStorableByID("lHandControl");
                    control.RotateTo(worldRot * (Quaternion.Inverse((Quaternion)prevWorldRot) * control.transform.rotation));
                }
                if (0 != (keysPressed & (1 << 9))) // rHand
                {
                    FreeControllerV3 control = (FreeControllerV3)containingAtom.GetStorableByID("rHandControl");
                    control.RotateTo(worldRot * (Quaternion.Inverse((Quaternion)prevWorldRot) * control.transform.rotation));
                }

                if (0 != (keysPressed & (1 << 10))) // lElbow
                {
                    FreeControllerV3 elbow = (FreeControllerV3)containingAtom.GetStorableByID("lElbowControl");
                    FreeControllerV3 hand = (FreeControllerV3)containingAtom.GetStorableByID("lHandControl");
                    elbow.RotateTo(worldRot * (Quaternion.Inverse((Quaternion)prevWorldRot) * elbow.transform.rotation));
                    hand.transform.position = elbow.transform.position + worldRot * (Quaternion.Inverse((Quaternion)prevWorldRot) * (hand.transform.position - elbow.transform.position));
                    hand.RotateTo(worldRot * (Quaternion.Inverse((Quaternion)prevWorldRot) * hand.transform.rotation));
                }
                if (0 != (keysPressed & (1 << 11))) // rElbow
                {
                    FreeControllerV3 elbow = (FreeControllerV3)containingAtom.GetStorableByID("rElbowControl");
                    FreeControllerV3 hand = (FreeControllerV3)containingAtom.GetStorableByID("rHandControl");
                    elbow.RotateTo(worldRot * (Quaternion.Inverse((Quaternion)prevWorldRot) * elbow.transform.rotation));
                    hand.transform.position = elbow.transform.position + worldRot * (Quaternion.Inverse((Quaternion)prevWorldRot) * (hand.transform.position - elbow.transform.position));
                    hand.RotateTo(worldRot * (Quaternion.Inverse((Quaternion)prevWorldRot) * hand.transform.rotation));
                }

                if (0 != (keysPressed & (1 << 12))) // lArm
                {
                    FreeControllerV3 arm = (FreeControllerV3)containingAtom.GetStorableByID("lArmControl");
                    FreeControllerV3 elbow = (FreeControllerV3)containingAtom.GetStorableByID("lElbowControl");
                    FreeControllerV3 hand = (FreeControllerV3)containingAtom.GetStorableByID("lHandControl");
                    arm.RotateTo(worldRot * (Quaternion.Inverse((Quaternion)prevWorldRot) * arm.transform.rotation));
                    elbow.transform.position = arm.transform.position + worldRot * (Quaternion.Inverse((Quaternion)prevWorldRot) * (elbow.transform.position - arm.transform.position));
                    elbow.RotateTo( worldRot * (Quaternion.Inverse((Quaternion)prevWorldRot) * elbow.transform.rotation));
                    hand.transform.position = arm.transform.position + worldRot * (Quaternion.Inverse((Quaternion)prevWorldRot) * (hand.transform.position - arm.transform.position));
                    hand.RotateTo(worldRot * (Quaternion.Inverse((Quaternion)prevWorldRot) * hand.transform.rotation));
                }
                if (0 != (keysPressed & (1 << 13))) // lArm
                {
                    FreeControllerV3 arm = (FreeControllerV3)containingAtom.GetStorableByID("rArmControl");
                    FreeControllerV3 elbow = (FreeControllerV3)containingAtom.GetStorableByID("rElbowControl");
                    FreeControllerV3 hand = (FreeControllerV3)containingAtom.GetStorableByID("rHandControl");
                    arm.RotateTo(worldRot * (Quaternion.Inverse((Quaternion)prevWorldRot) * arm.transform.rotation));
                    elbow.transform.position = arm.transform.position + worldRot * (Quaternion.Inverse((Quaternion)prevWorldRot) * (elbow.transform.position - arm.transform.position));
                    elbow.RotateTo(worldRot * (Quaternion.Inverse((Quaternion)prevWorldRot) * elbow.transform.rotation));
                    hand.transform.position = arm.transform.position + worldRot * (Quaternion.Inverse((Quaternion)prevWorldRot) * (hand.transform.position - arm.transform.position));
                    hand.RotateTo( worldRot * (Quaternion.Inverse((Quaternion)prevWorldRot) * hand.transform.rotation));
                }


                if (0 != (keysPressed & (1 << 16))) // hip
                {
                    FreeControllerV3 control = (FreeControllerV3)containingAtom.GetStorableByID("hipControl");
                    control.RotateTo(worldRot * (Quaternion.Inverse((Quaternion)prevWorldRot) * control.transform.rotation));
                }
                if (0 != (keysPressed & (1 << 17))) // chest
                {
                    FreeControllerV3 chest = (FreeControllerV3)containingAtom.GetStorableByID("chestControl");
                    chest.RotateTo(worldRot * (Quaternion.Inverse((Quaternion)prevWorldRot) * chest.transform.rotation));

                    string[] arr = new string[] {
                        "lArmControl","lElbowControl","lHandControl",
                        "rArmControl","rElbowControl","rHandControl",
                        "lShoulderControl","lNippleControl",
                        "rShoulderControl","rNippleControl",
                        "headControl","neckControl"
                    };
                    foreach (string t in arr)
                    {
                        FreeControllerV3 fc = (FreeControllerV3)containingAtom.GetStorableByID(t);
                        fc.transform.position = chest.transform.position + worldRot * (Quaternion.Inverse((Quaternion)prevWorldRot) * (fc.transform.position - chest.transform.position));
                        fc.RotateTo(worldRot * (Quaternion.Inverse((Quaternion)prevWorldRot) * fc.transform.rotation));
                    }

                }

                if (0 != (keysPressed & (1 << 24))) // lFoot
                {
                    FreeControllerV3 control = (FreeControllerV3)containingAtom.GetStorableByID("lFootControl");
                    control.RotateTo(worldRot * (Quaternion.Inverse((Quaternion)prevWorldRot) * control.transform.rotation));
                }
                if (0 != (keysPressed & (1 << 25))) // rFoot
                {
                    FreeControllerV3 control = (FreeControllerV3)containingAtom.GetStorableByID("rFootControl");
                    control.RotateTo(worldRot * (Quaternion.Inverse((Quaternion)prevWorldRot) * control.transform.rotation));
                }
                if (0 != (keysPressed & (1 << 26))) // lKnee
                {
                    FreeControllerV3 knee = (FreeControllerV3)containingAtom.GetStorableByID("lKneeControl");
                    FreeControllerV3 foot = (FreeControllerV3)containingAtom.GetStorableByID("lFootControl");
                    knee.RotateTo(worldRot * (Quaternion.Inverse((Quaternion)prevWorldRot) * knee.transform.rotation));
                    foot.transform.position = knee.transform.position + worldRot * (Quaternion.Inverse((Quaternion)prevWorldRot) * (foot.transform.position - knee.transform.position));
                    foot.RotateTo(worldRot * (Quaternion.Inverse((Quaternion)prevWorldRot) * foot.transform.rotation));
                }
                if (0 != (keysPressed & (1 << 27))) // rKnee
                {
                    FreeControllerV3 knee = (FreeControllerV3)containingAtom.GetStorableByID("rKneeControl");
                    FreeControllerV3 foot = (FreeControllerV3)containingAtom.GetStorableByID("rFootControl");
                    knee.RotateTo(worldRot * (Quaternion.Inverse((Quaternion)prevWorldRot) * knee.transform.rotation));
                    foot.transform.position = knee.transform.position + worldRot * (Quaternion.Inverse((Quaternion)prevWorldRot) * (foot.transform.position - knee.transform.position));
                    foot.RotateTo(worldRot * (Quaternion.Inverse((Quaternion)prevWorldRot) * foot.transform.rotation));
                }
                if (0 != (keysPressed & (1 << 28))) // lThigh
                {
                    FreeControllerV3 thigh = (FreeControllerV3)containingAtom.GetStorableByID("lThighControl");
                    FreeControllerV3 knee = (FreeControllerV3)containingAtom.GetStorableByID("lKneeControl");
                    FreeControllerV3 foot = (FreeControllerV3)containingAtom.GetStorableByID("lFootControl");
                    thigh.RotateTo(worldRot * (Quaternion.Inverse((Quaternion)prevWorldRot) * thigh.transform.rotation));
                    knee.transform.position = thigh.transform.position + worldRot * (Quaternion.Inverse((Quaternion)prevWorldRot) * (knee.transform.position - thigh.transform.position));
                    knee.RotateTo(worldRot * (Quaternion.Inverse((Quaternion)prevWorldRot) * knee.transform.rotation));
                    foot.transform.position = thigh.transform.position + worldRot * (Quaternion.Inverse((Quaternion)prevWorldRot) * (foot.transform.position - thigh.transform.position));
                    foot.RotateTo(worldRot * (Quaternion.Inverse((Quaternion)prevWorldRot) * foot.transform.rotation));
                }
                if (0 != (keysPressed & (1 << 29))) // rThigh
                {
                    FreeControllerV3 thigh = (FreeControllerV3)containingAtom.GetStorableByID("rThighControl");
                    FreeControllerV3 knee = (FreeControllerV3)containingAtom.GetStorableByID("rKneeControl");
                    FreeControllerV3 foot = (FreeControllerV3)containingAtom.GetStorableByID("rFootControl");
                    thigh.RotateTo(worldRot * (Quaternion.Inverse((Quaternion)prevWorldRot) * thigh.transform.rotation));
                    knee.transform.position = thigh.transform.position + worldRot * (Quaternion.Inverse((Quaternion)prevWorldRot) * (knee.transform.position - thigh.transform.position));
                    knee.RotateTo(worldRot * (Quaternion.Inverse((Quaternion)prevWorldRot) * knee.transform.rotation));
                    foot.transform.position = thigh.transform.position + worldRot * (Quaternion.Inverse((Quaternion)prevWorldRot) * (foot.transform.position - thigh.transform.position));
                    foot.RotateTo(worldRot * (Quaternion.Inverse((Quaternion)prevWorldRot) * foot.transform.rotation));
                }

            }

            gyroPrevTimestamp = timestamp;
            {
                prevAbsRot = absRot;
                prevAbsRotTime = timestamp;
            }
            prevWorldRot = worldRot;
        }

        // ARCore position record v1: anchor-relative Android sensor axes in meters.
        private const int PositionMask = (1 << 8) | (1 << 9) | (1 << 24) | (1 << 25);
        private readonly int[] positionBits = { 8, 9, 24, 25 };
        private readonly string[] positionControls = { "lHandControl", "rHandControl", "lFootControl", "rFootControl" };
        private readonly string[] positionParents = { "lArmControl", "rArmControl", "lThighControl", "rThighControl" };
        private readonly Vector3[] positionStart = new Vector3[4];
        private readonly Vector3[] phoneStart = new Vector3[4];
        private int positionActive;
        private int positionBlocked;
        private long positionReference;
        private long positionSequence = -1;
        private int lastPositionTick;
        private Quaternion positionBasis;
        private bool positionReady;

        private void resetPositionTracking()
        {
            positionBlocked |= keysPressed & PositionMask;
            positionActive = 0;
            positionReady = false;
        }

        private void processPosition(byte[] p, int offset)
        {
            if (p[offset + 1] != 1) { resetPositionTracking(); return; }
            byte tracking = p[offset + 2];
            long reference = BitConverter.ToInt64(p, offset + 3);
            long sequence = BitConverter.ToInt64(p, offset + 11);
            if (sequence <= positionSequence) return;
            positionSequence = sequence;
            lastPositionTick = TickCount;
            positionBlocked &= keysPressed;
            if (tracking != 1) { resetPositionTracking(); return; }
            float x = BitConverter.ToSingle(p, offset + 19);
            float y = BitConverter.ToSingle(p, offset + 23);
            float z = BitConverter.ToSingle(p, offset + 27);
            if (float.IsNaN(x) || float.IsInfinity(x) || float.IsNaN(y) || float.IsInfinity(y) ||
                float.IsNaN(z) || float.IsInfinity(z) || Mathf.Abs(x) > 10 || Mathf.Abs(y) > 10 || Mathf.Abs(z) > 10) {
                resetPositionTracking(); return;
            }
            Vector3 phone = new Vector3(x, y, -z);
            if (!positionReady || positionReference != reference) {
                resetPositionTracking();
                if (prevWorldRot == null) return;
                positionReference = reference;
                positionBasis = (Quaternion)prevWorldRot;
                positionReady = true;
            }
            for (int i = 0; i < positionBits.Length; i++) {
                int bit = 1 << positionBits[i];
                if ((keysPressed & bit) == 0) {
                    positionActive &= ~bit;
                    positionBlocked &= ~bit;
                    continue;
                }
                if ((positionBlocked & bit) != 0) continue;
                FreeControllerV3 control = (FreeControllerV3)containingAtom.GetStorableByID(positionControls[i]);
                FreeControllerV3 parent = (FreeControllerV3)containingAtom.GetStorableByID(positionParents[i]);
                if (control == null || parent == null) continue;
                if ((positionActive & bit) == 0) {
                    positionStart[i] = control.transform.position;
                    phoneStart[i] = phone;
                    positionActive |= bit;
                }
                Vector3 target = positionStart[i] + positionBasis * (phone - phoneStart[i]);
                float reach = i < 2 ? 0.55f : 0.85f;
                control.transform.position = parent.transform.position + Vector3.ClampMagnitude(target - parent.transform.position, reach);
            }
        }

        private void processMagnetic(Int64 timestamp, byte accuracy, float X, float Y, float Z)
        {
            //SuperController.LogError($"magnetic: /{accuracy}/  {X} , {Y} , {Z}");
            magneticField = new Vector3(X, Y, Z);
        }
        private void processGravity(Int64 timestamp, byte accuracy, float X, float Y, float Z)
        {
            //SuperController.LogError($"gravity: /{accuracy}/  {X} , {Y} , {Z}");
            gravity = new Vector3(X, Y, Z);
        }

        private void processPacket(byte[] p)
        {
            int pp = 0;
            while (pp < p.Length)
            {
                byte sensorType = p[pp];
                switch (sensorType)
                {
                    case 1: // Gyro
                        {
                            Int64 timestamp = System.BitConverter.ToInt64(p, pp + 1);
                            byte accuracy = p[pp + 9];
                            byte length = p[pp + 10];
                            pp += 11;
                            //SuperController.LogError($"length = {length}");
                            if (length == 3)
                            {
                                float XrotRate = System.BitConverter.ToSingle(p, pp);
                                pp += 4;
                                float YrotRate = System.BitConverter.ToSingle(p, pp);
                                pp += 4;
                                float ZrotRate = System.BitConverter.ToSingle(p, pp);
                                pp += 4;
                                processGyro(timestamp, accuracy, XrotRate, YrotRate, ZrotRate);
                            }
                            else return;
                        }
                        break;

                    case 2: // Ignore legacy linear acceleration records.
                        if (pp + 11 > p.Length) return;
                        int legacyLength = p[pp + 10];
                        if (pp + 11 + legacyLength * 4 > p.Length) return;
                        pp += 11 + legacyLength * 4;
                        break;
                    case 3: // Gravity
                        {
                            Int64 timestamp = System.BitConverter.ToInt64(p, pp + 1);
                            byte accuracy = p[pp + 9];
                            byte length = p[pp + 10];
                            pp += 11;
                            if (length == 3)
                            {
                                float X = System.BitConverter.ToSingle(p, pp);
                                pp += 4;
                                float Y = System.BitConverter.ToSingle(p, pp);
                                pp += 4;
                                float Z = System.BitConverter.ToSingle(p, pp);
                                pp += 4;
                                processGravity(timestamp, accuracy, X, Y, Z);
                            }
                            else return;
                        }
                        break;
                    case 4: // magnetic field
                        {
                            Int64 timestamp = System.BitConverter.ToInt64(p, pp + 1);
                            byte accuracy = p[pp + 9];
                            byte length = p[pp + 10];
                            pp += 11;
                            if (length == 3)
                            {
                                float X = System.BitConverter.ToSingle(p, pp);
                                pp += 4;
                                float Y = System.BitConverter.ToSingle(p, pp);
                                pp += 4;
                                float Z = System.BitConverter.ToSingle(p, pp);
                                pp += 4;
                                processMagnetic(timestamp, accuracy, X, Y, Z);
                            }
                            else return;
                        }
                        break;
                    case 31: // keys
                        {
                            Int32 keys = System.BitConverter.ToInt32(p, pp + 1);

                            //if (keys != keysPressed) SuperController.LogError($"keys = {keys:X}");

                            keysPressed = keys;
                            pp += 5;
                            break;
                        }
                    case 32: // azimuth
                        {
                            NORTH_DIRECTION = System.BitConverter.ToInt32(p, pp+1);
                            pp += 5;
                            break;
                        }

                    case 33: // Position v1: version, state, reference, sequence, xyz.
                        if (pp + 31 > p.Length) return;
                        processPosition(p, pp);
                        pp += 31;
                        break;

                    case 0xff: // announce packet
                        return;

                    default: // error
                        return;
                }

                if (pp < 0 || pp >= p.Length)
                    return;
            }
        }

        // Update is called with each rendered frame by Unity
        void Update()
        {
            string error = receiverError;
            if (error != null)
            {
                receiverError = null;
                SuperController.LogError(error);
            }
            if (positionSequence >= 0 && TickCount - lastPositionTick > 500) {
                resetPositionTracking();
                positionSequence = -1;
            }

            try
            {
                {
                    int udpCounter = this.udpCounter;
                    if (udpCounter >= udpCounterLastReported + 100)
                    {
                        //SuperController.LogError($"UDP reveiver have received {udpCounter} packets.");
                        udpCounterLastReported = udpCounter;
                    }
                }

                Queue queue;
                lock (dataChannel.SyncRoot)
                {
                    queue = (Queue)(dataChannel.Clone());
                    dataChannel.Clear();
                }
                while (queue.Count > 0)
                    processPacket((byte[])queue.Dequeue());

            }
            catch (Exception e)
            {
                SuperController.LogError("Exception caught: " + e);
            }
        }

        // FixedUpdate is called with each physics simulation frame by Unity
        void FixedUpdate()
        {
            try
            {
                // put code in here
            }
            catch (Exception e)
            {
                SuperController.LogError("Exception caught: " + e);
            }
        }

        // OnDestroy is where you should put any cleanup
        // if you registered objects to supercontroller or atom, you should unregister them here
        void OnDestroy()
        {
            StopReceiver();
        }


        #region UDP messages for debugging
        /*
        // this is just for debuging. Not related to other UDP activities
        // should be disabled upon release
        static private UdpClient __d_udpClient = null;
        public static void d(string str)
        {


            if (null == __d_udpClient)
            {
                try
                {
                    __d_udpClient = new UdpClient();
                }
                catch (Exception e)
                {
                }
            }
            IPEndPoint remoteIP = new IPEndPoint(IPAddress.Parse("192.168.1.11"), 65533);
            byte[] b = ASCIIEncoding.ASCII.GetBytes(str);

            __d_udpClient.Send(b, b.Length, remoteIP);

            // tcpdump -Aqnn udp port 65533
            // or, nicer:
            // unbuffer tcpdump -Aqnn udp port 65533 | stdbuf -o0 grep -v '192.168' | cut -b 29-
        }
        */
        #endregion


        private static long tickstart = -1;
        static public int TickCount {
        	get {
        		long t = System.DateTime.Now.Ticks;
        		if(tickstart == -1) tickstart=t;
        		return (int)((t-tickstart)/10000);
        	}
        }

    }

}