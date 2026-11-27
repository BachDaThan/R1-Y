package com.bachdathan.r1y.update;

import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Environment;
import android.util.Log;

import com.bachdathan.r1y.config.R1YConfig;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * Cập nhật firmware từ GitHub (repo BachDaThan/R1-Y).
 *
 * - Kiểm tra: GET https://api.github.com/repos/BachDaThan/R1-Y/releases/latest
 * - So sánh tag (v1.5.0) với version hiện tại của app.
 * - Tải APK (asset *.apk) về /sdcard/Download/R1Y-update.apk
 * - Cài: ưu tiên "pm install" qua root (nếu có), ngược lại mở trình cài đặt.
 *
 * Nút "Cập nhật" trong giao diện web (cổng 8088) gọi qua WebServer.
 */
public class UpdateManager {

    private static final String TAG = "Update";

    private final Context context;
    private final R1YConfig config;

    public UpdateManager(Context context) {
        this.context = context;
        this.config = new R1YConfig(context);
    }

    /** Thông tin bản cập nhật. */
    public static class UpdateInfo {
        public boolean hasUpdate;
        public String latestTag;
        public String currentVersion;
        public String apkUrl;
        public String apkName;
        public String releaseNotes;
        public String publishedAt;
    }

    public String getCurrentVersion() {
        try {
            PackageInfo pi = context.getPackageManager()
                .getPackageInfo(context.getPackageName(), 0);
            return pi.versionName;
        } catch (PackageManager.NameNotFoundException e) {
            return "0.0.0";
        }
    }

    /** So sánh phiên bản dạng x.y.z. Trả về >0 nếu a mới hơn b. */
    public static int compareVersion(String a, String b) {
        String[] pa = a.replace("v", "").split("\\.");
        String[] pb = b.replace("v", "").split("\\.");
        int n = Math.max(pa.length, pb.length);
        for (int i = 0; i < n; i++) {
            int va = i < pa.length ? parseInt(pa[i]) : 0;
            int vb = i < pb.length ? parseInt(pb[i]) : 0;
            if (va != vb) return va - vb;
        }
        return 0;
    }

    private static int parseInt(String s) {
        try {
            return Integer.parseInt(s.replaceAll("[^0-9]", ""));
        } catch (Exception e) {
            return 0;
        }
    }

    /** Kiểm tra bản mới nhất trên GitHub. */
    public UpdateInfo checkUpdate() throws Exception {
        String repo = config.getUpdateRepo();
        String api = "https://api.github.com/repos/" + repo + "/releases/latest";
        Log.i(TAG, "Kiểm tra cập nhật: " + api);

        HttpURLConnection conn = (HttpURLConnection) new URL(api).openConnection();
        conn.setConnectTimeout(15000);
        conn.setReadTimeout(15000);
        conn.setRequestProperty("User-Agent", "R1Y/1.5");
        conn.setRequestProperty("Accept", "application/vnd.github+json");
        int code = conn.getResponseCode();
        if (code != 200) {
            throw new Exception("GitHub API HTTP " + code);
        }
        BufferedReader br = new BufferedReader(new InputStreamReader(conn.getInputStream(), "UTF-8"));
        StringBuilder sb = new StringBuilder();
        String line;
        while ((line = br.readLine()) != null) sb.append(line);
        br.close();
        conn.disconnect();

        JSONObject j = new JSONObject(sb.toString());
        UpdateInfo info = new UpdateInfo();
        info.latestTag = j.optString("tag_name", "");
        info.currentVersion = getCurrentVersion();
        info.releaseNotes = j.optString("body", "");
        info.publishedAt = j.optString("published_at", "");

        JSONArray assets = j.optJSONArray("assets");
        if (assets != null) {
            for (int i = 0; i < assets.length(); i++) {
                JSONObject a = assets.optJSONObject(i);
                if (a == null) continue;
                String name = a.optString("name", "");
                if (name.endsWith(".apk")) {
                    info.apkUrl = a.optString("browser_download_url", "");
                    info.apkName = name;
                    break;
                }
            }
        }

        info.hasUpdate = compareVersion(info.latestTag, info.currentVersion) > 0
            && info.apkUrl != null && !info.apkUrl.isEmpty();

        config.setLastCheckTime(System.currentTimeMillis());
        Log.i(TAG, "Hiện tại " + info.currentVersion + " | mới nhất "
            + info.latestTag + " | có update: " + info.hasUpdate);
        return info;
    }

    /** Tải APK về /sdcard/Download/R1Y-update.apk. Trả về File. */
    public File downloadApk(String apkUrl) throws Exception {
        File dir = new File(Environment.getExternalStorageDirectory(), "Download");
        if (!dir.exists()) dir.mkdirs();
        File apk = new File(dir, "R1Y-update.apk");

        Log.i(TAG, "Tải APK: " + apkUrl);
        HttpURLConnection conn = (HttpURLConnection) new URL(apkUrl).openConnection();
        conn.setConnectTimeout(30000);
        conn.setReadTimeout(60000);
        conn.setRequestProperty("User-Agent", "R1Y/1.5");
        int code = conn.getResponseCode();
        if (code != 200) throw new Exception("Tải APK HTTP " + code);

        InputStream in = conn.getInputStream();
        FileOutputStream out = new FileOutputStream(apk);
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) != -1) out.write(buf, 0, n);
        out.close();
        in.close();
        conn.disconnect();

        if (!apk.exists() || apk.length() < 100000) {
            throw new Exception("File APK tải về không hợp lệ");
        }
        Log.i(TAG, "Tải xong APK: " + apk.length() + " bytes");
        return apk;
    }

    /**
     * Cài APK. Thử root "pm install -r" trước (R1 đã root thì không cần bấm tay),
     * lỗi thì mở trình cài đặt hệ thống (API 22 dùng file://).
     */
    public void installApk(File apk) throws Exception {
        try {
            Process p = Runtime.getRuntime().exec(new String[]{"su", "-c", "pm install -r " + apk.getAbsolutePath()});
            int code = p.waitFor();
            if (code == 0) {
                Log.i(TAG, "Cài qua root thành công");
                return;
            }
            Log.w(TAG, "pm install qua root trả về " + code + ", chuyển sang trình cài đặt");
        } catch (Exception e) {
            Log.w(TAG, "Không có root, dùng trình cài đặt: " + e.getMessage());
        }

        Intent intent = new Intent(Intent.ACTION_VIEW);
        intent.setDataAndType(Uri.fromFile(apk), "application/vnd.android.package-archive");
        intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        context.startActivity(intent);
    }

    /** Kiểm tra + tải + cài trong một bước (gọi từ nút Cập nhật). */
    public UpdateInfo updateNow() throws Exception {
        UpdateInfo info = checkUpdate();
        if (!info.hasUpdate) return info;
        File apk = downloadApk(info.apkUrl);
        installApk(apk);
        return info;
    }
}
