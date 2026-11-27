package com.bachdathan.r1y.config;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * Cấu hình trung tâm của Lily R1 (R1-Y).
 *
 * Lưu trong SharedPreferences "r1y_config":
 * - youtube_resolvers: mảng JSON [{"type":"piped|invidious|custom","url":"..."}]
 *   Thử đúng thứ tự. Để "[]" thì loa tự tìm máy chủ công khai
 *   (khi youtube_autodiscover = true), cuối cùng là máy chủ nhạc.
 * - youtube_autodiscover: tự tìm máy chủ công khai khi danh sách rỗng
 * - music_server: máy chủ nhạc cuối cùng (Tube / LiteTube bridge / R1-Y server)
 * - update_repo: repo GitHub để kiểm tra cập nhật (mặc định BachDaThan/R1-Y)
 * - update_auto_check: tự kiểm tra cập nhật khi khởi động
 */
public class R1YConfig {

    public static final String GITHUB_REPO = "BachDaThan/R1-Y";
    public static final String GITHUB_API = "https://api.github.com/repos/" + GITHUB_REPO + "/releases/latest";
    public static final int WEB_PORT = 8088;

    private static final String PREFS_NAME = "r1y_config";
    private static final String KEY_RESOLVERS = "youtube_resolvers";
    private static final String KEY_AUTODISCOVER = "youtube_autodiscover";
    private static final String KEY_MUSIC_SERVER = "music_server";
    private static final String KEY_UPDATE_REPO = "update_repo";
    private static final String KEY_AUTO_CHECK = "update_auto_check";
    private static final String KEY_LAST_CHECK = "update_last_check";
    private static final String KEY_DISC_PIPED = "discovered_piped";
    private static final String KEY_DISC_INV = "discovered_invidious";
    private static final String KEY_DISC_AT = "discovered_at";

    private final SharedPreferences prefs;

    public R1YConfig(Context context) {
        this.prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }

    /** Mảng JSON resolver, mặc định "[]" (tự tìm máy chủ công khai). */
    public String getResolversJson() {
        return prefs.getString(KEY_RESOLVERS, "[]");
    }

    public void setResolversJson(String json) {
        prefs.edit().putString(KEY_RESOLVERS, json).apply();
    }

    /** Tự tìm máy chủ công khai khi danh sách rỗng. Mặc định bật. */
    public boolean isAutodiscover() {
        return prefs.getBoolean(KEY_AUTODISCOVER, true);
    }

    public void setAutodiscover(boolean enabled) {
        prefs.edit().putBoolean(KEY_AUTODISCOVER, enabled).apply();
    }

    /** Máy chủ nhạc cuối cùng (Tube/LiteTube/R1-Y server). Rỗng = bỏ qua bước này. */
    public String getMusicServer() {
        return prefs.getString(KEY_MUSIC_SERVER, "");
    }

    public void setMusicServer(String url) {
        prefs.edit().putString(KEY_MUSIC_SERVER, url).apply();
    }

    /** Repo GitHub để cập nhật. */
    public String getUpdateRepo() {
        return prefs.getString(KEY_UPDATE_REPO, GITHUB_REPO);
    }

    public void setUpdateRepo(String repo) {
        prefs.edit().putString(KEY_UPDATE_REPO, repo).apply();
    }

    public boolean isAutoCheckUpdate() {
        return prefs.getBoolean(KEY_AUTO_CHECK, true);
    }

    public void setAutoCheckUpdate(boolean enabled) {
        prefs.edit().putBoolean(KEY_AUTO_CHECK, enabled).apply();
    }

    public long getLastCheckTime() {
        return prefs.getLong(KEY_LAST_CHECK, 0);
    }

    public void setLastCheckTime(long time) {
        prefs.edit().putLong(KEY_LAST_CHECK, time).apply();
    }

    // ------------------------------------------------------- máy chủ tự dò được

    /**
     * Máy chủ công cộng đã dò được và đang sống (lưu lại để lần sau thử trước).
     * Một URL mỗi dòng. Rỗng = chưa dò lần nào.
     */
    public String getDiscoveredServers() {
        String piped = prefs.getString(KEY_DISC_PIPED, "");
        String inv = prefs.getString(KEY_DISC_INV, "");
        StringBuilder sb = new StringBuilder();
        if (!piped.isEmpty()) sb.append(piped);
        if (!inv.isEmpty()) {
            if (sb.length() > 0) sb.append("\n");
            sb.append(inv);
        }
        return sb.toString();
    }

    public String getDiscoveredPiped() {
        return prefs.getString(KEY_DISC_PIPED, "");
    }

    public String getDiscoveredInvidious() {
        return prefs.getString(KEY_DISC_INV, "");
    }

    /** Lưu danh sách "type url" (mỗi dòng một cái) sau khi dò. */
    public void saveDiscovered(String pipedLines, String invidiousLines) {
        prefs.edit()
            .putString(KEY_DISC_PIPED, pipedLines == null ? "" : pipedLines)
            .putString(KEY_DISC_INV, invidiousLines == null ? "" : invidiousLines)
            .putLong(KEY_DISC_AT, System.currentTimeMillis())
            .apply();
    }

    public long getDiscoveredAt() {
        return prefs.getLong(KEY_DISC_AT, 0);
    }

    /** Xoá kết quả dò (khi mọi máy chủ đã chết, cần dò lại). */
    public void clearDiscovered() {
        prefs.edit()
            .remove(KEY_DISC_PIPED)
            .remove(KEY_DISC_INV)
            .remove(KEY_DISC_AT)
            .apply();
    }
}
