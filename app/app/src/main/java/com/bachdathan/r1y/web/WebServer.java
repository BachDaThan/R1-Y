package com.bachdathan.r1y.web;

import android.content.Context;
import android.util.Log;

import com.bachdathan.r1y.config.R1YConfig;
import com.bachdathan.r1y.hardware.HotspotManager;
import com.bachdathan.r1y.hardware.WifiProvisioner;
import com.bachdathan.r1y.update.UpdateManager;
import com.bachdathan.r1y.youtube.ResolverManager;
import com.bachdathan.r1y.youtube.SearchManager;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import fi.iki.elonen.NanoHTTPD;

/**
 * Web server của R1-Y trên cổng 8088 (NanoHTTPD).
 *
 * Giao diện giống ảnh: header R1-Y + trạng thái "Sẵn sàng",
 * nút "Cho điện thoại kết nối" / "Ngắt Bluetooth",
 * mục "Bộ lấy tiếng YouTube" với ô JSON + nút Tải lại / Lưu,
 * thanh điều hướng dưới: Trạng thái | Trợ lý | Nhạc | Nhà | Thêm.
 *
 * REST API (cho Tube/LiteTube/R1-Y server và điện thoại):
 *   GET  /api/youtube/resolvers   -> mảng JSON resolver
 *   POST /api/youtube/resolvers   -> lưu (nhận mảng, {"resolvers":[...]}, {"json":"..."}, {"value":[...]})
 *   GET  /api/youtube/discover    -> dò lại máy chủ công cộng (dùng khi để "[]")
 *   POST /api/youtube/discover    -> xoá cache dò rồi dò lại
 *   GET  /api/settings            -> toàn bộ cài đặt
 *   POST /api/settings            -> lưu cài đặt
 *   GET  /api/youtube/search?q=   -> tìm kiếm YouTube
 *   GET  /api/media/play?query=   -> tìm + lấy tiếng + phát (Tube gọi)
 *   POST /api/media/play          -> {"query":"...","source":"youtube"}
 *   GET  /api/update/check        -> kiểm tra cập nhật GitHub
 *   POST /api/update/install      -> tải + cài bản mới
 *   GET  /api/status              -> trạng thái loa
 *   GET  /pairing-code            -> tương thích bản cũ
 */
public class WebServer extends NanoHTTPD {

    private static final String TAG = "WebServer";

    private final Context context;
    private final R1YConfig config;
    private final ResolverManager resolverManager;
    private final SearchManager searchManager;
    private final UpdateManager updateManager;

    public WebServer(Context context) {
        super("0.0.0.0", R1YConfig.WEB_PORT);
        this.context = context;
        this.config = new R1YConfig(context);
        this.resolverManager = new ResolverManager(config);
        this.searchManager = new SearchManager(config);
        this.updateManager = new UpdateManager(context);
    }

    // ------------------------------------------------------------------ helpers

    private static Response json(int status, String body) {
        return newFixedLengthResponse(Response.Status.lookup(status), "application/json", body);
    }

    private static Response html(String body) {
        return newFixedLengthResponse(Response.Status.OK, "text/html; charset=utf-8", body);
    }

    private static String readBody(IHTTPSession session) throws IOException, ResponseException {
        Map<String, String> files = new HashMap<String, String>();
        session.parseBody(files);
        return files.get("postData");
    }

    private static String param(IHTTPSession session, String key) {
        // NanoHTTPD 2.3.1: getParameters() -> Map<String, List<String>>
        Map<String, List<String>> p = session.getParameters();
        List<String> v = p == null ? null : p.get(key);
        return (v != null && !v.isEmpty()) ? v.get(0) : "";
    }

    @Override
    public Response serve(IHTTPSession session) {
        String method = session.getMethod().name();
        String path = session.getUri();
        Log.d(TAG, method + " " + path);

        try {
            if ("/".equals(path) || "/index.html".equals(path)) {
                return html(PAGE);
            } else if ("/api/youtube/resolvers".equals(path)) {
                return "POST".equals(method) ? handleSaveResolvers(session) : handleGetResolvers();
            } else if ("/api/youtube/discover".equals(path)) {
                return handleDiscover("POST".equals(method));
            } else if ("/api/ai/servers".equals(path)) {
                return handleAiServers();
            } else if ("/api/hotspot".equals(path)) {
                return "POST".equals(method) ? handleHotspot(session) : handleHotspotStatus();
            } else if ("/api/wifi/scan".equals(path)) {
                return handleWifiScan();
            } else if ("/api/wifi/connect".equals(path)) {
                return handleWifiConnect(session);
            } else if ("/api/wifi/status".equals(path)) {
                return handleWifiStatus();
            } else if ("/api/wifi/forget".equals(path)) {
                return handleWifiForget(session);
            } else if ("/api/setup".equals(path)) {
                return "POST".equals(method) ? handleSetup(session) : handleSetupStatus();
            } else if ("/api/reboot".equals(path)) {
                return handleReboot(session);
            } else if ("/api/settings".equals(path)) {
                return "POST".equals(method) ? handleSaveSettings(session) : handleGetSettings();
            } else if ("/api/youtube/search".equals(path)) {
                return handleSearch(session);
            } else if ("/api/media/play".equals(path)) {
                return "POST".equals(method) ? handlePlayPost(session) : handlePlayGet(session);
            } else if ("/api/update/check".equals(path)) {
                return handleUpdateCheck();
            } else if ("/api/update/install".equals(path)) {
                return handleUpdateInstall();
            } else if ("/api/status".equals(path) || "/status".equals(path)) {
                return handleStatus();
            } else if ("/pairing-code".equals(path)) {
                return handlePairing();
            }
        } catch (Exception e) {
            Log.e(TAG, "Lỗi xử lý " + method + " " + path + ": " + e.getMessage(), e);
            return json(500, "{\"ok\":false,\"error\":\"" + e.getMessage() + "\"}");
        }
        return newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "Not Found");
    }

    // ------------------------------------------------------------------ endpoints

    private Response handleGetResolvers() {
        return json(200, config.getResolversJson());
    }

    private Response handleSaveResolvers(IHTTPSession session) {
        try {
            String body = readBody(session);
            if (body == null || body.trim().isEmpty()) {
                return json(400, "{\"ok\":false,\"error\":\"body rỗng\"}");
            }
            body = body.trim();
            String toSave = null;
            if (body.startsWith("[")) {
                toSave = body;
            } else {
                JSONObject o = new JSONObject(body);
                if (o.has("resolvers")) {
                    toSave = o.optJSONArray("resolvers") != null
                        ? o.optJSONArray("resolvers").toString() : "[]";
                } else if (o.has("json")) {
                    toSave = o.optString("json", "[]");
                } else if (o.has("value")) {
                    Object v = o.opt("value");
                    toSave = v instanceof JSONArray ? v.toString() : String.valueOf(v);
                } else if (o.has("youtube_resolvers")) {
                    toSave = o.optString("youtube_resolvers", "[]");
                }
            }
            if (toSave == null) {
                return json(400, "{\"ok\":false,\"error\":\"không hiểu định dạng\"}");
            }
            new JSONArray(toSave); // kiểm tra JSON hợp lệ
            config.setResolversJson(toSave);
            Log.i(TAG, "Đã lưu resolvers: " + toSave);
            return json(200, "{\"ok\":true,\"resolvers\":" + toSave + "}");
        } catch (Exception e) {
            Log.w(TAG, "Lưu resolvers lỗi: " + e.getMessage());
            return json(400, "{\"ok\":false,\"error\":\"" + e.getMessage() + "\"}");
        }
    }

    /**
     * Dò máy chủ công cộng (dùng khi mảng resolver để "[]").
     * POST = xoá cache cũ rồi dò lại từ đầu.
     */
    private Response handleDiscover(boolean forceRefresh) {
        JSONObject o = new JSONObject();
        try {
            if (forceRefresh) config.clearDiscovered();
            List<ResolverManager.Resolver> list = resolverManager.publicResolvers();
            JSONArray arr = new JSONArray();
            JSONArray json = new JSONArray();
            for (ResolverManager.Resolver r : list) {
                arr.put(r.type + " " + r.url);
                JSONObject e = new JSONObject();
                e.put("type", r.type);
                e.put("url", r.url);
                json.put(e);
            }
            o.put("ok", true);
            o.put("found", list.size());
            o.put("servers", arr);
            o.put("resolvers", json);
            o.put("note", "Mảng resolver đang " + (config.getResolversJson().trim().equals("[]")
                ? "rỗng ([]) nên loa dùng các máy chủ công cộng này."
                : "có cấu hình riêng; các máy chủ này chỉ là phương án dự phòng."));
        } catch (Exception e) {
            Log.w(TAG, "Dò máy chủ lỗi: " + e.getMessage());
            try {
                o.put("ok", false);
                o.put("error", e.getMessage());
            } catch (Exception ignored) {}
        }
        return json(200, o.toString());
    }

    /** Danh sách máy chủ AI có sẵn + máy chủ đang dùng. */
    private Response handleAiServers() {
        JSONObject o = new JSONObject();
        try {
            JSONArray arr = new JSONArray();
            for (String[] p : R1YConfig.AI_PRESETS) {
                JSONObject e = new JSONObject();
                e.put("id", p[0]);
                e.put("name", p[1]);
                e.put("url", p[2].isEmpty() ? config.getAiUrl() : p[2]);
                arr.put(e);
            }
            o.put("ok", true);
            o.put("servers", arr);
            o.put("current", config.getAiServer());
            o.put("url", config.getAiUrl());
            o.put("hasToken", !config.getAiToken().isEmpty());
        } catch (Exception e) {
            try { o.put("ok", false); o.put("error", e.getMessage()); } catch (Exception ignored) {}
        }
        return json(200, o.toString());
    }

    private HotspotManager hotspot() {
        return new HotspotManager(context);
    }

    private Response handleHotspotStatus() {
        JSONObject o = new JSONObject();
        try {
            HotspotManager h = hotspot();
            o.put("ok", true);
            o.put("enabled", h.isEnabled());
            o.put("wanted", config.isHotspotEnabled());
            o.put("ssid", config.getHotspotSsid());
            o.put("password", config.getHotspotPassword());
            o.put("autoStart", config.isHotspotAutoStart());
            o.put("url", h.gatewayUrl());
            o.put("hint", h.statusHint());
        } catch (Exception e) {
            try { o.put("ok", false); o.put("error", e.getMessage()); } catch (Exception ignored) {}
        }
        return json(200, o.toString());
    }

    /** Bật/tắt phát WiFi từ loa. Body: {"on":true,"ssid":"R1-Y","password":"...","autoStart":true} */
    private Response handleHotspot(IHTTPSession session) {
        JSONObject o = new JSONObject();
        try {
            String body = readBody(session);
            JSONObject in = (body == null || body.trim().isEmpty()) ? new JSONObject() : new JSONObject(body);
            if (in.has("ssid")) config.setHotspotSsid(in.optString("ssid"));
            if (in.has("password")) config.setHotspotPassword(in.optString("password"));
            if (in.has("autoStart")) config.setHotspotAutoStart(in.optBoolean("autoStart", true));

            boolean on = in.optBoolean("on", !hotspot().isEnabled());
            config.setHotspotEnabled(on);

            HotspotManager h = hotspot();
            boolean result = on ? h.enableFromConfig() : h.disable();
            o.put("ok", result || h.isEnabled() == on);
            o.put("enabled", h.isEnabled());
            o.put("ssid", config.getHotspotSsid());
            o.put("url", h.gatewayUrl());
            o.put("hint", h.statusHint());
            if (!result) {
                o.put("warning", "ROM có thể cần root/quyền hệ thống để bật hotspot. "
                    + "Thử: adb shell su -c \"cmd wifi start-softap " + config.getHotspotSsid()
                    + " wpa2 " + config.getHotspotPassword() + "\"");
            }
        } catch (Exception e) {
            try { o.put("ok", false); o.put("error", e.getMessage()); } catch (Exception ignored) {}
        }
        return json(200, o.toString());
    }

    // ---------------------------------------------------------- cài đặt WiFi

    private WifiProvisioner wifiSetup() {
        return new WifiProvisioner(context);
    }

    /** POST /api/wifi/scan -> danh sách WiFi quét được. */
    private Response handleWifiScan() {
        JSONObject o = new JSONObject();
        try {
            JSONArray list = wifiSetup().scan();
            o.put("ok", true);
            o.put("count", list.length());
            o.put("networks", list);
            o.put("current", new JSONArray().put(wifiSetup().currentIp()));
        } catch (Exception e) {
            try { o.put("ok", false); o.put("error", e.getMessage()); } catch (Exception ignored) {}
        }
        return json(200, o.toString());
    }

    /** POST /api/wifi/connect {"ssid":"...","password":"..."} */
    private Response handleWifiConnect(IHTTPSession session) {
        JSONObject o = new JSONObject();
        try {
            String body = readBody(session);
            JSONObject in = (body == null || body.trim().isEmpty()) ? new JSONObject() : new JSONObject(body);
            String ssid = in.optString("ssid", "");
            String pass = in.optString("password", "");
            if (ssid.trim().isEmpty()) {
                o.put("ok", false);
                o.put("error", "Thiếu tên WiFi (ssid)");
                return json(400, o.toString());
            }
            WifiProvisioner.ConnectResult r = wifiSetup().connect(ssid, pass);
            o.put("ok", r.ok);
            o.put("ssid", r.ssid);
            o.put("message", r.message);
            o.put("ip", r.ip);
            o.put("gateway", r.gateway);
            o.put("webUrl", r.webUrl);
            o.put("hotspotWasDisabled", r.hotspotWasDisabled);
            if (r.ok) {
                config.setSetupMode(false);
                o.put("next", "Loa đã vào WiFi nhà. Mở " + r.webUrl
                    + " hoặc dùng tool cài firmware qua mạng nội bộ.");
            }
        } catch (Exception e) {
            try { o.put("ok", false); o.put("error", e.getMessage()); } catch (Exception ignored) {}
        }
        return json(200, o.toString());
    }

    private Response handleWifiStatus() {
        return json(200, wifiSetup().status().toString());
    }

    /** POST /api/wifi/forget {"ssid":"..."} */
    private Response handleWifiForget(IHTTPSession session) {
        JSONObject o = new JSONObject();
        try {
            String body = readBody(session);
            JSONObject in = (body == null || body.trim().isEmpty()) ? new JSONObject() : new JSONObject(body);
            String ssid = in.optString("ssid", config.getHomeWifiSsid());
            boolean ok = wifiSetup().forget(ssid);
            o.put("ok", ok);
            o.put("ssid", ssid);
        } catch (Exception e) {
            try { o.put("ok", false); o.put("error", e.getMessage()); } catch (Exception ignored) {}
        }
        return json(200, o.toString());
    }

    private Response handleSetupStatus() {
        JSONObject o = new JSONObject();
        try {
            WifiProvisioner wp = wifiSetup();
            HotspotManager hs = hotspot();
            o.put("ok", true);
            o.put("setupMode", config.isSetupMode());
            o.put("hotspotOn", hs.isEnabled());
            o.put("hotspotSsid", config.getHotspotSsid());
            o.put("hotspotPassword", config.getHotspotPassword());
            o.put("hotspotUrl", hs.gatewayUrl());
            o.put("wifi", new JSONObject(wp.status().toString()));
            o.put("webUrl", wp.bestWebUrl());
            o.put("hasInternet", wp.hasInternet());
        } catch (Exception e) {
            try { o.put("ok", false); o.put("error", e.getMessage()); } catch (Exception ignored) {}
        }
        return json(200, o.toString());
    }

    /** POST /api/setup {"on":true} -> bật/tắt chế độ cài đặt (hotspot + chờ WiFi nhà). */
    private Response handleSetup(IHTTPSession session) {
        JSONObject o = new JSONObject();
        try {
            String body = readBody(session);
            JSONObject in = (body == null || body.trim().isEmpty()) ? new JSONObject() : new JSONObject(body);
            boolean on = in.optBoolean("on", true);
            config.setSetupMode(on);
            HotspotManager hs = hotspot();
            boolean ok = on ? hs.enableFromConfig() : hs.disable();
            o.put("ok", ok || hs.isEnabled() == on);
            o.put("setupMode", on);
            o.put("hotspotOn", hs.isEnabled());
            o.put("hotspotSsid", config.getHotspotSsid());
            o.put("hotspotUrl", hs.gatewayUrl());
            o.put("hint", on
                ? "Điện thoại nối WiFi \"" + config.getHotspotSsid() + "\" rồi mở " + hs.gatewayUrl()
                : "Đã tắt chế độ cài đặt");
            if (on && !ok) {
                o.put("warning", "ROM có thể cần root để bật hotspot — xem hướng dẫn trong tab Nhà.");
            }
        } catch (Exception e) {
            try { o.put("ok", false); o.put("error", e.getMessage()); } catch (Exception ignored) {}
        }
        return json(200, o.toString());
    }

    /**
     * POST /api/reboot {"mode":"app"|"device","confirm":true}
     * - app    : chỉ khởi động lại R1-Y (nhanh, web lên lại ~5 giây)
     * - device : khởi động lại cả loa (cần root; web lên lại ~40 giây)
     */
    private Response handleReboot(IHTTPSession session) {
        JSONObject o = new JSONObject();
        try {
            String body = readBody(session);
            JSONObject in = (body == null || body.trim().isEmpty()) ? new JSONObject() : new JSONObject(body);
            String mode = in.optString("mode", "app");
            if (!in.optBoolean("confirm", false)) {
                o.put("ok", false);
                o.put("error", "Cần xác nhận: gửi {\"confirm\":true}");
                return json(400, o.toString());
            }
            o.put("ok", true);
            o.put("mode", mode);
            if ("device".equals(mode)) {
                o.put("message", "Đang khởi động lại loa (khoảng 40-60 giây)");
                json(200, o.toString());
                // trả lời client trước rồi mới reboot
                new Thread(new Runnable() {
                    @Override
                    public void run() {
                        try {
                            Thread.sleep(800);
                            boolean done = false;
                            // ưu tiên reboot qua su
                            try {
                                Process p = Runtime.getRuntime().exec(new String[]{"su", "-c", "reboot"});
                                done = p.waitFor() == 0;
                            } catch (Exception ignored) {
                            }
                            if (!done) {
                                // không root: khởi động lại app là mức cao nhất có thể
                                Log.w(TAG, "Không có root — chỉ khởi động lại app");
                                android.os.Process.killProcess(android.os.Process.myPid());
                            }
                        } catch (Exception e) {
                            Log.w(TAG, "reboot lỗi: " + e.getMessage());
                        }
                    }
                }, "R1Y-Reboot").start();
                return null;
            }
            // mode = app
            o.put("message", "Đang khởi động lại R1-Y (khoảng 5-10 giây)");
            new Thread(new Runnable() {
                @Override
                public void run() {
                    try {
                        Thread.sleep(600);
                        android.os.Process.killProcess(android.os.Process.myPid());
                    } catch (Exception ignored) {
                    }
                }
            }, "R1Y-Restart").start();
            return json(200, o.toString());
        } catch (Exception e) {
            try { o.put("ok", false); o.put("error", e.getMessage()); } catch (Exception ignored) {}
            return json(500, o.toString());
        }
    }

    private Response handleGetSettings() {
        JSONObject o = new JSONObject();
        try {
            o.put("youtube_resolvers", new JSONArray(config.getResolversJson()));
            o.put("youtube_autodiscover", config.isAutodiscover());
            o.put("music_server", config.getMusicServer());
            o.put("update_repo", config.getUpdateRepo());
            o.put("update_auto_check", config.isAutoCheckUpdate());
            o.put("ai_server", config.getAiServer());
            o.put("ai_server_url", config.getAiUrl());
            o.put("ai_has_token", !config.getAiToken().isEmpty());
            o.put("hotspot_enabled", config.isHotspotEnabled());
            o.put("hotspot_ssid", config.getHotspotSsid());
            o.put("hotspot_password", config.getHotspotPassword());
            o.put("hotspot_auto_start", config.isHotspotAutoStart());
            o.put("audio_quality", config.getAudioQuality());
            o.put("setup_mode", config.isSetupMode());
            o.put("home_wifi_ssid", config.getHomeWifiSsid());
        } catch (Exception ignored) {}
        return json(200, o.toString());
    }

    private Response handleSaveSettings(IHTTPSession session) {
        try {
            JSONObject o = new JSONObject(readBody(session));
            if (o.has("youtube_resolvers")) {
                Object v = o.opt("youtube_resolvers");
                config.setResolversJson(v instanceof JSONArray ? v.toString() : String.valueOf(v));
            }
            if (o.has("youtube_autodiscover")) {
                config.setAutodiscover(o.optBoolean("youtube_autodiscover", true));
            }
            if (o.has("music_server")) {
                config.setMusicServer(o.optString("music_server", ""));
            }
            if (o.has("update_repo")) {
                config.setUpdateRepo(o.optString("update_repo", R1YConfig.GITHUB_REPO));
            }
            if (o.has("update_auto_check")) {
                config.setAutoCheckUpdate(o.optBoolean("update_auto_check", true));
            }
            if (o.has("ai_server")) {
                config.setAiServer(o.optString("ai_server", "xiaozhi"));
                String id = config.getAiServer();
                if (!"custom".equals(id)) config.setAiUrl(R1YConfig.aiPresetUrl(id));
            }
            if (o.has("ai_server_url")) config.setAiUrl(o.optString("ai_server_url", ""));
            if (o.has("ai_token")) config.setAiToken(o.optString("ai_token", ""));
            if (o.has("hotspot_ssid")) config.setHotspotSsid(o.optString("hotspot_ssid", "R1-Y"));
            if (o.has("hotspot_password")) config.setHotspotPassword(o.optString("hotspot_password", ""));
            if (o.has("hotspot_auto_start")) config.setHotspotAutoStart(o.optBoolean("hotspot_auto_start", true));
            if (o.has("hotspot_enabled")) config.setHotspotEnabled(o.optBoolean("hotspot_enabled", false));
            if (o.has("audio_quality")) config.setAudioQuality(o.optString("audio_quality", "high"));
            return json(200, "{\"ok\":true}");
        } catch (Exception e) {
            return json(400, "{\"ok\":false,\"error\":\"" + e.getMessage() + "\"}");
        }
    }

    private Response handleSearch(IHTTPSession session) {
        String q = param(session, "q");
        try {
            List<SearchManager.Result> res = searchManager.search(q, 20);
            JSONArray arr = new JSONArray();
            for (SearchManager.Result r : res) {
                JSONObject o = new JSONObject();
                o.put("videoId", r.videoId);
                o.put("title", r.title);
                o.put("author", r.author);
                o.put("lengthSeconds", r.lengthSeconds);
                arr.put(o);
            }
            return json(200, arr.toString());
        } catch (Exception e) {
            return json(500, "{\"ok\":false,\"error\":\"" + e.getMessage() + "\"}");
        }
    }

    private Response handlePlayGet(IHTTPSession session) {
        return doPlay(param(session, "query"));
    }

    private Response handlePlayPost(IHTTPSession session) {
        try {
            JSONObject o = new JSONObject(readBody(session));
            // {"query":"...","direct":true}  -> đưa từ khóa cho loa tự tìm + tự chuyển máy chủ
            return doPlay(o.optString("query", ""), !o.optBoolean("direct", false));
        } catch (Exception e) {
            return json(400, "{\"ok\":false,\"error\":\"" + e.getMessage() + "\"}");
        }
    }

    private Response doPlay(String query) {
        return doPlay(query, true);
    }

    /**
     * @param searchFirst true = tìm trước để biết videoId (trả về tiêu đề ngay);
     *                    false = đưa từ khóa cho AudioPlaybackService tự tìm + tự chuyển máy chủ.
     */
    private Response doPlay(String query, boolean searchFirst) {
        try {
            if (query == null || query.trim().isEmpty()) {
                return json(400, "{\"ok\":false,\"error\":\"thiếu query\"}");
            }
            String vid = SearchManager.parseVideoId(query);
            if (vid == null && !searchFirst) {
                // Không cần tìm ở đây: service tự tìm khi phát, rồi failover nếu lỗi
                android.content.Intent intent = new android.content.Intent(context,
                    com.bachdathan.r1y.service.AudioPlaybackService.class);
                intent.setAction(com.bachdathan.r1y.service.AudioPlaybackService.ACTION_PLAY_QUERY);
                intent.putExtra(com.bachdathan.r1y.service.AudioPlaybackService.EXTRA_QUERY, query);
                context.startService(intent);
                JSONObject o = new JSONObject();
                o.put("ok", true);
                o.put("queued", true);
                o.put("query", query);
                return json(200, o.toString());
            }
            if (vid == null) {
                List<SearchManager.Result> res = searchManager.search(query, 1);
                if (res.isEmpty()) {
                    return json(404, "{\"ok\":false,\"error\":\"không tìm thấy\"}");
                }
                vid = res.get(0).videoId;
            }
            ResolverManager.Resolved r = resolverManager.resolve(vid);
            android.content.Intent intent = new android.content.Intent(context,
                com.bachdathan.r1y.service.AudioPlaybackService.class);
            intent.setAction(com.bachdathan.r1y.service.AudioPlaybackService.ACTION_PLAY_URL);
            intent.putExtra("audio_url", r.audioUrl);
            intent.putExtra(com.bachdathan.r1y.service.AudioPlaybackService.EXTRA_VIDEO_ID, vid);
            intent.putExtra(com.bachdathan.r1y.service.AudioPlaybackService.EXTRA_SOURCE, r.source);
            context.startService(intent);
            JSONObject o = new JSONObject();
            o.put("ok", true);
            o.put("videoId", vid);
            o.put("title", r.title);
            o.put("author", r.author);
            o.put("audioUrl", r.audioUrl);
            o.put("source", r.source);
            o.put("tier", r.tier);
            JSONArray ladder = new JSONArray();
            if (r.ladder != null) {
                for (com.bachdathan.r1y.youtube.AudioFallback.Candidate c : r.ladder) {
                    JSONObject e = new JSONObject();
                    e.put("label", c.label);
                    e.put("tier", c.tier);
                    e.put("bitrate", c.bitrate);
                    e.put("mime", c.mime);
                    ladder.put(e);
                }
            }
            o.put("ladder", ladder);
            return json(200, o.toString());
        } catch (Exception e) {
            return json(500, "{\"ok\":false,\"error\":\"" + e.getMessage() + "\"}");
        }
    }

    private Response handleUpdateCheck() {
        try {
            UpdateManager.UpdateInfo info = updateManager.checkUpdate();
            JSONObject o = new JSONObject();
            o.put("hasUpdate", info.hasUpdate);
            o.put("latestTag", info.latestTag);
            o.put("currentVersion", info.currentVersion);
            o.put("apkUrl", info.apkUrl);
            o.put("apkName", info.apkName);
            o.put("releaseNotes", info.releaseNotes);
            o.put("publishedAt", info.publishedAt);
            return json(200, o.toString());
        } catch (Exception e) {
            return json(500, "{\"ok\":false,\"error\":\"" + e.getMessage() + "\"}");
        }
    }

    private Response handleUpdateInstall() {
        try {
            UpdateManager.UpdateInfo info = updateManager.updateNow();
            JSONObject o = new JSONObject();
            o.put("ok", true);
            o.put("hasUpdate", info.hasUpdate);
            o.put("latestTag", info.latestTag);
            return json(200, o.toString());
        } catch (Exception e) {
            return json(500, "{\"ok\":false,\"error\":\"" + e.getMessage() + "\"}");
        }
    }

    private Response handleStatus() {
        JSONObject o = new JSONObject();
        try {
            o.put("app", "R1-Y");
            o.put("version", updateManager.getCurrentVersion());
            o.put("port", R1YConfig.WEB_PORT);
            o.put("resolvers", new JSONArray(config.getResolversJson()));
            o.put("autodiscover", config.isAutodiscover());
            o.put("mode", config.getResolversJson().trim().equals("[]")
                ? "public" : "custom");
            o.put("audioQuality", config.getAudioQuality());
            o.put("aiServer", config.getAiServer());
            o.put("aiUrl", config.getAiUrl());
            o.put("hotspotEnabled", config.isHotspotEnabled());
            o.put("hotspotSsid", config.getHotspotSsid());
            o.put("discoveredPiped", config.getDiscoveredPiped());
            o.put("discoveredInvidious", config.getDiscoveredInvidious());
            o.put("musicServer", config.getMusicServer());
            o.put("updateRepo", config.getUpdateRepo());
        } catch (Exception ignored) {}
        return json(200, o.toString());
    }

    private Response handlePairing() {
        JSONObject o = new JSONObject();
        try {
            o.put("device_id", com.bachdathan.r1y.util.PairingCodeGenerator.getDeviceId(context));
            o.put("pairing_code", com.bachdathan.r1y.util.PairingCodeGenerator.getPairingCode(context));
            o.put("paired", com.bachdathan.r1y.util.PairingCodeGenerator.isPaired(context));
        } catch (Exception ignored) {}
        return json(200, o.toString());
    }

    // ------------------------------------------------------------------ giao diện

    private static final String PAGE =
        "<!DOCTYPE html><html lang=\"vi\"><head><meta charset=\"utf-8\">"
        + "<meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">"
        + "<title>R1-Y</title><style>"
        + "body{margin:0;background:#0f1115;color:#e8eaf0;font-family:system-ui,sans-serif}"
        + ".top{display:flex;align-items:center;gap:10px;padding:14px 16px}"
        + ".logo{width:44px;height:44px;border-radius:10px;background:#fff;color:#111;display:flex;align-items:center;justify-content:center;font-size:22px}"
        + ".name{font-size:20px;font-weight:700}.sub{font-size:12px;color:#9aa3b2}"
        + ".ready{margin-left:auto;background:#0f2e1d;color:#4ade80;padding:6px 12px;border-radius:20px;font-size:13px}"
        + ".card{margin:10px 14px;background:#171a21;border:1px solid #232733;border-radius:14px;padding:16px}"
        + ".btn{display:block;width:100%;padding:14px;border:none;border-radius:10px;font-size:16px;margin:8px 0;cursor:pointer}"
        + ".primary{background:#5b5bd6;color:#fff}.danger{background:#1d212b;color:#f87171;border:1px solid #2a2f3a}"
        + ".row{display:flex;gap:10px}.row .btn{flex:1}"
        + "textarea{width:100%;min-height:140px;background:#0b0d11;color:#e8eaf0;border:1px solid #2a2f3a;border-radius:10px;padding:10px;box-sizing:border-box;font-family:monospace}"
        + "code{background:#232733;padding:2px 6px;border-radius:6px;font-size:12px}"
        + ".nav{position:fixed;bottom:0;left:0;right:0;background:#14161c;display:flex;border-top:1px solid #232733}"
        + ".nav button{flex:1;background:none;border:none;color:#9aa3b2;padding:10px 0 14px;font-size:12px;cursor:pointer}"
        + ".nav button.on{color:#8b8bff}.nav .ic{font-size:20px;display:block}"
        + ".msg{font-size:13px;color:#9aa3b2;min-height:18px}"
        + "h3{margin:0 0 10px;font-size:17px}"
        + ".wifi{display:flex;align-items:center;gap:10px;padding:12px;border:1px solid #232733;border-radius:10px;margin:6px 0;cursor:pointer}"
        + ".wifi.sel{border-color:#5b5bd6;background:#1b1e2b}.wifi .nm{flex:1}.wifi .lk{font-size:12px;color:#9aa3b2}"
        + "</style></head><body>"
        + "<div class=\"top\"><div class=\"logo\">&#9635;</div>"
        + "<div><div class=\"name\">R1-Y</div><div class=\"sub\" id=\"sub\">... &middot; v1.6.2</div></div>"
        + "<div class=\"ready\">&#9679; S&#7841;n s&#224;ng</div></div>"
        + "<div class=\"card\" id=\"card-setup\">"
        + "<h3>&#128225; C&#224;i &#273;&#7863;t WiFi cho loa</h3>"
        + "<div class=\"msg\" id=\"setupHint\">Gi&#7919; n&#250;t tr&#242;n tr&#234;n loa &#273;&#7875; loa ph&#225;t WiFi, n&#7889;i v&#224;o r&#7891;i ch&#7885;n WiFi nh&#224; &#7903; &#273;&#226;y.</div>"
        + "<div class=\"row\"><button class=\"btn primary\" onclick=\"scanWifi()\">&#128269; Qu&#233;t WiFi</button>"
        + "<button class=\"btn danger\" onclick=\"setupMode()\">B&#7853;t ch&#7871; &#273;&#7897; c&#224;i &#273;&#7863;t</button></div>"
        + "<div id=\"wifiList\" style=\"margin-top:10px\"></div>"
        + "<input id=\"wifiPass\" type=\"password\" placeholder=\"M&#7853;t kh&#7849;u WiFi nh&#224;\" style=\"width:100%;margin-top:8px;padding:12px;border-radius:10px;border:1px solid #2a2f3a;background:#0b0d11;color:#e8eaf0;box-sizing:border-box\">"
        + "<div class=\"row\"><button class=\"btn primary\" onclick=\"connectWifi()\">K&#7871;t n&#7889;i WiFi n&#224;y</button></div>"
        + "<div class=\"msg\" id=\"wifiMsg\"></div></div>"
        + "<div class=\"card\">"
        + "<button class=\"btn primary\" onclick=\"connectPhone()\">Cho &#273;i&#7879;n tho&#7841;i k&#7871;t n&#7889;i</button>"
        + "<button class=\"btn danger\" onclick=\"disconnectBt()\">Ng&#7845;t Bluetooth</button>"
        + "<div class=\"row\"><button class=\"btn danger\" onclick=\"reboot('app')\">Kh&#7903;i &#273;&#7897;ng l&#7841;i app</button>"
        + "<button class=\"btn danger\" onclick=\"reboot('device')\">Kh&#7903;i &#273;&#7897;ng l&#7841;i loa</button></div>"
        + "<div class=\"msg\" id=\"btMsg\"></div></div>"
        + "<div class=\"card\" id=\"tab-nhac\">"
        + "<h3>&#127925; Ph&#225;t nh&#7841;c</h3>"
        + "<input id=\"playQ\" placeholder=\"D&#225;n link YouTube ho&#7863;c g&#245; t&#234;n b&#224;i...\" style=\"width:100%;padding:12px;border-radius:10px;border:1px solid #2a2f3a;background:#0b0d11;color:#e8eaf0;box-sizing:border-box\">"
        + "<div class=\"row\"><button class=\"btn primary\" onclick=\"playNow()\">&#9654; Ph&#225;t</button></div>"
        + "<div class=\"msg\" id=\"playMsg\"></div>"
        + "<h3 style=\"margin-top:18px\">&#9881; Ch&#7845;t l&#432;&#7907;ng &#226;m thanh</h3>"
        + "<select id=\"audioQ\" onchange=\"saveAudioQ()\" style=\"width:100%;padding:12px;border-radius:10px;border:1px solid #2a2f3a;background:#0b0d11;color:#e8eaf0\">"
        + "<option value=\"high\">Cao nh&#7845;t (Opus, nghe hay nh&#7845;t)</option>"
        + "<option value=\"compat\">T&#432;&#417;ng th&#237;ch (AAC/m4a)</option></select>"
        + "<h3 style=\"margin-top:18px\">&#9654; B&#7897; l&#7845;y ti&#7871;ng YouTube</h3>"
        + "<div>&#272;&#7875; <code>[]</code> (m&#7863;c &#273;&#7883;nh) loa t&#7921; d&#242; m&#225;y ch&#7911; YouTube c&#244;ng c&#7897;ng &#273;ang s&#7889;ng: t&#236;m ki&#7871;m v&#224; l&#7845;y &#226;m thanh gi&#7889;ng Tube/LiteTube. Khi c&#243; m&#225;y ch&#7911; ri&#234;ng th&#236; thay <code>[]</code> b&#7857;ng &#273;&#7883;a ch&#7881; &#273;&#243;.</div>"
        + "<div class=\"msg\" id=\"ytInfo\">&#272;ang ki&#7875;m tra m&#225;y ch&#7911; c&#244;ng c&#7897;ng...</div>"
        + "<textarea id=\"ytJson\">[]</textarea>"
        + "<div class=\"row\"><button class=\"btn danger\" onclick=\"loadRes()\">T&#7843;i l&#7841;i</button>"
        + "<button class=\"btn primary\" onclick=\"saveRes()\">L&#432;u</button></div>"
        + "<button class=\"btn danger\" onclick=\"discover()\">T&#7921; d&#242; l&#7841;i m&#225;y ch&#7911; c&#244;ng c&#7897;ng</button>"
        + "<div class=\"msg\" id=\"resMsg\"></div></div>"
        + "<div class=\"card\" id=\"tab-troly\" style=\"display:none\">"
        + "<h3>&#10024; M&#225;y ch&#7911; AI (tr&#7907; l&#253;)</h3>"
        + "<div class=\"msg\">M&#7863;c &#273;&#7883;nh l&#224; xiaozhi.me. C&#243; th&#7875; &#273;&#7893;i sang m&#225;y ch&#7911; t&#7921; d&#7921;ng ho&#7863;c URL ri&#234;ng.</div>"
        + "<select id=\"aiSel\" onchange=\"onAiChange()\" style=\"width:100%;padding:12px;border-radius:10px;border:1px solid #2a2f3a;background:#0b0d11;color:#e8eaf0\"></select>"
        + "<input id=\"aiUrl\" placeholder=\"wss://...\" style=\"width:100%;margin-top:8px;padding:12px;border-radius:10px;border:1px solid #2a2f3a;background:#0b0d11;color:#e8eaf0;box-sizing:border-box\">"
        + "<input id=\"aiToken\" placeholder=\"Token/API key (kh&#244;ng b&#7855;t bu&#7897;c)\" style=\"width:100%;margin-top:8px;padding:12px;border-radius:10px;border:1px solid #2a2f3a;background:#0b0d11;color:#e8eaf0;box-sizing:border-box\">"
        + "<div class=\"row\"><button class=\"btn primary\" onclick=\"saveAi()\">L&#432;u m&#225;y ch&#7911; AI</button></div>"
        + "<div class=\"msg\" id=\"aiMsg\"></div>"
        + "<div class=\"msg\">K&#7871;t n&#7889;i l&#7841;i tr&#7907; l&#253;: <code>adb shell am force-stop com.bachdathan.r1y</code> r&#7891;i m&#7903; l&#7841;i app.</div></div>"
        + "<div class=\"card\" id=\"tab-nha\" style=\"display:none\">"
        + "<h3>&#8962; Ph&#225;t WiFi t&#7915; loa</h3>"
        + "<div class=\"msg\">B&#7853;t &#273;&#7875; &#273;i&#7879;n tho&#7841;i k&#7871;t n&#7889;i tr&#7921;c ti&#7871;p v&#224;o loa (kh&#244;ng c&#7847;n router) r&#7891;i m&#7903; giao di&#7879;n n&#224;y.</div>"
        + "<input id=\"hsSsid\" placeholder=\"T&#234;n WiFi\" style=\"width:100%;padding:12px;border-radius:10px;border:1px solid #2a2f3a;background:#0b0d11;color:#e8eaf0;box-sizing:border-box\">"
        + "<input id=\"hsPass\" placeholder=\"M&#7853;t kh&#7849;u (&#8805;8 k&#253; t&#7921;)\" style=\"width:100%;margin-top:8px;padding:12px;border-radius:10px;border:1px solid #2a2f3a;background:#0b0d11;color:#e8eaf0;box-sizing:border-box\">"
        + "<div class=\"row\"><button class=\"btn primary\" onclick=\"hotspot(true)\">B&#7853;t WiFi</button>"
        + "<button class=\"btn danger\" onclick=\"hotspot(false)\">T&#7855;t WiFi</button></div>"
        + "<div class=\"msg\" id=\"hsMsg\"></div></div>"
        + "<div class=\"card\" id=\"tab-update\" style=\"display:none\">"
        + "<h3>&#8635; C&#7853;p nh&#7853;t firmware</h3>"
        + "<div class=\"msg\" id=\"updInfo\">Ngu&#7891;n: GitHub BachDaThan/R1-Y</div>"
        + "<div class=\"row\"><button class=\"btn danger\" onclick=\"checkUpd()\">Ki&#7875;m tra</button>"
        + "<button class=\"btn primary\" onclick=\"installUpd()\">C&#7853;p nh&#7853;t ngay</button></div>"
        + "<div class=\"msg\" id=\"updMsg\"></div></div>"
        + "<div class=\"card\" id=\"tab-trangthai\" style=\"display:none\">"
        + "<h3>Tr&#7841;ng th&#225;i</h3><div class=\"msg\" id=\"stInfo\">...</div>"
        + "<div class=\"msg\" id=\"stUrl\">&#272;i&#7873;u khi&#7875;n loa t&#7841;i: ...</div>"
        + "<button class=\"btn primary\" onclick=\"loadStatus()\">T&#7843;i l&#7841;i tr&#7841;ng th&#225;i</button></div>"
        + "<div class=\"nav\">"
        + "<button onclick=\"show('trangthai',this)\"><span class=\"ic\">&#9673;</span>Tr&#7841;ng th&#225;i</button>"
        + "<button onclick=\"show('troly',this)\"><span class=\"ic\">&#10024;</span>Tr&#7907; l&#253;</button>"
        + "<button class=\"on\" onclick=\"show('nhac',this)\"><span class=\"ic\">&#9835;</span>Nh&#7841;c</button>"
        + "<button onclick=\"show('nha',this)\"><span class=\"ic\">&#8962;</span>Nh&#224;</button>"
        + "<button onclick=\"show('them',this)\"><span class=\"ic\">&#9783;</span>Th&#234;m</button>"
        + "</div>"
        + "<script>"
        + "function show(id,btn){document.querySelectorAll('.nav button').forEach(function(b){b.classList.remove('on')});btn.classList.add('on');"
        + "document.getElementById('tab-nhac').style.display=(id==='nhac')?'block':'none';"
        + "document.getElementById('tab-update').style.display=(id==='them')?'block':'none';"
        + "document.getElementById('tab-trangthai').style.display=(id==='trangthai')?'block':'none';"
        + "document.getElementById('tab-troly').style.display=(id==='troly')?'block':'none';"
        + "document.getElementById('tab-nha').style.display=(id==='nha')?'block':'none';}"
        + "async function loadRes(){var r=await fetch('/api/youtube/resolvers');var t=await r.text();document.getElementById('ytJson').value=t;document.getElementById('resMsg').textContent='&#272;&#227; t&#7843;i l&#7841;i';showMode();}"
        + "async function showMode(){var r=await fetch('/api/settings');var j=await r.json();var n=(j.youtube_resolvers||[]).length;document.getElementById('ytInfo').textContent=n?('&#272;ang d&#249;ng '+n+' m&#225;y ch&#7911; ri&#234;ng c&#7911;a b&#7841;n.'):'&#272;ang d&#249;ng m&#225;y ch&#7911; YouTube c&#244;ng c&#7897;ng (t&#7921; d&#242;).';}"
        + "async function saveRes(){var v=document.getElementById('ytJson').value;try{JSON.parse(v)}catch(e){document.getElementById('resMsg').textContent='JSON kh&#244;ng h&#7907;p l&#7879;: '+e.message;return;}var r=await fetch('/api/youtube/resolvers',{method:'POST',body:v});var j=await r.json();document.getElementById('resMsg').textContent=j.ok?'&#272;&#227; l&#432;u':'L&#7895;i: '+j.error;showMode();}"
        + "async function discover(){document.getElementById('resMsg').textContent='&#272;ang d&#242; m&#225;y ch&#7911; c&#244;ng c&#7897;ng (c&#243; th&#7875; m&#7845;t 10-20 gi&#226;y)...';var r=await fetch('/api/youtube/discover',{method:'POST'});var j=await r.json();document.getElementById('resMsg').textContent=j.ok?('T&#236;m th&#7845;y '+j.found+' m&#225;y ch&#7911;: '+(j.servers||[]).join(', ')):('L&#7895;i: '+j.error);}"
        + "async function checkUpd(){document.getElementById('updMsg').textContent='&#272;ang ki&#7875;m tra...';var r=await fetch('/api/update/check');var j=await r.json();document.getElementById('updInfo').textContent=j.hasUpdate?('C&#243; b&#7843;n '+j.latestTag):('&#272;&#227; m&#7899;i nh&#7845;t ('+j.currentVersion+')');document.getElementById('updMsg').textContent=j.releaseNotes||'';}"
        + "async function installUpd(){document.getElementById('updMsg').textContent='&#272;ang t&#7843;i + c&#224;i...';var r=await fetch('/api/update/install',{method:'POST'});var j=await r.json();document.getElementById('updMsg').textContent=j.ok?'&#272;&#227; c&#224;i, loa s&#7869; kh&#7903;i &#273;&#7897;ng l&#7841;i':'L&#7895;i: '+j.error;}"
        + "async function loadStatus(){var r=await fetch('/api/status');var j=await r.json();"
        + "document.getElementById('stInfo').textContent=j.app+' v'+j.version+' — c&#7893;ng '+j.port;"
        + "document.getElementById('sub').textContent='v'+j.version;"
        + "var u=document.getElementById('stUrl');if(u)u.textContent='&#272;i&#7873;u khi&#7875;n loa t&#7841;i: http://'+location.hostname+':'+j.port;}"
        + "function connectPhone(){document.getElementById('btMsg').textContent='Bluetooth &#273;&#227; s&#7841;n s&#224;ng cho &#273;i&#7879;n tho&#7841;i k&#7871;t n&#7889;i';}"
        + "function disconnectBt(){document.getElementById('btMsg').textContent='&#272;&#227; ng&#7845;t Bluetooth';}"
        + "async function playNow(){var q=document.getElementById('playQ').value.trim();if(!q){return;}"
        + "var m=document.getElementById('playMsg');m.textContent='&#272;ang t&#236;m v&#224; ph&#225;t...';"
        + "try{var r=await fetch('/api/media/play',{method:'POST',body:JSON.stringify({query:q})});var j=await r.json();"
        + "m.textContent=j.ok?('&#272;ang ph&#225;t: '+(j.title||j.videoId)+' ('+(j.source||'')+')'):('L&#7895;i: '+j.error);}catch(e){m.textContent='L&#7895;i: '+e.message;}}"
        + "async function saveAudioQ(){var v=document.getElementById('audioQ').value;"
        + "var r=await fetch('/api/settings',{method:'POST',body:JSON.stringify({audio_quality:v})});var j=await r.json();"
        + "alert(j.ok?'&#272;&#227; l&#432;u ch&#7845;t l&#432;&#7907;ng: '+v:('L&#7895;i: '+j.error));}"
        + "async function loadAi(){var r=await fetch('/api/ai/servers');var j=await r.json();"
        + "var sel=document.getElementById('aiSel');sel.innerHTML='';"
        + "(j.servers||[]).forEach(function(s){var o=document.createElement('option');o.value=s.id;o.textContent=s.name;if(s.id===j.current){o.selected=true;}sel.appendChild(o);});"
        + "document.getElementById('aiUrl').value=j.url||'';}"
        + "function onAiChange(){var id=document.getElementById('aiSel').value;var u=document.getElementById('aiUrl');"
        + "if(id==='xiaozhi'){u.value='wss://xiaozhi.me/v1/ws';}else if(id==='xiaozhi_self'){u.value='ws://192.168.1.100:8000/xiaozhi/v1/';}else{u.value='';u.focus();}}"
        + "async function saveAi(){var id=document.getElementById('aiSel').value;var u=document.getElementById('aiUrl').value;var t=document.getElementById('aiToken').value;"
        + "var r=await fetch('/api/settings',{method:'POST',body:JSON.stringify({ai_server:id,ai_server_url:u,ai_token:t})});var j=await r.json();"
        + "document.getElementById('aiMsg').textContent=j.ok?'&#272;&#227; l&#432;u m&#225;y ch&#7911; AI. K&#7871;t n&#7889;i l&#7841;i &#273;&#7875; &#225;p d&#7909;ng.':('L&#7895;i: '+j.error);}"
        + "async function hotspot(on){var r=await fetch('/api/hotspot',{method:'POST',body:JSON.stringify({on:on,ssid:document.getElementById('hsSsid').value,password:document.getElementById('hsPass').value})});"
        + "var j=await r.json();var m=document.getElementById('hsMsg');"
        + "m.textContent=(j.ok?('OK: '+j.hint):('Kh&#244;ng b&#7853;t &#273;&#432;&#7907;c. '+j.hint))+(j.warning?(' | '+j.warning):'');}"
        + "async function loadHotspot(){var r=await fetch('/api/hotspot');var j=await r.json();"
        + "document.getElementById('hsSsid').value=j.ssid||'R1-Y';document.getElementById('hsPass').value=j.password||'';"
        + "document.getElementById('hsMsg').textContent=j.hint||'';}"
        + "async function loadAudioQ(){var r=await fetch('/api/settings');var j=await r.json();"
        + "document.getElementById('audioQ').value=j.audio_quality||'high';}"
        + "var pickedSsid='';"
        + "async function scanWifi(){var m=document.getElementById('wifiMsg');m.textContent='&#272;ang qu&#233;t...';"
        + "var r=await fetch('/api/wifi/scan');var j=await r.json();"
        + "var box=document.getElementById('wifiList');box.innerHTML='';"
        + "if(!j.ok){m.textContent='L&#7895;i: '+j.error;return;}"
        + "(j.networks||[]).forEach(function(n){var d=document.createElement('div');d.className='wifi';"
        + "d.innerHTML='<span class=\"nm\">'+n.ssid+'</span><span class=\"lk\">'+(n.secured?'&#128274;':'m&#7903;')+(n.saved?' &#10003;':'')+'</span>';"
        + "d.onclick=function(){pickedSsid=n.ssid;document.querySelectorAll('.wifi').forEach(function(e){e.classList.remove('sel')});d.classList.add('sel');"
        + "document.getElementById('wifiMsg').textContent='&#272;&#227; ch&#7885;n: '+n.ssid;};box.appendChild(d);});"
        + "m.textContent='Th&#7845;y '+j.count+' WiFi. Ch&#7885;n WiFi nh&#224; c&#7911;a b&#7841;n.';}"
        + "async function connectWifi(){if(!pickedSsid){document.getElementById('wifiMsg').textContent='Ch&#432;a ch&#7885;n WiFi';return;}"
        + "var m=document.getElementById('wifiMsg');m.textContent='&#272;ang k&#7871;t n&#7889;i '+pickedSsid+' (ch&#7901; ~25s)...';"
        + "var r=await fetch('/api/wifi/connect',{method:'POST',body:JSON.stringify({ssid:pickedSsid,password:document.getElementById('wifiPass').value})});"
        + "var j=await r.json();m.textContent=j.ok?(j.message+' | '+j.next):('Kh&#244;ng k&#7871;t n&#7889;i &#273;&#432;&#7907;c: '+j.message);"
        + "if(j.ok){document.getElementById('setupHint').textContent='Loa &#273;&#227; v&#224;o WiFi nh&#224;: '+j.ip;}}"
        + "async function setupMode(){var m=document.getElementById('wifiMsg');m.textContent='&#272;ang b&#7853;t ch&#7871; &#273;&#7897; c&#224;i &#273;&#7863;t...';"
        + "var r=await fetch('/api/setup',{method:'POST',body:'{\"on\":true}'});var j=await r.json();"
        + "m.textContent=(j.ok?'OK. ':'Kh&#244;ng b&#7853;t &#273;&#432;&#7907;c. ')+(j.hint||'')+(j.warning?(' | '+j.warning):'');}"
        + "async function loadSetup(){try{var r=await fetch('/api/setup');var j=await r.json();"
        + "var w=j.wifi||{};document.getElementById('setupHint').textContent=w.sameNetworkHint||'';"
        + "if(w.savedSsid){document.getElementById('wifiPass').placeholder='M&#7853;t kh&#7849;u WiFi nh&#224; ('+w.savedSsid+')';}}catch(e){}}"
        + "async function reboot(mode){"
        + "var t=mode==='device'?'Kh&#7903;i &#273;&#7897;ng l&#7841;i c&#7843; loa (ch&#7901; ~40-60 gi&#226;y, c&#7847;n root)':'Kh&#7903;i &#273;&#7897;ng l&#7841;i app R1-Y (ch&#7901; ~10 gi&#226;y)';"
        + "if(!confirm(t+'?'))return;"
        + "document.getElementById('btMsg').textContent='&#272;ang kh&#7903;i &#273;&#7897;ng l&#7841;i...';"
        + "try{var r=await fetch('/api/reboot',{method:'POST',body:JSON.stringify({mode:mode,confirm:true})});var j=await r.json();"
        + "document.getElementById('btMsg').textContent=j.message||j.error||'OK';"
        + "}catch(e){document.getElementById('btMsg').textContent='Loa &#273;ang kh&#7903;i &#273;&#7897;ng l&#7841;i — ch&#7901; r&#7891;i t&#7843;i l&#7841;i trang.';}}"
        + "loadRes();loadStatus();showMode();loadAi();loadHotspot();loadAudioQ();loadSetup();"
        + "</script></body></html>";
}
