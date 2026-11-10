package local.movementtrackerforvam.movementtrackerforvam;

import android.content.Context;
import android.content.SharedPreferences;
import android.preference.PreferenceManager;
import android.util.Log;

import java.io.IOException;
import java.lang.Runnable;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.SocketException;
import java.net.UnknownHostException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.Dictionary;
import java.util.Enumeration;
import java.util.LinkedList;
import java.util.TreeMap;

public class CommunicationThread implements Runnable {

    private volatile boolean stop = false;
    private Thread thr = null;

    private TreeMap<String,Long> clients_lastSeen = new TreeMap<String, Long>();

    public void start()
    {
        stop = false;
        thr = new Thread(this);
        thr.start();

    }

    public void stop() {
        stop = true;
        while(thr.isAlive()) {
            try {
                thr.join();
            } catch (InterruptedException e) {
            }
        }
        thr=null;

        for (DatagramSocket socket : sockets.values()) socket.close();
        sockets.clear();
        clients_lastSeen.clear();

        StateManager.update( (state) -> { state.clientsList = ""; return state; });

    }

    private void updateClients()
    {
        StringBuffer sb = new StringBuffer();
        for(String ia : clients_lastSeen.keySet() )
        {
            if(sb.length() > 0) sb.append(", ");
            sb.append(ia);
        }
        StateManager.update( (state) -> { state.clientsList = sb.toString(); return state; });
    }

    private long getPeriodicity()
    {
        Context context = MyApplication.getAppContext();
        SharedPreferences sp = PreferenceManager.getDefaultSharedPreferences(context);
        String freq = sp.getString("network_freq","60");
        int ifreq = Integer.parseInt(freq);
        long periodicity = 1000 / ifreq;
        return periodicity;
    }


    private TreeMap<Integer,DatagramSocket> sockets = new TreeMap<Integer,DatagramSocket>();
    private DatagramSocket getSocket()
    {
        Context context = MyApplication.getAppContext();
        SharedPreferences sp = PreferenceManager.getDefaultSharedPreferences(context);
        String port = sp.getString("network_port","62701");
        int iport = Integer.parseInt(port);

        if( ! sockets.containsKey(iport) ) {
            try {
                sockets.put(iport, new DatagramSocket(iport) );
                sockets.get(iport).setSoTimeout(1);
                sockets.get(iport).setBroadcast(true);
            }catch(SocketException e) {
                Log.d("CommunicationThread", "Socket Exception");
                throw new AssertionError("A socket was not created\n" + e.toString());
            }
        }

        return sockets.get(iport);

    }

    @Override
    public void run() {

        long timeStart = System.currentTimeMillis();
        byte [] buffer = new byte[1024];
        byte [] receiveBuffer = new byte[1024];
        long lastDiscovery = 0;

        while(! stop){

            long discoveryNow = android.os.SystemClock.elapsedRealtime();
            if (discoveryNow - lastDiscovery >= 500) {
                lastDiscovery = discoveryNow;
                DatagramSocket discoverySocket = getSocket();
                byte[] probe = new byte[]{(byte)0xfe};
                try {
                    discoverySocket.send(new DatagramPacket(probe, 1,
                            InetAddress.getByName("255.255.255.255"), discoverySocket.getLocalPort()));
                } catch (IOException e) { Log.d("CommunicationThread", "Broadcast discovery: " + e.getMessage()); }
                SharedPreferences cameraPreferences = MyApplication.getAppContext().getSharedPreferences("phone_camera", Context.MODE_PRIVATE);
                String host = cameraPreferences.getString("host", "").trim();
                if (host.isEmpty()) host = cameraPreferences.getString("last_server", "");
                if (!host.isEmpty()) {
                    try {
                        discoverySocket.send(new DatagramPacket(probe, 1,
                                InetAddress.getByName(host), discoverySocket.getLocalPort()));
                    } catch (IOException e) { Log.d("CommunicationThread", "Direct discovery: " + e.getMessage()); }
                }
            }

            ArrayList<SensorDatum> llsd = new ArrayList<>();

            SensorDatum datum;
            while( null != (datum = MyApplication.sensorDataPipe.poll()) )
            {
                //sb.append("" + datum.sensorType + " " + datum.timestamp + "\n");
                if(llsd.size() >= 40)
                    llsd.remove(0);
                llsd.add(datum);
            }

            ByteBuffer bb = ByteBuffer.wrap(buffer).order(ByteOrder.LITTLE_ENDIAN);

            if(null != MyApplication.keysSensorData)
            { // first, keys
                SensorDatum sd;
                synchronized (MyApplication.keysSensorData) {
                    sd = MyApplication.keysSensorData;
                }
                if(null != sd) {
                    bb.put((byte) sd.sensorType).putInt(sd.payload);
                }
            }

            {
                SharedPreferences sp = PreferenceManager.getDefaultSharedPreferences(MyApplication.getAppContext());
                int azimuth=-1;
                if(sp.contains("azimuth")) azimuth=sp.getInt("azimuth",-1);
                bb.put((byte)32).putInt(azimuth);
            }

            for(SensorDatum sd : llsd) {
                if(sd.sensorType == SensorDatum.GYRO
                        || sd.sensorType == SensorDatum.MAGNETIC
                        || sd.sensorType == SensorDatum.GRAVITY )
                {
                    bb.put((byte) sd.sensorType)
                            .putLong(sd.timestamp)
                            .put(sd.accuracy)
                            .put((byte) (sd.data.length));

                    for (float f : sd.data)
                        bb.putFloat(f);
                }
            }

            // Position record goes last so older plugins retain rotation support.
            PositionTracker.Sample position = PositionTracker.sample;
            boolean tracking = position != null && android.os.SystemClock.elapsedRealtime() - position.sampledAt < 500;
            PositionPacket.write(bb, tracking, tracking ? position.reference : 0L,
                    android.os.SystemClock.elapsedRealtimeNanos(),
                    tracking ? position.x : 0f, tracking ? position.y : 0f, tracking ? position.z : 0f);

            if( bb.position() > 0) {

                DatagramSocket socket = getSocket();

                try {

                    for(String ia : clients_lastSeen.keySet() ) {
                        DatagramPacket dp = new DatagramPacket(buffer, bb.position(), InetAddress.getByName(ia), socket.getLocalPort());
                        socket.send(dp);
                    }

                } catch (UnknownHostException e) {
                    throw new AssertionError("Error FDINFBQFDN :\n" + e.toString());
                } catch (IOException e) {
                    throw new AssertionError("Error DMFBSDLRPF :\n" + e.toString());
                }
            }

            try{
                DatagramPacket dp = new DatagramPacket(receiveBuffer ,0,1024);
                getSocket().receive(dp);
                if (dp.getLength() == 1 && dp.getData()[dp.getOffset()] == (byte) 0xff) {
                    String ia = dp.getAddress().getHostAddress();
                    clients_lastSeen.put(ia, System.currentTimeMillis());
                    updateClients();
                }
            }catch(IOException e) {}

            {
                boolean needUpdate = false;
                java.util.Iterator<java.util.Map.Entry<String, Long>> it = clients_lastSeen.entrySet().iterator();
                while (it.hasNext())
                {
                    long t = it.next().getValue();
                    if(t + 1500 < System.currentTimeMillis() )
                    {
                        it.remove();
                        needUpdate = true;
                    }
                }
                if(needUpdate)
                    updateClients();
            }

            //////////////////////////
            long periodicity = getPeriodicity();
            long d = periodicity - ( 1 + System.currentTimeMillis() - timeStart) % periodicity;
            try {
                Thread.sleep(d);
            }catch(InterruptedException e)
            {
                throw new AssertionError("There shouldn't be an InterruptedException!\n" + e.toString());
            }
        }

        //System.out.println("communication thread exited");

        for (DatagramSocket socket : sockets.values()) socket.close();
        sockets.clear();
        clients_lastSeen.clear();

        StateManager.update( (state) -> { state.clientsList = ""; return state; });
    }
}
