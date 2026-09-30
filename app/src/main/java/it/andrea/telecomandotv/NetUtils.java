package it.andrea.telecomandotv;

import org.json.JSONObject;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.NetworkInterface;
import java.net.Socket;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/** Ricerca del TV sulla rete locale e accensione con Wake-on-LAN. Da usare in background. */
public final class NetUtils {

    private NetUtils() { }

    public static class FoundTv {
        public final String ip;
        public final String name;
        public final String model;
        public final String mac;

        FoundTv(String ip, String name, String model, String mac) {
            this.ip = ip;
            this.name = name;
            this.model = model;
            this.mac = mac;
        }
    }

    public interface Progress {
        void onProgress(int done, int total);
    }

    /** Indirizzo IPv4 locale del telefono (preferisce l'interfaccia Wi-Fi). */
    public static String localIp() {
        String fallback = null;
        try {
            Enumeration<NetworkInterface> ifs = NetworkInterface.getNetworkInterfaces();
            if (ifs == null) return null;
            for (NetworkInterface ni : Collections.list(ifs)) {
                if (!ni.isUp() || ni.isLoopback()) continue;
                for (InetAddress a : Collections.list(ni.getInetAddresses())) {
                    if (a instanceof Inet4Address && a.isSiteLocalAddress()) {
                        String ip = a.getHostAddress();
                        if (ni.getName().startsWith("wlan")) return ip;
                        if (fallback == null) fallback = ip;
                    }
                }
            }
        } catch (Exception ignored) {
        }
        return fallback;
    }

    /** Scansiona la sottorete /24 cercando la porta 8001 dei TV Samsung. */
    public static List<FoundTv> scan(Progress progress) {
        List<FoundTv> found = Collections.synchronizedList(new ArrayList<>());
        String me = localIp();
        if (me == null) return found;
        String prefix = me.substring(0, me.lastIndexOf('.') + 1);
        ExecutorService pool = Executors.newFixedThreadPool(48);
        AtomicInteger done = new AtomicInteger();
        for (int i = 1; i <= 254; i++) {
            String ip = prefix + i;
            pool.execute(() -> {
                try (Socket s = new Socket()) {
                    s.connect(new InetSocketAddress(ip, 8001), 400);
                    s.close();
                    JSONObject info = SamsungTv.fetchInfo(ip, 1500);
                    if (info != null) {
                        JSONObject dev = info.optJSONObject("device");
                        String name = info.optString("name", "");
                        if (dev != null && name.isEmpty()) name = dev.optString("name", "");
                        String model = dev != null ? dev.optString("modelName", "") : "";
                        String mac = dev != null ? dev.optString("wifiMac", "") : "";
                        found.add(new FoundTv(ip, name.isEmpty() ? "TV Samsung" : name, model, mac));
                    }
                } catch (Exception ignored) {
                } finally {
                    if (progress != null) progress.onProgress(done.incrementAndGet(), 254);
                }
            });
        }
        pool.shutdown();
        try {
            pool.awaitTermination(30, TimeUnit.SECONDS);
        } catch (InterruptedException ignored) {
        }
        return new ArrayList<>(found);
    }

    /** Invia il "magic packet" Wake-on-LAN. Restituisce false se il MAC non è valido. */
    public static boolean wake(String mac, String tvIp) {
        String hex = mac == null ? "" : mac.replaceAll("[^0-9A-Fa-f]", "");
        if (hex.length() != 12) return false;
        byte[] macBytes = new byte[6];
        for (int i = 0; i < 6; i++) macBytes[i] = (byte) Integer.parseInt(hex.substring(i * 2, i * 2 + 2), 16);
        byte[] packet = new byte[6 + 16 * 6];
        for (int i = 0; i < 6; i++) packet[i] = (byte) 0xFF;
        for (int i = 6; i < packet.length; i += 6) System.arraycopy(macBytes, 0, packet, i, 6);

        List<String> targets = new ArrayList<>();
        targets.add("255.255.255.255");
        if (tvIp != null && tvIp.lastIndexOf('.') > 0) {
            targets.add(tvIp.substring(0, tvIp.lastIndexOf('.') + 1) + "255");
            targets.add(tvIp);
        }
        try (DatagramSocket ds = new DatagramSocket()) {
            ds.setBroadcast(true);
            for (int round = 0; round < 3; round++) {
                for (String t : targets) {
                    for (int port : new int[]{9, 7}) {
                        try {
                            ds.send(new DatagramPacket(packet, packet.length, InetAddress.getByName(t), port));
                        } catch (Exception ignored) {
                        }
                    }
                }
                Thread.sleep(100);
            }
            return true;
        } catch (Exception e) {
            return false;
        }
    }
}
