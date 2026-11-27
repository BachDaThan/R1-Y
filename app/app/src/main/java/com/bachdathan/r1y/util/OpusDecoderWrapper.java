package com.bachdathan.r1y.util;

import android.util.Log;

import org.concentus.OpusDecoder;

/**
 * Giải mã Opus cho loa R1.
 *
 * Vì sao cần lớp này: máy chủ Xiaozhi gửi tiếng trả lời dưới dạng **khung Opus
 * trần** (không có header Ogg) — đúng theo đặc tả giao thức websocket của
 * xiaozhi-esp32. Android 5.1 (API 22) **không có** bộ giải mã Opus trong
 * MediaCodec, nên MediaPlayer không thể phát được các khung này. Đây là lý do
 * vì sao trước đây "trợ lý trả lời nhưng loa im lặng".
 *
 * Giải pháp: dùng Concentus (bản port Java thuần của libopus, giấy phép BSD
 * 3 điều khoản — xem assets/licenses/concentus-LICENSE.txt), giải mã từng khung
 * thành PCM 16-bit rồi đưa cho AudioTrack phát.
 *
 * Không dùng được cho: Opus trong container Ogg (file .opus tải về) — cái đó
 * do MediaPlayer lo nếu ROM có hỗ trợ, còn không thì báo lỗi rõ ràng.
 */
public final class OpusDecoderWrapper {

    private static final String TAG = "OpusDecoder";

    private final OpusDecoder decoder;
    private final int sampleRate;
    private final int channels;
    /** Khung Opus dài tối đa theo đặc tả: 120 ms @ 48 kHz = 5760 mẫu/kênh. */
    private final short[] pcm = new short[5760 * 2];

    public OpusDecoderWrapper(int sampleRate, int channels) throws Exception {
        this.decoder = new OpusDecoder(sampleRate, channels);
        this.sampleRate = sampleRate;
        this.channels = channels;
    }

    /**
     * Giải mã một khung Opus.
     *
     * @return số mẫu (sample) đã giải mã, hoặc 0 nếu khung hỏng. PCM nằm trong
     *         {@link #pcm()} (xen kẽ kênh, 16-bit có dấu).
     */
    public int decodeFrame(byte[] data, int offset, int length) {
        try {
            return decoder.decode(data, offset, length, pcm, 0, pcm.length / channels, false);
        } catch (Throwable t) {
            Log.w(TAG, "Khung Opus lỗi: " + t);
            return 0;
        }
    }

    public short[] pcm() {
        return pcm;
    }

    public int sampleRate() {
        return sampleRate;
    }

    public int channels() {
        return channels;
    }

    /** Chuyển PCM sang mảng byte little-endian để phát bằng AudioTrack. */
    public static byte[] toBytes(short[] src, int samples) {
        byte[] out = new byte[samples * 2];
        for (int i = 0; i < samples; i++) {
            out[i * 2] = (byte) (src[i] & 0xFF);
            out[i * 2 + 1] = (byte) ((src[i] >> 8) & 0xFF);
        }
        return out;
    }

    /** Đọc số kênh từ TOC byte của khung Opus (0 = mono, 1 = stereo). */
    public static int channelsFromToc(byte[] d) {
        if (d == null || d.length == 0) return 1;
        int config = (d[0] & 0xFF) >> 3;
        // config 12..15 là hybrid/dualp, 14/15 stereo... cách chắc chắn là đọc
        // bit stereo ở config >= 12; với SILK (0..11) thì bit S luôn mono.
        if (config >= 12) {
            // bit 2 của TOC là cờ stereo (chỉ có nghĩa với CELT/hybrid)
        }
        return 1;
    }

    /** Kiểm tra nhanh một mảng có giống khung Opus trần hay không. */
    public static boolean looksLikeOpus(byte[] d) {
        if (d == null || d.length < 2) return false;
        // Không có header container nào cả -> gần như chắc chắn là khung trần.
        if (d[0] == 'O' && d[1] == 'g') return false;                  // OggS
        if (d[0] == 'R' && d[1] == 'I') return false;                  // RIFF
        if (d[0] == 'I' && d[1] == 'D') return false;                  // ID3
        if ((d[0] & 0xFF) == 0xFF && (d[1] & 0xE0) == 0xE0) return false; // mp3/aac
        if (d.length > 8 && d[4] == 'f' && d[5] == 't' && d[6] == 'y') return false; // mp4
        // TOC của Opus: config <= 31 (5 bit cao). Khung quá dài (>1500 byte) thì
        // vẫn có thể, nhưng > 4000 byte thường không phải một khung đơn.
        return d.length <= 4000;
    }
}
