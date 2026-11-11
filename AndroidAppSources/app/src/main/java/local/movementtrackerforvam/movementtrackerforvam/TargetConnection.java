package local.movementtrackerforvam.movementtrackerforvam;

import android.content.SharedPreferences;
import android.os.SystemClock;
import org.json.JSONObject;
import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/** Shared target state for both pages. All published snapshots are immutable. */
public final class TargetConnection {
    public static final class AtomChoice {
        public final int id;
        public final String name, type;

        AtomChoice(int id, String name, String type) {
            this.id = id;
            this.name = name;
            this.type = type;
        }

        @Override public String toString() { return name + " (" + type + ")"; }
    }

    public static final class State {
        public final int instance, revision, catalog, target;
        public final boolean selectable, control, position, rotation, loading;
        public final String name, type, installation, framing;
        public final InetAddress address;
        public final long received;

        State(JSONObject json, InetAddress address) {
            instance = json.optInt("instance");
            revision = json.optInt("revision");
            catalog = json.optInt("catalog");
            target = json.optInt("target");
            selectable = json.optBoolean("selectable");
            control = json.optBoolean("control");
            loading = json.optBoolean("loading");
            position = json.optBoolean("position");
            rotation = json.optBoolean("rotation");
            name = json.optString("name");
            type = json.optString("type");
            installation = json.optString("installation");
            framing = json.optString("framing");
            this.address = address;
            received = SystemClock.elapsedRealtime();
        }

        public boolean fresh() { return SystemClock.elapsedRealtime() - received < 1500; }
    }

    public static volatile State state;
    public static volatile String notice = "Finding VaM target service";
    private static volatile List<AtomChoice> choices = Collections.emptyList();
    private static int users, generation, nextRequest;
    private static volatile State controllerReady;
    private static volatile DatagramSocket socket;
    private static volatile Selection selection;

    private static class Selection {
        final State base;
        final int id, request;
        final long started = SystemClock.elapsedRealtime();

        Selection(State base, int id, int request) {
            this.base = base;
            this.id = id;
            this.request = request;
        }
    }

    public static boolean canControl() {
        State current = state;
        return current != null && current.fresh() && current.control && selection == null;
    }

    // The Controller page arms a revision only after clearing its pressed buttons.
    public static void armController(State target) { controllerReady = target; }

    public static boolean canSendController(State target) {
        State ready = controllerReady;
        return target != null && ready != null && canControl()
                && ready.instance == target.instance && ready.revision == target.revision;
    }

    public static boolean pending() { return selection != null; }
    public static List<AtomChoice> choices() { return choices; }

    public static synchronized void select(int id) {
        State current = state;
        if (current == null || !current.fresh() || !current.selectable) return;
        selection = new Selection(current, id, ++nextRequest);
        notice = "Changing target…";
    }

    public static synchronized void acquire() {
        if (users++ > 0) return;
        int currentGeneration = ++generation;
        notice = "Finding VaM target service";
        new Thread(() -> run(currentGeneration), "VaM target connection").start();
    }

    public static synchronized void release() {
        if (users == 0 || --users > 0) return;
        generation++;
        if (socket != null) socket.close();
        socket = null;
        state = null;
        selection = null;
        controllerReady = null;
    }

    private static synchronized boolean active(int currentGeneration) {
        return currentGeneration == generation;
    }

    private static void send(DatagramSocket socket, byte[] bytes, InetAddress address) {
        try {
            socket.send(new DatagramPacket(bytes, bytes.length, address, AppPreferences.VAM_PORT));
        } catch (IOException error) {
            State current = state;
            if (current == null || !current.fresh()) notice = "Retrying target connection: " + error.getMessage();
        }
    }

    private static void run(int currentGeneration) {
        try (DatagramSocket connection = new DatagramSocket()) {
            synchronized (TargetConnection.class) {
                if (!active(currentGeneration)) return;
                socket = connection;
            }
            connection.setBroadcast(true);
            connection.setSoTimeout(100);
            SharedPreferences prefs = AppPreferences.connectionAndCamera(MyApplication.getAppContext());
            String host = prefs.getString(AppPreferences.VAM_HOST, "").trim();
            InetAddress explicit = host.isEmpty() ? null : InetAddress.getByName(host);
            InetAddress broadcast = InetAddress.getByName("255.255.255.255");
            InetAddress peer = explicit;
            long lastSend = 0, began = SystemClock.elapsedRealtime();
            int page = 0, catalog = -1, instance = 0, total = 0;
            Map<Integer, AtomChoice> items = new TreeMap<>();
            Set<Integer> retiredInstances = new HashSet<>();
            byte[] buffer = new byte[1500];
            while (active(currentGeneration)) {
                long now = SystemClock.elapsedRealtime();
                State current = state;
                if (current != null && !current.fresh()) {
                    notice = "Disconnected — waiting for target information";
                    if (explicit == null) peer = null;
                }
                Selection pending = selection;
                if (pending != null && now - pending.started > 5000) {
                    selection = null;
                    notice = "Target selection timed out. Please try again.";
                    pending = null;
                }
                if (now - lastSend >= (page < total ? 40 : 500)) {
                    JSONObject query = new JSONObject();
                    query.put("op", pending == null ? "state" : "select");
                    query.put("page", page < total ? page : 0);
                    if (pending != null) {
                        query.put("instance", pending.base.instance);
                        query.put("revision", pending.base.revision);
                        query.put("target", pending.id);
                        query.put("request", pending.request);
                    }
                    byte[] bytes = ("PCT1" + query).getBytes(StandardCharsets.UTF_8);
                    send(connection, bytes, peer == null ? broadcast : peer);
                    if (peer == null) {
                        String last = prefs.getString(AppPreferences.LAST_SERVER, "");
                        if (!last.isEmpty()) send(connection, bytes, InetAddress.getByName(last));
                    }
                    lastSend = now;
                }
                DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
                try {
                    connection.receive(packet);
                } catch (SocketTimeoutException timeout) {
                    if (state == null && now - began > 3000) {
                        notice = "No target information. Check connection and update the VaM plugin.";
                    }
                    continue;
                } catch (SocketException error) {
                    if (!active(currentGeneration)) return;
                    notice = "Retrying target connection: " + error.getMessage();
                    Thread.sleep(100);
                    continue;
                }
                if (packet.getPort() != AppPreferences.VAM_PORT || packet.getLength() < 5 || packet.getLength() > 1200
                        || peer != null && !peer.equals(packet.getAddress())) continue;
                String text = new String(buffer, 0, packet.getLength(), StandardCharsets.UTF_8);
                if (!text.startsWith("PCT1")) continue;
                JSONObject json;
                try {
                    json = new JSONObject(text.substring(4));
                } catch (org.json.JSONException invalid) {
                    continue;
                }
                State next = new State(json, packet.getAddress());
                if (next.instance <= 0 || next.revision <= 0 || retiredInstances.contains(next.instance)) continue;
                synchronized (TargetConnection.class) {
                    if (!active(currentGeneration)) return;
                    current = state;
                    if (current != null && current.instance == next.instance
                            && (next.revision < current.revision || next.catalog < current.catalog)) continue;
                    peer = packet.getAddress();
                    if (!peer.getHostAddress().equals(prefs.getString(AppPreferences.LAST_SERVER, ""))) {
                        prefs.edit().putString(AppPreferences.LAST_SERVER, peer.getHostAddress()).apply();
                    }
                    if (instance != next.instance || catalog != next.catalog) {
                        items.clear();
                        choices = Collections.emptyList();
                        page = 0;
                        instance = next.instance;
                        catalog = next.catalog;
                    }
                    total = Math.min(4096, Math.max(0, json.optInt("count")));
                    int receivedPage = json.optInt("page", -1);
                    if (receivedPage >= 0 && receivedPage < total && json.optInt("itemId") > 0) {
                        AtomChoice old = items.get(receivedPage);
                        AtomChoice item = new AtomChoice(json.optInt("itemId"), json.optString("itemName"), json.optString("itemType"));
                        if (old == null || old.id != item.id || !old.name.equals(item.name) || !old.type.equals(item.type)) {
                            items.put(receivedPage, item);
                            choices = Collections.unmodifiableList(new ArrayList<>(items.values()));
                        }
                        while (items.containsKey(page)) page++;
                    }
                    if (current != null && current.instance != next.instance) retiredInstances.add(current.instance);
                    state = next;
                    pending = selection;
                    if (pending != null && json.optInt("request") == pending.request) {
                        selection = null;
                        notice = json.optString("error");
                    } else if (pending == null && (notice.startsWith("Finding") || notice.startsWith("Disconnected")
                            || notice.startsWith("No target") || notice.startsWith("Retrying"))) {
                        notice = "";
                    }
                }
            }
        } catch (Exception error) {
            if (active(currentGeneration)) notice = "Target connection: " + error.getMessage();
        }
    }
}
