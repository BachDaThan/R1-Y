package com.bachdathan.r1y.hardware;

import android.content.Context;
import android.net.DhcpInfo;
import android.net.wifi.ScanResult;
import android.net.wifi.SupplicantState;
import android.net.wifi.WifiConfiguration;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import com.bachdathan.r1y.config.R1YConfig;

import org.json.JSONArray;
import org.json.JSONObject;

import java.net.InetAddress;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Cấu hình WiFi cho loa trong "chế độ cài đặt".
 *
 * Luồng thực tế (giống thiết bị IoT thông thường):
 *   1. Giữ nút tròn trên loa (hoặc bấm trong web) -> loa phát WiFi riêng (hotspot "R1-Y").
 *   2. Điện thoại nối WiFi "R1-Y" -> mở http://192.168.43.1:8088.
 *   3. Chọn WiFi nhà trong danh sách quét được, nhập mật khẩu.
 *   4. Loa nối vào WiFi nhà -> tự tắt hotspot -> IP đổi sang dải nhà (192.168.x.x).
 *   5. Lúc này loa chung mạng với máy -> cài firmware qua LAN (ADB over WiFi / web).
 *
 * Module này lo bước 3-4: quét, nối, báo trạng thái, quên mạng.
 */
public class WifiProvisioner {

    private static final String TAG = "WifiSetup";

    private final Context context;
    private final R1YConfig config;
    private final WifiManager wifi;

    public WifiProvisioner(Context context) {
        this.context = context.getApplicationContext();
        this.config = new R1YConfig(this.context);
        this.wifi = (WifiManager) this.context.getSystemService(Context.WIFI_SERVICE);
    }

    // ------------------------------------------------------------------ quét

    /**
     * Quét WiFi xung quanh. Trả về mảng JSON: [{ssid, level, secured, freq}]
     * Đã khử trùng lặp SSID, xếp theo cường độ tín hiệu.
     */
    public JSONArray scan() {
        JSONArray out = new JSONArray();
        if (wifi == null) return out;

        if (!wifi.isWifiEnabled()) {
            // Bật WiFi để quét được (hotspot vẫn có thể đang chạy)
            try {
                wifi.setWifiEnabled(true);
                Thread.sleep(1200);
            } catch (Exception e) {
                Log.w(TAG, "Không bật được WiFi: " + e.getMessage());
            }
        }

        List<ScanResult> results;
        try {
            wifi.startScan();
        } catch (Exception e) {
            Log.w(TAG, "startScan lỗi: " + e.getMessage());
        }
        results = wifi.getScanResults();
        if (results == null) results = new ArrayList<ScanResult>();

        Map<String, ScanResult> best = new LinkedHashMap<String, ScanResult>();
        for (ScanResult r : results) {
            if (r == null || r.SSID == null || r.SSID.trim().isEmpty()) continue;
            ScanResult cur = best.get(r.SSID);
            if (cur == null || r.level > cur.level) best.put(r.SSID, r);
        }
        List<ScanResult> list = new ArrayList<ScanResult>(best.values());
        Collections.sort(list, new Comparator<ScanResult>() {
            @Override
            public int compare(ScanResult a, ScanResult b) {
                return b.level - a.level;
            }
        });

        for (ScanResult r : list) {
            try {
                JSONObject o = new JSONObject();
                o.put("ssid", r.SSID);
                o.put("level", r.level);
                o.put("freq", r.frequency);
                o.put("secured", !isOpen(r));
                o.put("saved", isSaved(r.SSID));
                out.put(o);
            } catch (Exception ignored) {
            }
        }
        Log.i(TAG, "Quét được " + out.length() + " WiFi");
        return out;
    }

    private static boolean isOpen(ScanResult r) {
        String caps = r.capabilities == null ? "" : r.capabilities;
        return !(caps.contains("WPA") || caps.contains("WEP") || caps.contains("EAP"));
    }

    // ------------------------------------------------------------------ nối

    /** Kết quả nối WiFi. */
    public static class ConnectResult {
        public boolean ok;
        public String ssid = "";
        public String message = "";
        public String ip = "";
        public String gateway = "";
        public String webUrl = "";
        public boolean hotspotWasDisabled;
    }

    /**
     * Nối loa vào WiFi nhà. Sẽ tắt hotspot trước (nhiều ROM không cho chạy song song),
     * thêm/lưu cấu hình rồi chờ tới khi lấy được IP.
     */
    public ConnectResult connect(String ssid, String password) {
        ConnectResult res = new ConnectResult();
        res.ssid = ssid == null ? "" : ssid;
        if (wifi == null) {
            res.message = "Máy không có WifiManager";
            return res;
        }
        if (res.ssid.trim().isEmpty()) {
            res.message = "Thiếu tên WiFi (SSID)";
            return res;
        }

        // 1) Tắt hotspot để nhường radio
        try {
            HotspotManager hs = new HotspotManager(context);
            if (hs.isEnabled()) {
                hs.disable();
                res.hotspotWasDisabled = true;
                Thread.sleep(1200);
            }
        } catch (Exception e) {
            Log.w(TAG, "Tắt hotspot không được: " + e.getMessage());
        }

        // 2) Bật WiFi
        try {
            if (!wifi.isWifiEnabled()) {
                wifi.setWifiEnabled(true);
                Thread.sleep(2000);
            }
        } catch (Exception e) {
            Log.w(TAG, "Bật WiFi lỗi: " + e.getMessage());
        }

        // 3) Thêm / cập nhật cấu hình mạng
        int netId = -1;
        try {
            WifiConfiguration conf = build(ssid, password);
            List<WifiConfiguration> existing = wifi.getConfiguredNetworks();
            if (existing != null) {
                for (WifiConfiguration c : existing) {
                    if (c.SSID != null && c.SSID.equals("\"" + ssid + "\"")) {
                        netId = c.networkId;
                        wifi.removeNetwork(netId);
                        break;
                    }
                }
            }
            netId = wifi.addNetwork(conf);
            if (netId < 0) {
                res.message = "Không thêm được cấu hình WiFi (ROM chặn?)";
                Log.w(TAG, res.message);
                return res;
            }
            wifi.saveConfiguration();
        } catch (Exception e) {
            res.message = "Lỗi thêm mạng: " + e.getMessage();
            Log.w(TAG, res.message);
            return res;
        }

        // 4) Kết nối
        try {
            wifi.disconnect();
            Thread.sleep(500);
            wifi.enableNetwork(netId, true);
            wifi.reconnect();
        } catch (Exception e) {
            Log.w(TAG, "Kết nối lỗi: " + e.getMessage());
        }

        // 5) Chờ lấy IP (tối đa ~25 giây)
        String ip = waitForIp(25000);
        if (ip != null && !ip.isEmpty() && !"0.0.0.0".equals(ip)) {
            res.ok = true;
            res.ip = ip;
            res.gateway = gatewayIp();
            res.webUrl = "http://" + ip + ":" + R1YConfig.WEB_PORT;
            res.message = "Đã nối WiFi \"" + res.ssid + "\"";
            config.setHomeWifi(ssid, password);
            Log.i(TAG, res.message + " IP=" + ip);
        } else {
            res.message = "Chưa lấy được IP. Kiểm tra lại mật khẩu WiFi hoặc sóng yếu.";
            WifiInfo info = wifi.getConnectionInfo();
            if (info != null) {
                SupplicantState st = info.getSupplicantState();
                res.message += " (trạng thái: " + (st == null ? "?" : st.toString()) + ")";
            }
        }
        return res;
    }

    /** Quên mạng đã lưu (khi mật khẩu đổi). */
    public boolean forget(String ssid) {
        if (wifi == null) return false;
        try {
            List<WifiConfiguration> list = wifi.getConfiguredNetworks();
            if (list == null) return false;
            for (WifiConfiguration c : list) {
                if (c.SSID != null && c.SSID.equals("\"" + ssid + "\"")) {
                    boolean ok = wifi.removeNetwork(c.networkId);
                    wifi.saveConfiguration();
                    return ok;
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "forget lỗi: " + e.getMessage());
        }
        return false;
    }

    // ------------------------------------------------------------------ trạng thái

    public JSONObject status() {
        JSONObject o = new JSONObject();
        try {
            WifiInfo info = wifi == null ? null : wifi.getConnectionInfo();
            boolean connected = false;
            String ssid = "", ip = "";
            int rssi = 0;
            if (info != null) {
                ssid = info.getSSID() == null ? "" : info.getSSID().replace("\"", "");
                int ipInt = info.getIpAddress();
                if (ipInt != 0) {
                    ip = String.format("%d.%d.%d.%d",
                        (ipInt & 0xff), (ipInt >> 8 & 0xff),
                        (ipInt >> 16 & 0xff), (ipInt >> 24 & 0xff));
                }
                rssi = info.getRssi();
                connected = info.getSupplicantState() == SupplicantState.COMPLETED
                    && !ssid.isEmpty() && !"<unknown ssid>".equals(ssid);
            }
            o.put("ok", true);
            o.put("connected", connected);
            o.put("ssid", ssid);
            o.put("ip", ip);
            o.put("rssi", rssi);
            o.put("gateway", gatewayIp());
            o.put("webUrl", ip.isEmpty() ? "" : ("http://" + ip + ":" + R1YConfig.WEB_PORT));
            o.put("hotspotOn", new HotspotManager(context).isEnabled());
            o.put("savedSsid", config.getHomeWifiSsid());
            o.put("sameNetworkHint", connected
                ? "Loa đang chung WiFi nhà — giờ có thể cài firmware qua mạng nội bộ."
                : "Loa chưa vào WiFi nhà.");
        } catch (Exception e) {
            try {
                o.put("ok", false);
                o.put("error", e.getMessage());
            } catch (Exception ignored) {
            }
        }
        return o;
    }

    /** IP hiện tại của loa (rỗng nếu chưa có). */
    public String currentIp() {
        try {
            WifiInfo info = wifi == null ? null : wifi.getConnectionInfo();
            if (info == null) return "";
            int ipInt = info.getIpAddress();
            if (ipInt == 0) return "";
            return String.format("%d.%d.%d.%d",
                (ipInt & 0xff), (ipInt >> 8 & 0xff), (ipInt >> 16 & 0xff), (ipInt >> 24 & 0xff));
        } catch (Exception e) {
            return "";
        }
    }

    public String gatewayIp() {
        try {
            DhcpInfo d = wifi == null ? null : wifi.getDhcpInfo();
            if (d == null || d.gateway == 0) return "";
            int g = d.gateway;
            return String.format("%d.%d.%d.%d", (g & 0xff), (g >> 8 & 0xff), (g >> 16 & 0xff), (g >> 24 & 0xff));
        } catch (Exception e) {
            return "";
        }
    }

    // ------------------------------------------------------------------ helpers

    private String waitForIp(long timeoutMs) {
        long end = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < end) {
            try {
                WifiInfo info = wifi.getConnectionInfo();
                if (info != null) {
                    SupplicantState st = info.getSupplicantState();
                    int ipInt = info.getIpAddress();
                    if (ipInt != 0 && st == SupplicantState.COMPLETED) {
                        return String.format("%d.%d.%d.%d",
                            (ipInt & 0xff), (ipInt >> 8 & 0xff),
                            (ipInt >> 16 & 0xff), (ipInt >> 24 & 0xff));
                    }
                }
                Thread.sleep(600);
            } catch (InterruptedException e) {
                break;
            } catch (Exception e) {
                Log.w(TAG, "waitForIp: " + e.getMessage());
                break;
            }
        }
        return "";
    }

    @SuppressWarnings("deprecation")
    private WifiConfiguration build(String ssid, String password) {
        WifiConfiguration conf = new WifiConfiguration();
        conf.SSID = "\"" + ssid + "\"";
        conf.hiddenSSID = false;
        boolean open = password == null || password.trim().isEmpty();
        if (open) {
            conf.allowedKeyManagement.set(WifiConfiguration.KeyMgmt.NONE);
        } else {
            conf.preSharedKey = "\"" + password + "\"";
            conf.allowedKeyManagement.set(WifiConfiguration.KeyMgmt.WPA_PSK);
            conf.allowedAuthAlgorithms.set(WifiConfiguration.AuthAlgorithm.OPEN);
            conf.allowedProtocols.set(WifiConfiguration.Protocol.RSN);
            conf.allowedProtocols.set(WifiConfiguration.Protocol.WPA);
            conf.allowedPairwiseCiphers.set(WifiConfiguration.PairwiseCipher.CCMP);
            conf.allowedPairwiseCiphers.set(WifiConfiguration.PairwiseCipher.TKIP);
            conf.allowedGroupCiphers.set(WifiConfiguration.GroupCipher.CCMP);
            conf.allowedGroupCiphers.set(WifiConfiguration.GroupCipher.TKIP);
            conf.priority = 100;
        }
        return conf;
    }

    @SuppressWarnings("deprecation")
    private boolean isSaved(String ssid) {
        try {
            List<WifiConfiguration> list = wifi == null ? null : wifi.getConfiguredNetworks();
            if (list == null) return false;
            for (WifiConfiguration c : list) {
                if (c.SSID != null && c.SSID.equals("\"" + ssid + "\"")) return true;
            }
        } catch (Exception ignored) {
        }
        return false;
    }

    /** Kiểm tra đã nối Internet chưa (dùng để báo cho người dùng). */
    public boolean hasInternet() {
        try {
            android.net.ConnectivityManager cm = (android.net.ConnectivityManager)
                context.getSystemService(Context.CONNECTIVITY_SERVICE);
            if (cm == null) return false;
            android.net.NetworkInfo ni = cm.getActiveNetworkInfo();
            return ni != null && ni.isConnected();
        } catch (Exception e) {
            return false;
        }
    }

    /** Gợi ý URL để mở giao diện, tuỳ trạng thái mạng. */
    public String bestWebUrl() {
        String ip = currentIp();
        if (!ip.isEmpty()) return "http://" + ip + ":" + R1YConfig.WEB_PORT;
        return new HotspotManager(context).gatewayUrl();
    }
}
