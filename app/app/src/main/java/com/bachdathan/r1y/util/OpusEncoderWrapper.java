package com.bachdathan.r1y.util;

import android.util.Log;

import com.bachdathan.r1y.config.R1YConfig;

import org.concentus.OpusApplication;
import org.concentus.OpusEncoder;
import org.concentus.OpusSignal;

import java.io.ByteArrayOutputStream;

/**
 * Mã hoá micro thành Opus để gửi máy chủ Xiaozhi.
 *
 * Giao thức websocket của Xiaozhi **bắt buộc** khung nhị phân là Opus (máy chủ
 * khai báo "format":"opus" trong gói hello). Android 5.1 không có encoder Opus
 * trong MediaCodec, nên phải dùng Concentus (Java thuần).
 *
 * Cũng có chế độ "PCM": gửi base64 PCM 16 kHz như bản cũ, CHỈ dùng khi trỏ vào
 * máy chủ tự dựng hiểu kiểu đó. Mặc định là Opus vì đó là đường chạy thật với
 * xiaozhi.me / xiaozhi-esp32.
 */
public final class OpusEncoderWrapper {

    private static final String TAG = "OpusEncoder";

    /** Xiaozhi dùng khung 60 ms @ 16 kHz = 960 mẫu/kênh. */
    public static final int FRAME_MS = 60;
    public static final int SAMPLE_RATE = 16000;
    public static final int FRAME_SAMPLES = SAMPLE_RATE * FRAME_MS / 1000; // 960

    private OpusEncoder encoder;
    private short[] pending = new short[FRAME_SAMPLES * 4];
    private int pendingLen = 0;

    /** Opus: gửi nhị phân. Pcm: gửi base64 PCM. */
    public static boolean useOpus(android.content.Context ctx) {
        try {
            return new R1YConfig(ctx).isAiUseOpus();
        } catch (Throwable t) {
            return true;
        }
    }

    public OpusEncoderWrapper() throws Exception {
        encoder = new OpusEncoder(SAMPLE_RATE, 1, OpusApplication.OPUS_APPLICATION_VOIP);
        encoder.setBitrate(24000);
        encoder.setComplexity(3);          // R1 là CPU 4 nhân A7 yếu — ưu tiên kịp thời gian
        encoder.setUseVBR(true);
        encoder.setSignalType(OpusSignal.OPUS_SIGNAL_VOICE);
    }

    /**
     * Nạp PCM 16-bit mono 16 kHz, trả về các khung Opus đã đủ 60 ms.
     *
     * @return danh sách khung Opus (có thể rỗng nếu chưa đủ một khung)
     */
    public java.util.List<byte[]> feed(short[] pcm, int len) {
        java.util.List<byte[]> out = new java.util.ArrayList<byte[]>();
        int off = 0;
        while (off < len) {
            int n = Math.min(len - off, FRAME_SAMPLES - pendingLen);
            System.arraycopy(pcm, off, pending, pendingLen, n);
            pendingLen += n;
            off += n;
            if (pendingLen == FRAME_SAMPLES) {
                byte[] frame = encodeFrame(pending, FRAME_SAMPLES);
                if (frame != null) out.add(frame);
                pendingLen = 0;
            }
        }
        return out;
    }

    private byte[] encodeFrame(short[] pcm, int samples) {
        try {
            byte[] buf = new byte[1275];   // khung Opus tối đa
            int n = encoder.encode(pcm, 0, samples, buf, 0, buf.length);
            if (n <= 0) return null;
            byte[] out = new byte[n];
            System.arraycopy(buf, 0, out, 0, n);
            return out;
        } catch (Throwable t) {
            Log.w(TAG, "Mã hoá Opus lỗi: " + t);
            return null;
        }
    }

    /** Gom vài khung thành một khung WebSocket để giảm số lần gửi. */
    public static byte[] concat(java.util.List<byte[]> frames) {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        for (byte[] f : frames) {
            bos.write(f, 0, f.length);
        }
        return bos.toByteArray();
    }
}
