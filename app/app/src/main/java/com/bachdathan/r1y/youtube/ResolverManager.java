package com.bachdathan.r1y.youtube;

import android.util.Log;

import com.bachdathan.r1y.config.R1YConfig;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Bộ lấy tiếng YouTube của R1-Y.
 *
 * Cấu hình là mảng JSON (đúng như giao diện "Bộ lấy tiếng YouTube"):
 *   [{"type":"piped|invidious|custom","url":"..."}]
 *
 * MẶC ĐỊNH LÀ "[]" -> loa tự dò máy chủ công cộng (Piped + Invidious đang sống),
 * tìm kiếm + lấy âm thanh giống hệt Tube/LiteTube. Chỉ khi bạn có máy chủ riêng
 * mới thay "[]" bằng địa chỉ máy chủ đó.
 *
 * Thứ tự thử:
 *   1) các resolver bạn đã cấu hình (đúng thứ tự trong mảng)
 *   2) nếu mảng rỗng + youtube_autodiscover: máy chủ công cộng tự dò
 *      (đã cache 6 giờ, hết hạn thì dò lại; danh sách lấy từ
 *       piped-instances.kavin.rocks và api.invidious.io)
 *   3) máy chủ nhạc cuối cùng (music_server)
 *
 * - piped:     GET {url}/streams/{id}       -> audioStreams (LiteTube: bitrate cao nhất trước)
 * - invidious: GET {url}/api/v1/videos/{id} -> adaptiveFormats (Tube: ưu tiên audio/mp4)
 * - custom:    thử Piped trước, lỗi thì thử Invidious (R1-Y server / Tube đều hỗ trợ cả hai)
 */
public class ResolverManager {

    private static final String TAG = "Resolver";

    /** Danh sách máy chủ Piped công cộng (nguồn chính thức của Piped). */
    private static final String PIPED_INSTANCES_URL = "https://piped-instances.kavin.rocks/";
    /** Danh sách máy chủ Invidious công cộng (nguồn chính thức của Invidious). */
    private static final String INVIDIOUS_INSTANCES_URL =
        "https://api.invidious.io/instances.json?sort_by=type,users";

    /** Dự phòng khi không lấy được danh sách (mạng chặn, API đổi...). */
    private static final String[] FALLBACK_PIPED = {
        "https://pipedapi.kavin.rocks",
        "https://pipedapi.adminforge.de",
        "https://pipedapi.leptons.xyz",
        "https://api.piped.private.coffee",
        "https://pipedapi.reallyaweso.me",
        "https://pipedapi.darkness.services"
    };
    private static final String[] FALLBACK_INVIDIOUS = {
        "https://inv.nadeko.net",
        "https://invidious.nerdvpn.de",
        "https://yewtu.be",
        "https://invidious.f5.si",
        "https://inv.tux.pizza",
        "https://iv.melmac.space"
    };

    /** Cache kết quả dò: 6 giờ. */
    private static final long DISCOVER_TTL_MS = 6L * 60 * 60 * 1000;
    /** Số máy chủ sống tối đa giữ lại mỗi loại. */
    private static final int MAX_ALIVE = 3;

    private final R1YConfig config;

    public ResolverManager(R1YConfig config) {
        this.config = config;
    }

    /** Một resolver trong mảng JSON. */
    public static class Resolver {
        public final String type;
        public final String url;

        public Resolver(String type, String url) {
            this.type = type == null ? "custom" : type.trim().toLowerCase();
            this.url = url == null ? "" : url.trim();
        }

        public String base() {
            return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
        }
    }

    /** Kết quả resolve: URL âm thanh + tiêu đề + tác giả. */
    public static class Resolved {
        public String audioUrl;
        public String title;
        public String author;
        public String videoId;
        /** Thang chất lượng: các URL dự phòng, tốt nhất trước (tự hạ cấp khi lỗi). */
        public List<AudioFallback.Candidate> ladder = new ArrayList<AudioFallback.Candidate>();
        /** Bậc đang dùng (1 = tốt nhất). */
        public int tier = 1;
        /** "piped:url" / "invidious:url" / "custom:url" / "autodiscover:piped:url" / "music:url" */
        public String source;
    }

    /** Đọc mảng JSON từ cấu hình. JSON sai -> danh sách rỗng (không crash). */
    public List<Resolver> parseResolvers(String json) {
        List<Resolver> out = new ArrayList<Resolver>();
        if (json == null) return out;
        json = json.trim();
        if (json.isEmpty() || "[]".equals(json)) return out;
        try {
            JSONArray arr = new JSONArray(json);
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.optJSONObject(i);
                if (o == null) continue;
                String type = o.optString("type", "custom");
                String url = o.optString("url", "");
                if (url.trim().isEmpty()) continue;
                out.add(new Resolver(type, url));
            }
        } catch (Exception e) {
            Log.w(TAG, "JSON resolver không hợp lệ: " + e.getMessage());
        }
        return out;
    }

    // ------------------------------------------------------------------ resolve

    /**
     * Lấy URL âm thanh cho videoId.
     * Danh sách cấu hình -> (rỗng thì) máy chủ công cộng tự dò -> máy chủ nhạc.
     */
    public Resolved resolve(String videoId) throws Exception {
        return resolveNext(videoId, null);
    }

    /**
     * Như resolve() nhưng bỏ qua các nguồn đã thử (dùng khi MediaPlayer lỗi giữa chừng
     * -> tự chuyển sang máy chủ kế tiếp, giống Tube/LiteTube đổi player client khi lỗi).
     *
     * @param tried mã nguồn đã thử ("piped:url", "autodiscover:piped:url"...), có thể null
     */
    public Resolved resolveNext(String videoId, Set<String> tried) throws Exception {
        if (videoId == null || !videoId.matches("[A-Za-z0-9_-]{11}")) {
            throw new IllegalArgumentException("Video ID không hợp lệ: " + videoId);
        }
        if (tried == null) tried = new LinkedHashSet<String>();
        List<Resolver> list = parseResolvers(config.getResolversJson());
        Exception lastError = null;

        // 1) Đúng thứ tự trong mảng JSON
        for (Resolver r : list) {
            if (tried.contains(r.type + ":" + r.url)) continue;
            try {
                Resolved res = resolveVia(r, videoId);
                if (ok(res)) {
                    res.source = r.type + ":" + r.url;
                    Log.i(TAG, "Resolve OK qua " + res.source);
                    return res;
                }
            } catch (Exception e) {
                lastError = e;
                Log.w(TAG, "Resolver " + r.type + " " + r.url + " lỗi: " + e.getMessage());
            }
        }

        // 2) Máy chủ công cộng (mặc định khi mảng rỗng, hoặc khi server riêng lỗi mà vẫn bật autodiscover)
        if (config.isAutodiscover()) {
            for (Resolver r : publicResolvers()) {
                if (tried.contains("autodiscover:" + r.type + ":" + r.url)) continue;
                try {
                    Resolved res = resolveVia(r, videoId);
                    if (ok(res)) {
                        res.source = "autodiscover:" + r.type + ":" + r.url;
                        Log.i(TAG, "Resolve OK qua " + res.source);
                        return res;
                    }
                } catch (Exception e) {
                    lastError = e;
                    Log.w(TAG, "Máy chủ công cộng " + r.url + " lỗi: " + e.getMessage());
                }
            }
        }

        // 3) Máy chủ nhạc cuối cùng
        String music = config.getMusicServer();
        if (music != null && !music.trim().isEmpty() && !tried.contains("music:" + music)) {
            try {
                Resolved res = resolveVia(new Resolver("custom", music.trim()), videoId);
                if (ok(res)) {
                    res.source = "music:" + music;
                    Log.i(TAG, "Resolve OK qua máy chủ nhạc " + music);
                    return res;
                }
            } catch (Exception e) {
                lastError = e;
                Log.w(TAG, "Máy chủ nhạc lỗi: " + e.getMessage());
            }
        }

        throw new Exception("Không lấy được âm thanh cho " + videoId
            + (lastError != null ? " (" + lastError.getMessage() + ")" : ""));
    }

    private static boolean ok(Resolved r) {
        return r != null && r.audioUrl != null && !r.audioUrl.isEmpty();
    }

    private Resolved resolveVia(Resolver r, String videoId) throws Exception {
        if ("piped".equals(r.type)) {
            return resolvePiped(r.base(), videoId);
        } else if ("invidious".equals(r.type)) {
            return resolveInvidious(r.base(), videoId);
        } else {
            // custom: thử Piped trước (LiteTube bridge / R1-Y server), lỗi thì thử Invidious (Tube)
            try {
                return resolvePiped(r.base(), videoId);
            } catch (Exception e) {
                return resolveInvidious(r.base(), videoId);
            }
        }
    }

    /** Piped / LiteTube bridge: GET /streams/{id} -> audioStreams (bitrate cao nhất trước). */
    private Resolved resolvePiped(String base, String videoId) throws Exception {
        JSONObject j = httpJson(base + "/streams/" + videoId, 4000, 10000);
        JSONArray audio = j.optJSONArray("audioStreams");
        if (audio == null || audio.length() == 0) {
            throw new Exception("Piped không có audioStreams");
        }
        List<AudioFallback.Candidate> ladder =
            AudioFallback.dedupe(AudioFallback.rankPiped(audio, config.getAudioQuality()));
        if (ladder.isEmpty()) throw new Exception("Piped không có luồng audio dùng được");
        // ghép URL tương đối
        for (AudioFallback.Candidate c : ladder) {
            if (c.url.startsWith("/")) c.url = base + c.url;
        }
        AudioFallback.Candidate best = ladder.get(0);
        Resolved res = new Resolved();
        res.ladder = ladder;
        res.tier = best.tier;
        res.audioUrl = best.url;
        res.title = j.optString("title", "");
        res.author = j.optString("uploader", "");
        res.videoId = videoId;
        return res;
    }

    /** Invidious / Tube: GET /api/v1/videos/{id} -> adaptiveFormats (audio). */
    private Resolved resolveInvidious(String base, String videoId) throws Exception {
        JSONObject j = httpJson(base + "/api/v1/videos/" + videoId, 4000, 10000);
        JSONArray formats = j.optJSONArray("adaptiveFormats");
        if (formats == null || formats.length() == 0) {
            throw new Exception("Invidious không có adaptiveFormats");
        }
        // Thang chất lượng: Opus cao -> Opus thấp -> AAC cao -> AAC thấp -> codec khác
        // -> cuối cùng là âm thanh lấy từ luồng video (nếu server chỉ trả video).
        List<AudioFallback.Candidate> ladder = AudioFallback.dedupe(
            AudioFallback.rankInvidious(formats, config.getAudioQuality()));
        if (ladder.isEmpty()) {
            throw new Exception("Invidious không có luồng audio/video nào dùng được");
        }
        for (AudioFallback.Candidate c : ladder) {
            if (c.url.startsWith("/")) c.url = base + c.url;
        }
        AudioFallback.Candidate best = ladder.get(0);
        Resolved res = new Resolved();
        res.ladder = ladder;
        res.tier = best.tier;
        res.audioUrl = best.url;
        res.title = j.optString("title", "");
        res.author = j.optString("author", "");
        res.videoId = videoId;
        Log.d(TAG, "Invidious chọn " + best.label + " (" + best.bitrate / 1000 + "kbps), thang " + ladder.size() + " bậc");
        return res;
    }

    // -------------------------------------------------------------- máy chủ công cộng

    /**
     * Danh sách máy chủ công cộng để thử khi để "[]".
     * Dùng cache đã dò (nếu còn hạn 6 giờ), hết hạn thì dò lại danh sách chính thức.
     */
    public List<Resolver> publicResolvers() {
        List<Resolver> out = new ArrayList<Resolver>();
        long at = config.getDiscoveredAt();
        boolean fresh = at > 0 && (System.currentTimeMillis() - at) < DISCOVER_TTL_MS;

        if (fresh) {
            addLines(out, "piped", config.getDiscoveredPiped());
            addLines(out, "invidious", config.getDiscoveredInvidious());
            if (!out.isEmpty()) return out;
        }

        List<String> piped = discoverPiped();
        List<String> invidious = discoverInvidious();

        for (String u : piped) out.add(new Resolver("piped", u));
        for (String u : invidious) out.add(new Resolver("invidious", u));

        config.saveDiscovered(joinLines(piped), joinLines(invidious));
        Log.i(TAG, "Dò được " + piped.size() + " Piped + " + invidious.size() + " Invidious");
        return out;
    }

    /** Dò máy chủ Piped đang sống (danh sách chính thức, lỗi thì dùng danh sách dự phòng). */
    public List<String> discoverPiped() {
        Set<String> cands = new LinkedHashSet<String>();
        try {
            JSONArray arr = httpJsonArray(PIPED_INSTANCES_URL, 4000, 8000);
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.optJSONObject(i);
                if (o == null) continue;
                String api = o.optString("api_url", "");
                if (api.startsWith("https://")) cands.add(strip(api));
            }
        } catch (Exception e) {
            Log.w(TAG, "Không lấy được danh sách Piped: " + e.getMessage());
        }
        if (cands.isEmpty()) {
            for (String u : FALLBACK_PIPED) cands.add(u);
        }
        return pickAlive("piped", new ArrayList<String>(cands));
    }

    /** Dò máy chủ Invidious đang sống (danh sách chính thức, lỗi thì dùng danh sách dự phòng). */
    public List<String> discoverInvidious() {
        Set<String> cands = new LinkedHashSet<String>();
        try {
            JSONArray arr = httpJsonArray(INVIDIOUS_INSTANCES_URL, 4000, 8000);
            for (int i = 0; i < arr.length(); i++) {
                JSONArray pair = arr.optJSONArray(i);
                if (pair == null || pair.length() < 2) continue;
                JSONObject info = pair.optJSONObject(1);
                if (info == null) continue;
                String type = info.optString("type", "");
                if (!"https".equals(type)) continue;
                if (info.has("api") && !info.optBoolean("api", true)) continue;
                String uri = info.optString("uri", "");
                if (uri.startsWith("https://")) cands.add(strip(uri));
            }
        } catch (Exception e) {
            Log.w(TAG, "Không lấy được danh sách Invidious: " + e.getMessage());
        }
        if (cands.isEmpty()) {
            for (String u : FALLBACK_INVIDIOUS) cands.add(u);
        }
        return pickAlive("invidious", new ArrayList<String>(cands));
    }

    /** Thử nhanh từng máy chủ, giữ lại tối đa MAX_ALIVE cái sống. */
    private List<String> pickAlive(String type, List<String> candidates) {
        List<String> alive = new ArrayList<String>();
        for (String base : candidates) {
            if (alive.size() >= MAX_ALIVE) break;
            if (probeAlive(type, base)) {
                alive.add(base);
                Log.i(TAG, type + " sống: " + base);
            } else {
                Log.d(TAG, type + " chết/bỏ qua: " + base);
            }
        }
        // Không cái nào sống -> ghép vài máy chủ đầu danh sách với danh sách dự phòng
        // để resolve() vẫn có cơ hội thành công thay vì báo lỗi ngay.
        if (alive.size() < MAX_ALIVE) {
            String[] fallback = "piped".equals(type) ? FALLBACK_PIPED : FALLBACK_INVIDIOUS;
            for (String u : fallback) {
                if (alive.size() >= MAX_ALIVE) break;
                if (!alive.contains(u)) alive.add(u);
            }
        }
        return alive;
    }

    /**
     * Kiểm tra máy chủ có phản hồi không.
     * Nhẹ: /healthcheck (Piped) hoặc /api/v1/stats (Invidious); không có thì thử gốc "/".
     */
    private boolean probeAlive(String type, String base) {
        String[] paths = "piped".equals(type)
            ? new String[]{"/healthcheck", "/"}
            : new String[]{"/api/v1/stats", "/"};
        for (String p : paths) {
            HttpURLConnection conn = null;
            try {
                conn = (HttpURLConnection) new URL(base + p).openConnection();
                conn.setConnectTimeout(3000);
                conn.setReadTimeout(4000);
                conn.setInstanceFollowRedirects(true);
                conn.setRequestProperty("User-Agent", "R1Y/1.5");
                int code = conn.getResponseCode();
                if (code >= 200 && code < 400) return true;
            } catch (Exception ignored) {
                // thử đường dẫn kế tiếp
            } finally {
                if (conn != null) conn.disconnect();
            }
        }
        return false;
    }

    private static void addLines(List<Resolver> out, String type, String lines) {
        if (lines == null || lines.trim().isEmpty()) return;
        for (String line : lines.split("\n")) {
            String u = line.trim();
            if (!u.isEmpty()) out.add(new Resolver(type, u));
        }
    }

    private static String joinLines(List<String> list) {
        StringBuilder sb = new StringBuilder();
        for (String s : list) {
            if (sb.length() > 0) sb.append("\n");
            sb.append(s);
        }
        return sb.toString();
    }

    private static String strip(String url) {
        String u = url.trim();
        while (u.endsWith("/")) u = u.substring(0, u.length() - 1);
        return u;
    }

    // ------------------------------------------------------------------ http

    /**
     * Chọn luồng audio tốt nhất từ Piped (LiteTube bridge).
     * quality=high -> bitrate cao nhất, ưu tiên Opus (webm/opus ~160kbps)
     * quality=compat -> ưu tiên m4a/AAC
     */
    private JSONObject pickPipedAudio(JSONArray audio, String quality) {
        boolean high = !"compat".equals(quality);
        JSONObject best = null;
        long bestScore = Long.MIN_VALUE;
        JSONObject firstMp4 = null, firstAny = null;
        for (int i = 0; i < audio.length(); i++) {
            JSONObject f = audio.optJSONObject(i);
            if (f == null) continue;
            String url = f.optString("url", "");
            if (url.isEmpty()) continue;
            if (firstAny == null) firstAny = f;
            String mime = f.optString("mimeType", f.optString("format", ""));
            boolean isMp4 = mime.toLowerCase().contains("mp4") || mime.toUpperCase().contains("M4A");
            boolean isOpus = mime.toLowerCase().contains("opus") || mime.toLowerCase().contains("webm");
            if (isMp4 && firstMp4 == null) firstMp4 = f;
            long br = parseBitrate(f);
            long score = br;
            if (high) {
                if (isOpus) score += 100000;   // ưu tiên Opus khi muốn chất lượng cao
            } else {
                if (isMp4) score += 100000;    // ưu tiên AAC khi cần tương thích
            }
            if (score > bestScore) {
                bestScore = score;
                best = f;
            }
        }
        if (best == null) return firstAny;
        if (!high && firstMp4 != null) return firstMp4;
        return best;
    }

    /** Đọc bitrate từ nhiều kiểu trường (số, "128kbps", "160 kbps"). */
    private static long parseBitrate(JSONObject f) {
        Object raw = f.opt("bitrate");
        if (raw instanceof Number) return ((Number) raw).longValue();
        String s = f.optString("bitrate", f.optString("quality", ""));
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("(\\d{2,4})").matcher(s);
        if (m.find()) {
            try {
                long v = Long.parseLong(m.group(1));
                // "160kbps" hoặc "160" đều hiểu là kbps
                return v < 1000 ? v * 1000 : v;
            } catch (Exception ignored) { }
        }
        String q = f.optString("quality", "");
        return q.toLowerCase().contains("high") ? 160000 : 0;
    }

    private JSONObject httpJson(String urlStr, int connectMs, int readMs) throws Exception {
        return new JSONObject(httpText(urlStr, connectMs, readMs));
    }

    private JSONArray httpJsonArray(String urlStr, int connectMs, int readMs) throws Exception {
        return new JSONArray(httpText(urlStr, connectMs, readMs));
    }

    private String httpText(String urlStr, int connectMs, int readMs) throws Exception {
        HttpURLConnection conn = (HttpURLConnection) new URL(urlStr).openConnection();
        try {
            conn.setConnectTimeout(connectMs);
            conn.setReadTimeout(readMs);
            conn.setRequestProperty("User-Agent", "R1Y/1.5");
            conn.setRequestProperty("Accept", "application/json");
            int code = conn.getResponseCode();
            if (code != 200) throw new Exception("HTTP " + code + " " + urlStr);
            BufferedReader br = new BufferedReader(new InputStreamReader(conn.getInputStream(), "UTF-8"));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = br.readLine()) != null) sb.append(line);
            br.close();
            return sb.toString();
        } finally {
            conn.disconnect();
        }
    }
}
