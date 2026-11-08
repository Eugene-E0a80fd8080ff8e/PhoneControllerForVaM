package local.movementtrackerforvam.movementtrackerforvam;
import android.Manifest;
import android.content.*;
import android.content.pm.PackageManager;
import android.graphics.*;
import android.os.*;
import android.support.v7.app.AppCompatActivity;
import android.view.*;
import android.widget.*;
import com.google.ar.core.*;
import java.net.*;
import java.nio.*;
import java.util.*;
import java.util.concurrent.atomic.*;

public class CameraActivity extends AppCompatActivity {
 private ImageView view; private TextView status; private CameraPoseTracker tracker;
 private volatile DatagramSocket socket; private volatile int generation;
 private volatile boolean held; private final AtomicBoolean posting=new AtomicBoolean();
 private LatestWorker decoder;
 private boolean startRequested=true, activityResumed, cameraRunning;
 private volatile float worldScale=1;
 private final AtomicInteger frameRequest=new AtomicInteger();
 private volatile long referenceOffset; private boolean installing;
 private static class Parts { int total,count,received; long time; byte[][] data; }
 private void message(int g,String s) { runOnUiThread(()->{if(g==generation)status.setText(s);}); }
 @Override public void onCreate(Bundle b) { super.onCreate(b);setTitle("Camera");
  LinearLayout root=new LinearLayout(this);root.setOrientation(1);
  status=new TextView(this);status.setText("Camera idle");root.addView(status);
  LinearLayout buttons=new LinearLayout(this);root.addView(buttons);
  Button start=new Button(this);start.setText("Start");start.setOnClickListener(v->{startRequested=true;autoStartWhenReady();});start.setMinWidth(0);start.setMinimumWidth(0);buttons.addView(start,new LinearLayout.LayoutParams(0,-2,1));
  Button stop=new Button(this);stop.setText("Stop");stop.setOnClickListener(v->{startRequested=false;stop();});stop.setMinWidth(0);stop.setMinimumWidth(0);buttons.addView(stop,new LinearLayout.LayoutParams(0,-2,1));
  Button hold=new Button(this);hold.setText("Hold");hold.setOnClickListener(v->{held=!held;hold.setText(held?"Resume":"Hold");referenceOffset++;});hold.setMinWidth(0);hold.setMinimumWidth(0);buttons.addView(hold,new LinearLayout.LayoutParams(0,-2,1));
  Button center=new Button(this);center.setText("Frame object");center.setOnClickListener(v->frameRequest.incrementAndGet());center.setMinWidth(0);center.setMinimumWidth(0);buttons.addView(center,new LinearLayout.LayoutParams(0,-2,1));
  try{worldScale=Float.parseFloat(getSharedPreferences("phone_camera",0).getString("scale","1"));}catch(Exception e){worldScale=1;}
  if(!Float.isFinite(worldScale)||worldScale<.02f||worldScale>10)worldScale=1;
  TextView scaleLabel=new TextView(this);scaleLabel.setText(String.format(java.util.Locale.ROOT,"World scale: %.2f×",worldScale));root.addView(scaleLabel);
  SeekBar scaleControl=new SeekBar(this);scaleControl.setMax(100);scaleControl.setProgress((int)Math.round(Math.log(worldScale/.02)/Math.log(500)*100));root.addView(scaleControl);
  scaleControl.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener(){
   public void onProgressChanged(SeekBar bar,int progress,boolean user){if(user){worldScale=(float)(.02*Math.pow(500,progress/100.0));scaleLabel.setText(String.format(java.util.Locale.ROOT,"World scale: %.2f×",worldScale));}}
   public void onStartTrackingTouch(SeekBar bar){}
   public void onStopTrackingTouch(SeekBar bar){getSharedPreferences("phone_camera",0).edit().putString("scale",Float.toString(worldScale)).apply();}
  });
  view=new ImageView(this);view.setScaleType(ImageView.ScaleType.FIT_CENTER);view.setBackgroundColor(Color.BLACK);root.addView(view,new LinearLayout.LayoutParams(-1,0,1));setContentView(root);
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
 @Override public boolean onCreateOptionsMenu(Menu m){m.add(0,1,0,"Controller").setShowAsAction(2);m.add(0,2,1,"Settings").setShowAsAction(2);return true;}
 @Override public boolean onOptionsItemSelected(MenuItem i){if(i.getItemId()==2)startActivity(new Intent(this,CameraSettingsActivity.class));else finish();return true;}
 @Override public void onResume(){super.onResume();activityResumed=true;view.post(this::autoStartWhenReady);}
 @Override public void onPause(){activityResumed=false;stop();super.onPause();}
 private void autoStartWhenReady(){
  if(!activityResumed||!startRequested||cameraRunning)return;
  // Wait for layout and release of any previous ARCore session before reopening it.
  if(view.getWidth()==0||view.getHeight()==0||(tracker!=null&&!tracker.stopped)){
   view.postDelayed(this::autoStartWhenReady,50);return;
  }
  start();
 }
 @Override public void onRequestPermissionsResult(int r,String[] p,int[] v){super.onRequestPermissionsResult(r,p,v);if(r==42&&v.length>0&&v[0]==0)autoStartWhenReady();}
 private void updateTrackingDisplayGeometry(){if(tracker!=null)tracker.setDisplayGeometry(getWindowManager().getDefaultDisplay().getRotation(),view.getWidth(),view.getHeight());}
 private void stop(){cameraRunning=false;generation++;if(decoder!=null){decoder.close();decoder=null;}DatagramSocket s=socket;socket=null;if(s!=null)s.close();if(tracker!=null)tracker.stop();getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);if(status!=null)status.setText("Stopped — image is not live");}
 private void start(){
  if(tracker!=null&&!tracker.stopped){status.setText("Tracking is active or still stopping; press Stop before restarting");return;}
  if(checkSelfPermission(Manifest.permission.CAMERA)!=PackageManager.PERMISSION_GRANTED){requestPermissions(new String[]{Manifest.permission.CAMERA},42);return;}
  try{
   if(ArCoreApk.getInstance().requestInstall(this,!installing)==ArCoreApk.InstallStatus.INSTALL_REQUESTED){installing=true;return;}
   Session session=new Session(this);Config c=session.getConfig();c.setPlaneFindingMode(Config.PlaneFindingMode.DISABLED);c.setUpdateMode(Config.UpdateMode.LATEST_CAMERA_IMAGE);session.configure(c);session.resume();
   int g=++generation;held=false;decoder=new LatestWorker("Camera JPEG decode");tracker=new CameraPoseTracker(session,s->message(g,s));updateTrackingDisplayGeometry();tracker.start();cameraRunning=true;getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
   String host=getSharedPreferences("phone_camera",0).getString("host","");float parsed;
   try{parsed=Float.parseFloat(getSharedPreferences("phone_camera",0).getString("scale","1"));}catch(Exception e){parsed=1;}
   worldScale=Float.isFinite(parsed)&&parsed>=.02f&&parsed<=10?parsed:1;
   int[] size=CameraStreamSize.forViewport(view.getWidth(),view.getHeight());
   new Thread(()->receive(g,host,size[0],size[1],decoder),"Camera UDP").start();
  }catch(Exception e){status.setText("ARCore: "+e.getMessage());}
 }
 private void receive(int g,String host,int streamWidth,int streamHeight,LatestWorker decodeWorker){
  try(DatagramSocket s=new DatagramSocket()){
   if(g!=generation)return;socket=s;s.setBroadcast(true);s.setSoTimeout(8);s.setReceiveBufferSize(1024*1024);
   InetAddress server=InetAddress.getByName(host.isEmpty()?"255.255.255.255":host);InetSocketAddress peer=null;
   AtomicReference<Bitmap> pendingImage=new AtomicReference<>();
   AtomicBoolean posting=new AtomicBoolean();
   AtomicLong decodeEpoch=new AtomicLong();
   long session=0,seq=0,lastHello=0,lastPose=0,lastFrame=0,lastCompleted=-1;int idle=0;
   Map<Long,Parts> frames=new LinkedHashMap<>();byte[] receive=new byte[1500];
   message(g,"Finding VaM camera service");
   while(g==generation){long now=SystemClock.elapsedRealtime();
    if(now-lastHello>500){byte[] h=ByteBuffer.allocate(13).order(ByteOrder.LITTLE_ENDIAN).put("PCCHELLO2".getBytes("US-ASCII")).putShort((short)streamWidth).putShort((short)streamHeight).array();s.send(new DatagramPacket(h,h.length,peer==null?new InetSocketAddress(server,62711):peer));lastHello=now;}
    CameraPoseTracker.Sample pose=CameraPoseTracker.sample;
    if(peer!=null&&now-lastPose>=16&&pose!=null&&now-pose.sampledAt<200){
     ByteBuffer p=ByteBuffer.allocate(64).order(ByteOrder.LITTLE_ENDIAN);p.put(new byte[]{80,67,80,49}).putInt((int)session).putLong(++seq).putLong(pose.reference+referenceOffset);
     p.putFloat(pose.x).putFloat(pose.y).putFloat(pose.z).putFloat(pose.qx).putFloat(pose.qy).putFloat(pose.qz).putFloat(pose.qw).putFloat(worldScale);p.put((byte)(held?1:0));p.putInt(60,frameRequest.get());s.send(new DatagramPacket(p.array(),64,peer));lastPose=now;
    }
    if(lastFrame>0&&now-lastFrame>1000){message(g,"Stream stalled — motion held");held=true;lastFrame=0;}
    frames.values().removeIf(f->now-f.time>500);
    DatagramPacket packet=new DatagramPacket(receive,receive.length);
    try{s.receive(packet);}catch(SocketTimeoutException e){continue;}
    ByteBuffer b=ByteBuffer.wrap(receive,0,packet.getLength()).order(ByteOrder.LITTLE_ENDIAN);
    if(packet.getLength()==12&&b.getInt(0)==0x32414350){
     if(Short.toUnsignedInt(b.getShort(8))!=streamWidth||Short.toUnsignedInt(b.getShort(10))!=streamHeight){message(g,"Server image size mismatch; update PhoneCamera plugin");continue;}
     InetSocketAddress from=(InetSocketAddress)packet.getSocketAddress();if(peer!=null&&!peer.equals(from))continue;peer=from;long id=Integer.toUnsignedLong(b.getInt(4));
     if(session!=id){decodeEpoch.incrementAndGet();session=id;frames.clear();lastCompleted=-1;seq=0;referenceOffset++;}continue;
    }
    if(peer==null||!peer.equals(packet.getSocketAddress())||packet.getLength()<28||b.getInt(0)!=0x31464350||Integer.toUnsignedLong(b.getInt(4))!=session)continue;
    long id=Integer.toUnsignedLong(b.getInt(8));int index=Short.toUnsignedInt(b.getShort(12)),count=Short.toUnsignedInt(b.getShort(14)),total=b.getInt(16),length=packet.getLength()-28;
    if(id<=lastCompleted||total<1||total>1024*1024||count!=(total+1171)/1172||index>=count||length!=Math.min(1172,total-index*1172))continue;
    Parts f=frames.get(id);if(f==null){if(frames.size()>=8)frames.remove(frames.keySet().iterator().next());f=new Parts();f.total=total;f.count=count;f.time=now;f.data=new byte[count][];frames.put(id,f);}
    if(f.total!=total||f.count!=count||f.data[index]!=null)continue;f.data[index]=Arrays.copyOfRange(receive,28,packet.getLength());f.received++;
    if(f.received!=count)continue;byte[] jpeg=new byte[total];int offset=0;for(byte[] part:f.data){System.arraycopy(part,0,jpeg,offset,part.length);offset+=part.length;}frames.remove(id);lastCompleted=id;
    lastFrame=now;
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
      Bitmap old=pendingImage.getAndSet(image);if(old!=null)old.recycle();
      if(posting.compareAndSet(false,true))runOnUiThread(()->{
       posting.set(false);Bitmap latest=pendingImage.getAndSet(null);
       if(latest==null)return;
       if(g==generation&&epoch==decodeEpoch.get()){view.setImageBitmap(latest);status.setText(held?"Camera held":"Live · "+streamWidth+"×"+streamHeight);}else latest.recycle();
      });
     }catch(RuntimeException e){message(g,"JPEG decode: "+e.getMessage());}
    });
   }
  }catch(Exception e){if(g==generation)message(g,"Connection: "+e.getMessage());}
 }
}
