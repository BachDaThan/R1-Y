package com.bachdathan.r1y.config;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * Cấu hình trung tâm của R1-Y.
 *
 * Lưu trong SharedPreferences "r1y_config":
 * - youtube_resolvers: mảng JSON [{"type":"piped|invidious|custom","url":"..."}]
 *   Thử đúng thứ tự. Để "[]" thì loa tự tìm máy chủ công khai
 *   (khi youtube_autodiscover = true), cuối cùng là máy chủ nhạc.
 * - youtube_autodiscover: tự tìm máy chủ công khai khi danh sách rỗng
 * - music_server: máy chủ nhạc cuối cùng (Tube / LiteTube bridge / R1-Y server)
 * - update_repo: repo GitHub để kiểm tra cập nhật (mặc định BachDaThan/R1-Y)
 * - update_auto_check: tự kiểm tra cập nhật khi khởi động
 */
public class R1YConfig {

    public static final String GITHUB_REPO = "BachDaThan/R1-Y";
    public static final String GITHUB_API = "https://api.github.com/repos/" + GITHUB_REPO + "/releases/latest";
    public static final int WEB_PORT = 8088;

    private static final String PREFS_NAME = "r1y_config";
    private static final String KEY_RESOLVERS = "youtube_resolvers";
    private static final String KEY_AUTODISCOVER = "youtube_autodiscover";
    private static final String KEY_MUSIC_SERVER = "music_server";
    private static final String KEY_UPDATE_REPO = "update_repo";
    private static final String KEY_AUTO_CHECK = "update_auto_check";
    private static final String KEY_LAST_CHECK = "update_last_check";
    private static final String KEY_AI_SERVER = "ai_server";
    private static final String KEY_AI_URL = "ai_server_url";
    private static final String KEY_AI_TOKEN = "ai_token";
    private static final String KEY_HOTSPOT_ON = "hotspot_enabled";
    private static final String KEY_HOTSPOT_SSID = "hotspot_ssid";
    private static final String KEY_HOTSPOT_PASS = "hotspot_password";
    private static final String KEY_HOTSPOT_AUTO = "hotspot_auto_start";
    private static final String KEY_AUDIO_QUALITY = "audio_quality";
    private static final String KEY_SETUP_MODE = "setup_mode";
    private static final String KEY_HOME_WIFI_SSID = "home_wifi_ssid";
    private static final String KEY_HOME_WIFI_PASS = "home_wifi_password";
    private static final String KEY_SETUP_AUTO = "setup_mode_auto";
    private static final String KEY_DISC_PIPED = "discovered_piped";
    private static final String KEY_DISC_INV = "discovered_invidious";
    private static final String KEY_DISC_AT = "discovered_at";

    private final SharedPreferences prefs;

    public R1YConfig(Context context) {
        this.prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }

    /** Mảng JSON resolver, mặc định "[]" (tự tìm máy chủ công khai). */
    public String getResolversJson() {
        return prefs.getString(KEY_RESOLVERS, "[]");
    }

    public void setResolversJson(String json) {
        prefs.edit().putString(KEY_RESOLVERS, json).apply();
    }

    /** Tự tìm máy chủ công khai khi danh sách rỗng. Mặc định bật. */
    public boolean isAutodiscover() {
        return prefs.getBoolean(KEY_AUTODISCOVER, true);
    }

    public void setAutodiscover(boolean enabled) {
        prefs.edit().putBoolean(KEY_AUTODISCOVER, enabled).apply();
    }

    /** Máy chủ nhạc cuối cùng (Tube/LiteTube/R1-Y server). Rỗng = bỏ qua bước này. */
    public String getMusicServer() {
        return prefs.getString(KEY_MUSIC_SERVER, "");
    }

    public void setMusicServer(String url) {
        prefs.edit().putString(KEY_MUSIC_SERVER, url).apply();
    }

    /** Repo GitHub để cập nhật. */
    public String getUpdateRepo() {
        return prefs.getString(KEY_UPDATE_REPO, GITHUB_REPO);
    }

    public void setUpdateRepo(String repo) {
        prefs.edit().putString(KEY_UPDATE_REPO, repo).apply();
    }

    public boolean isAutoCheckUpdate() {
        return prefs.getBoolean(KEY_AUTO_CHECK, true);
    }

    public void setAutoCheckUpdate(boolean enabled) {
        prefs.edit().putBoolean(KEY_AUTO_CHECK, enabled).apply();
    }

    public long getLastCheckTime() {
        return prefs.getLong(KEY_LAST_CHECK, 0);
    }

    public void setLastCheckTime(long time) {
        prefs.edit().putLong(KEY_LAST_CHECK, time).apply();
    }

    // ------------------------------------------------------- máy chủ tự dò được

    /**
     * Máy chủ công cộng đã dò được và đang sống (lưu lại để lần sau thử trước).
     * Một URL mỗi dòng. Rỗng = chưa dò lần nào.
     */
    public String getDiscoveredServers() {
        String piped = prefs.getString(KEY_DISC_PIPED, "");
        String inv = prefs.getString(KEY_DISC_INV, "");
        StringBuilder sb = new StringBuilder();
        if (!piped.isEmpty()) sb.append(piped);
        if (!inv.isEmpty()) {
            if (sb.length() > 0) sb.append("\n");
            sb.append(inv);
        }
        return sb.toString();
    }

    public String getDiscoveredPiped() {
        return prefs.getString(KEY_DISC_PIPED, "");
    }

    public String getDiscoveredInvidious() {
        return prefs.getString(KEY_DISC_INV, "");
    }

    /** Lưu danh sách "type url" (mỗi dòng một cái) sau khi dò. */
    public void saveDiscovered(String pipedLines, String invidiousLines) {
        prefs.edit()
            .putString(KEY_DISC_PIPED, pipedLines == null ? "" : pipedLines)
            .putString(KEY_DISC_INV, invidiousLines == null ? "" : invidiousLines)
            .putLong(KEY_DISC_AT, System.currentTimeMillis())
            .apply();
    }

    public long getDiscoveredAt() {
        return prefs.getLong(KEY_DISC_AT, 0);
    }

    /** Xoá kết quả dò (khi mọi máy chủ đã chết, cần dò lại). */
    public void clearDiscovered() {
        prefs.edit()
            .remove(KEY_DISC_PIPED)
            .remove(KEY_DISC_INV)
            .remove(KEY_DISC_AT)
            .apply();
    }

    // ================================================== máy chủ AI (trợ lý)

    /** Preset máy chủ AI. id -> tên hiển thị + URL mặc định. */
    public static final String[][] AI_PRESETS = {
        // id,                 tên hiển thị,                    URL mặc định
        {"xiaozhi",            "Xiaozhi.me (chính thức)",        "wss://xiaozhi.me/v1/ws"},
        {"xiaozhi_self",       "Xiaozhi tự dựng (esp32-server)", "ws://192.168.1.100:8000/xiaozhi/v1/"},
        {"custom",             "Tùy chỉnh (URL của bạn)",        ""},
    };

    public static String aiPresetName(String id) {
        for (String[] p : AI_PRESETS) if (p[0].equals(id)) return p[1];
        return id == null ? "?" : id;
    }

    public static String aiPresetUrl(String id) {
        for (String[] p : AI_PRESETS) if (p[0].equals(id)) return p[2];
        return "wss://xiaozhi.me/v1/ws";
    }

    /** Id preset đang dùng: xiaozhi (mặc định) | xiaozhi_self | custom. */
    public String getAiServer() {
        return prefs.getString(KEY_AI_SERVER, "xiaozhi");
    }

    public void setAiServer(String id) {
        prefs.edit().putString(KEY_AI_SERVER, id == null ? "xiaozhi" : id).apply();
    }

    /** URL WebSocket đang dùng. Preset "custom" thì lấy URL người dùng nhập. */
    public String getAiUrl() {
        String id = getAiServer();
        if ("custom".equals(id)) {
            String u = prefs.getString(KEY_AI_URL, "");
            return u.trim().isEmpty() ? aiPresetUrl("xiaozhi") : u.trim();
        }
        String saved = prefs.getString(KEY_AI_URL, "");
        return saved.trim().isEmpty() ? aiPresetUrl(id) : saved.trim();
    }

    public void setAiUrl(String url) {
        prefs.edit().putString(KEY_AI_URL, url == null ? "" : url.trim()).apply();
    }

    /**
     * Token/API key dự phòng cho máy chủ tự dựng (nếu server không cấp token
     * qua kích hoạt). Rỗng = dùng token lấy từ quy trình kích hoạt.
     */
    public String getAiToken() {
        return prefs.getString(KEY_AI_TOKEN, "");
    }

    public void setAiToken(String token) {
        prefs.edit().putString(KEY_AI_TOKEN, token == null ? "" : token.trim()).apply();
    }

    // ================================================== phát WiFi (hotspot)

    /** Bật/tắt phát WiFi từ loa (điện thoại kết nối trực tiếp để cài đặt). */
    public boolean isHotspotEnabled() {
        return prefs.getBoolean(KEY_HOTSPOT_ON, false);
    }

    public void setHotspotEnabled(boolean on) {
        prefs.edit().putBoolean(KEY_HOTSPOT_ON, on).apply();
    }

    public String getHotspotSsid() {
        return prefs.getString(KEY_HOTSPOT_SSID, "R1-Y");
    }

    public void setHotspotSsid(String ssid) {
        prefs.edit().putString(KEY_HOTSPOT_SSID, ssid == null || ssid.trim().isEmpty() ? "R1-Y" : ssid.trim()).apply();
    }

    public String getHotspotPassword() {
        return prefs.getString(KEY_HOTSPOT_PASS, "r1y12345678");
    }

    public void setHotspotPassword(String pass) {
        prefs.edit().putString(KEY_HOTSPOT_PASS,
            pass == null || pass.trim().length() < 8 ? "r1y12345678" : pass.trim()).apply();
    }

    /** Tự bật phát WiFi khi loa khởi động. */
    public boolean isHotspotAutoStart() {
        return prefs.getBoolean(KEY_HOTSPOT_AUTO, true);
    }

    public void setHotspotAutoStart(boolean on) {
        prefs.edit().putBoolean(KEY_HOTSPOT_AUTO, on).apply();
    }

    // ================================================== chất lượng âm thanh

    /** "high" (mặc định: bitrate cao nhất, ưu tiên Opus) hoặc "compat" (AAC/m4a). */
    public String getAudioQuality() {
        return prefs.getString(KEY_AUDIO_QUALITY, "high");
    }

    public void setAudioQuality(String q) {
        prefs.edit().putString(KEY_AUDIO_QUALITY,
            "compat".equals(q) ? "compat" : "high").apply();
    }

    /**
     * Đường ra âm thanh cho nhạc:
     *   "system" (MẶC ĐỊNH) — dùng STREAM_SYSTEM
     *   "music"            — dùng STREAM_MUSIC
     *   "ring"             — dùng STREAM_RING
     *
     * Vì sao mặc định là "system": loa Phicomm R1 có cấu hình route âm thanh
     * bất thường — STREAM_MUSIC không ra loa ngoài (đã kiểm chứng trên phần cứng
     * thật bởi các dự án firmware R1 khác). Đổi được trên web nếu ROM của bạn khác.
     */
    public String getAudioRoute() {
        return prefs.getString("audio_route", "system");
    }

    /**
     * Gửi micro lên máy chủ AI bằng Opus (mặc định, ĐÚNG giao thức Xiaozhi)
     * hay bằng PCM base64 (chỉ cho máy chủ tự dựng hiểu kiểu cũ).
     *
     * Giao thức websocket của Xiaozhi yêu cầu khung nhị phân là Opus — máy chủ
     * khai báo "format":"opus". Gửi PCM base64 thì máy chủ không hiểu, im lặng,
     * hoặc trả lỗi. Đây là lý do nút trên loa "không nói chuyện được với AI".
     */
    public boolean isAiUseOpus() {
        return prefs.getBoolean("ai_use_opus", true);
    }

    public void setAiUseOpus(boolean opus) {
        prefs.edit().putBoolean("ai_use_opus", opus).apply();
    }

    /**
     * Hệ số khuếch đại tín hiệu mic (1..30). Mic của R1 rất nhỏ, cần gain lớn
     * mới nghe được giọng nói cách 2-3 m. Mặc định 12: đủ nhạy mà không rít.
     */
    public int getMicGain() {
        int g = prefs.getInt("mic_gain", 12);
        return g < 1 ? 1 : (g > 30 ? 30 : g);
    }

    public void setMicGain(int g) {
        prefs.edit().putInt("mic_gain", g < 1 ? 1 : (g > 30 ? 30 : g)).apply();
    }

    /**
     * Tự đoán wake word bằng ngưỡng âm lượng. MẶC ĐỊNH TẮT vì nó kích hoạt
     * với mọi tiếng động to (tivi, đóng cửa…). Bật lại được nếu muốn thử.
     */
    public boolean isAutoWakeEnabled() {
        return prefs.getBoolean("auto_wake", false);
    }

    public void setAutoWakeEnabled(boolean on) {
        prefs.edit().putBoolean("auto_wake", on).apply();
    }

    /** Wake word người dùng đặt (mặc định 小智). Dùng để nhận diện theo âm lượng. */
    public String getWakeWordText() {
        return prefs.getString("wake_word_text", "小智");
    }

    public void setAudioRoute(String r) {
        String v = "music".equals(r) || "ring".equals(r) ? r : "system";
        prefs.edit().putString("audio_route", v).apply();
    }

    // ================================================== chế độ cài đặt (WiFi setup)

    /**
     * Đang ở "chế độ cài đặt": loa phát WiFi riêng để điện thoại nối vào
     * chọn WiFi nhà. Thường bật bằng cách giữ nút tròn trên loa.
     */
    public boolean isSetupMode() {
        return prefs.getBoolean(KEY_SETUP_MODE, false);
    }

    public void setSetupMode(boolean on) {
        prefs.edit().putBoolean(KEY_SETUP_MODE, on).apply();
    }

    /** WiFi nhà đã cấu hình (để hiện lại trong giao diện). */
    public String getHomeWifiSsid() {
        return prefs.getString(KEY_HOME_WIFI_SSID, "");
    }

    public void setHomeWifi(String ssid, String password) {
        prefs.edit()
            .putString(KEY_HOME_WIFI_SSID, ssid == null ? "" : ssid)
            .putString(KEY_HOME_WIFI_PASS, password == null ? "" : password)
            .apply();
    }

    public String getHomeWifiPassword() {
        return prefs.getString(KEY_HOME_WIFI_PASS, "");
    }

    /** Tự vào chế độ cài đặt khi chưa có WiFi nhà nào được lưu. */
    public boolean isSetupAutoWhenNoWifi() {
        return prefs.getBoolean(KEY_SETUP_AUTO, true);
    }

    public void setSetupAutoWhenNoWifi(boolean on) {
        prefs.edit().putBoolean(KEY_SETUP_AUTO, on).apply();
    }
}
