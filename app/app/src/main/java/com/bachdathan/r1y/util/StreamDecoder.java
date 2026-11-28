package com.bachdathan.r1y.util;

import android.content.Context;
import android.media.AudioFormat;
import android.media.AudioTrack;
import android.media.MediaCodec;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.util.Log;

import org.json.JSONObject;

import java.io.File;
import java.nio.ByteBuffer;

/**
 * Tự giải mã file âm thanh rồi đẩy thẳng ra loa bằng AudioTrack (MODE_STREAM).
 *
 * Vì sao cần: trên loa R1, tiếng bíp tổng hợp (AudioTrack) KÊU, nhưng
 * MediaPlayer lại im hoặc báo lỗi với file MP4/AAC tải từ máy chủ công cộng.
 * MediaPlayer của ROM phải đi qua đường phức tạp hơn (OMX node + AudioSink),
 * còn AudioTrack ghi PCM thô là đường ngắn nhất tới DAC.
 *
 * Cách làm giống các client Xiaozhi chạy được trên R1: MediaExtractor đọc khung
 * nén -> MediaCodec giải mã ra PCM -> AudioTrack.write(). Không phụ thuộc vào
 * việc ROM có "thích" cái đuôi file hay không.
 *
 * Kết quả trả về (JSON) có cả `peak` — mức âm thanh lớn nhất trong PCM đã ghi.
 * peak = 0 nghĩa là bộ giải mã chạy nhưng trả ra toàn số 0 (ROM giải mã hỏng),
 * khác hẳn với "không giải mã được" — hai lỗi này sửa theo hai cách khác nhau.
 */
public final class StreamDecoder {

    private static final String TAG = "StreamDecoder";

    /** Đang giải mã (để web biết ai đang giữ loa). */
    public static volatile boolean busy = false;
    /** Đã ghi được bao nhiêu ms (web hiện tiến trình). */
    public static volatile int positionMs = 0;
    /** Kết quả lần giải mã gần nhất (JSON) — hiện trên web. */
    public static volatile String lastResult = "";
    /** Bộ giải mã đã làm được gì (mime + tên codec) — hiện trên web. */
    public static volatile String codecName = "";

    private static volatile boolean cancel = false;
    private static volatile AudioTrack sTrack = null;
    private static volatile boolean paused = false;

    private StreamDecoder() { }

    /** Dừng hẳn (nút Ngưng / đổi bài). */
    public static void stopPlayback() {
        cancel = true;
        paused = false;
        AudioTrack t = sTrack;
        if (t != null) {
            try { t.pause(); } catch (Throwable ignored) { }
            try { t.flush(); } catch (Throwable ignored) { }
        }
    }

    /** Tạm dừng (giữ nguyên vị trí). */
    public static void pausePlayback() {
        paused = true;
        AudioTrack t = sTrack;
        if (t != null) {
            try { t.pause(); } catch (Throwable ignored) { }
        }
    }

    /** Phát tiếp sau khi tạm dừng. */
    public static void resumePlayback() {
        paused = false;
        AudioTrack t = sTrack;
        if (t != null) {
            try { t.play(); } catch (Throwable ignored) { }
        }
    }

    public static boolean canTry(File f) {
        return f != null && f.exists() && f.length() > 512;
    }

    /**
     * Giải mã + phát. Chạy ở luồng nền (hàm này chặn tới khi hết bài).
     *
     * @param maxSeconds 0 = phát hết; &gt;0 = dừng sau chừng đó giây (dùng khi thử)
     * @return JSON: ok, codec, sampleRate, channels, bytes, seconds, peak, error
     */
    public static JSONObject decodeToSpeaker(Context ctx, File file, int streamType,
                                             int maxSeconds) {
        JSONObject o = new JSONObject();
        MediaExtractor ex = null;
        MediaCodec dec = null;
        AudioTrack track = null;
        cancel = false;
        paused = false;
        busy = true;
        positionMs = 0;
        long writtenBytes = 0;
        int peak = 0;
        try {
            o.put("file", file.getName());
            o.put("size", file.length());
            ex = new MediaExtractor();
            ex.setDataSource(file.getAbsolutePath());

            int trackIndex = -1;
            MediaFormat fmt = null;
            for (int i = 0; i < ex.getTrackCount(); i++) {
                MediaFormat f = ex.getTrackFormat(i);
                String mime = f.getString(MediaFormat.KEY_MIME);
                if (mime != null && mime.startsWith("audio/")) {
                    trackIndex = i;
                    fmt = f;
                    break;
                }
            }
            if (trackIndex < 0) {
                o.put("ok", false);
                o.put("error", "File không có luồng âm thanh nào (chỉ có hình?).");
                return finish(o);
            }
            String mime = fmt.getString(MediaFormat.KEY_MIME);
            int sampleRate = fmt.containsKey(MediaFormat.KEY_SAMPLE_RATE)
                ? fmt.getInteger(MediaFormat.KEY_SAMPLE_RATE) : 44100;
            int channels = fmt.containsKey(MediaFormat.KEY_CHANNEL_COUNT)
                ? fmt.getInteger(MediaFormat.KEY_CHANNEL_COUNT) : 2;
            o.put("mime", mime);
            o.put("sampleRate", sampleRate);
            o.put("channels", channels);
            ex.selectTrack(trackIndex);

            dec = MediaCodec.createDecoderByType(mime);
            dec.configure(fmt, null, null, 0);
            dec.start();
            try { codecName = dec.getName(); } catch (Throwable ignored) { }
            o.put("codec", codecName);

            ByteBuffer[] in = dec.getInputBuffers();
            ByteBuffer[] out = dec.getOutputBuffers();

            boolean inDone = false;
            boolean outDone = false;
            int chanCfg = channels == 1
                ? AudioFormat.CHANNEL_OUT_MONO : AudioFormat.CHANNEL_OUT_STEREO;
            int bytesPerSec = sampleRate * channels * 2;

            while (!outDone) {
                if (cancel) {
                    o.put("cancelled", true);
                    break;
                }
                while (paused && !cancel) {
                    try { Thread.sleep(150); } catch (InterruptedException ignored) { }
                }
                if (!inDone) {
                    int ii = dec.dequeueInputBuffer(10000);
                    if (ii >= 0) {
                        ByteBuffer ib = in[ii];
                        int size = ex.readSampleData(ib, 0);
                        if (size < 0) {
                            dec.queueInputBuffer(ii, 0, 0, 0,
                                MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                            inDone = true;
                        } else {
                            dec.queueInputBuffer(ii, 0, size, ex.getSampleTime(), 0);
                            ex.advance();
                        }
                    }
                }

                MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
                int oi = dec.dequeueOutputBuffer(info, 10000);
                if (oi == MediaCodec.INFO_OUTPUT_BUFFERS_CHANGED) {
                    out = dec.getOutputBuffers();
                } else if (oi == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    // Một số file khai 44.1 kHz ở header nhưng giải ra 48 kHz:
                    // phải tạo lại AudioTrack theo thông số THẬT, nếu không thì
                    // ghi ra loa đúng nhưng nghe như tiếng "rè/garage".
                    MediaFormat of = dec.getOutputFormat();
                    if (of.containsKey(MediaFormat.KEY_SAMPLE_RATE)) {
                        sampleRate = of.getInteger(MediaFormat.KEY_SAMPLE_RATE);
                    }
                    if (of.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) {
                        channels = of.getInteger(MediaFormat.KEY_CHANNEL_COUNT);
                    }
                    chanCfg = channels == 1
                        ? AudioFormat.CHANNEL_OUT_MONO : AudioFormat.CHANNEL_OUT_STEREO;
                    bytesPerSec = sampleRate * channels * 2;
                    o.put("sampleRate", sampleRate);
                    o.put("channels", channels);
                    if (track != null) {
                        try { track.stop(); } catch (Throwable ignored) { }
                        try { track.release(); } catch (Throwable ignored) { }
                        track = null;
                    }
                } else if (oi >= 0) {
                    ByteBuffer ob = out[oi];
                    int size = info.size;
                    if (size > 0) {
                        byte[] pcm = new byte[size];
                        ob.position(info.offset);
                        ob.get(pcm, 0, size);
                        peak = Math.max(peak, peakOf(pcm, size));
                        if (track == null) {
                            try {
                                AudioDiag.ensureVolume(ctx);
                            } catch (Throwable ignored) { }
                            int minBuf = AudioTrack.getMinBufferSize(sampleRate, chanCfg,
                                AudioFormat.ENCODING_PCM_16BIT);
                            if (minBuf <= 0) minBuf = bytesPerSec / 2;
                            track = new AudioTrack(streamType, sampleRate, chanCfg,
                                AudioFormat.ENCODING_PCM_16BIT, minBuf * 2,
                                AudioTrack.MODE_STREAM);
                            sTrack = track;
                            track.play();
                        }
                        int off = 0;
                        while (off < size && !cancel) {
                            int n = track.write(pcm, off, size - off);
                            if (n <= 0) break;
                            off += n;
                            writtenBytes += n;
                        }
                        positionMs = (int) (writtenBytes * 1000L / Math.max(1, bytesPerSec));
                    }
                    dec.releaseOutputBuffer(oi, false);
                    if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) outDone = true;
                }
                if (maxSeconds > 0 && positionMs >= maxSeconds * 1000) {
                    o.put("limitedBy", "maxSeconds");
                    break;
                }
            }

            o.put("ok", writtenBytes > 0 && peak > 0);
            o.put("bytes", writtenBytes);
            o.put("seconds", positionMs / 1000);
            o.put("peak", peak);
            if (writtenBytes <= 0) {
                o.put("error", "Bộ giải mã không trả ra khung âm thanh nào cho định dạng này.");
            } else if (peak == 0) {
                o.put("error", "Bộ giải mã có chạy nhưng trả ra TOÀN SỐ 0 (ROM giải mã hỏng "
                    + "với định dạng này) — thử bài khác hoặc ép chất lượng AAC.");
            }
            Log.i(TAG, "Giải mã " + mime + ": " + writtenBytes + " byte, peak=" + peak);
        } catch (Throwable t) {
            Log.e(TAG, "Giải mã lỗi: " + t);
            try {
                o.put("ok", false);
                o.put("error", String.valueOf(t.getClass().getSimpleName() + ": " + t.getMessage()));
            } catch (Exception ignored) { }
        } finally {
            busy = false;
            sTrack = null;
            if (track != null) {
                try { track.stop(); } catch (Throwable ignored) { }
                try { track.release(); } catch (Throwable ignored) { }
            }
            if (dec != null) {
                try { dec.stop(); } catch (Throwable ignored) { }
                try { dec.release(); } catch (Throwable ignored) { }
            }
            if (ex != null) {
                try { ex.release(); } catch (Throwable ignored) { }
            }
        }
        return finish(o);
    }

    private static JSONObject finish(JSONObject o) {
        try { lastResult = o.toString(); } catch (Exception ignored) { }
        return o;
    }

    /** Mức lớn nhất của mẫu 16-bit little-endian (0..32767). */
    private static int peakOf(byte[] pcm, int size) {
        int peak = 0;
        for (int i = 0; i + 1 < size; i += 2) {
            int v = (short) ((pcm[i] & 0xFF) | (pcm[i + 1] << 8));
            if (v < 0) v = -v;
            if (v > peak) peak = v;
        }
        return peak;
    }
}
