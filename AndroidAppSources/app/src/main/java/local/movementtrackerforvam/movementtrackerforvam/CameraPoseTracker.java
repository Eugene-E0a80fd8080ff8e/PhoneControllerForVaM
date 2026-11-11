package local.movementtrackerforvam.movementtrackerforvam;

import android.opengl.EGL14;
import android.opengl.EGLConfig;
import android.opengl.EGLContext;
import android.opengl.EGLDisplay;
import android.opengl.EGLSurface;
import android.opengl.GLES11Ext;
import android.opengl.GLES20;
import android.os.SystemClock;

import com.google.ar.core.Anchor;
import com.google.ar.core.Frame;
import com.google.ar.core.Pose;
import com.google.ar.core.Session;
import com.google.ar.core.TrackingState;

import java.util.UUID;

/** Owns an offscreen GL context and ARCore session for camera pose tracking. */
final class CameraPoseTracker {
    interface Listener { void status(String message); }
    static final class Sample {
        final long reference;
        final long sampledAt;
        final float x, y, z, qx, qy, qz, qw;
        Sample(long reference, float x, float y, float z, float qx, float qy, float qz, float qw) {
            this.reference = reference; this.sampledAt = SystemClock.elapsedRealtime(); this.x = x; this.y = y; this.z = z; this.qx=qx; this.qy=qy; this.qz=qz; this.qw=qw;
        }
    }
    static volatile Sample sample;
    private final Session session;
    private final boolean alignHorizons;
    private final Listener listener;
    private volatile boolean running;
    volatile boolean stopped;
    private volatile int[] displayGeometry = {0, 1, 1};
    void setDisplayGeometry(int rotation, int width, int height) {
        displayGeometry = new int[]{rotation, Math.max(1, width), Math.max(1, height)};
    }
    private Thread thread;

    CameraPoseTracker(Session session, boolean alignHorizons, Listener listener) {
        this.session = session;
        this.alignHorizons = alignHorizons;
        this.listener = listener;
    }
    void start() { running = true; thread = new Thread(this::run, "ARCore position"); thread.start(); }
    void stop() {
        running = false;
        if (thread != null) {
            // Owner thread releases ARCore and EGL; never join the UI thread.
            thread = null;
        }
        sample = null;

    }
    private void run() {
        EGLDisplay display = EGL14.EGL_NO_DISPLAY;
        EGLContext context = EGL14.EGL_NO_CONTEXT;
        EGLSurface surface = EGL14.EGL_NO_SURFACE;
        Anchor anchor = null;
        String previousStatus = "";
        try {
            display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY);
            int[] version = new int[2];
            if (!EGL14.eglInitialize(display, version, 0, version, 1)) throw new IllegalStateException("OpenGL initialization failed");
            int[] attrs = {EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT, EGL14.EGL_SURFACE_TYPE, EGL14.EGL_PBUFFER_BIT, EGL14.EGL_NONE};
            EGLConfig[] configs = new EGLConfig[1]; int[] count = new int[1];
            if (!EGL14.eglChooseConfig(display, attrs, 0, configs, 0, 1, count, 0) || count[0] == 0) throw new IllegalStateException("No OpenGL configuration");
            context = EGL14.eglCreateContext(display, configs[0], EGL14.EGL_NO_CONTEXT, new int[]{EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE}, 0);
            surface = EGL14.eglCreatePbufferSurface(display, configs[0], new int[]{EGL14.EGL_WIDTH, 1, EGL14.EGL_HEIGHT, 1, EGL14.EGL_NONE}, 0);
            if (!EGL14.eglMakeCurrent(display, surface, surface, context)) throw new IllegalStateException("OpenGL context failed");
            int[] texture = new int[1]; GLES20.glGenTextures(1, texture, 0);
            GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, texture[0]);
            session.setCameraTextureName(texture[0]);
            long reference = 0;
            int previousRotation = -1, previousWidth = -1, previousHeight = -1;
            while (running) {
                int[] geometry = displayGeometry;
                if (geometry[0] != previousRotation || geometry[1] != previousWidth || geometry[2] != previousHeight) {
                    session.setDisplayGeometry(geometry[0], geometry[1], geometry[2]);
                    if (previousRotation != -1 && geometry[0] != previousRotation && anchor != null) {
                        anchor.detach(); anchor = null; sample = null;
                    }
                    previousRotation = geometry[0]; previousWidth = geometry[1]; previousHeight = geometry[2];
                }
                Frame frame = session.update();
                String status;
                if (frame.getCamera().getTrackingState() == TrackingState.TRACKING) {
                    Pose camera = frame.getCamera().getDisplayOrientedPose();
                    if (anchor == null) {
                        Pose origin = alignHorizons ? camera.extractTranslation() : camera;
                        anchor = session.createAnchor(origin);
                        reference = UUID.randomUUID().getMostSignificantBits();
                    }
                    if (anchor.getTrackingState() == TrackingState.TRACKING) {
                        Pose anchorPose = anchor.getPose();
                        if (alignHorizons) {
                            // Keep the anchor's position and heading, with ARCore world +Y as up.
                            float[] forward = anchorPose.getZAxis();
                            double halfYaw = Math.atan2(forward[0], forward[2]) * 0.5;
                            Pose yaw = Pose.makeRotation(0, (float) Math.sin(halfYaw), 0, (float) Math.cos(halfYaw));
                            anchorPose = anchorPose.extractTranslation().compose(yaw);
                        }
                        Pose relative = anchorPose.inverse().compose(camera);
                        sample = new Sample(reference, relative.tx(), relative.ty(), relative.tz(), relative.qx(), relative.qy(), relative.qz(), relative.qw());
                        status = "Position: tracking";
                    } else {
                        sample = null;
                        anchor.detach();
                        anchor = null;
                        status = "Position: locating reference";
                    }
                } else {
                    sample = null;
                    if (anchor != null) { anchor.detach(); anchor = null; }
                    String reason = frame.getCamera().getTrackingFailureReason().toString();
                    status = reason.equals("NONE") ? "Position: starting" : "Position: " + reason.replace('_', ' ').toLowerCase(java.util.Locale.ROOT);
                }
                if (!status.equals(previousStatus)) { previousStatus = status; listener.status(status); }
                SystemClock.sleep(16);
            }
        } catch (Exception e) {
            sample = null;
            listener.status("Position unavailable: " + e.getMessage());
        } finally {
            sample = null;
            if (anchor != null) anchor.detach();
            session.pause(); session.close();
            if (display != EGL14.EGL_NO_DISPLAY) {
                EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT);
                if (surface != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(display, surface);
                if (context != EGL14.EGL_NO_CONTEXT) EGL14.eglDestroyContext(display, context);
                EGL14.eglTerminate(display);
            }
            stopped=true;
        }
    }
}
