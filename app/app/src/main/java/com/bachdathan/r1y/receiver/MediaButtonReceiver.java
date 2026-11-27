package com.bachdathan.r1y.receiver;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.view.KeyEvent;

import com.bachdathan.r1y.util.VoiceTrigger;

/**
 * Nhận phím media / nút cứng của loa NGAY CẢ KHI app không ở tiền cảnh.
 *
 * Trên R1, nút trên đỉnh loa thường phát ra KEYCODE_MEDIA_* hoặc HEADSETHOOK.
 * Manifest khai báo receiver này với ưu tiên cao để giành lấy sự kiện từ trình
 * phát nhạc mặc định (vốn không tồn tại trên loa).
 */
public class MediaButtonReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null || !Intent.ACTION_MEDIA_BUTTON.equals(intent.getAction())) {
            return;
        }
        KeyEvent event = intent.getParcelableExtra(Intent.EXTRA_KEY_EVENT);
        if (event == null) return;
        VoiceTrigger.handleKey(context, event.getKeyCode(), event);

        // Không chuyển tiếp cho app khác: ta đã xử lý.
        if (isOrderedBroadcast()) {
            abortBroadcast();
        }
    }
}
