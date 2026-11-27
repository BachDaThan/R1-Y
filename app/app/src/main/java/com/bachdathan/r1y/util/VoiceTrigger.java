package com.bachdathan.r1y.util;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.KeyEvent;

import com.bachdathan.r1y.core.XiaozhiCore;
import com.bachdathan.r1y.service.VoiceRecognitionService;
import com.bachdathan.r1y.service.XiaozhiConnectionService;

/**
 * Nút trên loa → nói chuyện với trợ lý AI.
 *
 * Trước đây nút trên loa KHÔNG được nối vào đâu cả (app chỉ nghe wake word bằng
 * ngưỡng âm lượng), nên bấm nút không có phản ứng gì.
 *
 * Luồng đúng theo giao thức Xiaozhi khi người dùng bấm nút:
 *   1. nếu trợ lý đang nói  -> gửi abort (ngắt lời);
 *   2. gửi {"type":"listen","state":"start","mode":"manual"};
 *   3. mở cổng đẩy micro lên máy chủ (Opus 16 kHz, khung 60 ms);
 *   4. bấm lần nữa (hoặc hết thời gian) -> {"type":"listen","state":"stop"}.
 *
 * Có ba đường vào:
 *   - phím bấm khi app đang mở  (MainActivity.onKeyDown)
 *   - phím media từ hệ thống    (MediaButtonReceiver trong manifest)
 *   - nút trên web 8088         (/api/assistant?action=talk)
 */
public final class VoiceTrigger {

    private static final String TAG = "VoiceTrigger";

    /** Một lượt nói tối đa 15 giây rồi tự dừng (tránh kẹt micro). */
    private static final long MAX_UTTERANCE_MS = 15000;

    /** Mã phím gần nhất nhận được — hiện trên web để biết nút loa gửi mã gì. */
    public static volatile int lastKeyCode = 0;
    public static volatile String lastKeyName = "";
    public static volatile long lastKeyAt = 0;
    /** Đang trong một lượt nói (bấm nút/web). */
    public static volatile boolean talking = false;

    private static final Handler handler = new Handler(Looper.getMainLooper());
    private static final Runnable stopRunnable = new Runnable() {
        @Override
        public void run() {
            Log.i(TAG, "Hết " + MAX_UTTERANCE_MS + "ms — tự dừng lượt nói");
            stopTalking();
        }
    };

    private VoiceTrigger() {}

    /**
     * Xử lý một sự kiện phím.
     *
     * @return true nếu đã xử lý (để Activity không làm gì thêm)
     */
    public static boolean handleKey(Context ctx, int keyCode, KeyEvent event) {
        if (event != null && event.getAction() != KeyEvent.ACTION_DOWN) return false;
        if (event != null && event.getRepeatCount() > 0) return false;

        lastKeyCode = keyCode;
        lastKeyName = keyName(keyCode);
        lastKeyAt = System.currentTimeMillis();
        Log.i(TAG, "Nhận phím: " + keyCode + " (" + lastKeyName + ")");

        // Bỏ qua phím âm lượng / nguồn / home — để hệ thống xử lý như bình thường.
        if (keyCode == KeyEvent.KEYCODE_VOLUME_UP
                || keyCode == KeyEvent.KEYCODE_VOLUME_DOWN
                || keyCode == KeyEvent.KEYCODE_POWER
                || keyCode == KeyEvent.KEYCODE_HOME
                || keyCode == KeyEvent.KEYCODE_BACK
                || keyCode == KeyEvent.KEYCODE_MENU) {
            return false;
        }

        toggleTalking(ctx);
        return true;
    }

    /** Bấm nút: đang nói thì dừng, chưa nói thì bắt đầu. */
    public static synchronized void toggleTalking(Context ctx) {
        if (talking) {
            stopTalking();
        } else {
            startTalking(ctx);
        }
    }

    public static synchronized void startTalking(Context ctx) {
        XiaozhiCore core = XiaozhiCore.getInstance();
        XiaozhiConnectionService conn = core.getConnectionService();
        VoiceRecognitionService voice = core.getVoiceService();

        if (conn == null) {
            Log.w(TAG, "Chưa có dịch vụ kết nối AI — không nói chuyện được");
            AudioDiag.last = new org.json.JSONObject();
            return;
        }
        if (!conn.isConnected()) {
            Log.w(TAG, "Chưa kết nối máy chủ AI — thử kết nối lại");
            conn.connect();
            return;
        }

        // 1. Đang nói mà bấm -> ngắt lời trợ lý
        conn.sendAbort();
        // 2. Mở phiên nghe
        conn.sendStartListening(null);
        // 3. Đẩy micro lên
        if (voice != null) {
            voice.beginUtterance();
        } else {
            Log.w(TAG, "VoiceRecognitionService chưa chạy — không có micro");
            return;
        }

        talking = true;
        handler.removeCallbacks(stopRunnable);
        handler.postDelayed(stopRunnable, MAX_UTTERANCE_MS);
        AudioDiag.beep(ctx, 880, 120);      // bíp ngắn: "đang nghe đây"
    }

    public static synchronized void stopTalking() {
        handler.removeCallbacks(stopRunnable);
        if (!talking) return;
        talking = false;

        XiaozhiCore core = XiaozhiCore.getInstance();
        XiaozhiConnectionService conn = core.getConnectionService();
        VoiceRecognitionService voice = core.getVoiceService();

        if (voice != null) voice.endUtterance();
        if (conn != null) conn.sendStopListening();
    }

    /** Tên phím cho dễ đọc trên trang 8088. */
    public static String keyName(int keyCode) {
        switch (keyCode) {
            case KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE: return "MEDIA_PLAY_PAUSE";
            case KeyEvent.KEYCODE_MEDIA_PLAY: return "MEDIA_PLAY";
            case KeyEvent.KEYCODE_MEDIA_PAUSE: return "MEDIA_PAUSE";
            case KeyEvent.KEYCODE_MEDIA_NEXT: return "MEDIA_NEXT";
            case KeyEvent.KEYCODE_MEDIA_PREVIOUS: return "MEDIA_PREVIOUS";
            case KeyEvent.KEYCODE_HEADSETHOOK: return "HEADSETHOOK";
            case KeyEvent.KEYCODE_CALL: return "CALL";
            case KeyEvent.KEYCODE_SEARCH: return "SEARCH";
            case KeyEvent.KEYCODE_VOICE_ASSIST: return "VOICE_ASSIST";
            case KeyEvent.KEYCODE_HOME: return "HOME";
            case KeyEvent.KEYCODE_BACK: return "BACK";
            case KeyEvent.KEYCODE_MENU: return "MENU";
            case KeyEvent.KEYCODE_ENTER: return "ENTER";
            case KeyEvent.KEYCODE_DPAD_CENTER: return "DPAD_CENTER";
            case KeyEvent.KEYCODE_VOLUME_UP: return "VOLUME_UP";
            case KeyEvent.KEYCODE_VOLUME_DOWN: return "VOLUME_DOWN";
            case KeyEvent.KEYCODE_POWER: return "POWER";
            default: return "KEYCODE_" + keyCode;
        }
    }
}
