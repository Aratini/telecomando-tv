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
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

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
        public String iconPath = "";

        public AppInfo(String id, String name, int type) {
            this.id = id;
            this.name = name;
            this.type = type;
        }
    }

    public interface Callback {
        void onState(State state, String message);
        void onApps(List<AppInfo> apps);

        /** Il TV ha aperto (open=true) o chiuso la sua tastiera a schermo; text = testo già presente. */
        void onTvKeyboard(boolean open, String text);

        /** Icona di un'app arrivata dal TV (immagine PNG/JPG). */
        void onAppIcon(String appId, byte[] image);
    }

    private static final String REMOTE_NAME = "Telecomando Android";

    private final ExecutorService exec = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());
    private final SharedPreferences prefs;
    private final Callback callback;

    private volatile WsClient ws;
    private volatile State state = State.DISCONNECTED;
    private volatile String host;
    private volatile boolean wantConnected = false;   // l'app è in primo piano e vuole restare collegata
    private volatile boolean fallbackTried = false;   // provata già l'altra porta dopo una chiusura
    private volatile int forcedPort = 0;
    private volatile long lastAutoReconnect = 0;

    // ---------------------------------------------------------------- registro diagnostico

    private static final java.util.LinkedList<String> LOG = new java.util.LinkedList<>();

    public static void log(String msg) {
        String line = new java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.ITALY).format(new java.util.Date())
                + "  " + msg;
        synchronized (LOG) {
            LOG.add(line);
            while (LOG.size() > 80) LOG.removeFirst();
        }
    }

    public static String getLog() {
        synchronized (LOG) {
            StringBuilder b = new StringBuilder();
            for (String l : LOG) b.append(l).append('\n');
            return b.length() == 0 ? "(vuoto)" : b.toString();
        }
    }

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
            wantConnected = true;
            fallbackTried = false;
            forcedPort = 0;
            doConnect();
        });
    }

    public void disconnect() {
        exec.execute(() -> {
            wantConnected = false;
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
        int preferred = forcedPort != 0 ? forcedPort : prefs.getInt("port_" + h, 8001);
        int[] ports = preferred == 8002 ? new int[]{8002, 8001} : new int[]{8001, 8002};
        if (forcedPort != 0) ports = new int[]{forcedPort};

        String lastError = "";
        for (int port : ports) {
            boolean secure = port == 8002;
            String path = "/api/v2/channels/samsung.remote.control?name=" + name;
            if (secure && token != null) path += "&token=" + token;
            WsClient c = new WsClient(new TvListener(port));
            ws = c;
            try {
                log("Connessione a " + h + ":" + port + (secure ? " (sicura" + (token != null ? ", con token)" : ")") : ""));
                c.connect(h, port, secure, path, 4000);
                log("Porta " + port + ": collegamento accettato, attendo autorizzazione");
                prefs.edit().putInt("port_" + h, port).apply();
                setStateIf(State.CONNECTING, State.WAITING_AUTH, "Se compare una richiesta sul TV, scegli «Consenti»");
                return;
            } catch (Exception e) {
                if (ws == c) ws = null;
                lastError = e.getMessage();
                log("Porta " + port + ": errore " + lastError);
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
        private final int port;
        private volatile boolean authorized = false;
        private volatile boolean denied = false;

        TvListener(int port) {
            this.port = port;
        }

        @Override
        public void onMessage(WsClient client, String text) {
            log("TV → " + (text.length() > 160 ? text.substring(0, 160) + "…" : text));
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
                        authorized = true;
                        fallbackTried = false;
                        forcedPort = 0;
                        prefs.edit().putInt("port_" + host, port).apply();
                        setState(State.CONNECTED, "Connesso");
                        requestApps();
                        break;
                    case "ms.channel.unauthorized":
                        denied = true;
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
                    case "ed.apps.icon":
                        onIconReceived(data);
                        break;
                    case "ms.remote.imeStart":
                    case "ms.remote.imeUpdate": {
                        String current = decodeImeText(o);
                        main.post(() -> callback.onTvKeyboard(true, current));
                        break;
                    }
                    case "ms.remote.imeEnd":
                    case "ms.remote.imeDone":
                        main.post(() -> callback.onTvKeyboard(false, ""));
                        break;
                    default:
                        break;
                }
            } catch (JSONException ignored) {
            }
        }

        @Override
        public void onClose(WsClient client, String reason) {
            log("Porta " + port + ": connessione chiusa (" + reason + ")");
            if (ws != client) return;
            ws = null;
            if (!wantConnected || denied) {
                if (state != State.DISCONNECTED) setState(State.DISCONNECTED, "Connessione persa (" + reason + ")");
                return;
            }
            if (!authorized) {
                // Il TV ha chiuso prima di autorizzarci: provo l'altra porta (8001 ↔ 8002 sicura)
                if (!fallbackTried) {
                    fallbackTried = true;
                    forcedPort = port == 8001 ? 8002 : 8001;
                    log("Riprovo sulla porta " + forcedPort);
                    setState(State.CONNECTING, "Riprovo in modalità " + (forcedPort == 8002 ? "sicura" : "normale") + "…");
                    exec.execute(SamsungTv.this::doConnect);
                    return;
                }
                forcedPort = 0;
                setState(State.DISCONNECTED, "Il TV chiude la connessione (" + reason + "). Sul TV apri Impostazioni > "
                        + "Generali > Gestione dispositivi esterni > Gestione connessione dispositivi: controlla che "
                        + "«Telecomando Android» non sia bloccato, poi tocca qui per riprovare.");
                return;
            }
            // Era collegato e la connessione è caduta: riconnessione automatica (al massimo una ogni 5 s)
            long now = System.currentTimeMillis();
            if (now - lastAutoReconnect > 5000) {
                lastAutoReconnect = now;
                setState(State.CONNECTING, "Riconnessione…");
                main.postDelayed(() -> exec.execute(SamsungTv.this::doConnect), 800);
            } else {
                setState(State.DISCONNECTED, "Connessione persa (" + reason + "). Tocca qui per riprovare.");
            }
        }
    }

    /** Il testo del campo sul TV arriva (se c'è) in base64 nel campo "data". */
    private static String decodeImeText(JSONObject o) {
        String d = o.optString("data", "");
        if (d.isEmpty() || d.startsWith("{")) return "";
        try {
            return new String(Base64.decode(d, Base64.DEFAULT), StandardCharsets.UTF_8);
        } catch (Exception e) {
            return "";
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
            if (id.isEmpty()) continue;
            AppInfo info = new AppInfo(id, n, a.optInt("app_type", 2));
            info.iconPath = a.optString("icon", "");
            list.add(info);
        }
        Collections.sort(list, (x, y) -> x.name.compareToIgnoreCase(y.name));
        main.post(() -> callback.onApps(list));
    }

    // ---------------------------------------------------------------- icone delle app

    private final Object iconLock = new Object();
    private volatile AppInfo pendingIcon;
    private volatile CountDownLatch iconLatch;
    private Thread iconThread;

    /** Chiede al TV le icone delle app indicate, una alla volta (il TV risponde senza dire di quale app si tratta). */
    public void requestIcons(List<AppInfo> wanted) {
        synchronized (iconLock) {
            if (iconThread != null && iconThread.isAlive()) iconThread.interrupt();
            List<AppInfo> todo = new ArrayList<>();
            for (AppInfo a : wanted) if (a.iconPath != null && !a.iconPath.isEmpty()) todo.add(a);
            if (todo.isEmpty()) return;
            iconThread = new Thread(() -> {
                for (AppInfo a : todo) {
                    if (Thread.currentThread().isInterrupted()) return;
                    WsClient c = ws;
                    if (c == null || state != State.CONNECTED) return;
                    CountDownLatch latch = new CountDownLatch(1);
                    pendingIcon = a;
                    iconLatch = latch;
                    c.send(obj("method", "ms.channel.emit", "params", obj("event", "ed.apps.icon", "to", "host",
                            "data", obj("iconPath", a.iconPath))).toString());
                    try {
                        latch.await(2000, TimeUnit.MILLISECONDS);
                    } catch (InterruptedException e) {
                        return;
                    }
                }
                pendingIcon = null;
            }, "icons");
            iconThread.setDaemon(true);
            iconThread.start();
        }
    }

    private void onIconReceived(JSONObject data) {
        AppInfo a = pendingIcon;
        CountDownLatch latch = iconLatch;
        if (a == null || data == null) return;
        String b64 = data.optString("imageBase64", "");
        if (!b64.isEmpty()) {
            try {
                byte[] img = Base64.decode(b64, Base64.DEFAULT);
                String id = a.id;
                main.post(() -> callback.onAppIcon(id, img));
            } catch (Exception ignored) {
            }
        }
        if (latch != null) latch.countDown();
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

    private volatile boolean cancelSequence = false;

    /** Invia una sequenza di tasti con una pausa tra l'uno e l'altro (es. "canale 21", "volume +5"). */
    public void keySequence(List<String> codes, long delayMs) {
        keySequence(codes, delayMs, null);
    }

    /** Come sopra; onDone (sul thread principale) riceve true se la sequenza è arrivata in fondo. */
    public void keySequence(List<String> codes, long delayMs, java.util.function.Consumer<Boolean> onDone) {
        cancelSequence = false;
        exec.execute(() -> {
            boolean completed = true;
            for (String code : codes) {
                if (cancelSequence || !ensureConnected()) {
                    completed = false;
                    break;
                }
                WsClient c = ws;
                if (c != null) c.send(remote(obj("Cmd", "Click", "DataOfCmd", code, "Option", "false",
                        "TypeOfRemote", "SendRemoteKey")).toString());
                try {
                    Thread.sleep(delayMs);
                } catch (InterruptedException e) {
                    completed = false;
                    break;
                }
            }
            final boolean ok = completed;
            if (onDone != null) main.post(() -> onDone.accept(ok));
        });
    }

    public void cancelSequence() {
        cancelSequence = true;
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

    /**
     * Stato di un'app sul TV (GET /api/v2/applications/ID).
     * 2 = in primo piano, 1 = avviata (il TV non dice se è in primo piano), 0 = non aperta, -1 = sconosciuto.
     */
    public static int appStatus(String h, String appId) {
        try {
            HttpURLConnection con = (HttpURLConnection) new URL("http://" + h + ":8001/api/v2/applications/"
                    + URLEncoder.encode(appId, "UTF-8")).openConnection();
            con.setConnectTimeout(1500);
            con.setReadTimeout(2500);
            if (con.getResponseCode() != 200) return -1;
            InputStream is = con.getInputStream();
            ByteArrayOutputStream b = new ByteArrayOutputStream();
            byte[] buf = new byte[2048];
            int r;
            while ((r = is.read(buf)) > 0) b.write(buf, 0, r);
            is.close();
            con.disconnect();
            JSONObject o = new JSONObject(new String(b.toByteArray(), StandardCharsets.UTF_8));
            log("Stato app " + appId + ": " + o.toString());
            if (o.has("visible")) return o.optBoolean("visible") ? 2 : 0;
            return o.optBoolean("running") ? 1 : 0;
        } catch (Exception e) {
            return -1;
        }
    }

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
