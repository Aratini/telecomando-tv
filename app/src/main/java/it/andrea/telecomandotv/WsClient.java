package it.andrea.telecomandotv;

import android.util.Base64;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.util.concurrent.atomic.AtomicBoolean;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

/**
 * Client WebSocket minimale (RFC 6455), senza librerie esterne.
 * Gestisce solo ciò che serve per il TV Samsung: frame di testo, ping/pong, chiusura.
 * Tutti i metodi che fanno I/O vanno chiamati da un thread in background.
 */
public class WsClient {

    public interface Listener {
        void onMessage(WsClient client, String text);
        void onClose(WsClient client, String reason);
    }

    private final Listener listener;
    private final SecureRandom rnd = new SecureRandom();
    private final AtomicBoolean closeNotified = new AtomicBoolean(false);
    private Socket socket;
    private InputStream in;
    private OutputStream out;
    private volatile boolean open;

    public WsClient(Listener listener) {
        this.listener = listener;
    }

    public boolean isOpen() {
        return open;
    }

    public void connect(String host, int port, boolean secure, String path, int timeoutMs) throws IOException {
        Socket raw = new Socket();
        raw.connect(new InetSocketAddress(host, port), timeoutMs);
        Socket s = raw;
        if (secure) {
            try {
                SSLContext ctx = SSLContext.getInstance("TLS");
                // Il TV usa un certificato autofirmato: sulla rete di casa lo accettiamo.
                ctx.init(null, new TrustManager[]{new X509TrustManager() {
                    public void checkClientTrusted(X509Certificate[] c, String a) { }
                    public void checkServerTrusted(X509Certificate[] c, String a) { }
                    public X509Certificate[] getAcceptedIssuers() { return new X509Certificate[0]; }
                }}, rnd);
                SSLSocket ssl = (SSLSocket) ctx.getSocketFactory().createSocket(raw, host, port, true);
                ssl.setSoTimeout(timeoutMs);
                ssl.startHandshake();
                s = ssl;
            } catch (IOException e) {
                closeQuietly(raw);
                throw e;
            } catch (Exception e) {
                closeQuietly(raw);
                throw new IOException("TLS: " + e.getMessage());
            }
        }
        s.setSoTimeout(timeoutMs);
        s.setTcpNoDelay(true);
        socket = s;
        in = new BufferedInputStream(s.getInputStream());
        out = s.getOutputStream();

        byte[] k = new byte[16];
        rnd.nextBytes(k);
        String key = Base64.encodeToString(k, Base64.NO_WRAP);
        String req = "GET " + path + " HTTP/1.1\r\n"
                + "Host: " + host + ":" + port + "\r\n"
                + "Upgrade: websocket\r\n"
                + "Connection: Upgrade\r\n"
                + "Sec-WebSocket-Key: " + key + "\r\n"
                + "Sec-WebSocket-Version: 13\r\n"
                + "Origin: http://" + host + "\r\n\r\n";
        out.write(req.getBytes(StandardCharsets.UTF_8));
        out.flush();

        String status = readLine();
        if (status == null || !status.contains(" 101")) {
            closeQuietly(s);
            throw new IOException("handshake rifiutato (" + status + ")");
        }
        String line;
        while ((line = readLine()) != null && !line.isEmpty()) {
            // intestazioni ignorate
        }
        s.setSoTimeout(0);
        open = true;
        Thread t = new Thread(this::readLoop, "ws-read");
        t.setDaemon(true);
        t.start();
    }

    public boolean send(String text) {
        if (!open) return false;
        try {
            sendFrame(0x1, text.getBytes(StandardCharsets.UTF_8));
            return true;
        } catch (IOException e) {
            close();
            return false;
        }
    }

    public void close() {
        boolean wasOpen = open;
        open = false;
        if (wasOpen) {
            try {
                sendFrame(0x8, new byte[0]);
            } catch (Exception ignored) {
            }
        }
        closeQuietly(socket);
    }

    private synchronized void sendFrame(int opcode, byte[] p) throws IOException {
        ByteArrayOutputStream f = new ByteArrayOutputStream(p.length + 14);
        f.write(0x80 | opcode);
        if (p.length < 126) {
            f.write(0x80 | p.length);
        } else if (p.length < 65536) {
            f.write(0x80 | 126);
            f.write((p.length >> 8) & 0xFF);
            f.write(p.length & 0xFF);
        } else {
            f.write(0x80 | 127);
            long len = p.length;
            for (int i = 7; i >= 0; i--) f.write((int) ((len >> (8 * i)) & 0xFF));
        }
        byte[] m = new byte[4];
        rnd.nextBytes(m);
        f.write(m, 0, 4);
        for (int i = 0; i < p.length; i++) f.write(p[i] ^ m[i % 4]);
        out.write(f.toByteArray());
        out.flush();
    }

    private void readLoop() {
        String reason = "connessione chiusa";
        ByteArrayOutputStream msg = new ByteArrayOutputStream();
        int msgOpcode = 0;
        try {
            loop:
            while (open) {
                int b0 = in.read();
                if (b0 < 0) break;
                int b1 = read1();
                boolean fin = (b0 & 0x80) != 0;
                int opcode = b0 & 0x0F;
                boolean masked = (b1 & 0x80) != 0;
                long len = b1 & 0x7F;
                if (len == 126) {
                    len = ((long) read1() << 8) | read1();
                } else if (len == 127) {
                    len = 0;
                    for (int i = 0; i < 8; i++) len = (len << 8) | read1();
                }
                if (len > 16 * 1024 * 1024) throw new IOException("frame troppo grande");
                byte[] mask = masked ? readFully(4) : null;
                byte[] payload = readFully((int) len);
                if (mask != null) {
                    for (int i = 0; i < payload.length; i++) payload[i] ^= mask[i % 4];
                }
                switch (opcode) {
                    case 0x1:
                    case 0x2:
                        msg.reset();
                        msgOpcode = opcode;
                        msg.write(payload);
                        if (fin) deliver(msg, msgOpcode);
                        break;
                    case 0x0:
                        msg.write(payload);
                        if (fin) deliver(msg, msgOpcode);
                        break;
                    case 0x8:
                        reason = "chiusa dal televisore";
                        break loop;
                    case 0x9:
                        sendFrame(0xA, payload);
                        break;
                    default:
                        break;
                }
            }
        } catch (IOException e) {
            if (open) reason = e.getMessage();
        } finally {
            open = false;
            closeQuietly(socket);
            if (closeNotified.compareAndSet(false, true)) listener.onClose(this, reason);
        }
    }

    private void deliver(ByteArrayOutputStream msg, int opcode) {
        if (opcode == 0x1) listener.onMessage(this, new String(msg.toByteArray(), StandardCharsets.UTF_8));
        msg.reset();
    }

    private int read1() throws IOException {
        int b = in.read();
        if (b < 0) throw new IOException("flusso interrotto");
        return b;
    }

    private byte[] readFully(int n) throws IOException {
        byte[] buf = new byte[n];
        int off = 0;
        while (off < n) {
            int r = in.read(buf, off, n - off);
            if (r < 0) throw new IOException("flusso interrotto");
            off += r;
        }
        return buf;
    }

    private String readLine() throws IOException {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        int c;
        while ((c = in.read()) >= 0) {
            if (c == '\n') break;
            if (c != '\r') b.write(c);
        }
        if (c < 0 && b.size() == 0) return null;
        return new String(b.toByteArray(), StandardCharsets.UTF_8);
    }

    private static void closeQuietly(Socket s) {
        if (s == null) return;
        try {
            s.close();
        } catch (IOException ignored) {
        }
    }
}
