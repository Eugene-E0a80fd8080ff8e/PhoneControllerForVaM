package local.movementtrackerforvam.movementtrackerforvam;
import android.Manifest;
import android.content.*;
import android.content.pm.PackageManager;
import android.graphics.*;
import android.os.*;
import android.preference.PreferenceManager;
import android.support.v7.app.AppCompatActivity;
import android.view.*;
import android.widget.*;
import com.google.ar.core.*;
import java.io.IOException;
import java.net.*;
import java.nio.*;
import java.util.*;
import java.util.concurrent.atomic.*;

public class CameraActivity extends AppCompatActivity {
 private static final int CAMERA_PERMISSION = 42, PHOTO_STORAGE_PERMISSION = 43;
 private TargetBar targetBar;
 private CameraGrabControl grabControl;
 private GrabModeControl grabModeControl;
 private volatile int grabMode = GrabModeControl.POS_RELAX;
 private boolean showGrab;
 private float grabRadius;
 private ImageView view; private TextView status; private CameraPoseTracker tracker;
 private PositionScaleControl scaleControl;
 private CameraOverlayMenu actionsControl;
 private Button holdAction;
 private TextView liveBadge;
 private LinearLayout captureControls;
 private Button takePictureButton;
 private byte[] displayedJpeg, pendingPhoto;
 private boolean cameraPermissionRequested;
 private volatile DatagramSocket socket; private volatile int generation;
 private volatile boolean held; private final AtomicBoolean posting=new AtomicBoolean();
 private LatestWorker decoder;
 private boolean startRequested=true, activityResumed, cameraRunning;
 private volatile float worldScale=1;
 private final AtomicInteger frameRequest=new AtomicInteger();
 private volatile long referenceOffset; private boolean installing;
 private static class Parts {
  int total, count, received, hintCount;
  long time;
  byte[][] data;
  CameraGrabControl.Marker marker;
 }
 private static final class DecodedFrame {
  final Bitmap image;
  final byte[] jpeg;
  final CameraGrabControl.Marker marker;
  final int instance, revision;
  final long epoch;

  DecodedFrame(Bitmap image, byte[] jpeg, CameraGrabControl.Marker marker, int instance, int revision, long epoch) {
   this.image = image;
   this.jpeg = jpeg;
   this.marker = marker;
   this.instance = instance;
   this.revision = revision;
   this.epoch = epoch;
  }
 }
 private void message(int g,String s) { runOnUiThread(()->{if(g==generation)showStatus(s);}); }
 @Override public void onCreate(Bundle b) { super.onCreate(b);
  LinearLayout root=new LinearLayout(this);root.setOrientation(1);
  status=new TextView(this);status.setText("Camera idle");root.addView(status);
  FrameLayout preview = new FrameLayout(this);
  view = new ImageView(this);
  view.setScaleType(ImageView.ScaleType.FIT_CENTER);
  view.setBackgroundColor(Color.BLACK);
  preview.addView(view, new FrameLayout.LayoutParams(-1, -1));
  grabControl = new CameraGrabControl(this, view, this::canStartGrab);
  preview.addView(grabControl.overlay, new FrameLayout.LayoutParams(-1, -1));

  scaleControl = new PositionScaleControl(this, scale -> {
   grabControl.clear();
   worldScale = scale;
   AppPreferences.connectionAndCamera(this).edit()
       .putString(AppPreferences.POSITION_SCALE, Float.toString(scale)).apply();
  });
  actionsControl = new CameraOverlayMenu(this, Gravity.END, true);
  actionsControl.addAction(getString(R.string.camera_action_start), () -> {
   startRequested = true;
   cameraPermissionRequested = false;
   autoStartWhenReady();
  });
  actionsControl.addAction(getString(R.string.camera_action_stop), () -> {
   startRequested = false;
   stop();
  });
  holdAction = actionsControl.addAction(getString(R.string.camera_action_hold), () -> {
   grabControl.clear();
   held = !held;
   referenceOffset++;
   updateHoldAction();
   if (held && liveBadge.getVisibility() == View.VISIBLE) showStatus("Camera held");
  });
  actionsControl.addAction(getString(R.string.camera_action_frame), this::requestFrame);

  LinearLayout controls = new LinearLayout(this);
  controls.setOrientation(LinearLayout.HORIZONTAL);
  controls.setBaselineAligned(false);
  controls.addView(scaleControl, new LinearLayout.LayoutParams(0, -2, 1));
  liveBadge = new TextView(this);
  liveBadge.setText(R.string.camera_live);
  liveBadge.setGravity(Gravity.CENTER);
  liveBadge.setTextColor(Color.WHITE);
  liveBadge.setTextSize(14);
  liveBadge.setBackground(CameraOverlayButton.background(this, 0xFF1556A0, false));
  int badgePadding = CameraOverlayButton.dp(this, 10);
  liveBadge.setPadding(badgePadding, 0, badgePadding, 0);
  liveBadge.setVisibility(View.GONE);
  controls.addView(liveBadge, new LinearLayout.LayoutParams(-2, CameraOverlayButton.dp(this, 48)));
  controls.addView(actionsControl, new LinearLayout.LayoutParams(0, -2, 1));
  FrameLayout.LayoutParams overlay = new FrameLayout.LayoutParams(-1, -2, Gravity.TOP | Gravity.START);
  int margin = CameraOverlayButton.dp(this, 8);
  overlay.setMargins(margin, margin, margin, 0);
  preview.addView(controls, overlay);
  targetBar = new TargetBar(this, false, this::refreshGrabTarget);
  targetBar.setFrameCurrentAction(this::requestFrame);
  LinearLayout bottomControls = new LinearLayout(this);
  bottomControls.setGravity(Gravity.BOTTOM);
  bottomControls.setBaselineAligned(false);
  bottomControls.setLayoutDirection(View.LAYOUT_DIRECTION_LTR);
  bottomControls.addView(targetBar, new LinearLayout.LayoutParams(0, -2, 1));
  grabModeControl = new GrabModeControl(this, mode -> {
   grabControl.clear();
   grabMode = mode;
   PreferenceManager.getDefaultSharedPreferences(this).edit().putInt(AppPreferences.GRAB_MODE, mode).apply();
   refreshGrabTarget();
  });
  bottomControls.addView(grabModeControl, new LinearLayout.LayoutParams(-2, -2));
  captureControls = new LinearLayout(this);
  captureControls.setOrientation(LinearLayout.VERTICAL);
  takePictureButton = new CameraOverlayButton(this);
  takePictureButton.setText(R.string.camera_take_picture);
  takePictureButton.setTextSize(20);
  takePictureButton.setVisibility(View.GONE);
  takePictureButton.setOnClickListener(button -> takePicture());
  int buttonSize = CameraOverlayButton.dp(this, 115);
  captureControls.addView(takePictureButton, new LinearLayout.LayoutParams(buttonSize, buttonSize));
  captureControls.addView(grabControl.button, new LinearLayout.LayoutParams(buttonSize, buttonSize));
  LinearLayout.LayoutParams captureLayout = new LinearLayout.LayoutParams(-2, -2);
  captureLayout.setMarginStart(CameraOverlayButton.dp(this, 4));
  bottomControls.addView(captureControls, captureLayout);
  FrameLayout.LayoutParams targetOverlay = new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM | Gravity.LEFT);
  targetOverlay.setMargins(margin, 0, margin, margin);
  preview.addView(bottomControls, targetOverlay);
  preview.getViewTreeObserver().addOnGlobalLayoutListener(() -> {
   WindowInsets insets = preview.getRootWindowInsets();
   if (insets == null) return;
   View decor = getWindow().getDecorView();
   int[] previewLocation = new int[2], decorLocation = new int[2];
   preview.getLocationInWindow(previewLocation);
   decor.getLocationInWindow(decorLocation);
   int safeBottom = decorLocation[1] + decor.getHeight() - insets.getSystemWindowInsetBottom();
   int bottomMargin = margin + Math.max(0, previewLocation[1] + preview.getHeight() - safeBottom);
   if (targetOverlay.bottomMargin != bottomMargin) {
    targetOverlay.bottomMargin = bottomMargin;
    bottomControls.setLayoutParams(targetOverlay);
   }
  });
  root.addView(preview, new LinearLayout.LayoutParams(-1, 0, 1));
  setContentView(root);
  root.getViewTreeObserver().addOnGlobalLayoutListener(this::updateTrackingDisplayGeometry);
  // AppCompat 27 can place content behind the action bar on edge-to-edge Android.
  // Measure actual overlap so portrait/landscape and different bar heights work.
  View actionBar=findViewById(android.support.v7.appcompat.R.id.action_bar_container);
  if(actionBar!=null) root.getViewTreeObserver().addOnGlobalLayoutListener(()->{
   int[] contentPosition=new int[2],barPosition=new int[2];
   root.getLocationInWindow(contentPosition);actionBar.getLocationInWindow(barPosition);
   int top=actionBar.isShown()?Math.max(0,barPosition[1]+actionBar.getHeight()-contentPosition[1]):0;
   if(root.getPaddingTop()!=top)root.setPadding(root.getPaddingLeft(),top,root.getPaddingRight(),root.getPaddingBottom());
  });
 }
 @Override public boolean onCreateOptionsMenu(Menu menu) {
  getMenuInflater().inflate(R.menu.settings_menu, menu);
  menu.findItem(R.id.invoke_camera_page).setVisible(false);
  menu.findItem(R.id.invoke_joints_page).setVisible(true);
  return true;
 }

 @Override public boolean onOptionsItemSelected(MenuItem item) {
  if (item.getItemId() == R.id.invoke_joints_page) {
   Intent intent = new Intent(this, MainActivity.class);
   intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
   startActivity(intent);
   return true;
  }
  if (item.getItemId() == R.id.invoke_settings_page) {
   startActivity(new Intent(this, SettingsActivity.class));
   return true;
  }
  return super.onOptionsItemSelected(item);
 }

 @Override public void onBackPressed() {
  if (!grabModeControl.collapse() && !actionsControl.collapse() && !scaleControl.collapse()) super.onBackPressed();
 }

 @Override public void onResume() {
  super.onResume();
  SharedPreferences preferences = PreferenceManager.getDefaultSharedPreferences(this);
  showGrab = preferences.getBoolean(AppPreferences.SHOW_CAMERA_GRAB, true);
  boolean showPicture = preferences.getBoolean(AppPreferences.SHOW_CAMERA_PICTURE, false);
  takePictureButton.setVisibility(showPicture ? View.VISIBLE : View.GONE);
  captureControls.setVisibility(showGrab || showPicture ? View.VISIBLE : View.GONE);
  grabRadius = AppPreferences.readGrabRadius(preferences) / 100f;
  grabControl.configure(showGrab, grabRadius);
  grabMode = preferences.getInt(AppPreferences.GRAB_MODE, GrabModeControl.POS_RELAX);
  grabModeControl.setVisibility(showGrab ? View.VISIBLE : View.GONE);
  refreshGrabTarget();
  targetBar.start();
  activityResumed = true;
  worldScale = AppPreferences.readPositionScale(this);
  scaleControl.setScales(worldScale, AppPreferences.readScalePresets(this));
  view.post(this::autoStartWhenReady);
 }

 private void refreshGrabTarget() {
  TargetConnection.State state = TargetConnection.state;
  grabControl.checkTarget(state);
  boolean connected = state != null && state.fresh();
  boolean supportsMode = !connected || state.cameraGrabModes;
  boolean supportsRotationOff = !connected || state.cameraGrabRotationOff;
  int mode = connected ? grabModeFor(state) : grabMode;
  grabModeControl.showMode(mode, supportsMode, supportsRotationOff, !connected || state.cameraGrabHeadOnly);
 }

 private int grabModeFor(TargetConnection.State state) {
  int mode = grabMode;
  if (!state.cameraGrabModes || (mode == GrabModeControl.POS_RELAX && !state.cameraGrabRotationOff))
   return GrabModeControl.POSITION;
  if (mode == GrabModeControl.HEAD_ONLY && !state.cameraGrabHeadOnly) return GrabModeControl.BOTH;
  return mode;
 }

 private void requestFrame() {
  grabControl.clear();
  frameRequest.incrementAndGet();
 }

 private boolean canStartGrab() {
  TargetConnection.State target = TargetConnection.state;
  CameraPoseTracker.Sample pose = CameraPoseTracker.sample;
  return activityResumed && !held && liveBadge.getVisibility() == View.VISIBLE
      && target != null && target.fresh() && target.target != 0 && target.cameraGrab
      && !target.loading && !TargetConnection.pending()
      && pose != null && SystemClock.elapsedRealtime() - pose.sampledAt < 200;
 }

 private void showStatus(String text) {
  grabControl.clear();
  liveBadge.setVisibility(View.GONE);
  if (!status.getText().toString().equals(text)) status.setText(text);
  status.setVisibility(View.VISIBLE);
 }

 private void showLive(int width, int height) {
  if (liveBadge.getVisibility() == View.VISIBLE) return;
  liveBadge.setContentDescription(getString(R.string.camera_live_description, width, height));
  status.setVisibility(View.GONE);
  liveBadge.setVisibility(View.VISIBLE);
 }

 private int streamResolution() {
  try {
   return Integer.parseInt(PreferenceManager.getDefaultSharedPreferences(this)
       .getString(AppPreferences.CAMERA_RESOLUTION, Integer.toString(CameraStreamSize.DEFAULT_RESOLUTION)));
  } catch (NumberFormatException invalid) {
   return CameraStreamSize.DEFAULT_RESOLUTION;
  }
 }

 private void updateHoldAction() {
  holdAction.setText(held ? R.string.camera_action_resume : R.string.camera_action_hold);
 }

 @Override public void onPause(){actionsControl.collapse();activityResumed=false;targetBar.stop();stop();super.onPause();}
 private void autoStartWhenReady(){
  if(!activityResumed||!startRequested||cameraRunning)return;
  // Wait for layout and release of any previous ARCore session before reopening it.
  if(view.getWidth()==0||view.getHeight()==0||(tracker!=null&&!tracker.stopped)){
   view.postDelayed(this::autoStartWhenReady,50);return;
  }
  start();
 }
 private void takePicture() {
  byte[] jpeg = displayedJpeg;
  if (jpeg == null) {
   Toast.makeText(this, R.string.camera_picture_no_frame, Toast.LENGTH_SHORT).show();
   return;
  }
  if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q
      && checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
   pendingPhoto = jpeg;
   takePictureButton.setEnabled(false);
   requestPermissions(new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE}, PHOTO_STORAGE_PERMISSION);
   return;
  }
  savePicture(jpeg);
 }

 private void savePicture(byte[] jpeg) {
  takePictureButton.setEnabled(false);
  new Thread(() -> {
   int message = R.string.camera_picture_saved;
   try {
    CameraPhotoStore.save(getApplicationContext(), jpeg);
   } catch (IOException | RuntimeException failure) {
    android.util.Log.e("CameraActivity", "Could not save VaM picture", failure);
    message = R.string.camera_picture_failed;
   }
   int result = message;
   runOnUiThread(() -> {
    if (isDestroyed()) return;
    takePictureButton.setEnabled(true);
    Toast.makeText(this, result, Toast.LENGTH_SHORT).show();
   });
  }, "Save VaM picture").start();
 }

 @Override public void onRequestPermissionsResult(int request, String[] permissions, int[] results) {
  super.onRequestPermissionsResult(request, permissions, results);
  boolean granted = results.length > 0 && results[0] == PackageManager.PERMISSION_GRANTED;
  if (request == CAMERA_PERMISSION && granted) autoStartWhenReady();
  if (request == PHOTO_STORAGE_PERMISSION) {
   byte[] jpeg = pendingPhoto;
   pendingPhoto = null;
   if (granted && jpeg != null) savePicture(jpeg);
   else {
    takePictureButton.setEnabled(true);
    if (!granted) Toast.makeText(this, R.string.camera_picture_permission_required, Toast.LENGTH_SHORT).show();
   }
  }
 }
 private void updateTrackingDisplayGeometry(){if(tracker!=null)tracker.setDisplayGeometry(getWindowManager().getDefaultDisplay().getRotation(),view.getWidth(),view.getHeight());}
 private void stop(){cameraRunning=false;generation++;if(decoder!=null){decoder.close();decoder=null;}DatagramSocket s=socket;socket=null;if(s!=null)s.close();if(tracker!=null)tracker.stop();getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);showStatus("Stopped — image is not live");}
 private void start(){
  if(tracker!=null&&!tracker.stopped){showStatus("Tracking is active or still stopping; press Stop before restarting");return;}
  if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
   showStatus(getString(R.string.camera_permission_required));
   if (!cameraPermissionRequested) {
    cameraPermissionRequested = true;
    requestPermissions(new String[]{Manifest.permission.CAMERA}, CAMERA_PERMISSION);
   }
   return;
  }
  try{
   if(ArCoreApk.getInstance().requestInstall(this,!installing)==ArCoreApk.InstallStatus.INSTALL_REQUESTED){installing=true;return;}
   Session session=new Session(this);Config c=session.getConfig();c.setPlaneFindingMode(Config.PlaneFindingMode.DISABLED);c.setUpdateMode(Config.UpdateMode.LATEST_CAMERA_IMAGE);session.configure(c);session.resume();
   boolean alignHorizons = PreferenceManager.getDefaultSharedPreferences(this)
       .getBoolean(AppPreferences.ALIGN_HORIZONS, true);
   held = false;
   updateHoldAction();
   int g=++generation;decoder=new LatestWorker("Camera JPEG decode");tracker=new CameraPoseTracker(session,alignHorizons,s->message(g,s));updateTrackingDisplayGeometry();tracker.start();cameraRunning=true;getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
   int liveHeight = view.getHeight() + (status.getVisibility() == View.VISIBLE ? status.getHeight() : 0);
   int[] size = CameraStreamSize.forViewport(view.getWidth(), liveHeight, streamResolution());
   boolean grabRequested = showGrab;
   float streamGrabRadius = grabRadius;
   LatestWorker streamDecoder = decoder;
   new Thread(() -> receive(g, size[0], size[1], alignHorizons, grabRequested, streamGrabRadius, streamDecoder), "Camera UDP").start();
  }catch(Exception e){showStatus("ARCore: "+e.getMessage());}
 }
 private void receive(int g,int streamWidth,int streamHeight,boolean alignHorizons,boolean grabRequested,float grabRadius,LatestWorker decodeWorker){
  try(DatagramSocket s=new DatagramSocket()){
   if(g!=generation)return;socket=s;s.setBroadcast(true);s.setSoTimeout(8);s.setReceiveBufferSize(1024*1024);
   InetSocketAddress peer=null;
   AtomicReference<DecodedFrame> pendingImage = new AtomicReference<>();
   AtomicBoolean posting=new AtomicBoolean();
   AtomicLong decodeEpoch=new AtomicLong();
   AtomicLong lastDisplayedFrame = new AtomicLong();
   long session=0,seq=0,lastHello=0,lastPose=0,lastCompleted=-1;int targetRevision=0,targetInstance=0;
   Map<Long,Parts> frames=new LinkedHashMap<>();byte[] receive=new byte[1500];
   message(g,"Finding VaM camera service");
   while(g==generation){long now=SystemClock.elapsedRealtime();
    long lastFrame = lastDisplayedFrame.get();
    if (lastFrame > 0 && now - lastFrame > 1000 && lastDisplayedFrame.compareAndSet(lastFrame, 0)) {
     held = true;
     message(g, "Stream stalled — motion held");
     runOnUiThread(() -> { if (g == generation) updateHoldAction(); });
    }
    TargetConnection.State target=TargetConnection.state;
    if(target==null||!target.fresh()){Thread.sleep(20);continue;}
    if(target.instance!=targetInstance||target.revision!=targetRevision){
     targetInstance=target.instance;targetRevision=target.revision;decodeEpoch.incrementAndGet();frames.clear();referenceOffset++;
     DecodedFrame stale = pendingImage.getAndSet(null);
     if (stale != null) stale.image.recycle();
     lastDisplayedFrame.set(0);
     runOnUiThread(() -> {
      if (g != generation) return;
      view.setImageDrawable(null);
      showStatus("Waiting for camera frames");
     });
    }
    if(peer!=null&&!peer.getAddress().equals(target.address)){peer=null;session=0;lastCompleted=-1;}
    boolean grabProtocol = grabRequested && target.cameraGrab;
    int version = !grabProtocol ? 2 : target.cameraGrabVersion >= 4 ? 4 : 3;
    if (now - lastHello > 500) {
     String greeting = "PCCHELLO" + version;
     byte[] h = ByteBuffer.allocate(13).order(ByteOrder.LITTLE_ENDIAN).put(greeting.getBytes("US-ASCII"))
         .putShort((short) streamWidth).putShort((short) streamHeight).array();
     s.send(new DatagramPacket(h, h.length, peer == null ? new InetSocketAddress(target.address, AppPreferences.VAM_PORT) : peer));
     lastHello = now;
    }
    CameraPoseTracker.Sample pose=CameraPoseTracker.sample;
    if(target.target!=0&&!TargetConnection.pending()&&peer!=null&&now-lastPose>=16&&pose!=null&&now-pose.sampledAt<200){
     ByteBuffer p = ByteBuffer.allocate(version == 4 ? 84 : grabProtocol ? 80 : 72).order(ByteOrder.LITTLE_ENDIAN);
     p.putInt(0x00504350 | (('0' + version) << 24)).putInt((int) session).putLong(++seq)
         .putLong(pose.reference + referenceOffset);
     p.putFloat(pose.x).putFloat(pose.y).putFloat(pose.z).putFloat(pose.qx).putFloat(pose.qy)
         .putFloat(pose.qz).putFloat(pose.qw).putFloat(worldScale);
     boolean cameraHeld = held;
     p.put((byte) (cameraHeld ? 1 : 0)).put((byte) (alignHorizons ? 1 : 0));
     p.putInt(60, frameRequest.get());
     p.putInt(64, targetInstance);
     p.putInt(68, targetRevision);
     if (grabProtocol) {
      p.put(58, (byte) 1);
      if (version == 4) {
       int flags = grabControl.showingPoints() ? 1 : 0;
       if (target.cameraGrabModes) flags |= grabModeFor(target);
       p.put(59, (byte) flags);
       p.putFloat(80, grabRadius);
      }
      CameraGrabControl.Grip grip = cameraHeld ? null : grabControl.gripFor(session, targetInstance, targetRevision);
      if (grip != null) {
       p.putInt(72, grip.gesture);
       p.putInt(76, grip.marker.frame);
      }
     }
     s.send(new DatagramPacket(p.array(), p.capacity(), peer));
     lastPose = now;
    }
    frames.values().removeIf(f->now-f.time>500);
    DatagramPacket packet=new DatagramPacket(receive,receive.length);
    try{s.receive(packet);}catch(SocketTimeoutException e){continue;}
    ByteBuffer b=ByteBuffer.wrap(receive,0,packet.getLength()).order(ByteOrder.LITTLE_ENDIAN);
    if(packet.getLength()==12&&b.getInt(0)==(0x00414350 | (('0' + version) << 24))){
     if(!packet.getAddress().equals(target.address)||packet.getPort()!=AppPreferences.VAM_PORT)continue;
     if(Short.toUnsignedInt(b.getShort(8))!=streamWidth||Short.toUnsignedInt(b.getShort(10))!=streamHeight){message(g,"Server image size mismatch; update PhoneCamera plugin");continue;}
     InetSocketAddress from=(InetSocketAddress)packet.getSocketAddress();if(peer!=null&&!peer.equals(from))continue;peer=from;long id=Integer.toUnsignedLong(b.getInt(4));
     if(session!=id){AppPreferences.connectionAndCamera(this).edit().putString(AppPreferences.LAST_SERVER,from.getAddress().getHostAddress()).apply();decodeEpoch.incrementAndGet();session=id;frames.clear();lastCompleted=-1;seq=0;referenceOffset++;lastDisplayedFrame.set(0);message(g,"Waiting for camera frames");}continue;
    }
    int headerSize = grabProtocol ? 48 : 36;
    int chunk = 1200 - headerSize;
    if (peer == null || !peer.equals(packet.getSocketAddress()) || packet.getLength() < headerSize
        || b.getInt(0) != (0x00464350 | (('0' + version) << 24))
        || Integer.toUnsignedLong(b.getInt(4)) != session
        || b.getInt(28) != targetInstance || b.getInt(32) != targetRevision) continue;
    long id = Integer.toUnsignedLong(b.getInt(8));
    int index = Short.toUnsignedInt(b.getShort(12)), count = Short.toUnsignedInt(b.getShort(14));
    int total = b.getInt(16);
    int hintCount = version == 4 ? Byte.toUnsignedInt(b.get(41)) : 0;
    int hintBytes = hintCount * 4;
    int payloadOffset = headerSize + (index == 0 ? hintBytes : 0);
    int jpegOffset = index == 0 ? 0 : index * chunk - hintBytes;
    int length = packet.getLength() - payloadOffset;
    if (id <= lastCompleted || total < 1 || total > 1024 * 1024
        || count != (total + hintBytes + chunk - 1) / chunk || index >= count
        || length != Math.min(1200 - payloadOffset, total - jpegOffset)) continue;

    Parts f = frames.get(id);
    if (f == null) {
     if (frames.size() >= 8) frames.remove(frames.keySet().iterator().next());
     f = new Parts();
     f.total = total; f.count = count; f.time = now; f.hintCount = hintCount;
     f.data = new byte[count][];
     frames.put(id, f);
    }
    if (f.total != total || f.count != count || f.hintCount != hintCount || f.data[index] != null) continue;
    if (grabProtocol && index == 0) {
     f.marker = new CameraGrabControl.Marker(b, session, targetInstance, targetRevision, hintCount);
    }
    f.data[index] = Arrays.copyOfRange(receive, payloadOffset, packet.getLength());
    f.received++;
    if (f.received != count) continue;
    byte[] jpeg = new byte[total];
    int offset = 0;
    for (byte[] part : f.data) {
     System.arraycopy(part, 0, jpeg, offset, part.length);
     offset += part.length;
    }
    frames.remove(id);
    lastCompleted = id;
    final int imageTargetRevision = targetRevision, imageTargetInstance = targetInstance;
    final CameraGrabControl.Marker marker = f.marker;
    final long frameId=id, streamSession=session, epoch=decodeEpoch.get(); final InetSocketAddress endpoint=peer;
    decodeWorker.submit(()->{
     if(g!=generation||epoch!=decodeEpoch.get())return;
     try{
      BitmapFactory.Options options=new BitmapFactory.Options();options.inJustDecodeBounds=true;BitmapFactory.decodeByteArray(jpeg,0,jpeg.length,options);
      if(options.outWidth!=streamWidth||options.outHeight!=streamHeight)return;
      Bitmap image=BitmapFactory.decodeByteArray(jpeg,0,jpeg.length);if(image==null)return;
      if(g!=generation||epoch!=decodeEpoch.get()){image.recycle();return;}
      byte[] ack=ByteBuffer.allocate(12).order(ByteOrder.LITTLE_ENDIAN).put(new byte[]{80,66,65,49}).putInt((int)streamSession).putInt((int)frameId).array();
      try{s.send(new DatagramPacket(ack,12,endpoint));}catch(java.io.IOException ignored){}
      DecodedFrame frame = new DecodedFrame(image, jpeg, marker, imageTargetInstance, imageTargetRevision, epoch);
      DecodedFrame old = pendingImage.getAndSet(frame);
      if (old != null) old.image.recycle();
      if (posting.compareAndSet(false, true)) runOnUiThread(() -> {
       posting.set(false);
       DecodedFrame latest = pendingImage.getAndSet(null);
       if (latest == null) return;
       TargetConnection.State liveTarget = TargetConnection.state;
       if (g == generation && latest.epoch == decodeEpoch.get() && liveTarget != null && liveTarget.fresh()
           && liveTarget.instance == latest.instance && liveTarget.revision == latest.revision) {
        view.setImageBitmap(latest.image);
        displayedJpeg = latest.jpeg;
        lastDisplayedFrame.set(SystemClock.elapsedRealtime());
        if (held) showStatus("Camera held");
        else {
         showLive(streamWidth, streamHeight);
         grabControl.showFrame(TargetConnection.pending() ? null : latest.marker);
        }
       } else latest.image.recycle();
      });
     }catch(RuntimeException e){message(g,"JPEG decode: "+e.getMessage());}
    });
   }
  }catch(Exception e){if(g==generation)message(g,"Connection: "+e.getMessage());}
 }
}
