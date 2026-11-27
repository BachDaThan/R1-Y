package com.bachdathan.r1y.util;

import android.content.Context;
import android.media.AudioManager;
import android.media.AudioTrack;
import android.media.MediaPlayer;
import android.util.Log;

import org.json.JSONObject;

import java.io.IOException;

/**
 * Chẩn đoán âm thanh — trả lời câu hỏi "vì sao loa không ra tiếng?" ngay trên web
 * mà không cần cắm cáp/logcat.
 *
 * Kiểm tra theo thứ tự từ phần mềm tới phần cứng:
 *   1. âm lượng STREAM_MUSIC (bị vặn về 0 thì mọi thứ khác đúng vẫn im)
 *   2. loa có đang bị chuyển sang chế độ tai nghe/ngắt loa ngoài không
 *   3. phát một tiếng bíp tổng hợp (nếu bíp kêu → phần cứng OK, lỗi ở mạng/URL)
 *   4. phát thử một URL media (nếu bíp kêu mà URL không kêu → link/định dạng)
 *
 * Kết quả ghi vào AudioDiag.last để trang 8088 hiển thị và để tool đọc qua API.
 */
public final class AudioDiag {

    private static final String TAG = "AudioDiag";

    public static volatile JSONObject last = null;

    /**
     * STREAM dùng để phát ra loa ngoài của R1.
     *
     * Loa Phicomm R1 có cấu hình route âm thanh bất thường: STREAM_MUSIC KHÔNG
     * ra loa ngoài (đã kiểm chứng trên phần cứng thật). Phải dùng STREAM_SYSTEM.
     * Người dùng đổi được trên web (tab Nhạc) nếu ROM của họ khác.
     */
    public static int streamType(android.content.Context ctx) {
        String route = "system";
        try {
            route = new com.bachdathan.r1y.config.R1YConfig(ctx).getAudioRoute();
        } catch (Throwable ignored) {}
        if ("music".equals(route)) return AudioManager.STREAM_MUSIC;
        if ("ring".equals(route)) return AudioManager.STREAM_RING;
        return AudioManager.STREAM_SYSTEM;
    }

    public static String streamName(android.content.Context ctx) {
        int t = streamType(ctx);
        if (t == AudioManager.STREAM_MUSIC) return "music (STREAM_MUSIC)";
        if (t == AudioManager.STREAM_RING) return "ring (STREAM_RING)";
        return "system (STREAM_SYSTEM)";
    }

    private AudioDiag() {}

    /** Đọc nhanh trạng thái âm thanh hiện tại (không phát gì). */
    public static JSONObject status(Context ctx) {
        JSONObject o = new JSONObject();
        try {
            AudioManager am = (AudioManager) ctx.getSystemService(Context.AUDIO_SERVICE);
            int vol = am.getStreamVolume(AudioManager.STREAM_MUSIC);
            int max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
            o.put("volume", vol);
            o.put("volumeMax", max);
            o.put("muted", vol == 0);
            o.put("musicActive", am.isMusicActive());
            o.put("speakerphone", am.isSpeakerphoneOn());
            o.put("mode", am.getMode());
            o.put("wiredHeadset", am.isWiredHeadsetOn());
            o.put("bluetoothA2dp", am.isBluetoothA2dpOn());
            o.put("streamVolumeOk", vol > 0);
            int st = streamType(ctx);
            o.put("route", streamName(ctx));
            o.put("routeVolume", am.getStreamVolume(st));
            o.put("routeVolumeMax", am.getStreamMaxVolume(st));
            o.put("streamMusicVolume", am.getStreamVolume(AudioManager.STREAM_MUSIC));
            // Chẩn đoán micro (nút trên loa / nói chuyện với AI)
            o.put("micEnergy", com.bachdathan.r1y.service.VoiceRecognitionService.lastMicEnergy);
            o.put("micRestarts", com.bachdathan.r1y.service.VoiceRecognitionService.micRestarts);
            o.put("streaming", com.bachdathan.r1y.service.VoiceRecognitionService.streaming);
            o.put("micGain", new com.bachdathan.r1y.config.R1YConfig(ctx).getMicGain());
            o.put("lastKey", com.bachdathan.r1y.util.VoiceTrigger.lastKeyName);
            o.put("streamSystemVolume", am.getStreamVolume(AudioManager.STREAM_SYSTEM));
            if (vol == 0) {
                o.put("advice", "Âm lượng nhạc đang bằng 0 — đây là lý do không có tiếng. "
                    + "Bấm nút 'Bật âm lượng' hoặc dùng remote loa để tăng.");
            }
            // Codec: loa có bộ giải mã Opus/WebM không (câu hỏi hay gặp: "firmware có
            // giải mã stream của máy chủ công cộng không?").
            boolean opus = com.bachdathan.r1y.youtube.AudioFallback.deviceSupportsOpus();
            o.put("opusSupported", opus);
            o.put("codecAdvice", opus
                ? "Loa có bộ giải mã Opus — dùng được cả Opus lẫn AAC."
                : "Loa KHÔNG có bộ giải mã Opus/WebM (bình thường với Android 5.1). "
                  + "App đã tự ưu tiên AAC/m4a; nếu máy chủ chỉ trả Opus thì chuyển "
                  + "'Chất lượng âm thanh' sang Tương thích (AAC/m4a).");
            if (!opus) {
                // Danh sách codec âm thanh thực có, để đối chiếu khi cần
                JSONObject codecs = new JSONObject();
                try {
                    int n = android.media.MediaCodecList.getCodecCount();
                    for (int i = 0; i < n; i++) {
                        android.media.MediaCodecInfo ci =
                            android.media.MediaCodecList.getCodecInfoAt(i);
                        if (ci == null || ci.isEncoder()) continue;
                        String[] ts = ci.getSupportedTypes();
                        if (ts == null) continue;
                        for (String t : ts) {
                            if (t != null && t.startsWith("audio/")) codecs.put(t, true);
                        }
                    }
                } catch (Throwable ignored) {}
                o.put("audioCodecs", codecs);
            }

            // Mic: có nghe thấy gì không (chẩn đoán "gọi trợ lý không phản hồi")
            double e = com.bachdathan.r1y.service.VoiceRecognitionService.lastMicEnergy;
            long at = com.bachdathan.r1y.service.VoiceRecognitionService.lastMicAt;
            o.put("micEnergy", (int) e);
            o.put("micAgeMs", at == 0 ? -1 : (System.currentTimeMillis() - at));
            o.put("micAlive", at > 0 && (System.currentTimeMillis() - at) < 3000);
            if (at == 0) {
                o.put("micAdvice", "Chưa thu được âm thanh nào từ mic — kiểm tra quyền ghi âm "
                    + "(adb shell pm grant com.bachdathan.r1y android.permission.RECORD_AUDIO).");
            } else if (e < 50) {
                o.put("micAdvice", "Mic gần như im lặng — nói to gần loa rồi bấm lại. "
                    + "Nếu vẫn ~0 thì mic bị chặn quyền hoặc hỏng.");
            }
        } catch (Throwable t) {
            try { o.put("error", String.valueOf(t.getMessage())); } catch (Exception ignored) {}
        }
        return o;
    }

    /**
     * Nâng âm lượng của ĐÚNG stream đang dùng (mặc định STREAM_SYSTEM trên R1),
     * và cả STREAM_MUSIC cho chắc — trên R1 hai mức này tách rời nhau.
     */
    public static boolean ensureVolume(Context ctx) {
        try {
            AudioManager am = (AudioManager) ctx.getSystemService(Context.AUDIO_SERVICE);
            int st = streamType(ctx);
            for (int s : new int[]{st, AudioManager.STREAM_MUSIC}) {
                int max = am.getStreamMaxVolume(s);
                int target = Math.max(7, (int) (max * 0.8));
                if (am.getStreamVolume(s) < target) {
                    am.setStreamVolume(s, target, 0);
                }
            }
            return am.getStreamVolume(st) > 0;
        } catch (Throwable t) {
            Log.w(TAG, "Không đặt được âm lượng: " + t);
            return false;
        }
    }

    /**
     * Phát tiếng bíp 1 giây sinh bằng code (không cần mạng, không cần file).
     * Kêu = loa + volume + đường ra âm thanh đều ổn → lỗi nằm ở link/phía mạng.
     */
    public static boolean beep(Context ctx, int freqHz, final int durationMs) {
        ensureVolume(ctx);
        final int sr = 44100;
        final int n = sr * durationMs / 1000;
        final short[] buf = new short[n];
        for (int i = 0; i < n; i++) {
            // biên độ giảm dần để không bị "cụp" đột ngột
            double env = 1.0 - (double) i / n;
            buf[i] = (short) (Math.sin(2 * Math.PI * freqHz * i / sr) * 12000 * env);
        }
        AudioTrack track = null;
        try {
            track = new AudioTrack(streamType(ctx), sr,
                android.media.AudioFormat.CHANNEL_OUT_MONO,
                android.media.AudioFormat.ENCODING_PCM_16BIT,
                n * 2, AudioTrack.MODE_STATIC);
            track.write(buf, 0, n);
            track.play();
            Thread.sleep(durationMs + 120);
            return true;
        } catch (Throwable t) {
            Log.e(TAG, "Bíp lỗi: " + t);
            return false;
        } finally {
            if (track != null) {
                try { track.stop(); } catch (Throwable ignored) {}
                try { track.release(); } catch (Throwable ignored) {}
            }
        }
    }

    /**
     * Phát thử 1 URL bằng MediaPlayer (giống hệt cách loa phát nhạc).
     * Trả về kết quả chi tiết để hiển thị trên web.
     */
    public static JSONObject testUrl(Context ctx, String url, int waitMs) {
        JSONObject o = new JSONObject();
        MediaPlayer mp = null;
        try {
            ensureVolume(ctx);
            o.put("url", url);
            mp = new MediaPlayer();
            mp.setAudioStreamType(streamType(ctx));
            final Throwable[] err = new Throwable[1];
            mp.setOnErrorListener(new MediaPlayer.OnErrorListener() {
                @Override
                public boolean onError(MediaPlayer m, int what, int extra) {
                    err[0] = new IOException("MediaPlayer what=" + what + " extra=" + extra
                        + explain(what, extra));
                    return true;
                }
            });
            mp.setDataSource(url);
            mp.prepare();
            o.put("durationMs", mp.getDuration());
            mp.start();
            Thread.sleep(Math.min(waitMs, Math.max(400, mp.getDuration())));
            o.put("playing", mp.isPlaying());
            try { mp.stop(); } catch (Throwable ignored) {}
            if (err[0] != null) throw err[0];
            o.put("ok", true);
        } catch (Throwable t) {
            try {
                o.put("ok", false);
                o.put("error", String.valueOf(t.getMessage()));
                o.put("hint", hint(t));
            } catch (Exception ignored) {}
        } finally {
            if (mp != null) {
                try { mp.release(); } catch (Throwable ignored) {}
            }
            try { last = o; } catch (Exception ignored) {}
        }
        return o;
    }

    private static String explain(int what, int extra) {
        // -1004 = IO_ERROR (link chết/403), -1007 = MALFORMED, -1010 = UNSUPPORTED
        if (what == -1004) return " (IO_ERROR: link chết/403/hết hạn — server chặn IP)";
        if (what == -1007) return " (MALFORMED: file/stream hỏng)";
        if (what == -1010) return " (UNSUPPORTED: loa không đọc được codec này)";
        if (what == 1 && extra == -1010) return " (UNSUPPORTED codec)";
        return "";
    }

    private static String hint(Throwable t) {
        String m = String.valueOf(t.getMessage()).toLowerCase();
        if (m.contains("unsupported") || m.contains("-1010")) {
            return "Loa không đọc được codec của luồng này (thường là Opus/WebM). "
                + "Đổi 'Chất lượng âm thanh' sang 'Tương thích (AAC/m4a)'.";
        }
        if (m.contains("io_error") || m.contains("-1004") || m.contains("403")) {
            return "Máy chủ từ chối link (403/hết hạn) — thường do YouTube chặn IP máy chủ. "
                + "Bấm 'Tự dò lại máy chủ công cộng' rồi thử lại.";
        }
        if (m.contains("ssl") || m.contains("cert") || m.contains("trust")) {
            return "Lỗi chứng chỉ HTTPS — cần bản có CompatTls (v1.7.0 trở lên).";
        }
        return "STREAM_MUSIC đang bật, nên vấn đề nằm ở link/định dạng chứ không phải phần cứng.";
    }
}
