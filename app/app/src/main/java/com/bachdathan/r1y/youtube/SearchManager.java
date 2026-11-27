package com.bachdathan.r1y.youtube;

import android.util.Log;

import com.bachdathan.r1y.config.R1YConfig;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Tìm kiếm YouTube chính xác, kế thừa từ Tube và LiteTube.
 *
 * - Tube (lily_search.py): yt-dlp ytsearch, nhận diện link YouTube
 *   (watch?v=, youtu.be/, shorts/, embed/, live/), resolver giả kiểu Invidious
 *   (/api/v1/search?q=&type=video, /api/v1/videos/{id}).
 * - LiteTube (yt-bridge): Piped API (/search?q=&filter=videos, /streams/{id}),
 *   luồng âm thanh do yt-dlp bóc tách, xếp bitrate cao nhất.
 *
 * MẶC ĐỊNH mảng resolver là "[]" -> tự dùng máy chủ công cộng (giống Tube/LiteTube
 * khi chưa cấu hình server riêng). Chỉ khi bạn có máy chủ riêng mới thay "[]".
 *
 * Dán link YouTube -> nhận diện ngay, không cần tìm.
 */
public class SearchManager {

    private static final String TAG = "Search";

    // Giống Tube lookup(): youtube.com/watch?v=, youtu.be/, shorts/, embed/, live/
    private static final Pattern YT_ID = Pattern.compile(
        "(?:youtube\\.com/(?:watch\\?(?:[^#]*&)?v=|shorts/|embed/|live/|v/)|youtu\\.be/|music\\.youtube\\.com/watch\\?(?:[^#]*&)?v=)([A-Za-z0-9_-]{11})");
    private static final Pattern YT_PLAYLIST = Pattern.compile("[?&]list=([A-Za-z0-9_-]+)");
    private static final Pattern YT_ID_ONLY = Pattern.compile("^[A-Za-z0-9_-]{11}$");

    private final R1YConfig config;
    private final ResolverManager resolverManager;

    public SearchManager(R1YConfig config) {
        this.config = config;
        this.resolverManager = new ResolverManager(config);
    }

    /** Một kết quả tìm kiếm. */
    public static class Result {
        public String videoId;
        public String title;
        public String author;
        public long lengthSeconds;

        @Override
        public String toString() {
            return title + " — " + author + " (" + videoId + ")";
        }
    }

    public ResolverManager getResolverManager() {
        return resolverManager;
    }

    /** Nhận diện link YouTube -> videoId (null nếu không phải link). */
    public static String parseVideoId(String input) {
        if (input == null) return null;
        String s = input.trim();
        if (YT_ID_ONLY.matcher(s).matches()) return s;  // người dùng dán thẳng ID
        Matcher m = YT_ID.matcher(s);
        return m.find() ? m.group(1) : null;
    }

    public static boolean isPlaylist(String input) {
        return input != null && YT_PLAYLIST.matcher(input).find();
    }

    /**
     * Tìm theo từ khóa. Dán link video -> trả về đúng 1 kết quả.
     * Thử máy chủ riêng (nếu có) -> máy chủ công cộng (mặc định) -> máy chủ nhạc.
     */
    public List<Result> search(String query, int limit) throws Exception {
        if (query == null || query.trim().isEmpty()) {
            throw new IllegalArgumentException("Từ khóa rỗng");
        }
        String q = query.trim();

        // Dán link video -> không cần tìm (giống Tube lookup)
        String vid = parseVideoId(q);
        if (vid != null) {
            List<Result> one = new ArrayList<Result>();
            Result r = new Result();
            r.videoId = vid;
            r.title = vid;
            r.author = "";
            one.add(r);
            return one;
        }

        int want = limit <= 0 ? 20 : limit;
        List<ResolverManager.Resolver> list = resolverManager.parseResolvers(config.getResolversJson());
        Exception lastError = null;

        // 1) Đúng thứ tự trong mảng JSON
        for (ResolverManager.Resolver r : list) {
            try {
                List<Result> res = searchVia(r, q, want);
                if (res != null && !res.isEmpty()) {
                    Log.i(TAG, "Tìm OK qua " + r.type + ":" + r.url + " (" + res.size() + " kết quả)");
                    return res;
                }
            } catch (Exception e) {
                lastError = e;
                Log.w(TAG, "Tìm qua " + r.type + " " + r.url + " lỗi: " + e.getMessage());
            }
        }

        // 2) Máy chủ công cộng (mặc định khi "[]")
        if (config.isAutodiscover()) {
            for (ResolverManager.Resolver r : resolverManager.publicResolvers()) {
                try {
                    List<Result> res = searchVia(r, q, want);
                    if (res != null && !res.isEmpty()) {
                        Log.i(TAG, "Tìm OK qua công cộng " + r.type + ":" + r.url
                            + " (" + res.size() + " kết quả)");
                        return res;
                    }
                } catch (Exception e) {
                    lastError = e;
                    Log.w(TAG, "Tìm qua công cộng " + r.url + " lỗi: " + e.getMessage());
                }
            }
        }

        // 3) Máy chủ nhạc cuối cùng
        String music = config.getMusicServer();
        if (music != null && !music.trim().isEmpty()) {
            try {
                List<Result> res = searchVia(new ResolverManager.Resolver("custom", music.trim()), q, want);
                if (res != null && !res.isEmpty()) return res;
            } catch (Exception e) {
                lastError = e;
            }
        }

        throw new Exception("Không tìm được '" + q + "'"
            + (lastError != null ? " (" + lastError.getMessage() + ")" : ""));
    }

    private List<Result> searchVia(ResolverManager.Resolver r, String q, int limit) throws Exception {
        if ("piped".equals(r.type)) {
            return searchPiped(r.base(), q, limit);
        } else if ("invidious".equals(r.type)) {
            return searchInvidious(r.base(), q, limit);
        } else {
            try {
                return searchPiped(r.base(), q, limit);
            } catch (Exception e) {
                return searchInvidious(r.base(), q, limit);
            }
        }
    }

    /** Piped / LiteTube: GET /search?q=&filter=videos -> items[]. */
    private List<Result> searchPiped(String base, String q, int limit) throws Exception {
        String url = base + "/search?q=" + URLEncoder.encode(q, "UTF-8") + "&filter=videos";
        JSONObject j = new JSONObject(httpText(url, 4000, 12000));
        JSONArray items = j.optJSONArray("items");
        List<Result> out = new ArrayList<Result>();
        Set<String> seen = new LinkedHashSet<String>();
        if (items == null) return out;
        for (int i = 0; i < items.length() && out.size() < limit; i++) {
            JSONObject it = items.optJSONObject(i);
            if (it == null) continue;
            String u = it.optString("url", "");
            String id = u.startsWith("/watch?v=") ? u.substring(9) : u;
            if (!id.matches("[A-Za-z0-9_-]{11}")) continue;
            if (!seen.add(id)) continue;
            Result r = new Result();
            r.videoId = id;
            r.title = it.optString("title", id);
            r.author = it.optString("uploaderName", "");
            r.lengthSeconds = it.optLong("duration", 0);
            out.add(r);
        }
        return out;
    }

    /** Invidious / Tube: GET /api/v1/search?q=&type=video -> [{videoId,...}]. */
    private List<Result> searchInvidious(String base, String q, int limit) throws Exception {
        String url = base + "/api/v1/search?q=" + URLEncoder.encode(q, "UTF-8") + "&type=video";
        JSONArray arr = new JSONArray(httpText(url, 4000, 12000));
        List<Result> out = new ArrayList<Result>();
        Set<String> seen = new LinkedHashSet<String>();
        for (int i = 0; i < arr.length() && out.size() < limit; i++) {
            JSONObject it = arr.optJSONObject(i);
            if (it == null) continue;
            // Invidious trả lẫn kênh/playlist -> chỉ lấy video
            String type = it.optString("type", "video");
            if (!"video".equals(type)) continue;
            String id = it.optString("videoId", "");
            if (!id.matches("[A-Za-z0-9_-]{11}")) continue;
            if (!seen.add(id)) continue;
            Result r = new Result();
            r.videoId = id;
            r.title = it.optString("title", id);
            r.author = it.optString("author", "");
            r.lengthSeconds = it.optLong("lengthSeconds", 0);
            out.add(r);
        }
        return out;
    }

    private String httpText(String urlStr, int connectMs, int readMs) throws Exception {
        HttpURLConnection conn = (HttpURLConnection) new URL(urlStr).openConnection();
        try {
            conn.setConnectTimeout(connectMs);
            conn.setReadTimeout(readMs);
            conn.setRequestProperty("User-Agent", "R1Y/1.4");
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
