package com.bachdathan.r1y.ui;

import android.app.Activity;
import android.os.Bundle;
import android.widget.TextView;

import com.bachdathan.r1y.R;

/**
 * Settings Activity - Cấu hình ứng dụng
 * TODO: Implement full settings UI
 */
public class SettingsActivity extends Activity {
    
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        
        // Tạo UI đơn giản
        TextView textView = new TextView(this);
        textView.setText("Settings\n\nTODO: Implement settings UI");
        textView.setPadding(50, 50, 50, 50);
        textView.setTextSize(16);
        
        setContentView(textView);
    }
}