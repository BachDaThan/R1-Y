package com.bachdathan.r1y.hardware;

import android.content.Context;
import android.net.wifi.WifiConfiguration;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.util.Log;

import com.bachdathan.r1y.config.R1YConfig;

import java.lang.reflect.Method;
import java.util.List;

/**
 * Phát WiFi (hotspot) từ loa R1-Y.
 *
 * Mục đích: khi không có router / muốn cấu hình loa ở nơi khác, bật hotspot
 * "R1-Y" rồi dùng điện thoại kết nối vào để mở http://192.168.43.1:8088 cài đặt.
 *
 * Loa R1 chạy Android 5.1 (API 22) nên KHÔNG có API công khai như
 * WifiManager.startLocalOnlyHotspot() (API 26+). Ta dùng reflection tới các
 * method cũ của WifiManager — cách phổ biến trên thiết bị root / system app.
 *
 * Nếu máy không cho phép (thiếu quyền hệ thống), module trả về false và
 * gợi ý dùng lệnh root tương đương trong {@link #statusHint()}.
 */
public class HotspotManager {

    private static final String TAG = "Hotspot";

    private final Context context;
    private final R1YConfig config;

    public HotspotManager(Context context) {
        this.context = context.getApplicationContext();
        this.config = new R1YConfig(this.context);
    }

    /** WiFi hotspot của loa thường ở địa chỉ này. */
    public static final String HOTSPOT_GATEWAY = "192.168.43.1";

    /**
     * Bật hotspot theo cấu hình đã lưu (SSID + mật khẩu).
     * @return true nếu đã bật được
     */
    public boolean enableFromConfig() {
        return enable(config.getHotspotSsid(), config.getHotspotPassword());
    }

    /** Bật hotspot với SSID/mật khẩu cho trước (mật khẩu >= 8 ký tự hoặc rỗng = mở). */
    @SuppressWarnings({"deprecation", "unchecked"})
    public boolean enable(String ssid, String password) {
        WifiManager wifi = (WifiManager) context.getSystemService(Context.WIFI_SERVICE);
        if (wifi == null) {
            Log.e(TAG, "Không lấy được WifiManager");
            return false;
        }

        // Android 6+: đã có API chính thức (một số máy vẫn cần quyền hệ thống)
        if (Build.VERSION.SDK_INT >= 26) {
            try {
                Method m = WifiManager.class.getMethod("startLocalOnlyHotspot",
                    Class.forName("android.net.wifi.WifiManager$LocalOnlyHotspotCallback"),
                    android.os.Handler.class);
                Log.i(TAG, "Thiết bị có startLocalOnlyHotspot (API 26+)");
                // Không gọi trực tiếp vì cần callback; rơi xuống nhánh reflection bên dưới
                m = null;
            } catch (Exception ignored) {
                // tiếp tục
            }
        }

        try {
            WifiConfiguration conf = buildConfig(ssid, password);
            // Cách 1: setWifiApEnabled (API <= 25, hay dùng trên box Android TV)
            Method setAp = WifiManager.class.getMethod("setWifiApEnabled",
                WifiConfiguration.class, boolean.class);
            Object result = setAp.invoke(wifi, conf, true);
            if (Boolean.TRUE.equals(result)) {
                Log.i(TAG, "Bật hotspot bằng setWifiApEnabled: " + ssid);
                return true;
            }
            Log.w(TAG, "setWifiApEnabled trả " + result);
        } catch (NoSuchMethodException e) {
            Log.w(TAG, "ROM không có setWifiApEnabled");
        } catch (Exception e) {
            Log.w(TAG, "setWifiApEnabled lỗi: " + e);
        }

        try {
            // Cách 2: reflection tới WifiApConfigStore/SoftApManager (một số ROM khác)
            Class<?> cls = Class.forName("android.net.wifi.WifiManager");
            Method m = cls.getDeclaredMethod("setWifiApConfiguration", WifiConfiguration.class);
            m.setAccessible(true);
            m.invoke(wifi, conf);
            Method enable = cls.getDeclaredMethod("setWifiApEnabled", WifiConfiguration.class, boolean.class);
            enable.setAccessible(true);
            Object r = enable.invoke(wifi, conf, true);
            Log.i(TAG, "Bật hotspot bằng reflection 2: " + r);
            return Boolean.TRUE.equals(r);
        } catch (Exception e) {
            Log.w(TAG, "Reflection hotspot thất bại: " + e);
        }

        // Cách 3: dùng lệnh shell (cần root) — svc wifi / cmd wifi / hostapd
        if (runRoot("svc wifi enable >/dev/null 2>&1; "
                + "cmd wifi start-softap \"" + ssid + "\" wpa2 \"" + password + "\" >/dev/null 2>&1")) {
            Log.i(TAG, "Bật hotspot bằng cmd wifi (root)");
            return true;
        }

        Log.e(TAG, "Không bật được hotspot trên ROM này (cần quyền hệ thống hoặc root).");
        return false;
    }

    public boolean disable() {
        WifiManager wifi = (WifiManager) context.getSystemService(Context.WIFI_SERVICE);
        if (wifi == null) return false;
        try {
            Method setAp = WifiManager.class.getMethod("setWifiApEnabled",
                WifiConfiguration.class, boolean.class);
            setAp.invoke(wifi, null, false);
            Log.i(TAG, "Đã tắt hotspot");
            return true;
        } catch (Exception e) {
            Log.w(TAG, "Tắt hotspot lỗi: " + e);
        }
        if (runRoot("cmd wifi stop-softap >/dev/null 2>&1")) return true;
        return false;
    }

    /** Đang phát hotspot hay không. */
    @SuppressWarnings("deprecation")
    public boolean isEnabled() {
        WifiManager wifi = (WifiManager) context.getSystemService(Context.WIFI_SERVICE);
        if (wifi == null) return false;
        try {
            Method getState = WifiManager.class.getMethod("getWifiApState");
            int state = (Integer) getState.invoke(wifi);
            // 13 = WIFI_AP_STATE_ENABLED
            return state == 13 || state == 12;
        } catch (Exception ignored) {
        }
        try {
            Method isAp = WifiManager.class.getMethod("isWifiApEnabled");
            return Boolean.TRUE.equals(isAp.invoke(wifi));
        } catch (Exception ignored) {
        }
        return false;
    }

    /** Địa chỉ nên dùng để mở giao diện loa khi nối vào hotspot. */
    public String gatewayUrl() {
        int port = R1YConfig.WEB_PORT;
        return "http://" + HOTSPOT_GATEWAY + ":" + port;
    }

    public String statusHint() {
        if (isEnabled()) {
            return "Đang phát WiFi \"" + config.getHotspotSsid() + "\" — nối vào rồi mở " + gatewayUrl();
        }
        return "Hotspot chưa bật. ROM có thể cần quyền hệ thống/root; "
             + "khi đó bật bằng ADB: adb shell su -c \"cmd wifi start-softap R1-Y wpa2 r1y12345678\"";
    }

    // ------------------------------------------------------------------ helpers

    @SuppressWarnings("deprecation")
    private WifiConfiguration buildConfig(String ssid, String password) {
        WifiConfiguration conf = new WifiConfiguration();
        conf.SSID = ssid;
        conf.allowedAuthAlgorithms.clear();
        conf.allowedGroupCiphers.clear();
        conf.allowedKeyManagement.clear();
        conf.allowedPairwiseCiphers.clear();
        conf.allowedProtocols.clear();

        boolean open = password == null || password.trim().length() < 8;
        if (open) {
            conf.allowedKeyManagement.set(WifiConfiguration.KeyMgmt.NONE);
        } else {
            conf.preSharedKey = password;
            conf.allowedAuthAlgorithms.set(WifiConfiguration.AuthAlgorithm.OPEN);
            conf.allowedKeyManagement.set(WifiConfiguration.KeyMgmt.WPA_PSK);
            conf.allowedProtocols.set(WifiConfiguration.Protocol.RSN);
            conf.allowedProtocols.set(WifiConfiguration.Protocol.WPA);
            conf.allowedPairwiseCiphers.set(WifiConfiguration.PairwiseCipher.CCMP);
            conf.allowedPairwiseCiphers.set(WifiConfiguration.PairwiseCipher.TKIP);
            conf.allowedGroupCiphers.set(WifiConfiguration.GroupCipher.CCMP);
            conf.allowedGroupCiphers.set(WifiConfiguration.GroupCipher.TKIP);
        }
        return conf;
    }

    /** Chạy lệnh bằng su (root). true nếu lệnh chạy được. */
    private boolean runRoot(String cmd) {
        Process p = null;
        try {
            p = Runtime.getRuntime().exec(new String[]{"su", "-c", cmd});
            int code = p.waitFor();
            Log.i(TAG, "su -c exit=" + code);
            return code == 0;
        } catch (Exception e) {
            Log.w(TAG, "Không có root: " + e);
            return false;
        } finally {
            if (p != null) p.destroy();
        }
    }

    /** Danh sách SSID đã cấu hình trên máy (để hiển thị). */
    @SuppressWarnings("deprecation")
    public String describe() {
        return config.getHotspotSsid() + " / " + config.getHotspotPassword()
             + " · " + (isEnabled() ? "đang bật" : "đang tắt");
    }

    /** Bật khi khởi động nếu người dùng chọn. */
    public void maybeAutoStart() {
        if (!config.isHotspotEnabled() || !config.isHotspotAutoStart()) return;
        if (isEnabled()) return;
        Log.i(TAG, "Tự bật hotspot khi khởi động");
        enableFromConfig();
    }

    /** Liệt kê cấu hình WiFi đã lưu (không dùng, giữ cho tương lai). */
    @SuppressWarnings("deprecation")
    public List<WifiConfiguration> configuredNetworks() {
        WifiManager wifi = (WifiManager) context.getSystemService(Context.WIFI_SERVICE);
        return wifi == null ? null : wifi.getConfiguredNetworks();
    }
}
