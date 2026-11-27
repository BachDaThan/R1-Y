package com.bachdathan.r1y.util;

import android.content.Context;
import android.util.Base64;
import android.util.Log;

import com.bachdathan.r1y.R;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.security.KeyStore;
import java.security.cert.Certificate;
import java.security.cert.CertificateFactory;
import java.util.Enumeration;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;

/**
 * Android 5.1 (loa R1) có kho chứng chỉ gốc quá cũ nên HTTPS tới Piped/Invidious/xiaozhi
 * báo "Trust anchor for certification path not found".
 * Lớp này gộp kho chứng chỉ của hệ thống + bộ CA gốc đóng kèm (res/raw/extra_cas.pem)
 * rồi đặt làm mặc định cho mọi HttpsURLConnection. VẪN kiểm tra chứng chỉ đầy đủ
 * (không phải "tin tất cả").
 */
public final class CompatTls {
    private static final String TAG = "CompatTls";
    private static volatile boolean installed = false;

    private CompatTls() {}

    public static synchronized void install(Context ctx) {
        if (installed) return;
        try {
            KeyStore ks = KeyStore.getInstance(KeyStore.getDefaultType());
            ks.load(null, null);

            int sys = 0;
            try {
                KeyStore sysKs = KeyStore.getInstance("AndroidCAStore");
                sysKs.load(null, null);
                Enumeration<String> en = sysKs.aliases();
                while (en.hasMoreElements()) {
                    String a = en.nextElement();
                    Certificate c = sysKs.getCertificate(a);
                    if (c != null) { ks.setCertificateEntry("sys-" + a, c); sys++; }
                }
            } catch (Throwable t) {
                Log.w(TAG, "Không đọc được kho CA hệ thống: " + t);
            }

            int extra = 0;
            InputStream in = ctx.getResources().openRawResource(R.raw.extra_cas);
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
            in.close();
            String pem = new String(bos.toByteArray(), "UTF-8");
            Matcher m = Pattern.compile(
                "-----BEGIN CERTIFICATE-----(.*?)-----END CERTIFICATE-----", Pattern.DOTALL).matcher(pem);
            CertificateFactory cf = CertificateFactory.getInstance("X.509");
            while (m.find()) {
                try {
                    byte[] der = Base64.decode(m.group(1).replaceAll("\\s+", ""), Base64.DEFAULT);
                    Certificate c = cf.generateCertificate(new ByteArrayInputStream(der));
                    ks.setCertificateEntry("extra-" + (extra++), c);
                } catch (Throwable ignore) { /* bỏ qua chứng chỉ máy cũ không đọc được */ }
            }

            TrustManagerFactory tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
            tmf.init(ks);
            SSLContext sc = SSLContext.getInstance("TLS");
            sc.init(null, tmf.getTrustManagers(), null);
            HttpsURLConnection.setDefaultSSLSocketFactory(sc.getSocketFactory());
            installed = true;
            Log.i(TAG, "Đã cài kho CA: hệ thống=" + sys + ", đóng kèm=" + extra);
        } catch (Throwable t) {
            Log.e(TAG, "Không cài được kho CA bổ sung: " + t, t);
        }
    }
}
