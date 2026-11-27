package com.bachdathan.r1y.receiver;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

import com.bachdathan.r1y.config.XiaozhiConfig;
import com.bachdathan.r1y.service.HTTPServerService;
import com.bachdathan.r1y.service.VoiceRecognitionService;
import com.bachdathan.r1y.service.XiaozhiConnectionService;
import com.bachdathan.r1y.service.LEDControlService;

/**
 * Receiver để tự động khởi động các service khi R1 boot
 */
public class BootReceiver extends BroadcastReceiver {
    
    private static final String TAG = "BootReceiver";
    
    @Override
    public void onReceive(Context context, Intent intent) {
        if (Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction()) ||
            "android.intent.action.QUICKBOOT_POWERON".equals(intent.getAction())) {
            
            Log.d(TAG, "Boot completed, starting Xiaozhi services");
            
            XiaozhiConfig config = new XiaozhiConfig(context);
            
            // Chỉ tự động start nếu user đã enable
            if (config.isAutoStart()) {
                // Start LED service first
                Intent ledIntent = new Intent(context, LEDControlService.class);
                ledIntent.setAction(LEDControlService.ACTION_SET_IDLE);
                context.startService(ledIntent);
                
                // Start Xiaozhi connection service
                Intent xiaozhiIntent = new Intent(context, XiaozhiConnectionService.class);
                context.startService(xiaozhiIntent);
                
                // Start voice recognition service
                Intent voiceIntent = new Intent(context, VoiceRecognitionService.class);
                context.startService(voiceIntent);

                // Start web server (giao diện R1-Y trên cổng 8088)
                Intent httpIntent = new Intent(context, HTTPServerService.class);
                context.startService(httpIntent);

                // Tự phát WiFi (hotspot) nếu người dùng đã bật,
                // hoặc tự vào "chế độ cài đặt" khi loa chưa có WiFi nhà nào.
                try {
                    final Context ctx = context.getApplicationContext();
                    new Thread(new Runnable() {
                        @Override
                        public void run() {
                            com.bachdathan.r1y.config.R1YConfig cfg =
                                new com.bachdathan.r1y.config.R1YConfig(ctx);
                            com.bachdathan.r1y.hardware.HotspotManager hs =
                                new com.bachdathan.r1y.hardware.HotspotManager(ctx);
                            com.bachdathan.r1y.hardware.WifiProvisioner wp =
                                new com.bachdathan.r1y.hardware.WifiProvisioner(ctx);

                            boolean noHomeWifi = cfg.getHomeWifiSsid().isEmpty() || !wp.hasInternet();
                            if (cfg.isSetupAutoWhenNoWifi() && noHomeWifi) {
                                Log.i(TAG, "Chưa có WiFi nhà -> vào chế độ cài đặt");
                                cfg.setSetupMode(true);
                                cfg.setHotspotEnabled(true);
                                hs.enableFromConfig();
                            } else {
                                hs.maybeAutoStart();
                            }
                        }
                    }, "R1Y-Hotspot").start();
                } catch (Exception e) {
                    Log.w(TAG, "Không tự bật hotspot: " + e.getMessage());
                }

                Log.d(TAG, "All services started successfully");
            } else {
                Log.d(TAG, "Auto-start disabled in config");
            }
        }
    }
}