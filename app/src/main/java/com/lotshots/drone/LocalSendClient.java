package com.lotshots.drone;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.URL;
import java.net.URLEncoder;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

/**
 * Sends files straight to the LocalSend app on your Mac (LocalSend protocol v2), over your Wi-Fi.
 * Nothing goes through the internet. LocalSend doesn't need to be installed on the controller.
 */
public final class LocalSendClient {

    public static final int PORT = 53317;

    /** A LocalSend device found on the network. */
    public static final class Device {
        public final String ip, alias, protocol, fingerprint;

        Device(String ip, String alias, String protocol, String fingerprint) {
            this.ip = ip;
            this.alias = alias;
            this.protocol = protocol;
            this.fingerprint = fingerprint;
        }
    }

    private final String alias;
    private final String fingerprint;

    public LocalSendClient(String alias, String fingerprint) {
        this.alias = alias;
        this.fingerprint = fingerprint;
    }

    // ------------------------------------------------------------------ HTTP helpers

    // LocalSend uses self-signed certificates on your own network, so they can't be checked
    // against a public authority. Only used for LocalSend devices on the local Wi-Fi.
    private static final SSLContext TRUST_ALL;

    static {
        SSLContext ctx = null;
        try {
            ctx = SSLContext.getInstance("TLS");
            ctx.init(null, new TrustManager[]{new X509TrustManager() {
                public void checkClientTrusted(X509Certificate[] c, String a) {
                }

                public void checkServerTrusted(X509Certificate[] c, String a) {
                }

                public X509Certificate[] getAcceptedIssuers() {
                    return new X509Certificate[0];
                }
            }}, new SecureRandom());
        } catch (Exception ignored) {
        }
        TRUST_ALL = ctx;
    }

    private static HttpURLConnection open(String url, int connectMs, int readMs) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        if (c instanceof HttpsURLConnection && TRUST_ALL != null) {
            ((HttpsURLConnection) c).setSSLSocketFactory(TRUST_ALL.getSocketFactory());
            ((HttpsURLConnection) c).setHostnameVerifier((h, s) -> true);
        }
        c.setConnectTimeout(connectMs);
        c.setReadTimeout(readMs);
        return c;
    }

    /** Returns {status code, response body}. */
    private static Object[] post(String url, String contentType, byte[] body, int connectMs, int readMs) throws Exception {
        HttpURLConnection c = open(url, connectMs, readMs);
        c.setRequestMethod("POST");
        c.setDoOutput(true);
        c.setRequestProperty("Content-Type", contentType);
        c.setFixedLengthStreamingMode(body.length);
        try (OutputStream o = c.getOutputStream()) {
            o.write(body);
        }
        int code = c.getResponseCode();
        InputStream in = code < 400 ? c.getInputStream() : c.getErrorStream();
        String text = "";
        if (in != null) {
            ByteArrayOutputStream b = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) b.write(buf, 0, n);
            in.close();
            text = b.toString("UTF-8");
        }
        c.disconnect();
        return new Object[]{code, text};
    }

    private JSONObject info(String protocol) throws Exception {
        JSONObject o = new JSONObject();
        o.put("alias", alias);
        o.put("version", "2.1");
        o.put("deviceModel", "DJI RC Pro");
        o.put("deviceType", "mobile");
        o.put("fingerprint", fingerprint);
        o.put("port", PORT);
        o.put("protocol", protocol);
        o.put("download", false);
        return o;
    }

    // ------------------------------------------------------------------ discovery

    /** Asks one address whether LocalSend is running there. Tries https first, then http. */
    public Device probe(String ip) {
        for (String proto : new String[]{"https", "http"}) {
            try {
                Object[] r = post(proto + "://" + ip + ":" + PORT + "/api/localsend/v2/register",
                        "application/json", info(proto).toString().getBytes("UTF-8"), 800, 3500);
                int code = (Integer) r[0];
                if (code == 200) {
                    JSONObject j = new JSONObject((String) r[1]);
                    String fp = j.optString("fingerprint", "");
                    if (fp.equals(fingerprint)) return null; // that's us
                    return new Device(ip, j.optString("alias", ip), proto, fp);
                }
                // Something on LocalSend's port answered, just not the way we expected: still show it.
                if (code > 0) return new Device(ip, "LocalSend at " + ip, proto, "");
            } catch (java.net.ConnectException e) {
                return null; // nothing listening: don't bother with http
            } catch (Exception ignored) {
                // try the other protocol
            }
        }
        return null;
    }

    /** The controller's own Wi-Fi addresses (e.g. 192.168.1.23). */
    public static List<String> localIps() {
        List<String> out = new ArrayList<>();
        try {
            Enumeration<NetworkInterface> ifs = NetworkInterface.getNetworkInterfaces();
            while (ifs != null && ifs.hasMoreElements()) {
                NetworkInterface ni = ifs.nextElement();
                if (!ni.isUp() || ni.isLoopback()) continue;
                for (InetAddress a : Collections.list(ni.getInetAddresses())) {
                    if (a instanceof Inet4Address && a.isSiteLocalAddress()) out.add(a.getHostAddress());
                }
            }
        } catch (Exception ignored) {
        }
        return out;
    }

    /**
     * LocalSend's own discovery: shout "I'm here" to the LocalSend multicast group and listen
     * for other LocalSend devices answering or announcing themselves.
     */
    public List<Device> multicastDiscover(int listenMs) {
        List<Device> found = new ArrayList<>();
        java.net.MulticastSocket sock = null;
        try {
            InetAddress group = InetAddress.getByName("224.0.0.167");
            sock = new java.net.MulticastSocket(null);
            sock.setReuseAddress(true);
            sock.bind(new java.net.InetSocketAddress(PORT));
            // Join on every real network (the RC Pro also has an internal 192.168.50.x link to the drone).
            List<NetworkInterface> nets = new ArrayList<>();
            Enumeration<NetworkInterface> ifs = NetworkInterface.getNetworkInterfaces();
            while (ifs != null && ifs.hasMoreElements()) {
                NetworkInterface ni = ifs.nextElement();
                if (!ni.isUp() || ni.isLoopback() || !ni.supportsMulticast()) continue;
                for (InetAddress a : Collections.list(ni.getInetAddresses())) {
                    if (a instanceof Inet4Address && a.isSiteLocalAddress()) {
                        nets.add(ni);
                        break;
                    }
                }
            }
            java.net.InetSocketAddress groupAddr = new java.net.InetSocketAddress(group, PORT);
            for (NetworkInterface ni : nets) {
                try {
                    sock.joinGroup(groupAddr, ni);
                } catch (Exception ignored) {
                }
            }
            if (nets.isEmpty()) sock.joinGroup(group);
            sock.setSoTimeout(400);
            JSONObject hello = info("https");
            hello.put("announce", true);
            hello.put("announcement", true);
            byte[] msg = hello.toString().getBytes("UTF-8");
            long end = System.currentTimeMillis() + listenMs;
            long nextHello = 0;
            byte[] buf = new byte[8192];
            while (System.currentTimeMillis() < end) {
                if (System.currentTimeMillis() >= nextHello) {
                    // say hello on each network, not just the default one
                    for (NetworkInterface ni : nets) {
                        try {
                            sock.setNetworkInterface(ni);
                            sock.send(new java.net.DatagramPacket(msg, msg.length, group, PORT));
                        } catch (Exception ignored) {
                        }
                    }
                    if (nets.isEmpty()) sock.send(new java.net.DatagramPacket(msg, msg.length, group, PORT));
                    nextHello = System.currentTimeMillis() + 1500;
                }
                java.net.DatagramPacket p = new java.net.DatagramPacket(buf, buf.length);
                try {
                    sock.receive(p);
                } catch (java.net.SocketTimeoutException e) {
                    continue;
                }
                try {
                    JSONObject j = new JSONObject(new String(p.getData(), 0, p.getLength(), "UTF-8"));
                    String fp = j.optString("fingerprint", "");
                    if (fp.isEmpty() || fp.equals(fingerprint)) continue;
                    String ip = p.getAddress().getHostAddress();
                    boolean dup = false;
                    for (Device d : found) if (d.fingerprint.equals(fp)) dup = true;
                    if (!dup) found.add(new Device(ip, j.optString("alias", ip), j.optString("protocol", "https"), fp));
                } catch (Exception ignored) {
                }
            }
        } catch (Exception ignored) {
        } finally {
            if (sock != null) sock.close();
        }
        return found;
    }

    /** Checks every address on the Wi-Fi (x.x.x.1–254) for LocalSend, plus LocalSend's multicast discovery. */
    public List<Device> scan() {
        List<Device> found = Collections.synchronizedList(new ArrayList<>());
        Thread mc = new Thread(() -> {
            for (Device d : multicastDiscover(6000)) {
                // confirm over HTTP when possible (gives the right protocol)
                Device checked = probe(d.ip);
                found.add(checked != null ? checked : d);
            }
        });
        mc.start();
        List<String> mine = localIps();
        ExecutorService pool = Executors.newFixedThreadPool(48);
        for (String me : mine) {
            String prefix = me.substring(0, me.lastIndexOf('.') + 1);
            for (int i = 1; i <= 254; i++) {
                String ip = prefix + i;
                if (ip.equals(me)) continue;
                pool.execute(() -> {
                    Device d = probe(ip);
                    if (d != null) found.add(d);
                });
            }
        }
        pool.shutdown();
        try {
            pool.awaitTermination(25, TimeUnit.SECONDS);
            mc.join(10000);
        } catch (InterruptedException ignored) {
        }
        // drop duplicates (same device found both ways)
        List<Device> out = new ArrayList<>();
        synchronized (found) {
            for (Device d : found) {
                boolean dup = false;
                for (Device o : out) if (o.fingerprint.equals(d.fingerprint) || o.ip.equals(d.ip)) dup = true;
                if (!dup) out.add(d);
            }
        }
        return out;
    }

    // ------------------------------------------------------------------ sending

    /**
     * Sends one file. Blocks while the Mac shows "Accept?" (up to ~2 minutes).
     * Returns null on success, or a short message saying what went wrong.
     */
    public String send(Device d, String fileName, String mime, byte[] data) {
        String base = d.protocol + "://" + d.ip + ":" + PORT + "/api/localsend/v2/";
        try {
            JSONObject file = new JSONObject();
            file.put("id", "f1");
            file.put("fileName", fileName);
            file.put("size", data.length);
            file.put("fileType", mime);
            JSONObject files = new JSONObject();
            files.put("f1", file);
            JSONObject req = new JSONObject();
            req.put("info", info(d.protocol));
            req.put("files", files);

            Object[] r = post(base + "prepare-upload", "application/json", req.toString().getBytes("UTF-8"), 3000, 120000);
            int code = (Integer) r[0];
            if (code == 204) return null; // Mac says nothing needs sending
            if (code == 403) return "Declined on the Mac";
            if (code == 401) return "The Mac's LocalSend asks for a PIN — turn the PIN off in LocalSend settings";
            if (code == 409) return "The Mac is busy with another transfer — try again";
            if (code != 200) return "The Mac replied " + code;

            JSONObject resp = new JSONObject((String) r[1]);
            String session = resp.getString("sessionId");
            String token = resp.getJSONObject("files").getString("f1");
            String url = base + "upload?sessionId=" + URLEncoder.encode(session, "UTF-8")
                    + "&fileId=f1&token=" + URLEncoder.encode(token, "UTF-8");
            Object[] u = post(url, "application/octet-stream", data, 3000, 60000);
            int uc = (Integer) u[0];
            return uc == 200 ? null : "Upload failed (" + uc + ")";
        } catch (java.net.SocketTimeoutException e) {
            return "No answer from the Mac (it wasn't accepted in time?)";
        } catch (java.net.ConnectException e) {
            return "Couldn't reach the Mac — is LocalSend open and on the same Wi-Fi?";
        } catch (Exception e) {
            return "Send failed: " + e.getMessage();
        }
    }
}
