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
            } else if ("/api/player".equals(path)) {
                return handlePlayer(session, method);
            } else if ("/api/audio/test".equals(path)) {
                return handleAudioTest(session);
            } else if ("/api/audio/probe".equals(path)) {
                return json(200, com.bachdathan.r1y.util.UrlProbe.probe(
                        param(session, "url")).toString());
            } else if ("/api/audio/playing".equals(path)) {
                return handlePlayingDiag();
            } else if ("/api/audio/status".equals(path)) {
                return json(200, com.bachdathan.r1y.util.AudioDiag.status(context).toString());
            } else if ("/api/voice".equals(path)) {
                return handleVoice(session);
            } else if ("/api/assistant".equals(path)) {
                return handleAssistant("POST".equals(method));
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
            o.put("audio_route", config.getAudioRoute());
            o.put("mic_gain", config.getMicGain());
            o.put("auto_wake", config.isAutoWakeEnabled());
            o.put("ai_use_opus", config.isAiUseOpus());
            o.put("cache_audio", config.isCacheAudio());
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
            if (o.has("audio_route")) config.setAudioRoute(o.optString("audio_route", "system"));
            if (o.has("mic_gain")) config.setMicGain(o.optInt("mic_gain", 12));
            if (o.has("auto_wake")) config.setAutoWakeEnabled(o.optBoolean("auto_wake", false));
            if (o.has("ai_use_opus")) config.setAiUseOpus(o.optBoolean("ai_use_opus", true));
            if (o.has("cache_audio")) config.setCacheAudio(o.optBoolean("cache_audio", true));
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

    /**
     * Điều khiển phát nhạc: /api/player?action=pause|resume|stop|repeat
     *                          &on=0|1 (cho repeat)
     * GET trả trạng thái: đang phát gì, tạm dừng chưa, có lặp không, âm lượng.
     */
    private Response handlePlayer(IHTTPSession session, String method) {
        JSONObject o = new JSONObject();
        try {
            String action = param(session, "action");
            String svc = com.bachdathan.r1y.service.AudioPlaybackService.class.getName();
            if ("POST".equals(method) && action != null && !action.isEmpty()) {
                if ("route".equals(action)) {
                    String v = param(session, "value");
                    new R1YConfig(context).setAudioRoute(v);
                    o.put("ok", true);
                    o.put("route", new R1YConfig(context).getAudioRoute());
                    o.put("message", "Đã đổi đường ra âm thanh: " + v
                        + ". Ngưng phát rồi bấm Phát lại để áp dụng.");
                } else if ("repeat".equals(action)) {
                    String on = param(session, "on");
                    boolean want = "1".equals(on) || "true".equalsIgnoreCase(on);
                    com.bachdathan.r1y.service.AudioPlaybackService.repeatOne = want;
                    o.put("ok", true);
                    o.put("repeat", want);
                    o.put("message", want ? "Đã bật lặp lại bài đang phát."
                        : "Đã tắt lặp lại.");
                } else {
                    android.content.Intent i = new android.content.Intent(context,
                        com.bachdathan.r1y.service.AudioPlaybackService.class);
                    if ("pause".equals(action)) {
                        i.setAction(com.bachdathan.r1y.service.AudioPlaybackService.ACTION_PAUSE);
                        o.put("message", "Đã tạm dừng.");
                    } else if ("resume".equals(action)) {
                        i.setAction(com.bachdathan.r1y.service.AudioPlaybackService.ACTION_RESUME);
                        o.put("message", "Đang phát tiếp.");
                    } else if ("stop".equals(action)) {
                        i.setAction(com.bachdathan.r1y.service.AudioPlaybackService.ACTION_STOP);
                        o.put("message", "Đã ngưng phát bài này.");
                    } else {
                        o.put("ok", false);
                        o.put("error", "action lạ: " + action);
                        return json(400, o.toString());
                    }
                    context.startService(i);
                    o.put("ok", true);
                    o.put("action", action);
                }
            }
            o.put("playing", com.bachdathan.r1y.service.AudioPlaybackService.isPlayingNow());
            o.put("paused", com.bachdathan.r1y.service.AudioPlaybackService.paused);
            o.put("repeat", com.bachdathan.r1y.service.AudioPlaybackService.repeatOne);
            o.put("title", com.bachdathan.r1y.service.AudioPlaybackService.getNowPlayingTitle());
            o.put("url", com.bachdathan.r1y.service.AudioPlaybackService.getNowPlayingUrl());
            o.put("lastError", com.bachdathan.r1y.service.AudioPlaybackService.lastError);
            o.put("volume", com.bachdathan.r1y.util.AudioDiag.status(context).optInt("volume", -1));
        } catch (Exception e) {
            try { o.put("ok", false); o.put("error", String.valueOf(e.getMessage())); } catch (Exception ignored) {}
        }
        return json(200, o.toString());
    }

    /**
     * GET  /api/audio/test            -> trạng thái âm lượng + kết quả lần trước
     * POST /api/audio/test?kind=beep  -> phát bíp tổng hợp (không cần mạng)
     * POST /api/audio/test?kind=url&url=... -> phát thử 1 URL
     * POST /api/audio/test?kind=volume      -> bật âm lượng lên
     */
    private Response handleAudioTest(IHTTPSession session) {
        String kind = param(session, "kind");
        if (kind == null || kind.isEmpty()) kind = "status";
        JSONObject o = new JSONObject();
        try {
            if ("beep".equals(kind)) {
                final boolean ok = com.bachdathan.r1y.util.AudioDiag.beep(context, 880, 900);
                o.put("ok", ok);
                o.put("kind", "beep");
                o.put("message", ok
                    ? "Đã phát tiếng bíp 1 giây. Nếu loa KHÔNG kêu: lỗi ở âm lượng/đường ra âm thanh "
                      + "(không phải mạng). Nếu loa CÓ kêu: phần cứng ổn, lỗi nằm ở link nhạc."
                    : "Không phát được bíp (AudioTrack lỗi) — kiểm tra âm lượng hệ thống.");
            } else if ("volume".equals(kind)) {
                boolean ok = com.bachdathan.r1y.util.AudioDiag.ensureVolume(context);
                o.put("ok", ok);
                o.put("kind", "volume");
                o.put("status", com.bachdathan.r1y.util.AudioDiag.status(context));
                o.put("message", ok ? "Đã nâng âm lượng nhạc." : "Không đổi được âm lượng.");
            } else if ("url".equals(kind)) {
                String url = param(session, "url");
                if (url == null || url.trim().isEmpty()) {
                    o.put("ok", false);
                    o.put("error", "thiếu url");
                    return json(400, o.toString());
                }
                o.put("kind", "url");
                o.put("result", com.bachdathan.r1y.util.AudioDiag.testUrl(context, url.trim(), 4000));
                o.put("ok", true);
            } else {
                o.put("ok", true);
                o.put("kind", "status");
                o.put("status", com.bachdathan.r1y.util.AudioDiag.status(context));
                o.put("last", com.bachdathan.r1y.util.AudioDiag.last);
            }
        } catch (Exception e) {
            try { o.put("ok", false); o.put("error", String.valueOf(e.getMessage())); } catch (Exception ignored) {}
        }
        return json(200, o.toString());
    }

    /**
     * Điều khiển nói chuyện với trợ lý từ web (không cần bấm nút trên loa).
     *
     *   GET  /api/voice                -> trạng thái giao thức + mic
     *   POST /api/voice?action=talk    -> bắt đầu một lượt nói (hoặc ngắt lời)
     *   POST /api/voice?action=abort   -> ngắt lời trợ lý
     *   POST /api/voice?action=text&text=... -> gửi câu gõ tay
     *   POST /api/voice?action=key&code=66   -> giả lập phím (thử nút trên loa)
     */
    private Response handleVoice(NanoHTTPD.IHTTPSession session) {
        String action = param(session, "action");
        org.json.JSONObject out = new org.json.JSONObject();
        try {
            com.bachdathan.r1y.service.XiaozhiConnectionService conn =
                com.bachdathan.r1y.core.XiaozhiCore.getInstance().getConnectionService();
            com.bachdathan.r1y.service.VoiceRecognitionService voice =
                com.bachdathan.r1y.core.XiaozhiCore.getInstance().getVoiceService();

            if ("talk".equals(action)) {
                com.bachdathan.r1y.util.VoiceTrigger.toggleTalking(context);
                out.put("ok", true);
                out.put("talking", com.bachdathan.r1y.util.VoiceTrigger.talking);
            } else if ("abort".equals(action)) {
                if (conn != null) conn.sendAbort();
                out.put("ok", true);
            } else if ("text".equals(action)) {
                String text = param(session, "text");
                if (conn != null && text != null && !text.trim().isEmpty()) {
                    conn.sendStartListening(null);
                    conn.sendTextMessage(text.trim());
                    out.put("ok", true);
                } else {
                    out.put("ok", false);
                    out.put("error", "Thiếu câu hoặc chưa kết nối máy chủ AI");
                }
            } else if ("key".equals(action)) {
                int code = Integer.parseInt(param(session, "code"));
                android.view.KeyEvent ev = new android.view.KeyEvent(
                        android.view.KeyEvent.ACTION_DOWN, code);
                boolean handled = com.bachdathan.r1y.util.VoiceTrigger.handleKey(
                        context, code, ev);
                out.put("ok", true);
                out.put("handled", handled);
                out.put("keyName", com.bachdathan.r1y.util.VoiceTrigger.keyName(code));
            }

            out.put("connected", conn != null && conn.isConnected());
            if (conn != null) {
                org.json.JSONObject ps = conn.protocolStatus();
                java.util.Iterator<String> it = ps.keys();
                while (it.hasNext()) {
                    String k = it.next();
                    out.put(k, ps.get(k));
                }
            }
            out.put("talking", com.bachdathan.r1y.util.VoiceTrigger.talking);
            out.put("streaming",
                com.bachdathan.r1y.service.VoiceRecognitionService.streaming);
            out.put("streamFrames",
                com.bachdathan.r1y.service.VoiceRecognitionService.streamFrames);
            out.put("micRestarts",
                com.bachdathan.r1y.service.VoiceRecognitionService.micRestarts);
            out.put("micMuted",
                com.bachdathan.r1y.service.VoiceRecognitionService.micMuted);
            out.put("lastKeyName",
                com.bachdathan.r1y.util.VoiceTrigger.lastKeyName);
            out.put("micGain", new R1YConfig(context).getMicGain());

            // Kết luận: vì sao bấm nói mà AI không trả lời
            String verdict = "";
            String advice = "";
            boolean isConn = conn != null && conn.isConnected();
            boolean isOpen = conn != null && conn.protocolStatus().optBoolean("channelOpen");
            int sent = com.bachdathan.r1y.service.VoiceRecognitionService.streamFrames;
            int recvBin = com.bachdathan.r1y.service.XiaozhiConnectionService.recvBinary;
            String lastType = com.bachdathan.r1y.service.XiaozhiConnectionService.lastJsonType;
            if (conn == null) {
                verdict = "App chưa có dịch vụ kết nối AI";
                advice = "Mở lại app trên loa.";
            } else if (!isConn) {
                verdict = "Chưa kết nối máy chủ AI";
                advice = "Kiểm tra WiFi và URL máy chủ; bấm 'Nói' để thử nối lại.";
            } else if (!isOpen) {
                verdict = "Đã nối WebSocket nhưng máy chủ CHƯA trả hello";
                advice = "Sai URL/token/header. Kiểm tra URL 'wss://api.tenclass.net/xiaozhi/v1/', "
                       + "lấy lại mã kích hoạt ở tab Trợ lý.";
            } else if (sent == 0) {
                verdict = "Kênh OK — chưa gửi khung micro nào";
                advice = "Bấm 'Nói' (hoặc nút trên loa) rồi nói.";
            } else if (recvBin == 0 && "".equals(lastType)) {
                verdict = "Đã gửi " + sent + " khung nhưng máy chủ KHÔNG trả gì";
                advice = "Mic gần như im lặng (tăng 'Độ nhạy mic' ở tab Thêm) hoặc "
                       + "chưa bấm 'Nói' đủ lâu.";
            } else if (recvBin == 0) {
                verdict = "Máy chủ có trả lời (JSON: " + lastType + ") nhưng KHÔNG gửi khung tiếng";
                advice = "Máy chủ đang trả bằng định dạng khác Opus (vd JSON base64). "
                       + "Bỏ chọn 'Gửi PCM' nếu đã bật, hoặc dùng máy chủ esp32-server.";
            } else {
                verdict = "Đã nhận " + recvBin + " khung tiếng — kiểm tra đường ra nếu vẫn im";
                advice = "Bấm 'Thử loa (bíp)'. Bíp kêu = codec/đường ra; bíp im = âm lượng.";
            }
            out.put("verdict", verdict);
            if (!advice.isEmpty()) out.put("advice", advice);
        } catch (Throwable t) {
            try {
                out.put("ok", false);
                out.put("error", String.valueOf(t));
            } catch (Throwable ignored) {}
        }
        return json(200, out.toString());
    }

    /**
     * "Báo đang phát mà im" — quyết định xem lỗi nằm ở đâu bằng một câu trả lời.
     *
     * Ba tầng có thể hỏng, tách bạch được:
     *   A. link/nguồn   -> MediaPlayer lỗi (lastError, lastProbe)
     *   B. giải mã      -> prepare xong nhưng position không nhích
     *   C. đường ra/c.h   -> position nhích mà vẫn không nghe (thử bíp)
     */
    private Response handlePlayingDiag() {
        org.json.JSONObject o = new org.json.JSONObject();
        try {
            com.bachdathan.r1y.service.AudioPlaybackService a =
                com.bachdathan.r1y.core.XiaozhiCore.getInstance().getAudioService();
            long preparedAt = com.bachdathan.r1y.service.AudioPlaybackService.lastPreparedAt;
            String err = com.bachdathan.r1y.service.AudioPlaybackService.lastError;
            boolean playing = a != null && a.isPlayingNow();
            boolean paused = a != null && a.isPaused();
            String title = a == null ? "" : a.getNowPlayingTitle();
            String url = a == null ? "" : a.getNowPlayingUrl();

            int pos = -1;
            try {
                if (a != null) pos = a.getPositionMs();
            } catch (Throwable ignored) {}

            o.put("playing", playing);
            o.put("paused", paused);
            o.put("title", title == null ? "" : title);
            o.put("positionMs", pos);
            o.put("preparedAgeMs", preparedAt == 0 ? -1
                    : System.currentTimeMillis() - preparedAt);
            o.put("lastError", err == null ? "" : err);
            o.put("lastProbe", com.bachdathan.r1y.service.AudioPlaybackService.lastProbe);
            o.put("route", com.bachdathan.r1y.util.AudioDiag.streamName(context));

            String verdict;
            String advice = "";
            if (playing && pos >= 0 && pos == 0 && preparedAt > 0) {
                verdict = "Đã chuẩn bị xong nhưng bộ phát chưa chạy được khung nào";
                advice = "Kiểm tra link (nút bên dưới) và thử chất lượng 'Tương thích'.";
            } else if (playing && pos > 0) {
                verdict = "Đang chạy tới " + (pos / 1000) + " giây";
                advice = "Nếu tai không nghe gì: bấm 'Phát thử tiếng bíp'. Bíp kêu = "
                       + "đường ra OK, lỗi ở codec; bíp im = đường ra/âm lượng.";
            } else if (!err.isEmpty()) {
                verdict = "Link/nguồn lỗi: " + err;
                advice = "Đổi bài khác hoặc 'Tải lại' danh sách máy chủ rồi Lưu.";
            } else if ("".equals(url) || url == null) {
                verdict = "Chưa có bài nào được phát";
                advice = "";
            } else {
                verdict = "Chưa phát (đang tạm dừng hoặc chờ link)";
                advice = "Nếu treo ở 'đang tải': link chậm/chết. Bấm Ngưng rồi phát lại.";
            }
            o.put("verdict", verdict);
            if (!advice.isEmpty()) o.put("advice", advice);
        } catch (Throwable t) {
            try {
                o.put("verdict", "Lỗi đọc trạng thái: " + t);
            } catch (Exception ignored) {}
        }
        return json(200, o.toString());
    }

    private Response handleAssistant(boolean start) {
        JSONObject o = new JSONObject();
        try {
            boolean act = com.bachdathan.r1y.activation.ActivationState.activated
                || com.bachdathan.r1y.activation.DeviceFingerprint.getInstance(context).isActivated();
            if (start && !act) {
                com.bachdathan.r1y.activation.ActivationState.error = "";
                com.bachdathan.r1y.activation.ActivationState.fetchStatus =
                    "Đang hỏi máy chủ Xiaozhi (api.tenclass.net)...";
                new Thread(new Runnable() {
                    @Override public void run() {
                        try {
                            new com.bachdathan.r1y.activation.DeviceActivator(context).startActivation();
                        } catch (Throwable t) {
                            com.bachdathan.r1y.activation.ActivationState.fetchStatus = "";
                            com.bachdathan.r1y.activation.ActivationState.error = String.valueOf(t.getMessage());
                        }
                    }
                }, "R1Y-Activate").start();
            }
            o.put("ok", true);
            o.put("activated", act);
            o.put("code", com.bachdathan.r1y.activation.ActivationState.code);
            o.put("error", com.bachdathan.r1y.activation.ActivationState.error);
            o.put("attempt", com.bachdathan.r1y.activation.ActivationState.attempt);
            o.put("max", com.bachdathan.r1y.activation.ActivationState.max);
            o.put("fetchStatus", com.bachdathan.r1y.activation.ActivationState.fetchStatus);
            o.put("url", com.bachdathan.r1y.activation.OTAConfigManager.getLastOtaUrl());
            o.put("httpCode", com.bachdathan.r1y.activation.OTAConfigManager.getLastHttpCode());
            o.put("response", com.bachdathan.r1y.activation.OTAConfigManager.getLastResponse());
        } catch (Exception e) {
            return json(500, "{\"ok\":false}");
        }
        return json(200, o.toString());
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
        + "<meta name=\"viewport\" content=\"width=device-width,initial-scale=1,maximum-scale=1,viewport-fit=cover\">"
        + "<meta name=\"theme-color\" content=\"#0f1115\">"
        + "<title>R1-Y</title><style>"
        + "body{margin:0;background:#0f1115;color:#e8eaf0;font-family:system-ui,sans-serif}"
        + ".top{display:flex;align-items:center;gap:10px;padding:14px 16px}"
        + ".logo{width:44px;height:44px;border-radius:10px;background:#fff;color:#111;display:flex;align-items:center;justify-content:center;font-size:22px}"
        + ".name{font-size:20px;font-weight:700}.sub{font-size:12px;color:#9aa3b2}"
        + ".ready{margin-left:auto;background:#0f2e1d;color:#4ade80;padding:6px 12px;border-radius:20px;font-size:13px}"
        + ".card{margin:10px 14px;background:#171a21;border:1px solid #232733;border-radius:14px;padding:16px}"
        // --- Mobile-first (v1.9.0): hiển thị đúng trên điện thoại, không tràn ngang ---
        + "*{box-sizing:border-box;-webkit-tap-highlight-color:transparent}"
        + "html,body{width:100%;max-width:100%;overflow-x:hidden;-webkit-text-size-adjust:100%}"
        + "body{padding-bottom:env(safe-area-inset-bottom)}"
        // input/select phải >=16px, nếu không iOS tự zoom trang khi bấm vào
        + "input,select,textarea,button{font-size:16px;font-family:inherit;max-width:100%}"
        + "button,.btn{min-height:46px;touch-action:manipulation}"
        + ".card{margin:10px 12px}"
        + "@media (min-width:760px){.card{max-width:720px;margin-left:auto;margin-right:auto}}"
        + "@media (max-width:360px){.card{margin:8px 6px;padding:12px}.btn{font-size:15px}}"
        + "table{width:100%;border-collapse:collapse}"
        + "pre,code{white-space:pre-wrap;word-break:break-word}"
        + "img,video,canvas{max-width:100%;height:auto}"
        + ".btn{display:block;width:100%;padding:14px;border:none;border-radius:10px;font-size:16px;margin:8px 0;cursor:pointer}"
        + ".primary{background:#5b5bd6;color:#fff}.danger{background:#1d212b;color:#f87171;border:1px solid #2a2f3a}"
        + ".row{display:flex;gap:10px;flex-wrap:wrap}.row .btn{flex:1 1 44%;min-width:130px}"
        + "textarea{width:100%;min-height:140px;background:#0b0d11;color:#e8eaf0;border:1px solid #2a2f3a;border-radius:10px;padding:10px;box-sizing:border-box;font-family:monospace}"
        + "code{background:#232733;padding:2px 6px;border-radius:6px;font-size:12px}"
        + ".nav{position:fixed;bottom:0;left:0;right:0;background:#14161c;display:flex;border-top:1px solid #232733;padding-bottom:env(safe-area-inset-bottom);z-index:50}"
        + "main,body>div{padding-bottom:76px}"
        + ".nav button{flex:1;background:none;border:none;color:#9aa3b2;padding:10px 0 14px;font-size:12px;cursor:pointer}"
        + ".nav button.on{color:#8b8bff}.nav .ic{font-size:20px;display:block}"
        + ".msg{font-size:13px;color:#9aa3b2;min-height:18px}"
        + "h3{margin:0 0 10px;font-size:17px}"
        + ".wifi{display:flex;align-items:center;gap:10px;padding:12px;border:1px solid #232733;border-radius:10px;margin:6px 0;cursor:pointer}"
        + ".wifi.sel{border-color:#5b5bd6;background:#1b1e2b}.wifi .nm{flex:1}.wifi .lk{font-size:12px;color:#9aa3b2}"
        + "</style></head><body>"
        + "<div class=\"top\"><div class=\"logo\">&#9635;</div>"
        + "<div><div class=\"name\">R1-Y</div><div class=\"sub\" id=\"sub\">... &middot; v1.9.1</div></div>"
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
        + "<div class=\"row\"><button class=\"btn danger\" onclick=\"pl('pause')\">&#10074;&#10074; T&#7841;m d&#7915;ng</button>"
        + "<button class=\"btn primary\" onclick=\"pl('resume')\">&#9654; Ph&#225;t ti&#7871;p</button></div>"
        + "<div class=\"row\"><button class=\"btn danger\" onclick=\"pl('stop')\">&#10074;&#10074;&#10074; Ng&#432;ng h&#7859;n</button>"
        + "<button class=\"btn danger\" id=\"repBtn\" onclick=\"plRepeat()\">&#128257; L&#7863;p l&#7841;i: T&#7854;T</button></div>"
        + "<div class=\"msg\" id=\"nowBox\"></div>"
        + "<select id=\"routeSel\" onchange=\"plRoute()\" style=\"width:100%;padding:12px;border-radius:10px;border:1px solid #2a2f3a;background:#0b0d11;color:#e8eaf0\">"
        + "<option value=\"system\">&#272;&#432;&#7901;ng ra: H&#7879; th&#7889;ng (STREAM_SYSTEM) &#8212; m&#7863;c &#273;&#7883;nh cho loa R1</option>"
        + "<option value=\"music\">&#272;&#432;&#7901;ng ra: Nh&#7841;c (STREAM_MUSIC)</option>"
        + "<option value=\"ring\">&#272;&#432;&#7901;ng ra: Chu&#244;ng (STREAM_RING)</option></select>"
        + "<h3 style=\"margin-top:18px\">&#128264; Ki&#7875;m tra loa c&#243; ra ti&#7871;ng kh&#244;ng</h3>"
        + "<div class=\"msg\" id=\"sndMsg\">B&#7845;m n&#250;t b&#234;n d&#432;&#7899;i &#273;&#7875; loa ph&#225;t 1 ti&#7871;ng b&#237;p (kh&#244;ng c&#7847;n m&#7841;ng).</div>"
        + "<div class=\"row\"><button class=\"btn primary\" onclick=\"sndBeep()\">&#128266; Ph&#225;t th&#7917; ti&#7871;ng b&#237;p</button>"
        + "<button class=\"btn danger\" onclick=\"sndVol()\">B&#7853;t &#226;m l&#432;&#7907;ng</button></div>"
        + "<div class=\"msg\" id=\"sndInfo\"></div>"
        + "<div class=\"msg\" id=\"codecInfo\"></div>"
        + "<button class=\"btn\" onclick=\"diagPlaying()\">&#128269; Ki&#7875;m tra v&#236; sao im (b&#225;o &#273;ang ph&#225;t m&#224; kh&#244;ng ra ti&#7871;ng)</button>"
        + "<div class=\"msg\" id=\"diagBox\"></div>"
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
        + "<div class=\"msg\" id=\"actBox\" style=\"font-size:15px;line-height:1.6\">&#272;ang ki&#7875;m tra k&#237;ch ho&#7841;t...</div>"
        + "<div class=\"row\"><button class=\"btn primary\" onclick=\"startAct()\">L&#7845;y m&#227; k&#237;ch ho&#7841;t</button></div>"
        + "<div class=\"msg\">M&#7863;c &#273;&#7883;nh l&#224; xiaozhi.me. C&#243; th&#7875; &#273;&#7893;i sang m&#225;y ch&#7911; t&#7921; d&#7921;ng ho&#7863;c URL ri&#234;ng.</div>"
        + "<select id=\"aiSel\" onchange=\"onAiChange()\" style=\"width:100%;padding:12px;border-radius:10px;border:1px solid #2a2f3a;background:#0b0d11;color:#e8eaf0\"></select>"
        + "<input id=\"aiUrl\" placeholder=\"wss://...\" style=\"width:100%;margin-top:8px;padding:12px;border-radius:10px;border:1px solid #2a2f3a;background:#0b0d11;color:#e8eaf0;box-sizing:border-box\">"
        + "<input id=\"aiToken\" placeholder=\"Token/API key (kh&#244;ng b&#7855;t bu&#7897;c)\" style=\"width:100%;margin-top:8px;padding:12px;border-radius:10px;border:1px solid #2a2f3a;background:#0b0d11;color:#e8eaf0;box-sizing:border-box\">"
        + "<div class=\"row\"><button class=\"btn primary\" onclick=\"saveAi()\">L&#432;u m&#225;y ch&#7911; AI</button></div>"
        + "<div class=\"msg\" id=\"aiMsg\"></div>"
        + "<h3 style=\"margin-top:18px\">&#127908; Tr&#7907; l&#253; c&#243; nghe / n&#243;i &#273;&#432;&#7907;c kh&#244;ng</h3>"
        + "<div class=\"msg\" id=\"micInfo\"></div>"
        + "<div class=\"row\"><button class=\"btn primary\" onclick=\"sndBeep()\">&#128266; Th&#7917; loa (b&#237;p)</button>"
        + "<button class=\"btn danger\" onclick=\"loadMic()\">Ki&#7875;m tra mic</button></div>"
        + "<div class=\"msg\">N&#243;i to g&#7847;n loa r&#7891;i b&#7845;m <b>Ki&#7875;m tra mic</b>: s&#7889; ph&#7843;i nh&#7843;y l&#234;n. N&#7871;u lu&#244;n ~0 th&#236; mic b&#7883; ch&#7863;n quy&#7873;n ho&#7863;c h&#7887;ng.</div>"
        + "<div class=\"msg\">K&#7871;t n&#7889;i l&#7841;i tr&#7907; l&#253;: <code>adb shell am force-stop com.bachdathan.r1y</code> r&#7891;i m&#7903; l&#7841;i app.</div>"
        + "<h3 style=\"margin-top:18px\">&#128172; N&#243;i chuy&#7879;n v&#7899;i tr&#7907; l&#253;</h3>"
        + "<div class=\"row\"><button class=\"btn primary\" onclick=\"voiceTalk()\">&#127908; N&#243;i (b&#7845;m &#273;&#7875; n&#243;i, b&#7845;m n&#7919;a &#273;&#7875; th&#244;i)</button></div>"
        + "<input id=\"voiceText\" placeholder=\"Ho&#7863;c g&#245; c&#226;u h&#7887;i r&#7891;i Enter\" style=\"width:100%;margin-top:8px;padding:12px;border-radius:10px;border:1px solid #2a2f3a;background:#0b0d11;color:#e8eaf0;box-sizing:border-box\" onkeydown=\"if(event.key==='Enter'){voiceText()}\">"
        + "<div class=\"row\"><button class=\"btn\" onclick=\"voiceText()\">G&#7917;i c&#226;u</button>"
        + "<button class=\"btn danger\" onclick=\"voiceAbort()\">Ng&#7915;ng tr&#7907; l&#253; n&#243;i</button></div>"
        + "<div class=\"msg\" id=\"voiceInfo\"></div>"
        + "<div class=\"msg\">N&#7871;u b&#7845;m <b>N&#243;i</b> m&#224; tr&#7907; l&#253; im: xem d&#242;ng <b>k&#234;nh</b> &#7903; tr&#234;n. <code>ch&#432;a m&#7903;</code> = m&#225;y ch&#7911; ch&#432;a tr&#7843; hello (sai URL/token). <code>OK</code> m&#224; v&#7851;n im = mic ho&#7863;c m&#225;y ch&#7911; t&#7915; ch&#7889;i.</div></div>"
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
        + "async function loadRes(){var r=await fetch('/api/youtube/resolvers');var t=await r.text();document.getElementById('ytJson').value=t;document.getElementById('resMsg').textContent='\\u0110\\u00e3 t\\u1ea3i l\\u1ea1i';showMode();}"
        + "async function showMode(){var r=await fetch('/api/settings');var j=await r.json();var n=(j.youtube_resolvers||[]).length;document.getElementById('ytInfo').textContent=n?('\\u0110ang d\\u00f9ng '+n+' m\\u00e1y ch\\u1ee7 ri\\u00eang c\\u1ee7a b\\u1ea1n.'):'\\u0110ang d\\u00f9ng m\\u00e1y ch\\u1ee7 YouTube c\\u00f4ng c\\u1ed9ng (t\\u1ef1 d\\u00f2).';}"
        + "async function saveRes(){var v=document.getElementById('ytJson').value;try{JSON.parse(v)}catch(e){document.getElementById('resMsg').textContent='JSON kh\\u00f4ng h\\u1ee3p l\\u1ec7: '+e.message;return;}var r=await fetch('/api/youtube/resolvers',{method:'POST',body:v});var j=await r.json();document.getElementById('resMsg').textContent=j.ok?'\\u0110\\u00e3 l\\u01b0u':'L\\u1ed7i: '+j.error;showMode();}"
        + "async function discover(){document.getElementById('resMsg').textContent='\\u0110ang d\\u00f2 m\\u00e1y ch\\u1ee7 c\\u00f4ng c\\u1ed9ng (c\\u00f3 th\\u1ec3 m\\u1ea5t 10-20 gi\\u00e2y)...';var r=await fetch('/api/youtube/discover',{method:'POST'});var j=await r.json();document.getElementById('resMsg').textContent=j.ok?('T\\u00ecm th\\u1ea5y '+j.found+' m\\u00e1y ch\\u1ee7: '+(j.servers||[]).join(', ')):('L\\u1ed7i: '+j.error);}"
        + "async function checkUpd(){document.getElementById('updMsg').textContent='\\u0110ang ki\\u1ec3m tra...';var r=await fetch('/api/update/check');var j=await r.json();document.getElementById('updInfo').textContent=j.hasUpdate?('C\\u00f3 b\\u1ea3n '+j.latestTag):('\\u0110\\u00e3 m\\u1edbi nh\\u1ea5t ('+j.currentVersion+')');document.getElementById('updMsg').textContent=j.releaseNotes||'';}"
        + "async function installUpd(){document.getElementById('updMsg').textContent='\\u0110ang t\\u1ea3i + c\\u00e0i...';var r=await fetch('/api/update/install',{method:'POST'});var j=await r.json();document.getElementById('updMsg').textContent=j.ok?'\\u0110\\u00e3 c\\u00e0i, loa s\\u1ebd kh\\u1edfi \\u0111\\u1ed9ng l\\u1ea1i':'L\\u1ed7i: '+j.error;}"
        + "async function loadStatus(){var r=await fetch('/api/status');var j=await r.json();"
        + "document.getElementById('stInfo').textContent=j.app+' v'+j.version+' — c\\u1ed5ng '+j.port;"
        + "document.getElementById('sub').textContent='v'+j.version;"
        + "var u=document.getElementById('stUrl');if(u)u.textContent='\\u0110i\\u1ec1u khi\\u1ec3n loa t\\u1ea1i: http://'+location.hostname+':'+j.port;}"
        + "function connectPhone(){document.getElementById('btMsg').textContent='Bluetooth \\u0111\\u00e3 s\\u1ea1n s\\u00e0ng cho \\u0111i\\u1ec7n tho\\u1ea1i k\\u1ebft n\\u1ed1i';}"
        + "function disconnectBt(){document.getElementById('btMsg').textContent='\\u0110\\u00e3 ng\\u1ea5t Bluetooth';}"
        + "async function playNow(){var q=document.getElementById('playQ').value.trim();if(!q){return;}"
        + "var m=document.getElementById('playMsg');m.textContent='\\u0110ang t\\u00ecm v\\u00e0 ph\\u00e1t...';"
        + "try{var r=await fetch('/api/media/play',{method:'POST',body:JSON.stringify({query:q})});var j=await r.json();"
        + "m.textContent=j.ok?('\\u0110ang ph\\u00e1t: '+(j.title||j.videoId)+' ('+(j.source||'')+')'):('L\\u1ed7i: '+j.error);}catch(e){m.textContent='L\\u1ed7i: '+e.message;}}"
        + "async function saveAudioQ(){var v=document.getElementById('audioQ').value;"
        + "var r=await fetch('/api/settings',{method:'POST',body:JSON.stringify({audio_quality:v})});var j=await r.json();"
        + "alert(j.ok?'\\u0110\\u00e3 l\\u01b0u ch\\u1ea5t l\\u01b0\\u1ee3ng: '+v:('L\\u1ed7i: '+j.error));}"
        + "async function loadAct(){try{var r=await fetch('/api/assistant');var j=await r.json();var b=document.getElementById('actBox');"
        + "if(j.activated){b.textContent='\\u0110\\u00e3 k\\u00edch ho\\u1ea1t xiaozhi.me. Tr\\u1ee3 l\\u00fd s\\u1eb5n s\\u00e0ng.';}"
        + "else if(j.code){b.innerHTML='M\\u00e3 k\\u00edch ho\\u1ea1t: <b style=\\\"font-size:30px;letter-spacing:5px\\\">'+j.code+'</b><br>V\\u00e0o xiaozhi.me, th\\u00eam thi\\u1ebft b\\u1ecb v\\u00e0 nh\\u1eadp m\\u00e3 n\\u00e0y. Trang t\\u1ef1 c\\u1eadp nh\\u1eadt.';}"
        + "else{var t='';"
        + "if(j.fetchStatus){t+=j.fetchStatus;}"
        + "if(j.httpCode){t+='\\nM\\u00e1y ch\\u1ee7 tr\\u1ea3 HTTP '+j.httpCode+'.';}"
        + "if(j.error){t+='\\nL\\u1ed7i: '+j.error;}"
        + "if(!t){t='Ch\\u01b0a c\\u00f3 m\\u00e3. B\\u1ea5m \\\"L\\u1ea5y m\\u00e3 k\\u00edch ho\\u1ea1t\\\".';}"
        + "if(j.response){t+='\\n\\nM\\u00e1y ch\\u1ee7 tr\\u1ea3 v\\u1ec1:\\n'+j.response;}"
        + "b.style.whiteSpace='pre-wrap';b.textContent=t;}}catch(e){}}"
        + "async function startAct(){var b=document.getElementById('actBox');b.textContent='\\u0110ang l\\u1ea5y m\\u00e3...';try{await fetch('/api/assistant',{method:'POST'});}catch(e){}setTimeout(loadAct,3000);}"
        + "setInterval(function(){if(document.getElementById('tab-troly').style.display!=='none'){loadAct();}},5000);"
        + "setInterval(function(){var a=document.getElementById('nowBox');if(a){plState();}},5000);"
        + "async function diagPlaying(){var b=document.getElementById('diagBox');if(!b){return;}"
        + "b.textContent='\\u0110ang ki\\u1ec3m tra...';"
        + "var t='';try{var r=await fetch('/api/audio/playing');var j=await r.json();"
        + "t='K\\u1ebft lu\\u1eadn: '+(j.verdict||'?')+'\\n';"
        + "if(j.advice){t+='N\\u00ean l\\u00e0m: '+j.advice+'\\n';}"
        + "t+='\\u0110\\u01b0\\u1eddng ra: '+(j.route||'?')+' | v\\u1ecb tr\\u00ed: '+j.positionMs+'ms';"
        + "if(j.lastError){t+='\\nL\\u1ed7i MediaPlayer: '+j.lastError;}"
        + "var u=null;try{var r2=await fetch('/api/player');var j2=await r2.json();u=j2.url;}catch(e){}"
        + "if(u){t+='\\n\\n\\u0110ang ki\\u1ec3m tra link...';b.style.whiteSpace='pre-wrap';b.textContent=t;"
        + "try{var r3=await fetch('/api/audio/probe?url='+encodeURIComponent(u));var j3=await r3.json();"
        + "t+='\\nLink: HTTP '+j3.status+' | '+(j3.contentType||'?')+' | '+(j3.magic||'?');"
        + "t+='\\n-> '+(j3.verdict||'?');if(j3.advice){t+='\\nN\\u00ean l\\u00e0m: '+j3.advice;}"
        + "}catch(e){t+='\\nKh\\u00f4ng ki\\u1ec3m tra \\u0111\\u01b0\\u1ee3c link: '+e.message;}}"
        + "}catch(e){t='L\\u1ed7i: '+e.message;}"
        + "b.style.whiteSpace='pre-wrap';b.textContent=t;}"
        + "async function loadMic(){var m=document.getElementById('micInfo');m.textContent='\u0110ang \u0111\u1ecdc mic...';"
        + "try{var r=await fetch('/api/audio/status');var j=await r.json();"
        + "var t='M\u1ee9c \u00e2m thanh mic: '+(j.micEnergy!=null?j.micEnergy:'?');"
        + "if(j.micAdvice){t+=' \u2014 '+j.micAdvice;}m.textContent=t;}catch(e){m.textContent='L\u1ed7i: '+e.message;}}"
        + "async function voiceTalk(){try{await fetch('/api/voice?action=talk',{method:'POST'});}catch(e){}setTimeout(voiceStatus,300);}"
        + "async function voiceAbort(){try{await fetch('/api/voice?action=abort',{method:'POST'});}catch(e){}setTimeout(voiceStatus,300);}"
        + "async function voiceText(){var t=document.getElementById('voiceText').value;if(!t){return;}"
        + "try{var r=await fetch('/api/voice?action=text&text='+encodeURIComponent(t),{method:'POST'});var j=await r.json();"
        + "if(!j.ok){alert(j.error||'L\u1ed7i');}}catch(e){}document.getElementById('voiceText').value='';setTimeout(voiceStatus,800);}"
        + "async function voiceStatus(){var b=document.getElementById('voiceInfo');if(!b){return;}"
        + "try{var r=await fetch('/api/voice');var j=await r.json();"
        + "var t='K\u00eanh: '+(j.channelOpen?'OK':'ch\u01b0a m\u1edf (ch\u01b0a nh\u1eadn hello c\u1ee7a m\u00e1y ch\u1ee7)')"
        + "+' | K\u1ebft n\u1ed1i: '+(j.connected?'c\u00f3':'kh\u00f4ng')"
        + "+' | G\u1eedi PCM: '+(j.useOpus?'Opus':'PCM')"
        + "+' | Khung g\u1eedi: '+j.sentFrames+' | Nh\u1eadn: '+j.recvOk+' (l\u1ed7i '+j.recvBad+')';"
        + "if(j.listenState){t+=' | L\u1ec7nh cu\u1ed1i: '+j.listenState;}"
        + "if(j.lastStt){t+='\\nB\u1ea1n n\u00f3i: '+j.lastStt;}"
        + "if(j.lastTts){t+='\\nTr\u1ee3 l\u00fd: '+j.lastTts;}"
        + "if(j.lastKeyName){t+=' | Ph\u00edm cu\u1ed1i: '+j.lastKeyName;}"
        + "if(j.micRestarts>0){t+=' | Mic t\u1ef1 kh\u1edfi \u0111\u1ed9ng l\u1ea1i: '+j.micRestarts+' l\u1ea7n';}"
        + "if(j.verdict){t+='\\n\\nK\u1ebft lu\u1eadn: '+j.verdict;}"
        + "if(j.advice){t+='\\nN\u00ean l\u00e0m: '+j.advice;}"
        + "b.style.whiteSpace='pre-wrap';b.textContent=t;}catch(e){}}"
        + "setInterval(function(){if(document.getElementById('voiceInfo')&&document.getElementById('tab-troly').style.display!=='none'){voiceStatus();}},4000);"
        + "async function loadAi(){var r=await fetch('/api/ai/servers');var j=await r.json();"
        + "var sel=document.getElementById('aiSel');sel.innerHTML='';"
        + "(j.servers||[]).forEach(function(s){var o=document.createElement('option');o.value=s.id;o.textContent=s.name;if(s.id===j.current){o.selected=true;}sel.appendChild(o);});"
        + "document.getElementById('aiUrl').value=j.url||'';}"
        + "function onAiChange(){var id=document.getElementById('aiSel').value;var u=document.getElementById('aiUrl');"
        + "if(id==='xiaozhi'){u.value='wss://api.tenclass.net/xiaozhi/v1/';}else if(id==='xiaozhi_self'){u.value='ws://192.168.1.100:8000/xiaozhi/v1/';}else{u.value='';u.focus();}}"
        + "async function saveAi(){var id=document.getElementById('aiSel').value;var u=document.getElementById('aiUrl').value;var t=document.getElementById('aiToken').value;"
        + "var r=await fetch('/api/settings',{method:'POST',body:JSON.stringify({ai_server:id,ai_server_url:u,ai_token:t})});var j=await r.json();"
        + "document.getElementById('aiMsg').textContent=j.ok?'\\u0110\\u00e3 l\\u01b0u m\\u00e1y ch\\u1ee7 AI. K\\u1ebft n\\u1ed1i l\\u1ea1i \\u0111\\u1ec3 \\u00e1p d\\u1ee5ng.':('L\\u1ed7i: '+j.error);}"
        + "async function hotspot(on){var r=await fetch('/api/hotspot',{method:'POST',body:JSON.stringify({on:on,ssid:document.getElementById('hsSsid').value,password:document.getElementById('hsPass').value})});"
        + "var j=await r.json();var m=document.getElementById('hsMsg');"
        + "m.textContent=(j.ok?('OK: '+j.hint):('Kh\\u00f4ng b\\u1eadt \\u0111\\u01b0\\u1ee3c. '+j.hint))+(j.warning?(' | '+j.warning):'');}"
        + "async function loadHotspot(){var r=await fetch('/api/hotspot');var j=await r.json();"
        + "document.getElementById('hsSsid').value=j.ssid||'R1-Y';document.getElementById('hsPass').value=j.password||'';"
        + "document.getElementById('hsMsg').textContent=j.hint||'';}"
        + "async function pl(action){var m=document.getElementById('playMsg');"
        + "try{var r=await fetch('/api/player?action='+action,{method:'POST'});var j=await r.json();"
        + "m.textContent=j.message||j.error||'';}catch(e){m.textContent='L\u1ed7i: '+e.message;}plState();}"
        + "async function plRepeat(){try{var cur=document.getElementById('repBtn').textContent;"
        + "var on=cur.indexOf('T\u1eaeT')>=0?'1':'0';"
        + "await fetch('/api/player?action=repeat&on='+on,{method:'POST'});plState();}catch(e){}}"
        + "async function plRoute(){var v=document.getElementById('routeSel').value;"
        + "var m=document.getElementById('playMsg');"
        + "try{var r=await fetch('/api/player?action=route&value='+v,{method:'POST'});var j=await r.json();"
        + "m.textContent=j.message||'';}catch(e){m.textContent='L\u1ed7i: '+e.message;}}"
        + "async function plRouteLoad(){try{var r=await fetch('/api/settings');var j=await r.json();"
        + "var a=document.getElementById('routeSel');if(a&&j.audio_route){a.value=j.audio_route;}}catch(e){}}"
        + "async function plState(){try{var r=await fetch('/api/player');var j=await r.json();"
        + "var b=document.getElementById('repBtn');if(b){b.textContent='\ud83d\udd01 L\u1eb7p l\u1ea1i: '+(j.repeat?'B\u1eacT':'T\u1eaeT');}"
        + "var n=document.getElementById('nowBox');if(n){"
        + "var t=j.title?('\u0110ang ph\u00e1t: '+j.title):(j.playing?'\u0110ang ph\u00e1t':'');"
        + "if(j.paused){t='\u0110ANG T\u1ea0M D\u1eeaNG'+(j.title?(' \u2014 '+j.title):'');}"
        + "var v=(j.volume!=null&&j.volume>=0)?(' | \u00e2m l\u01b0\u1ee3ng '+j.volume):'';"
        + "n.textContent=t+v;}}catch(e){}}"
        + "async function sndStatus(){try{var r=await fetch('/api/audio/status');var j=await r.json();"
        + "var t='\u00c2m l\u01b0\u1ee3ng: '+(j.volume!=null?j.volume+'/'+j.volumeMax:'?');"
        + "if(j.muted){t+=' \u2014 \u0110ANG B\u1eb0NG 0 (kh\u00f4ng ra ti\u1ebfng)';}"
        + "if(j.wiredHeadset){t+=' | \u0111ang nh\u1eadn tai nghe';}"
        + "if(j.bluetoothA2dp){t+=' | \u0111ang ph\u00e1t qua Bluetooth';}"
        + "var e=document.getElementById('sndInfo');if(e)e.textContent=t;"
        + "if(document.getElementById('sndInfo')&&j.route){var si=document.getElementById('sndInfo');si.textContent='\u0110\u01b0\u1eddng ra: '+j.route+' \u2014 \u00e2m l\u01b0\u1ee3ng '+(j.routeVolume!=null?j.routeVolume+'/'+j.routeVolumeMax:'?');}"
        + "var c=document.getElementById('codecInfo');if(c){"
        + "if(j.avoidOpus){c.textContent='Codec: m\u00e1y KH\u00d4NG gi\u1ea3i m\u00e3 Opus/WebM \u2014 app b\u1eaft bu\u1ed9c ch\u1ecdn AAC/m4a ('+(j.aacSupported?'c\u00f3':'KH\u00d4NG c\u00f3')+' AAC)';}"
        + "else{c.textContent='Codec: c\u00f3 Opus (d\u00f9ng \u0111\u01b0\u1ee3c c\u1ea3 Opus l\u1eabn AAC)';}"
        + "}}catch(e){}}"
        + "async function sndBeep(){var m=document.getElementById('sndMsg');m.textContent='\u0110ang ph\u00e1t b\u00edp...';"
        + "try{var r=await fetch('/api/audio/test?kind=beep',{method:'POST'});var j=await r.json();"
        + "m.textContent=j.message||j.error;}catch(e){m.textContent='L\u1ed7i: '+e.message;}sndStatus();}"
        + "async function sndVol(){var m=document.getElementById('sndMsg');"
        + "try{var r=await fetch('/api/audio/test?kind=volume',{method:'POST'});var j=await r.json();"
        + "m.textContent=j.message||'';}catch(e){m.textContent='L\u1ed7i: '+e.message;}sndStatus();}"
        + "async function loadAudioQ(){var r=await fetch('/api/settings');var j=await r.json();"
        + "document.getElementById('audioQ').value=j.audio_quality||'high';}"
        + "var pickedSsid='';"
        + "async function scanWifi(){var m=document.getElementById('wifiMsg');m.textContent='\\u0110ang qu\\u00e9t...';"
        + "var r=await fetch('/api/wifi/scan');var j=await r.json();"
        + "var box=document.getElementById('wifiList');box.innerHTML='';"
        + "if(!j.ok){m.textContent='L\\u1ed7i: '+j.error;return;}"
        + "(j.networks||[]).forEach(function(n){var d=document.createElement('div');d.className='wifi';"
        + "d.innerHTML='<span class=\"nm\">'+n.ssid+'</span><span class=\"lk\">'+(n.secured?'\\ud83d\\udd12':'m\\u1edf')+(n.saved?' \\u2713':'')+'</span>';"
        + "d.onclick=function(){pickedSsid=n.ssid;document.querySelectorAll('.wifi').forEach(function(e){e.classList.remove('sel')});d.classList.add('sel');"
        + "document.getElementById('wifiMsg').textContent='\\u0110\\u00e3 ch\\u1ecdn: '+n.ssid;};box.appendChild(d);});"
        + "m.textContent='Th\\u1ea5y '+j.count+' WiFi. Ch\\u1ecdn WiFi nh\\u00e0 c\\u1ee7a b\\u1ea1n.';}"
        + "async function connectWifi(){if(!pickedSsid){document.getElementById('wifiMsg').textContent='Ch\\u01b0a ch\\u1ecdn WiFi';return;}"
        + "var m=document.getElementById('wifiMsg');m.textContent='\\u0110ang k\\u1ebft n\\u1ed1i '+pickedSsid+' (ch\\u1edd ~25s)...';"
        + "var r=await fetch('/api/wifi/connect',{method:'POST',body:JSON.stringify({ssid:pickedSsid,password:document.getElementById('wifiPass').value})});"
        + "var j=await r.json();m.textContent=j.ok?(j.message+' | '+j.next):('Kh\\u00f4ng k\\u1ebft n\\u1ed1i \\u0111\\u01b0\\u1ee3c: '+j.message);"
        + "if(j.ok){document.getElementById('setupHint').textContent='Loa \\u0111\\u00e3 v\\u00e0o WiFi nh\\u00e0: '+j.ip;}}"
        + "async function setupMode(){var m=document.getElementById('wifiMsg');m.textContent='\\u0110ang b\\u1eadt ch\\u1ebf \\u0111\\u1ed9 c\\u00e0i \\u0111\\u1eb7t...';"
        + "var r=await fetch('/api/setup',{method:'POST',body:'{\"on\":true}'});var j=await r.json();"
        + "m.textContent=(j.ok?'OK. ':'Kh\\u00f4ng b\\u1eadt \\u0111\\u01b0\\u1ee3c. ')+(j.hint||'')+(j.warning?(' | '+j.warning):'');}"
        + "async function loadSetup(){try{var r=await fetch('/api/setup');var j=await r.json();"
        + "var w=j.wifi||{};document.getElementById('setupHint').textContent=w.sameNetworkHint||'';"
        + "if(w.savedSsid){document.getElementById('wifiPass').placeholder='M\\u1eadt kh\\u1ea9u WiFi nh\\u00e0 ('+w.savedSsid+')';}}catch(e){}}"
        + "async function reboot(mode){"
        + "var t=mode==='device'?'Kh\\u1edfi \\u0111\\u1ed9ng l\\u1ea1i c\\u1ea3 loa (ch\\u1edd ~40-60 gi\\u00e2y, c\\u1ea7n root)':'Kh\\u1edfi \\u0111\\u1ed9ng l\\u1ea1i app R1-Y (ch\\u1edd ~10 gi\\u00e2y)';"
        + "if(!confirm(t+'?'))return;"
        + "document.getElementById('btMsg').textContent='\\u0110ang kh\\u1edfi \\u0111\\u1ed9ng l\\u1ea1i...';"
        + "try{var r=await fetch('/api/reboot',{method:'POST',body:JSON.stringify({mode:mode,confirm:true})});var j=await r.json();"
        + "document.getElementById('btMsg').textContent=j.message||j.error||'OK';"
        + "}catch(e){document.getElementById('btMsg').textContent='Loa \\u0111ang kh\\u1edfi \\u0111\\u1ed9ng l\\u1ea1i — ch\\u1edd r\\u1ed3i t\\u1ea3i l\\u1ea1i trang.';}}"
        + "loadRes();loadStatus();showMode();loadAi();loadAct();loadHotspot();loadAudioQ();loadSetup();sndStatus();plState();plRouteLoad();voiceStatus();"
        + "</script></body></html>";
}
