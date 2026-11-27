package com.bachdathan.r1y.service;

import android.app.Service;
import android.content.Intent;
import android.os.IBinder;
import android.util.Log;

import com.bachdathan.r1y.web.WebServer;

/**
 * Web server của Lily R1 trên cổng 8088.
 * Phục vụ giao diện (như ảnh) + REST API cho Tube/LiteTube/R1-Y server.
 * Xem WebServer.java để biết danh sách endpoint.
 */
public class HTTPServerService extends Service {

    private static final String TAG = "HTTPServer";
    private WebServer server;

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (server == null) {
            try {
                server = new WebServer(this);
                server.start();
                Log.i(TAG, "WebServer Lily R1 đã chạy trên cổng 8088");
            } catch (Exception e) {
                Log.e(TAG, "Không khởi động được WebServer: " + e.getMessage(), e);
            }
        }
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        if (server != null) {
            try {
                server.stop();
            } catch (Exception ignored) {}
            server = null;
        }
        super.onDestroy();
        Log.i(TAG, "WebServer đã dừng");
    }
}
