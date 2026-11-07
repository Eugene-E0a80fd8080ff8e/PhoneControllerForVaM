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

/** Owns an offscreen GL context and ARCore session for position-only tracking. */
final class PositionTracker {
    interface Listener { void status(String message); }
    static final class Sample {
        final long reference;
        final long sampledAt;
        final float x, y, z;
        Sample(long reference, float x, float y, float z) {
            this.reference = reference; this.sampledAt = SystemClock.elapsedRealtime(); this.x = x; this.y = y; this.z = z;
        }
    }
    static volatile Sample sample;
    private final Session session;
    private final Listener listener;
    private volatile boolean running;
    private Thread thread;

    PositionTracker(Session session, Listener listener) { this.session = session; this.listener = listener; }
    void start() { running = true; thread = new Thread(this::run, "ARCore position"); thread.start(); }
    void stop() {
        running = false;
        if (thread != null) {
            try { thread.join(); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            thread = null;
        }
        sample = null;
        session.pause();
        session.close();
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
            while (running) {
                Frame frame = session.update();
                String status;
                if (frame.getCamera().getTrackingState() == TrackingState.TRACKING) {
                    if (anchor == null) {
                        // Anchor axes match the Android sensor frame at acquisition.
                        Pose origin = frame.getAndroidSensorPose();
                        anchor = session.createAnchor(origin);
                        reference = UUID.randomUUID().getMostSignificantBits();
                    }
                    if (anchor.getTrackingState() == TrackingState.TRACKING) {
                        Pose relative = anchor.getPose().inverse().compose(frame.getAndroidSensorPose());
                        sample = new Sample(reference, relative.tx(), relative.ty(), relative.tz());
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
            if (display != EGL14.EGL_NO_DISPLAY) {
                EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT);
                if (surface != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(display, surface);
                if (context != EGL14.EGL_NO_CONTEXT) EGL14.eglDestroyContext(display, context);
                EGL14.eglTerminate(display);
            }
        }
    }
}
