package local.movementtrackerforvam.movementtrackerforvam;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;
import android.os.SystemClock;
import android.view.MotionEvent;
import android.view.View;
import android.widget.Button;
import android.widget.ImageView;

import java.nio.ByteBuffer;
import java.util.function.BooleanSupplier;

/** Couples the highlight and press gesture to the exact frame displayed in the preview. */
final class CameraGrabControl {
    private static final int OFFER = 1, HELD = 2;
    private static final int INACTIVE_COLOR = 0xFF661D25, ACTIVE_COLOR = 0xFF164C30;

    static final class Marker {
        final long session;
        final int frame, instance, revision, x, y, state, gesture;
        final int[] points;

        Marker(ByteBuffer packet, long session, int instance, int revision, int hintCount) {
            this.session = session;
            this.instance = instance;
            this.revision = revision;
            frame = packet.getInt(8);
            x = Short.toUnsignedInt(packet.getShort(36));
            y = Short.toUnsignedInt(packet.getShort(38));
            state = Byte.toUnsignedInt(packet.get(40));
            gesture = packet.getInt(44);
            points = hintCount == 0 ? null : new int[hintCount];
            for (int i = 0; i < hintCount; i++) points[i] = packet.getInt(48 + i * 4);
        }
    }

    static final class Grip {
        final int gesture;
        final Marker marker;

        Grip(int gesture, Marker marker) {
            this.gesture = gesture;
            this.marker = marker;
        }
    }

    final Button button;
    final View overlay;
    private final BooleanSupplier canStart;
    private boolean enabled;
    private float catchRadius;
    private int buttonColor;
    private volatile boolean showingPoints;
    private Marker displayed;
    private long displayedAt;
    private int nextGesture, pointer = -1;
    private volatile Grip grip;

    CameraGrabControl(Context context, ImageView image, BooleanSupplier canStart) {
        this.canStart = canStart;
        button = new CameraOverlayButton(context);
        button.setText(R.string.camera_grab);
        button.setTextSize(24);
        button.setOnTouchListener((view, event) -> handleTouch(event));
        overlay = new View(context) {
            private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
            private final float[] location = new float[2];
            private final RectF displayedImage = new RectF();

            @Override protected void onDraw(Canvas canvas) {
                Marker marker = displayed;
                if (!enabled || marker == null) return;
                Drawable imageDrawable = image.getDrawable();
                if (imageDrawable == null) return;
                Rect bounds = imageDrawable.getBounds();
                Grip current = grip;
                if (showingPoints && current == null) {
                    drawAimArea(canvas, bounds);
                    if (marker.points != null) {
                        for (int point : marker.points) {
                            int x = point & 0xffff, y = point >>> 16;
                            if (marker.state == OFFER && x == marker.x && y == marker.y) continue;
                            drawRing(canvas, bounds, x, y, 6, 0xFF86CBFF);
                        }
                    }
                }
                if (current == null ? marker.state == OFFER
                        : marker.state == HELD && marker.gesture == current.gesture) {
                    drawRing(canvas, bounds, marker.x, marker.y, 9,
                            marker.state == HELD ? 0xFF76E1AB : 0xFFFFD666);
                }
            }

            private void drawAimArea(Canvas canvas, Rect bounds) {
                displayedImage.set(bounds);
                image.getImageMatrix().mapRect(displayedImage);
                displayedImage.offset(image.getPaddingLeft(), image.getPaddingTop());
                float radius = Math.min(displayedImage.width(), displayedImage.height()) * catchRadius;
                float x = displayedImage.centerX(), y = displayedImage.centerY();
                int saved = canvas.save();
                canvas.clipRect(displayedImage);
                paint.setStyle(Paint.Style.FILL);
                paint.setColor(0x2986CBFF);
                canvas.drawCircle(x, y, radius, paint);
                drawOutline(canvas, x, y, radius, 0xFF86CBFF);
                canvas.restoreToCount(saved);
            }

            private void drawRing(Canvas canvas, Rect bounds, int u, int v, int radiusDp, int color) {
                if (u == 0 || v == 0) return;
                location[0] = bounds.left + bounds.width() * ((u - 1) / 65534f);
                location[1] = bounds.top + bounds.height() * ((v - 1) / 65534f);
                image.getImageMatrix().mapPoints(location);
                float x = location[0] + image.getPaddingLeft();
                float y = location[1] + image.getPaddingTop();
                drawOutline(canvas, x, y, CameraOverlayButton.dp(context, radiusDp), color);
            }

            private void drawOutline(Canvas canvas, float x, float y, float radius, int color) {
                paint.setStyle(Paint.Style.STROKE);
                paint.setStrokeWidth(CameraOverlayButton.dp(context, 4));
                paint.setColor(0xC0182027);
                canvas.drawCircle(x, y, radius, paint);
                paint.setStrokeWidth(CameraOverlayButton.dp(context, 2));
                paint.setColor(color);
                canvas.drawCircle(x, y, radius, paint);
            }
        };
        overlay.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        button.setOnClickListener(view -> {
            TargetConnection.State target = TargetConnection.state;
            if (target == null || target.cameraGrabVersion < 4 || !canStart.getAsBoolean()) return;
            showingPoints = !showingPoints;
            overlay.invalidate();
        });
        updateButton();
    }

    void configure(boolean enabled, float catchRadius) {
        this.enabled = enabled;
        this.catchRadius = catchRadius;
        button.setVisibility(enabled ? View.VISIBLE : View.GONE);
        overlay.setVisibility(enabled ? View.VISIBLE : View.GONE);
        clear();
    }

    void showFrame(Marker marker) {
        if (!enabled) return;
        Grip current = grip;
        if (current != null && (marker == null || marker.session != current.marker.session
                || marker.instance != current.marker.instance || marker.revision != current.marker.revision
                || (marker.gesture >= current.gesture
                    && (marker.gesture != current.gesture || marker.state != HELD)))) {
            release();
        }
        displayed = marker;
        displayedAt = SystemClock.elapsedRealtime();
        updateButton();
    }

    void clear() {
        displayed = null;
        showingPoints = false;
        release();
    }

    void checkTarget(TargetConnection.State target) {
        if (displayed != null && (target == null || !target.fresh() || target.loading
                || TargetConnection.pending() || target.instance != displayed.instance
                || target.revision != displayed.revision)) clear();
    }

    Grip gripFor(long session, int instance, int revision) {
        Grip current = grip;
        if (current == null || current.marker.session != session
                || current.marker.instance != instance || current.marker.revision != revision) return null;
        return current;
    }

    boolean showingPoints() { return showingPoints; }

    private boolean canGrabDisplayed() {
        return enabled && displayed != null && displayed.state == OFFER
                && SystemClock.elapsedRealtime() - displayedAt < 500 && canStart.getAsBoolean();
    }

    private boolean handleTouch(MotionEvent event) {
        if (event.getActionMasked() == MotionEvent.ACTION_DOWN) {
            if (!canGrabDisplayed()) return false; // Let a red-button tap use the click listener.
            showingPoints = false;
            pointer = event.getPointerId(0);
            grip = new Grip(++nextGesture, displayed);
            updateButton();
            return true;
        }
        if (pointer == -1) return false;
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_POINTER_UP:
                if (event.getPointerId(event.getActionIndex()) == pointer) release();
                break;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                release();
                break;
            default:
                break;
        }
        return true;
    }

    private void release() {
        boolean wasPressing = grip != null;
        grip = null;
        pointer = -1;
        if (wasPressing) button.setPressed(false);
        updateButton();
    }

    private void updateButton() {
        boolean pressing = grip != null;
        boolean available = pressing || canGrabDisplayed();
        int color = available ? ACTIVE_COLOR : INACTIVE_COLOR;
        if (color != buttonColor) {
            buttonColor = color;
            Context context = button.getContext();
            button.setBackground(CameraOverlayButton.background(context, color, true));
            button.setContentDescription(context.getString(available
                    ? R.string.camera_grab_hint : R.string.camera_grab_show_points));
        }
        button.setEnabled(enabled);
        if (pressing) button.setPressed(true);
        overlay.invalidate();
    }
}
