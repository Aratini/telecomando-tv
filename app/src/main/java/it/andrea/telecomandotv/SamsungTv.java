package it.andrea.telecomandotv;

import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.util.Base64;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Protocollo "Smart View" dei TV Samsung Tizen (2016+):
 * WebSocket su ws://IP:8001 (o wss://IP:8002 con token) canale samsung.remote.control.
 */
public class SamsungTv {

    public enum State { DISCONNECTED, CONNECTING, WAITING_AUTH, CONNECTED }

    public static class AppInfo {
        public final String id;
        public final String name;
        public final int type;

        public AppInfo(String id, String name, int type) {
            this.id = id;
            this.name = name;
            this.type = type;
        }
    }

    public interface Callback {
        void onState(State state, String message);
        void onApps(List<AppInfo> apps);
    }

    private static final String REMOTE_NAME = "Telecomando Android";

    private final ExecutorService exec = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());
    private final SharedPreferences prefs;
    private final Callback callback;

    private volatile WsClient ws;
    private volatile State state = State.DISCONNECTED;
    private volatile String host;

    public SamsungTv(SharedPreferences prefs, Callback callback) {
        this.prefs = prefs;
        this.callback = callback;
    }

    public State getState() {
        return state;
    }

    public String getHost() {
        return host;
    }

    public boolean isConnected() {
        return state == State.CONNECTED;
    }

    // ---------------------------------------------------------------- connessione

    public void connect(String newHost) {
        exec.execute(() -> {
            if (!newHost.equals(host)) closeCurrent();
            host = newHost;
            doConnect();
        });
    }

    public void disconnect() {
        exec.execute(() -> {
            closeCurrent();
            setState(State.DISCONNECTED, "Disconnesso");
        });
    }

    private void closeCurrent() {
        WsClient c = ws;
        ws = null;
        if (c != null) c.close();
    }

    private void doConnect() {
        String h = host;
        if (h == null) return;
        WsClient current = ws;
        if (current != null && current.isOpen()) return;

        setState(State.CONNECTING, "Connessione a " + h + "…");
        String name = Base64.encodeToString(REMOTE_NAME.getBytes(StandardCharsets.UTF_8), Base64.NO_WRAP);
        String token = prefs.getString("token_" + h, null);
        int preferred = prefs.getInt("port_" + h, 8001);
        int[] ports = preferred == 8002 ? new int[]{8002, 8001} : new int[]{8001, 8002};

        String lastError = "";
        for (int port : ports) {
            boolean secure = port == 8002;
            String path;
            try {
                path = "/api/v2/channels/samsung.remote.control?name=" + URLEncoder.encode(name, "UTF-8");
            } catch (Exception e) {
                path = "/api/v2/channels/samsung.remote.control?name=" + name;
            }
            if (secure && token != null) path += "&token=" + token;
            WsClient c = new WsClient(new TvListener());
            ws = c;
            try {
                c.connect(h, port, secure, path, 4000);
                prefs.edit().putInt("port_" + h, port).apply();
                setStateIf(State.CONNECTING, State.WAITING_AUTH, "Se compare una richiesta sul TV, scegli «Consenti»");
                return;
            } catch (Exception e) {
                if (ws == c) ws = null;
                lastError = e.getMessage();
            }
        }
        setState(State.DISCONNECTED, "TV non raggiungibile (" + lastError + "). È acceso e sulla stessa rete Wi-Fi?");
    }

    /** Da chiamare sul thread dell'executor: si assicura di avere una connessione autorizzata. */
    private boolean ensureConnected() {
        if (state == State.CONNECTED && ws != null && ws.isOpen()) return true;
        doConnect();
        long end = System.currentTimeMillis() + 3000;
        while (System.currentTimeMillis() < end) {
            if (state == State.CONNECTED) return true;
            if (state == State.DISCONNECTED) return false;
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                return false;
            }
        }
        return state == State.CONNECTED;
    }

    private class TvListener implements WsClient.Listener {
        @Override
        public void onMessage(WsClient client, String text) {
            try {
                JSONObject o = new JSONObject(text);
                String event = o.optString("event");
                JSONObject data = o.optJSONObject("data");
                switch (event) {
                    case "ms.channel.connect":
                        if (data != null) {
                            String t = data.optString("token", "");
                            if (!t.isEmpty() && host != null) prefs.edit().putString("token_" + host, t).apply();
                        }
                        setState(State.CONNECTED, "Connesso");
                        requestApps();
                        break;
                    case "ms.channel.unauthorized":
                        setState(State.DISCONNECTED, "Il TV ha rifiutato l'accesso. Riprova e premi «Consenti» sul TV "
                                + "(o sblocca il dispositivo in Impostazioni > Generali > Gestione dispositivi esterni).");
                        client.close();
                        break;
                    case "ms.channel.timeOut":
                        setState(State.DISCONNECTED, "Nessuna risposta alla richiesta sul TV. Riprova.");
                        client.close();
                        break;
                    case "ed.installedApp.get":
                        parseApps(data);
                        break;
                    default:
                        break;
                }
            } catch (JSONException ignored) {
            }
        }

        @Override
        public void onClose(WsClient client, String reason) {
            if (ws == client) {
                ws = null;
                if (state != State.DISCONNECTED) setState(State.DISCONNECTED, "Connessione persa (" + reason + ")");
            }
        }
    }

    private void parseApps(JSONObject data) {
        if (data == null) return;
        JSONArray arr = data.optJSONArray("data");
        if (arr == null) return;
        List<AppInfo> list = new ArrayList<>();
        for (int i = 0; i < arr.length(); i++) {
            JSONObject a = arr.optJSONObject(i);
            if (a == null) continue;
            String id = a.optString("appId", "");
            String n = a.optString("name", id);
            if (!id.isEmpty()) list.add(new AppInfo(id, n, a.optInt("app_type", 2)));
        }
        Collections.sort(list, (x, y) -> x.name.compareToIgnoreCase(y.name));
        main.post(() -> callback.onApps(list));
    }

    private synchronized void setState(State s, String msg) {
        state = s;
        main.post(() -> callback.onState(s, msg));
    }

    private synchronized void setStateIf(State expected, State s, String msg) {
        if (state == expected) setState(s, msg);
    }

    // ---------------------------------------------------------------- comandi

    private void sendNow(JSONObject payload) {
        if (!ensureConnected()) return;
        WsClient c = ws;
        if (c != null) c.send(payload.toString());
    }

    private void send(JSONObject payload) {
        exec.execute(() -> sendNow(payload));
    }

    private static JSONObject obj(Object... kv) {
        JSONObject o = new JSONObject();
        try {
            for (int i = 0; i + 1 < kv.length; i += 2) o.put((String) kv[i], kv[i + 1]);
        } catch (JSONException ignored) {
        }
        return o;
    }

    private static JSONObject remote(JSONObject params) {
        return obj("method", "ms.remote.control", "params", params);
    }

    public void key(String code) {
        send(remote(obj("Cmd", "Click", "DataOfCmd", code, "Option", "false", "TypeOfRemote", "SendRemoteKey")));
    }

    public void keyPress(String code, boolean down) {
        send(remote(obj("Cmd", down ? "Press" : "Release", "DataOfCmd", code, "Option", "false",
                "TypeOfRemote", "SendRemoteKey")));
    }

    public void sendText(String text) {
        String b64 = Base64.encodeToString(text.getBytes(StandardCharsets.UTF_8), Base64.NO_WRAP);
        send(remote(obj("Cmd", b64, "DataOfCmd", "base64", "TypeOfRemote", "SendInputString")));
        send(remote(obj("TypeOfRemote", "SendInputEnd")));
    }

    public void mouseMove(int dx, int dy) {
        exec.execute(() -> {
            if (state != State.CONNECTED) return; // i movimenti non devono accodare riconnessioni
            WsClient c = ws;
            if (c == null) return;
            JSONObject pos = obj("x", dx, "y", dy, "Time", String.valueOf(System.currentTimeMillis()));
            c.send(remote(obj("Cmd", "Move", "Position", pos, "TypeOfRemote", "ProcessMouseDevice")).toString());
        });
    }

    public void mouseClick() {
        send(remote(obj("Cmd", "LeftClick", "TypeOfRemote", "ProcessMouseDevice")));
    }

    public void requestApps() {
        exec.execute(() -> {
            WsClient c = ws;
            if (c == null) return;
            c.send(obj("method", "ms.channel.emit",
                    "params", obj("event", "ed.installedApp.get", "to", "host")).toString());
        });
    }

    /** Avvia un'app: prima via REST (funziona bene sui modelli 2016-2017), poi via WebSocket. */
    public void launchApp(AppInfo app) {
        exec.execute(() -> {
            String h = host;
            if (h == null) return;
            boolean ok = false;
            try {
                HttpURLConnection con = (HttpURLConnection) new URL("http://" + h + ":8001/api/v2/applications/"
                        + URLEncoder.encode(app.id, "UTF-8")).openConnection();
                con.setRequestMethod("POST");
                con.setConnectTimeout(2500);
                con.setReadTimeout(2500);
                con.setDoOutput(true);
                OutputStream os = con.getOutputStream();
                os.write(new byte[0]);
                os.close();
                int code = con.getResponseCode();
                ok = code >= 200 && code < 300;
                con.disconnect();
            } catch (Exception ignored) {
            }
            if (!ok) {
                String action = app.type == 4 ? "NATIVE_LAUNCH" : "DEEP_LINK";
                sendNow(obj("method", "ms.channel.emit", "params", obj("event", "ed.apps.launch", "to", "host",
                        "data", obj("appId", app.id, "action_type", action))));
            }
        });
    }

    // ---------------------------------------------------------------- info dispositivo

    /** Legge http://IP:8001/api/v2/ ; restituisce null se non è un TV Samsung. */
    public static JSONObject fetchInfo(String h, int timeoutMs) {
        try {
            HttpURLConnection con = (HttpURLConnection) new URL("http://" + h + ":8001/api/v2/").openConnection();
            con.setConnectTimeout(timeoutMs);
            con.setReadTimeout(timeoutMs * 3);
            if (con.getResponseCode() != 200) return null;
            InputStream is = con.getInputStream();
            ByteArrayOutputStream b = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int r;
            while ((r = is.read(buf)) > 0) b.write(buf, 0, r);
            is.close();
            con.disconnect();
            JSONObject o = new JSONObject(new String(b.toByteArray(), StandardCharsets.UTF_8));
            return o.optJSONObject("device") != null ? o : null;
        } catch (Exception e) {
            return null;
        }
    }
}
