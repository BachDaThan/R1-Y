package com.bachdathan.r1y.youtube;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Thang chất lượng âm thanh có tự hạ cấp.
 *
 * Vấn đề: máy chủ công cộng (Piped/Invidious) nhiều khi chỉ trả một số codec,
 * hoặc không có Opus, hoặc MediaPlayer của loa không đọc được WebM/Opus.
 *
 * Cách giải: xếp hạng mọi luồng audio tìm được thành "thang" ưu tiên,
 * thử từ tốt nhất xuống thấp nhất; MediaPlayer lỗi thì tự hạ sang bậc kế tiếp.
 * Khi hết bậc audio riêng, vẫn lấy được audio trong luồng video thường
 * (video/mp4 có acodec AAC — cách cuối cùng để "vẫn phát được").
 *
 * Thang:
 *   1. Opus/WebM  ≥ 128kbps   (tốt nhất)
 *   2. Opus/WebM  < 128kbps
 *   3. AAC/M4A    ≥ 128kbps
 *   4. AAC/M4A    < 128kbps
 *   5. Audio codec khác (vorbis, mp3...)
 *   6. Audio lấy từ luồng video (mp4/webm có tiếng)
 *
 * Nếu người dùng chọn "compat" (ưu tiên AAC) thì đảo 1-2 với 3-4.
 */
public class AudioFallback {

    /** Một bậc trong thang chất lượng. */
    public static class Candidate {
        public String url;
        public String type;      // "opus" | "aac" | "other" | "video"
        public int bitrate;      // bps
        public String mime;      // audio/webm, audio/mp4, video/mp4...
        public int tier;         // 1..6, nhỏ hơn = tốt hơn
        public String label;

        @Override
        public String toString() {
            return label + " (" + bitrate / 1000 + "kbps) " + mime;
        }
    }

    /**
     * Xếp hạng các luồng I(nvidious) adaptiveFormats theo thang chất lượng.
     * @param formats mảng adaptiveFormats từ /api/v1/videos/{id}
     * @param quality "high" (ưu tiên Opus) hoặc "compat" (ưu tiên AAC)
     */
    public static List<Candidate> rankInvidious(JSONArray formats, String quality) {
        List<Candidate> list = new ArrayList<Candidate>();
        if (formats == null) return list;
        boolean high = !"compat".equals(quality);
        for (int i = 0; i < formats.length(); i++) {
            JSONObject f = formats.optJSONObject(i);
            if (f == null) continue;
            Candidate c = fromFormat(f, high);
            if (c != null && c.url != null && !c.url.isEmpty()) list.add(c);
        }
        sort(list);
        return list;
    }

    /** Xếp hạng các luồng Piped audioStreams. */
    public static List<Candidate> rankPiped(JSONArray audio, String quality) {
        List<Candidate> list = new ArrayList<Candidate>();
        if (audio == null) return list;
        boolean high = !"compat".equals(quality);
        for (int i = 0; i < audio.length(); i++) {
            JSONObject f = audio.optJSONObject(i);
            if (f == null) continue;
            String url = f.optString("url", "");
            if (url.isEmpty()) continue;
            String mime = f.optString("mimeType", f.optString("format", "")).toLowerCase();
            Candidate c = new Candidate();
            c.url = url;
            c.mime = mime;
            c.bitrate = bitrateOf(f);
            c.type = detectType(mime, c.bitrate);
            c.tier = tierOf(c.type, c.bitrate, high);
            c.label = labelOf(c);
            list.add(c);
        }
        sort(list);
        return list;
    }

    private static Candidate fromFormat(JSONObject f, boolean high) {
        String url = f.optString("url", "");
        if (url.isEmpty()) return null;
        String type = f.optString("type", "");      // "audio/webm; codecs=\"opus\""
        String lower = type.toLowerCase();

        Candidate c = new Candidate();
        c.url = url;
        c.mime = type;

        boolean isAudio = lower.startsWith("audio/");
        boolean isVideo = lower.startsWith("video/");
        if (!isAudio && !isVideo) return null;
        // Luồng video phải có tiếng mới dùng được (acodec != none)
        if (isVideo) {
            String acodec = f.optString("acodec", "");
            if (acodec.isEmpty() || "none".equals(acodec)) return null;
        }

        c.bitrate = bitrateOf(f);
        if (isVideo) {
            c.type = "video";
        } else {
            c.type = detectType(lower, c.bitrate);
        }
        c.tier = tierOf(c.type, c.bitrate, high);
        c.label = labelOf(c);
        return c;
    }

    private static String detectType(String mime, int bitrate) {
        String m = mime.toLowerCase();
        if (m.contains("opus")) return "opus";
        if (m.contains("mp4") || m.contains("aac") || m.contains("m4a")) return "aac";
        if (m.contains("webm")) return "opus";   // webm audio thường là opus/vorbis
        if (m.contains("mpeg") || m.contains("mp3")) return "other";
        if (m.startsWith("video/")) return "video";
        return "other";
    }

    /** Bậc 1 (tốt nhất) .. 6 (thấp nhất). */
    private static int tierOf(String type, int bitrate, boolean high) {
        boolean hq = bitrate >= 128000;
        if ("opus".equals(type)) return hq ? 1 : 2;
        if ("aac".equals(type)) return hq ? 3 : 4;
        if ("other".equals(type)) return 5;
        if ("video".equals(type)) return 6;
        return 6;
    }

    private static String labelOf(Candidate c) {
        String codec;
        if ("opus".equals(c.type)) codec = "Opus";
        else if ("aac".equals(c.type)) codec = "AAC/m4a";
        else if ("video".equals(c.type)) codec = "Âm thanh từ video";
        else codec = "Codec khác";
        String q = c.bitrate >= 128000 ? "cao" : "thấp";
        return codec + " " + q;
    }

    /** Sắp theo bậc rồi tới bitrate (cao trước). */
    private static void sort(List<Candidate> list) {
        Collections.sort(list, new Comparator<Candidate>() {
            @Override
            public int compare(Candidate a, Candidate b) {
                if (a.tier != b.tier) return a.tier - b.tier;
                return b.bitrate - a.bitrate;
            }
        });
    }

    private static int bitrateOf(JSONObject f) {
        Object raw = f.opt("bitrate");
        if (raw instanceof Number) {
            long v = ((Number) raw).longValue();
            return (int) (v < 1000 ? v * 1000 : v);
        }
        String s = f.optString("bitrate", f.optString("quality", ""));
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("(\\d{2,4})").matcher(s);
        if (m.find()) {
            int v = Integer.parseInt(m.group(1));
            return v < 1000 ? v * 1000 : v;
        }
        return 128000;
    }

    /**
     * Tạo danh sách URL để thử lần lượt (đã khử trùng lặp, giữ thứ tự thang).
     * Dùng cho AudioPlaybackService khi cần tự hạ chất lượng.
     */
    public static List<Candidate> dedupe(List<Candidate> list) {
        List<Candidate> out = new ArrayList<Candidate>();
        Set<String> seen = new LinkedHashSet<String>();
        for (Candidate c : list) {
            if (c == null || c.url == null || c.url.isEmpty()) continue;
            if (seen.add(c.url)) out.add(c);
        }
        return out;
    }

    /** Mô tả thang cho API/UI: ["1. Opus cao (160kbps)", ...] */
    public static List<String> describe(List<Candidate> list) {
        List<String> out = new ArrayList<String>();
        int i = 1;
        for (Candidate c : list) {
            out.add(i++ + ". " + c.label + " (" + c.bitrate / 1000 + "kbps)");
        }
        return out;
    }
}
