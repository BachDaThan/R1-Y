package com.bachdathan.r1y.util;

import android.util.Log;

import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Kiểm tra một link âm thanh TRƯỚC KHI đưa cho MediaPlayer, và báo cáo bằng
 * ngôn ngữ người dùng đọc được (hiện trên trang 8088).
 *
 * Vì sao cần: trước đây khi nhạc "báo đang phát mà im", không có cách nào biết
 * link YouTube trả về cái gì. Có 4 khả năng rất khác nhau và cách sửa cũng khác:
 *
 *   1. 403 / 401  -> link hết hạn hoặc bị khoá theo IP/User-Agent
 *                    (link do máy chủ công cộng sinh ra thường khoá IP của nó)
 *   2. content-type không phải audio -> máy chủ trả HTML (trang lỗi / bắt đăng nhập)
 *   3. Codec máy không giải mã được (Opus/WebM trên Android 5.1)
 *   4. Link tốt -> lỗi nằm ở đường ra âm thanh (xem AudioDiag)
 */
public final class UrlProbe {

    private static final String TAG = "UrlProbe";

    /** UA giả lập trình phát — nhiều CDN từ chối UA lạ hoặc UA rỗng. */
    public static final String USER_AGENT =
        "Mozilla/5.0 (Linux; Android 5.1; Phicomm R1) AppleWebKit/537.36 "
        + "(KHTML, like Gecko) Chrome/80.0 Mobile Safari/537.36";

    private UrlProbe() {}

    /** Header cần gắn khi mở link YouTube bằng MediaPlayer. */
    public static Map<String, String> headersFor(String url) {
        Map<String, String> h = new LinkedHashMap<String, String>();
        h.put("User-Agent", USER_AGENT);
        String u = url == null ? "" : url.toLowerCase(Locale.US);
        if (u.contains("googlevideo.com") || u.contains("youtube.com")
                || u.contains("ytimg.com") || u.contains("googleusercontent")) {
            h.put("Referer", "https://www.youtube.com/");
            h.put("Origin", "https://www.youtube.com");
        }
        return h;
    }

    /**
     * Mở thử link, đọc tối đa 16 byte đầu.
     *
     * @return JSON: ok, status, contentType, length, magic, verdict, advice
     */
    public static org.json.JSONObject probe(String url) {
        org.json.JSONObject o = new org.json.JSONObject();
        HttpURLConnection c = null;
        try {
            o.put("url", abbreviate(url));
            if (url == null || url.trim().isEmpty()) {
                o.put("ok", false);
                o.put("verdict", "Không có link");
                return o;
            }

            c = (HttpURLConnection) new URL(url).openConnection();
            c.setConnectTimeout(8000);
            c.setReadTimeout(8000);
            c.setInstanceFollowRedirects(true);
            c.setRequestProperty("User-Agent", USER_AGENT);
            for (Map.Entry<String, String> e : headersFor(url).entrySet()) {
                c.setRequestProperty(e.getKey(), e.getValue());
            }
            // Range 0-15: chỉ cần 16 byte, không tải cả bài.
            c.setRequestProperty("Range", "bytes=0-15");

            int code = c.getResponseCode();
            String ct = c.getContentType();
            long len = c.getContentLength();

            byte[] head = new byte[16];
            int n = 0;
            try {
                InputStream in = code >= 400 ? c.getErrorStream() : c.getInputStream();
                if (in != null) {
                    n = in.read(head);
                    in.close();
                }
            } catch (Throwable ignored) {}

            o.put("ok", code == 200 || code == 206);
            o.put("status", code);
            o.put("contentType", ct == null ? "" : ct);
            o.put("length", len);
            o.put("magic", describeMagic(head, n));

            String verdict;
            String advice = "";
            if (code == 403 || code == 401) {
                verdict = "Máy chủ từ chối (" + code + ") — link hết hạn hoặc khoá IP/User-Agent";
                advice = "Thử bài khác, hoặc đổi máy chủ YouTube (bấm 'Tải lại' rồi Lưu).";
            } else if (code >= 400) {
                verdict = "Lỗi HTTP " + code;
                advice = "Link hỏng hoặc máy chủ công cộng đang chặn. Đổi máy chủ.";
            } else if (ct != null && ct.startsWith("text/")) {
                verdict = "Nhận về TRANG WEB chứ không phải âm thanh";
                advice = "Máy chủ công cộng trả trang lỗi. Đổi máy chủ hoặc thử lại.";
            } else {
                String m = describeMagic(head, n);
                if ("webm/matroska".equals(m) || "ogg".equals(m)) {
                    verdict = "WebM/Ogg — R1 (Android 5.1) có thể không giải mã được";
                    advice = "Chọn chất lượng 'Tương thích' (AAC/m4a) ở tab Nhạc.";
                } else if ("mp4/m4a".equals(m)) {
                    verdict = "OK — định dạng MP4/M4A, R1 giải mã được";
                } else if ("mp3".equals(m)) {
                    verdict = "OK — MP3, R1 giải mã được";
                } else if ("mpeg-ts".equals(m)) {
                    verdict = "MPEG-TS — R1 thường KHÔNG phát được";
                    advice = "Chọn chất lượng 'Tương thích'.";
                } else if (m.startsWith("khác (00 00") || m.startsWith("khác (0 0")) {
                    verdict = "File âm thanh dạng MP4/AAC (bắt đầu bằng 00 00 00 xx) — "
                            + "R1 giải mã được";
                } else {
                    verdict = "Không nhận ra định dạng (magic: " + m + ")";
                    advice = "Gửi dòng này cho mình để xem.";
                }
            }
            o.put("verdict", verdict);
            if (!advice.isEmpty()) o.put("advice", advice);
            Log.i(TAG, "Probe " + code + " " + ct + " -> " + verdict);
        } catch (Throwable t) {
            try {
                o.put("ok", false);
                o.put("verdict", "Không mở được link: " + t.getClass().getSimpleName());
                o.put("error", String.valueOf(t.getMessage()));
            } catch (Exception ignored) {}
        } finally {
            if (c != null) c.disconnect();
        }
        return o;
    }

    /**
     * Tải link về một file tạm và trả đường dẫn file đó (null nếu thất bại).
     *
     * Vì sao phải tải thay vì đưa URL thẳng cho MediaPlayer:
     *  - API 22 không có setDataSource(String, Map) nên không gắn được
     *    User-Agent/Referer — nhiều CDN YouTube trả 403 khi UA rỗng;
     *  - khi CDN trả trang HTML (lỗi/quảng cáo chặn), MediaPlayer vẫn báo
     *    "đang phát" rồi im lặng — tải về rồi kiểm tra thì biết ngay;
     *  - tải trọn bài còn cho phép tiến trình phát/lặp lại ổn định trên R1.
     */
    public static String download(android.content.Context ctx, String url) {
        HttpURLConnection c = null;
        java.io.File out = null;
        try {
            com.bachdathan.r1y.config.R1YConfig cfg =
                new com.bachdathan.r1y.config.R1YConfig(ctx);
            if (!cfg.isCacheAudio()) {
                return url;   // người dùng tắt "tải trước" -> đưa URL trực tiếp
            }

            c = (HttpURLConnection) new URL(url).openConnection();
            c.setConnectTimeout(10000);
            c.setReadTimeout(30000);
            c.setInstanceFollowRedirects(true);
            for (Map.Entry<String, String> e : headersFor(url).entrySet()) {
                c.setRequestProperty(e.getKey(), e.getValue());
            }
            int code = c.getResponseCode();
            if (code != 200 && code != 206) {
                Log.w(TAG, "Tải link: HTTP " + code);
                return null;
            }
            String ct = c.getContentType();
            if (ct != null && ct.startsWith("text/")) {
                Log.w(TAG, "Tải link: nhận về trang web (" + ct + ")");
                return null;
            }

            byte[] magic = new byte[16];
            out = java.io.File.createTempFile("r1y_", guessExt(url, ct), ctx.getCacheDir());

            InputStream in = c.getInputStream();
            java.io.FileOutputStream fos = new java.io.FileOutputStream(out);
            byte[] buf = new byte[16 * 1024];
            int n, total = 0, checked = 0;
            // Mạng WiFi 2.4G của loa hay đứt giữa bài. Nếu đã tải được phần đầu
            // ĐÚNG định dạng thì vẫn giữ file (phát được đoạn đã có) thay vì
            // xoá sạch rồi báo "Tải link thất bại" — đúng như ảnh người dùng gửi.
            try {
                while ((n = in.read(buf)) > 0) {
                    if (checked < 16) {
                        int take = Math.min(16 - checked, n);
                        System.arraycopy(buf, 0, magic, checked, take);
                        checked += take;
                    }
                    fos.write(buf, 0, n);
                    total += n;
                    if (total > 60 * 1024 * 1024) {   // chặn file rác quá lớn
                        Log.w(TAG, "File quá lớn, dừng tải");
                        break;
                    }
                }
            } catch (Throwable t) {
                Log.w(TAG, "Tải dở rồi đứt ở " + total + " byte: " + t);
            } finally {
                try { fos.close(); } catch (Throwable ignored) {}
                try { in.close(); } catch (Throwable ignored) {}
            }

            String fmt = describeMagic(magic, 16);
            boolean usable = total > 256 * 1024
                && !fmt.startsWith("khác (") && !"?".equals(fmt);
            if (total <= 0 || (fmt.startsWith("khác (") && total < 256 * 1024)) {
                out.delete();
                Log.w(TAG, "Tải link: chỉ được " + total + " byte (" + fmt + ")");
                return null;
            }
            Log.i(TAG, "Đã tải " + total + " byte, magic=" + fmt
                + (usable ? "" : " (file ngắn, vẫn thử phát)"));
            return out.getAbsolutePath();
        } catch (Throwable t) {
            Log.w(TAG, "Tải link lỗi: " + t);
            if (out != null) out.delete();
            return null;
        } finally {
            if (c != null) c.disconnect();
        }
    }

    private static String guessExt(String url, String ct) {
        String u = url == null ? "" : url.toLowerCase(Locale.US);
        if (u.contains("mime=audio%2fmp4") || u.contains("mime=audio/mp4")) return ".m4a";
        if (u.contains("mime=audio%2fwebm") || u.contains("mime=audio/webm")) return ".webm";
        if (u.contains(".m4a")) return ".m4a";
        if (u.contains(".mp3")) return ".mp3";
        if (u.contains(".webm")) return ".webm";
        if (u.contains(".ogg") || u.contains(".opus")) return ".ogg";
        if (ct != null) {
            if (ct.contains("mp4")) return ".m4a";
            if (ct.contains("webm")) return ".webm";
            if (ct.contains("mpeg")) return ".mp3";
            if (ct.contains("ogg")) return ".ogg";
        }
        return ".bin";
    }

    /** Nhận dạng định dạng từ vài byte đầu (giống cách trình phát thật làm). */
    public static String describeMagic(byte[] d, int n) {
        if (d == null || n < 2) return "?";
        int b0 = d[0] & 0xFF, b1 = d[1] & 0xFF;
        // MP4/M4A: 4 byte đầu là ĐỘ DÀI hộp, nên 'ftyp' nằm ở offset 4..7 —
        // không phải ở offset 0. (Trước v1.9.2 chỗ này đọc sai offset nên mọi
        // file M4A/AAC đều bị báo "khác (00 00)".)
        if (n >= 8 && d[4] == 'f' && d[5] == 't' && d[6] == 'y' && d[7] == 'p') {
            return "mp4/m4a";
        }
        if (n >= 4 && b0 == 'O' && b1 == 'g' && d[2] == 'g' && d[3] == 'S') return "ogg";
        if (n >= 4 && d[0] == 0x1A && d[1] == 0x45 && d[2] == (byte) 0xDF && d[3] == (byte) 0xA3) {
            return "webm/matroska";
        }
        if (n >= 3 && b0 == 'I' && b1 == 'D' && d[2] == '3') return "mp3";
        if (n >= 12 && b0 == 'R' && b1 == 'I' && d[2] == 'F' && d[3] == 'F') return "wav";
        if (b0 == 0xFF && (b1 & 0xF0) == 0xF0) return "aac(adts)";
        if (b0 == 0xFF && (b1 & 0xE0) == 0xE0) return "mp3";
        if (b0 == 0x47 && n > 1) return "mpeg-ts";
        return "khác (" + String.format("%02X %02X", b0, b1) + ")";
    }

    private static String abbreviate(String url) {
        if (url == null) return "";
        if (url.length() <= 90) return url;
        return url.substring(0, 60) + "…" + url.substring(url.length() - 25);
    }
}
