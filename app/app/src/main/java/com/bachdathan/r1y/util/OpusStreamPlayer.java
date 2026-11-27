package com.bachdathan.r1y.util;

import android.content.Context;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioTrack;
import android.util.Log;

/**
 * Phát luồng Opus nhận từ máy chủ Xiaozhi.
 *
 * Máy chủ gửi tiếng trả lời từng khung Opus 60 ms liên tiếp. Ta:
 *   1. giải mã từng khung bằng {@link OpusDecoderWrapper} (Concentus, Java thuần);
 *   2. ghi PCM vào một AudioTrack MODE_STREAM duy nhất.
 *
 * Vì sao phải giữ MỘT AudioTrack cho cả câu: nếu tạo/giải phóng AudioTrack (và
 * AudioRecord) quá nhanh, tầng audio HAL của R1 (Android 5.1) có thể **deadlock
 * vĩnh viễn** — sau đó loa câm cho tới khi rút điện. Đây là lỗi đã được ghi
 * nhận trên phần cứng thật, nên ta gom khung và phát liên tục.
 *
 * Nếu quá {@link #IDLE_FLUSH_MS} không có khung mới, coi như hết câu và đóng
 * AudioTrack — để lần nói sau có thể mở lại sạch sẽ.
 */
public final class OpusStreamPlayer {

    private static final String TAG = "OpusStreamPlayer";

    /** Hết câu nếu không có khung mới trong khoảng này. */
    private static final long IDLE_FLUSH_MS = 1500;

    private AudioTrack track;
    private OpusDecoderWrapper decoder;
    private int sampleRate = 24000;
    private int channels = 1;
    private long lastFrameAt;
    private boolean speaking;

    /** Số khung đã giải mã / số khung lỗi — hiển thị trên trang 8088. */
    public volatile int framesOk = 0;
    public volatile int framesBad = 0;
    public volatile long lastFrameBytes = 0;

    public synchronized boolean isSpeaking() {
        return speaking && (System.currentTimeMillis() - lastFrameAt) < IDLE_FLUSH_MS;
    }

    /**
     * Nạp một khung Opus. Tự mở AudioTrack ở khung đầu tiên.
     *
     * @param rate sample rate máy chủ khai báo trong gói hello (thường 24000)
     */
    public synchronized void feed(Context ctx, byte[] data, int rate, int chans) {
        if (data == null || data.length == 0) return;
        lastFrameBytes = data.length;

        if (track == null || (rate > 0 && rate != sampleRate) || chans != channels) {
            close();
            sampleRate = rate > 0 ? rate : 24000;
            channels = chans > 0 ? chans : 1;
            if (!open(ctx)) return;
        }

        int samples = decoder.decodeFrame(data, 0, data.length);
        if (samples <= 0) {
            framesBad++;
            // Khung hỏng giữa câu: chèn im lặng để không bị méo tiếng.
            write(new byte[Math.max(2, lastFrameBytes * 2)], 0);
            return;
        }
        framesOk++;
        lastFrameAt = System.currentTimeMillis();
        speaking = true;

        byte[] pcm = OpusDecoderWrapper.toBytes(decoder.pcm(), samples * channels);
        write(pcm, samples * channels * 2);
    }

    /** Gọi định kỳ (ví dụ mỗi 500 ms) để đóng AudioTrack khi đã hết câu. */
    public synchronized void tick() {
        if (track != null && speaking
                && (System.currentTimeMillis() - lastFrameAt) > IDLE_FLUSH_MS) {
            Log.i(TAG, "Hết câu — đã giải mã " + framesOk + " khung, lỗi " + framesBad);
            close();
        }
    }

    private boolean open(Context ctx) {
        try {
            AudioDiag.ensureVolume(ctx);
            int minBuf = AudioTrack.getMinBufferSize(sampleRate,
                    channels == 2 ? AudioFormat.CHANNEL_OUT_STEREO : AudioFormat.CHANNEL_OUT_MONO,
                    AudioFormat.ENCODING_PCM_16BIT);
            int bufSize = Math.max(minBuf * 4, sampleRate);   // ~0.5s ở 24kHz
            track = new AudioTrack(
                    AudioDiag.streamType(ctx),   // R1: STREAM_SYSTEM, không phải STREAM_MUSIC
                    sampleRate,
                    channels == 2 ? AudioFormat.CHANNEL_OUT_STEREO : AudioFormat.CHANNEL_OUT_MONO,
                    AudioFormat.ENCODING_PCM_16BIT,
                    bufSize,
                    AudioTrack.MODE_STREAM);
            if (track.getState() != AudioTrack.STATE_INITIALIZED) {
                Log.e(TAG, "AudioTrack không khởi tạo được (state=" + track.getState() + ")");
                track = null;
                return false;
            }
            decoder = new OpusDecoderWrapper(sampleRate, channels);
            track.play();
            framesOk = 0;
            framesBad = 0;
            Log.i(TAG, "Mở AudioTrack " + sampleRate + "Hz, " + channels + " kênh");
            return true;
        } catch (Throwable t) {
            Log.e(TAG, "Không mở được Opus: " + t);
            track = null;
            decoder = null;
            return false;
        }
    }

    private void write(byte[] pcm, int len) {
        if (track == null) return;
        try {
            track.write(pcm, 0, len > 0 ? len : pcm.length);
        } catch (Throwable t) {
            Log.w(TAG, "Ghi AudioTrack lỗi: " + t);
        }
    }

    public synchronized void close() {
        speaking = false;
        if (track != null) {
            try {
                track.stop();
            } catch (Throwable ignored) {}
            try {
                track.release();
            } catch (Throwable ignored) {}
            track = null;
        }
        decoder = null;
    }

    public synchronized String status() {
        return sampleRate + "Hz/" + channels + "ch, khung OK " + framesOk + ", lỗi " + framesBad;
    }
}
