using System;
using System.Collections.Generic;
using UnityEngine;

namespace MVRPlugin
{
    public partial class PhoneController
    {
        private const int GrabPosition = 2, GrabRotation = 4, GrabRotationOff = 8, GrabHeadOnly = 16, GrabAxes = GrabPosition | GrabRotation;
        private const byte GrabOffer = 1, GrabHeld = 2;
        private const double GrabPoseTimeout = .25, GrabOfferTimeout = 1;
        private const float DefaultGrabRadius = .05f;
        private const int MaxGrabHints = 255;

        private struct GrabPointPosition
        {
            public ushort x, y;
        }

        private sealed class GrabMarker
        {
            public FreeControllerV3 point;
            public int viewEpoch, mode;
            public ushort x, y;
            public byte state;
            public int gesture;
            public GrabPointPosition[] hints;
        }

        private sealed class RelaxedBodyControl
        {
            public FreeControllerV3 point;
            public Rigidbody link;
            public FreeControllerV3.PositionState position;
            public FreeControllerV3.RotationState rotation;
        }

        private List<RelaxedBodyControl> relaxedBodyControls;
        private FreeControllerV3 aimedGrabPoint, grabbedPoint;
        private Rigidbody grabAnchor;
        private Vector3 grabOffset;
        private Quaternion grabRotationOffset;
        private int linkedGrabAxes;
        private bool restoreGrabRotation;
        private FreeControllerV3.RotationState rotationBeforeGrab;
        private Worker grabOwner;
        private PoseData grabStartPose;
        private int grabViewEpoch;

        private bool CanAimGrab(Worker active, PoseData pose)
        {
            return active != null && active.grabProtocol && !active.expired && !active.done
                && !cancelled && isActiveAndEnabled && capture != null
                && targetAtom != null && targetAtom.on && !SuperController.singleton.isLoading
                && pose != null && pose.grabEnabled && !pose.hold && pose.revision == targetRevision
                && Now() - pose.received <= GrabPoseTimeout;
        }

        private int EligibleGrabAxes(FreeControllerV3 point, int mode)
        {
            if (point == null || point.containingAtom != targetAtom || point.control == null
                || !point.gameObject.activeInHierarchy || !point.stateCanBeModified || point.isGrabbing || point.linkToRB != null
                || point.currentPositionState == FreeControllerV3.PositionState.ParentLink
                || point.currentPositionState == FreeControllerV3.PositionState.PhysicsLink
                || point.currentRotationState == FreeControllerV3.RotationState.ParentLink
                || point.currentRotationState == FreeControllerV3.RotationState.PhysicsLink)
                return 0;

            if (targetAtom.type == "Person" && (point == targetAtom.mainController || point.followWhenOffRB == null))
                return 0;
            if (targetAtom.type != "Person" && point != targetAtom.mainController) return 0;

            bool headOnly = (mode & GrabHeadOnly) != 0;
            if (headOnly && (targetAtom.type != "Person" || point != targetAtom.GetStorableByID("headControl")))
                return 0;

            int eligible = 0;
            if ((mode & GrabPosition) != 0 && CanMoveGrabPosition(point)
                && (point.currentPositionState == FreeControllerV3.PositionState.On
                    || point.currentPositionState == FreeControllerV3.PositionState.Comply))
                eligible |= GrabPosition;
            if ((mode & GrabRotation) != 0 && CanMoveGrabRotation(point)
                && (point.currentRotationState == FreeControllerV3.RotationState.On
                    || point.currentRotationState == FreeControllerV3.RotationState.Comply))
                eligible |= GrabRotation;
            return headOnly && eligible != GrabAxes ? 0 : eligible;
        }

        private static bool CanMoveGrabPosition(FreeControllerV3 point)
        {
            return point.canGrabPosition && !(point.xLock && point.yLock && point.zLock)
                && !(point.xLocalLock && point.yLocalLock && point.zLocalLock);
        }

        private static bool CanMoveGrabRotation(FreeControllerV3 point)
        {
            return point.canGrabRotation && !(point.xRotLock && point.yRotLock && point.zRotLock);
        }

        private bool ProjectGrabPoint(FreeControllerV3 point, out Vector3 screen)
        {
            screen = capture.WorldToViewportPoint(point.control.position);
            return screen.z >= capture.nearClipPlane && screen.z <= capture.farClipPlane
                && screen.x >= 0 && screen.x <= 1 && screen.y >= 0 && screen.y <= 1;
        }

        private float GrabDistanceSquared(Vector3 screen)
        {
            // Measure the radius against the shorter image edge.
            float x = (screen.x - .5f) * Mathf.Max(1, capture.aspect);
            float y = (screen.y - .5f) * Mathf.Max(1, 1 / capture.aspect);
            return x * x + y * y;
        }

        private GrabMarker CaptureGrabMarker(Worker active, PoseData pose)
        {
            var marker = new GrabMarker { gesture = active.lastGrabGesture, viewEpoch = grabViewEpoch, mode = pose == null ? 0 : pose.grabMode };
            if (!CanAimGrab(active, pose))
            {
                aimedGrabPoint = null;
                return marker;
            }

            Vector3 screen = Vector3.zero;
            if (grabbedPoint != null)
            {
                // Confirm the grip even when its point goes outside the visible image.
                marker.state = GrabHeld;
                if (grabbedPoint.control != null && ProjectGrabPoint(grabbedPoint, out screen))
                    SetMarkerPosition(marker, screen);
                return marker;
            }

            Vector3 bestScreen;
            float radius = pose.grabRadius;
            float exitRadius = radius * 1.4f;
            bool retainAim = EligibleGrabAxes(aimedGrabPoint, pose.grabMode) != 0 && ProjectGrabPoint(aimedGrabPoint, out screen)
                && GrabDistanceSquared(screen) <= exitRadius * exitRadius;
            if (retainAim) bestScreen = screen;
            else
            {
                aimedGrabPoint = null;
                bestScreen = Vector3.zero;
            }

            List<GrabPointPosition> hints = pose.showGrabHints ? new List<GrabPointPosition>() : null;
            if (!retainAim || hints != null)
            {
                bool person = targetAtom.type == "Person";
                FreeControllerV3[] points = person ? targetAtom.freeControllers : null;
                int count = person ? (points == null ? 0 : points.Length) : 1;
                float bestDistance = radius * radius;
                for (int i = 0; i < count; i++)
                {
                    FreeControllerV3 point = person ? points[i] : targetAtom.mainController;
                    if (EligibleGrabAxes(point, pose.grabMode) == 0 || !ProjectGrabPoint(point, out screen)) continue;
                    if (hints != null && hints.Count < MaxGrabHints) hints.Add(ToGrabImagePoint(screen));
                    if (retainAim) continue;

                    float distance = GrabDistanceSquared(screen);
                    if (distance > bestDistance) continue;
                    if (distance == bestDistance && aimedGrabPoint != null && screen.z >= bestScreen.z) continue;
                    aimedGrabPoint = point;
                    bestScreen = screen;
                    bestDistance = distance;
                }
            }

            if (hints != null && hints.Count > 0) marker.hints = hints.ToArray();
            if (aimedGrabPoint != null)
            {
                marker.point = aimedGrabPoint;
                marker.state = GrabOffer;
                SetMarkerPosition(marker, bestScreen);
            }
            return marker;
        }

        private static GrabPointPosition ToGrabImagePoint(Vector3 screen)
        {
            // Zero means the point is outside the image.
            return new GrabPointPosition
            {
                x = (ushort)(1 + Mathf.RoundToInt(screen.x * 65534)),
                y = (ushort)(1 + Mathf.RoundToInt((1 - screen.y) * 65534))
            };
        }

        private static void SetMarkerPosition(GrabMarker marker, Vector3 screen)
        {
            GrabPointPosition position = ToGrabImagePoint(screen);
            marker.x = position.x;
            marker.y = position.y;
        }

        private bool GripIsCurrent(Worker active, PoseData pose)
        {
            return grabAnchor != null && grabbedPoint != null && grabbedPoint.control != null
                && grabbedPoint.containingAtom == targetAtom && grabbedPoint.linkToRB == grabAnchor
                && grabbedPoint.isGrabbing && grabbedPoint.gameObject.activeInHierarchy
                && ((linkedGrabAxes & GrabPosition) == 0 || (CanMoveGrabPosition(grabbedPoint)
                    && grabbedPoint.currentPositionState == FreeControllerV3.PositionState.ParentLink))
                && ((linkedGrabAxes & GrabRotation) == 0 || (CanMoveGrabRotation(grabbedPoint)
                    && grabbedPoint.currentRotationState == FreeControllerV3.RotationState.ParentLink))
                && ((grabStartPose.grabMode & GrabRotationOff) == 0
                    || grabbedPoint.currentRotationState == FreeControllerV3.RotationState.Off)
                && grabOwner == active && CanAimGrab(active, pose)
                && pose.grabGesture == grabStartPose.grabGesture && pose.revision == grabStartPose.revision
                && pose.reference == grabStartPose.reference && pose.scale == grabStartPose.scale
                && pose.grabMode == grabStartPose.grabMode
                && pose.alignHorizons == grabStartPose.alignHorizons && pose.frameCommand == grabStartPose.frameCommand;
        }

        private void ExpireCameraGrab()
        {
            if (grabOwner != null && !GripIsCurrent(worker, worker == null ? null : worker.Latest()))
                ReleaseCameraGrab();
        }

        private void UpdateCameraGrab(Worker active, PoseData pose)
        {
            if (grabOwner != null && !GripIsCurrent(active, pose)) ReleaseCameraGrab();
            if (grabOwner != null)
            {
                if ((linkedGrabAxes & GrabPosition) != 0)
                {
                    Vector3 position = capture.transform.TransformPoint(grabOffset);
                    if (!FiniteGrabPosition(position))
                    {
                        ReleaseCameraGrab();
                        return;
                    }
                    grabAnchor.position = position;
                }
                if ((linkedGrabAxes & GrabRotation) != 0)
                    grabAnchor.rotation = capture.transform.rotation * grabRotationOffset;
                return;
            }

            if (pose == null || pose.grabGesture <= active.lastGrabGesture) return;
            // Consume each press once, including a rejected press or one whose lease expired.
            active.lastGrabGesture = pose.grabGesture;
            if (!CanAimGrab(active, pose)) return;

            GrabMarker offer = active.FindGrabOffer(pose.grabFrame, targetRevision);
            if (offer == null || offer.viewEpoch != grabViewEpoch || offer.mode != pose.grabMode) return;
            int axes = EligibleGrabAxes(offer.point, pose.grabMode);
            if (axes != 0) BeginCameraGrab(active, pose, offer.point, axes);
        }

        private static bool FiniteGrabPosition(Vector3 position)
        {
            return !float.IsNaN(position.x) && !float.IsInfinity(position.x)
                && !float.IsNaN(position.y) && !float.IsInfinity(position.y)
                && !float.IsNaN(position.z) && !float.IsInfinity(position.z);
        }

        private void BeginCameraGrab(Worker active, PoseData pose, FreeControllerV3 point, int axes)
        {
            Vector3 start = point.control.position;
            if (!FiniteGrabPosition(start)) return;
            GameObject anchor = new GameObject("Phone camera grab");
            try
            {
                anchor.transform.position = start;
                anchor.transform.rotation = point.control.rotation;
                grabAnchor = anchor.AddComponent<Rigidbody>();
                grabAnchor.isKinematic = true;
                grabAnchor.useGravity = false;
                grabOffset = capture.transform.InverseTransformPoint(start);
                grabRotationOffset = Quaternion.Inverse(capture.transform.rotation) * point.control.rotation;
                linkedGrabAxes = axes;
                grabbedPoint = point;
                grabOwner = active;
                grabStartPose = pose;
                point.isGrabbing = true;
                FreeControllerV3.SelectLinkState link = axes == GrabAxes ? FreeControllerV3.SelectLinkState.PositionAndRotation
                    : axes == GrabRotation ? FreeControllerV3.SelectLinkState.Rotation : FreeControllerV3.SelectLinkState.Position;
                point.SelectLinkToRigidbody(grabAnchor, link);
                if ((pose.grabMode & GrabRotationOff) != 0 && point.linkToRB == grabAnchor
                    && point.currentPositionState == FreeControllerV3.PositionState.ParentLink
                    && point.currentRotationState != FreeControllerV3.RotationState.Off)
                {
                    rotationBeforeGrab = point.currentRotationState;
                    restoreGrabRotation = true;
                    point.currentRotationState = FreeControllerV3.RotationState.Off;
                }
                if (!GripIsCurrent(active, pose)) ReleaseCameraGrab();
                else if ((pose.grabMode & GrabHeadOnly) != 0) RelaxBodyControls(point);
            }
            catch (Exception error)
            {
                bool linked = grabAnchor != null && point != null && point.linkToRB == grabAnchor;
                if (grabAnchor != null) ReleaseCameraGrab();
                else UnityEngine.Object.Destroy(anchor);
                if (!linked && point != null && point.linkToRB == null)
                {
                    try { point.isGrabbing = false; }
                    catch (Exception cleanupError) { SuperController.LogError("Camera Grab cleanup: " + cleanupError); }
                }
                SuperController.LogError("Camera Grab start: " + error);
            }
        }

        private void RelaxBodyControls(FreeControllerV3 head)
        {
            Atom person = head.containingAtom;
            if (person == null || person.freeControllers == null) return;
            relaxedBodyControls = new List<RelaxedBodyControl>();
            foreach (FreeControllerV3 point in person.freeControllers)
            {
                if (point == null || point == head || point == person.mainController
                    || point.followWhenOffRB == null || !point.stateCanBeModified || point.isGrabbing)
                    continue;

                relaxedBodyControls.Add(new RelaxedBodyControl
                {
                    point = point, link = point.linkToRB,
                    position = point.currentPositionState, rotation = point.currentRotationState
                });
                point.currentPositionState = FreeControllerV3.PositionState.Off;
                point.currentRotationState = FreeControllerV3.RotationState.Off;
            }
        }

        private static void RestoreBodyControls(List<RelaxedBodyControl> controls)
        {
            if (controls == null) return;
            foreach (RelaxedBodyControl saved in controls)
            {
                FreeControllerV3 point = saved.point;
                if (point == null || point.isGrabbing || point.linkToRB != saved.link) continue;
                try
                {
                    if (point.currentPositionState == FreeControllerV3.PositionState.Off)
                        point.currentPositionState = saved.position;
                }
                catch (Exception error) { SuperController.LogError("Camera Grab body position restore: " + error); }
                try
                {
                    if (point != null && !point.isGrabbing && point.linkToRB == saved.link
                        && point.currentRotationState == FreeControllerV3.RotationState.Off)
                        point.currentRotationState = saved.rotation;
                }
                catch (Exception error) { SuperController.LogError("Camera Grab body rotation restore: " + error); }
            }
        }

        private void ReleaseCameraGrab()
        {
            List<RelaxedBodyControl> body = relaxedBodyControls;
            relaxedBodyControls = null;
            FreeControllerV3 point = grabbedPoint;
            Rigidbody anchor = grabAnchor;
            bool restoreRotation = restoreGrabRotation;
            FreeControllerV3.RotationState previousRotation = rotationBeforeGrab;
            restoreGrabRotation = false;
            grabbedPoint = null;
            grabAnchor = null;
            grabOwner = null;
            grabStartPose = null;
            linkedGrabAxes = 0;
            aimedGrabPoint = null;
            try
            {
                if (!ReferenceEquals(anchor, null) && point != null && ReferenceEquals(point.linkToRB, anchor))
                {
                    try { point.isGrabbing = false; }
                    finally
                    {
                        if (point != null && ReferenceEquals(point.linkToRB, anchor))
                        {
                            try { point.RestorePreLinkState(); }
                            finally
                            {
                                if (restoreRotation && point != null && point.linkToRB == null
                                    && point.currentRotationState == FreeControllerV3.RotationState.Off)
                                    point.currentRotationState = previousRotation;
                            }
                        }
                    }
                }
            }
            catch (Exception error) { SuperController.LogError("Camera Grab release: " + error); }
            finally
            {
                if (anchor != null) UnityEngine.Object.Destroy(anchor.gameObject);
                RestoreBodyControls(body);
            }
        }

        private void ResetGrabView()
        {
            ReleaseCameraGrab();
            grabViewEpoch++;
        }
    }
}
