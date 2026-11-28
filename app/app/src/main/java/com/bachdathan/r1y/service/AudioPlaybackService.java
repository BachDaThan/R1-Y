package com.bachdathan.r1y.service;

import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.os.Binder;
import android.os.IBinder;
import android.os.PowerManager;
import android.util.Log;

import com.bachdathan.r1y.config.R1YConfig;
import com.bachdathan.r1y.util.StreamDecoder;
import com.bachdathan.r1y.youtube.AudioFallback;
import com.bachdathan.r1y.youtube.ResolverManager;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Service phát audio từ Xiaozhi (TTS responses)
 * Hỗ trợ phát từ URL hoặc raw audio data
 */
public class AudioPlaybackService extends Service implements 
    MediaPlayer.OnPreparedListener,
    MediaPlayer.OnCompletionListener,
    MediaPlayer.OnErrorListener {
    
    private static final String TAG = "AudioPlayback";
    
    public static final String ACTION_PLAY_URL = "com.bachdathan.r1y.PLAY_URL";
    public static final String ACTION_PLAY_DATA = "com.bachdathan.r1y.PLAY_DATA";
    public static final String ACTION_STOP = "com.bachdathan.r1y.STOP";
    public static final String ACTION_PAUSE = "com.bachdathan.r1y.PAUSE";
    public static final String ACTION_RESUME = "com.bachdathan.r1y.RESUME";

    /** Phát nhạc YouTube bằng tìm kiếm/link (loa tự tìm, tự lấy âm thanh, tự chuyển máy chủ). */
    public static final String ACTION_PLAY_QUERY = "com.bachdathan.r1y.PLAY_QUERY";
    public static final String EXTRA_QUERY = "query";
    public static final String EXTRA_VIDEO_ID = "video_id";
    public static final String EXTRA_SOURCE = "source_hint";
    
    private MediaPlayer mediaPlayer;
    private AudioManager audioManager;
    private boolean isPrepared = false;

    /** Ngữ cảnh phát nhạc YouTube: giữ videoId + nguồn đã thử để tự chuyển máy chủ khi lỗi. */
    private String currentVideoId;
    private String currentQuery;
    private String currentSource;
    private final Set<String> triedSources = new LinkedHashSet<String>();
    private boolean retrying = false;

    // ===== Chẩn đoán tiến trình phát (hiện trên trang 8088) =====
    /** Lần cuối chuyển sang trạng thái "đã chuẩn bị xong" (0 = chưa). */
    public static volatile long lastPreparedAt = 0;
    /** Số byte AudioTrack/MediaPlayer báo đã phát ở lần kiểm tra gần nhất. */
    public static volatile int lastPositionMs = 0;
    /** Lỗi gần nhất của MediaPlayer (what/extra) — rỗng = không có. */
    public static volatile String lastError = "";
    /** Đang chờ prepareAsync (ms) — nếu quá lâu là link treo. */
    private volatile long prepareStartedAt = 0;
    /** Kết quả kiểm tra link gần nhất (UrlProbe). */
    public static volatile String lastProbe = "";

    /** Đang phát bằng bộ tự giải mã (thay cho MediaPlayer) hay không. */
    public static volatile boolean usingDecoder = false;
    /** Bộ giải mã đã thử và thất bại cho bài này → đừng thử lại (tránh vòng lặp). */
    private volatile boolean decodeFailed = false;

    /** Lặp lại bài đang phát (như YouTube "lặp 1 bài"). */
    public static volatile boolean repeatOne = false;
    /** Đang tạm dừng (người dùng bấm Tạm dừng). */
    public static volatile boolean paused = false;
    /** URL + tiêu đề bản đang phát — để phát lại/lặp. */
    private static volatile String nowPlayingUrl = null;
    public static volatile String nowPlayingTitle = "";
    public static volatile String nowPlayingId = "";

    public static boolean isPlayingNow() {
        return nowPlayingUrl != null;
    }

    public static String getNowPlayingTitle() {
        return nowPlayingTitle;
    }

    public static String getNowPlayingUrl() {
        return nowPlayingUrl;
    }

    /** Vị trí phát hiện tại (ms). -1 nếu chưa có bộ phát. */
    public int getPositionMs() {
        if (usingDecoder) return StreamDecoder.positionMs;
        try {
            if (mediaPlayer != null && isPrepared) return mediaPlayer.getCurrentPosition();
        } catch (Throwable ignored) {}
        return -1;
    }

    /** Thang chất lượng hiện tại (Opus -> AAC -> video -> ...) để tự hạ bậc khi lỗi. */
    private final List<AudioFallback.Candidate> ladder = new ArrayList<AudioFallback.Candidate>();
    private int ladderIndex = 0;
    
    private PlaybackCallback callback;
    
    public interface PlaybackCallback {
        void onPlaybackStarted();
        void onPlaybackCompleted();
        void onPlaybackError(String error);
    }
    
    private final IBinder binder = new LocalBinder();
    
    public class LocalBinder extends Binder {
        public AudioPlaybackService getService() {
            return AudioPlaybackService.this;
        }
    }
    
    @Override
    public void onCreate() {
        super.onCreate();
        audioManager = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
        initMediaPlayer();
        Log.d(TAG, "AudioPlaybackService created");
    }
    
    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && intent.getAction() != null) {
            String action = intent.getAction();
            
            switch (action) {
                case ACTION_PLAY_URL:
                    String audioUrl = intent.getStringExtra("audio_url");
                    if (audioUrl != null) {
                        // Nguồn do WebServer chọn -> cho phép tự chuyển nếu lỗi
                        currentVideoId = intent.getStringExtra(EXTRA_VIDEO_ID);
                        currentSource = intent.getStringExtra(EXTRA_SOURCE);
                        currentQuery = null;
                        triedSources.clear();
                        if (currentSource != null) triedSources.add(currentSource);
                        retrying = false;
                        ladder.clear();
                        ladderIndex = 0;
                        playFromUrl(audioUrl);
                    }
                    break;

                case ACTION_PLAY_QUERY:
                    currentQuery = intent.getStringExtra(EXTRA_QUERY);
                    currentVideoId = intent.getStringExtra(EXTRA_VIDEO_ID);
                    currentSource = null;
                    triedSources.clear();
                    retrying = false;
                    resolveAndPlayAsync(currentQuery, currentVideoId);
                    break;
                    
                case ACTION_PLAY_DATA:
                    byte[] audioData = intent.getByteArrayExtra("audio_data");
                    if (audioData != null) {
                        playFromData(audioData);
                    }
                    break;
                    
                case ACTION_STOP:
                    stop();
                    break;
                    
                case ACTION_PAUSE:
                    pause();
                    break;
                    
                case ACTION_RESUME:
                    resume();
                    break;
            }
        }
        
        return START_NOT_STICKY;
    }
    
    @Override
    public IBinder onBind(Intent intent) {
        return binder;
    }
    
    public void setCallback(PlaybackCallback callback) {
        this.callback = callback;
    }
    
    /**
     * Khởi tạo MediaPlayer
     */
    private void initMediaPlayer() {
        if (mediaPlayer != null) {
            releaseMediaPlayer();
        }
        
        mediaPlayer = new MediaPlayer();
        mediaPlayer.setWakeMode(getApplicationContext(), PowerManager.PARTIAL_WAKE_LOCK);
        // Loa R1: STREAM_MUSIC không ra loa ngoài -> dùng STREAM_SYSTEM (mặc định),
        // đổi được trên web (tab Nhạc) nếu ROM khác.
        mediaPlayer.setAudioStreamType(com.bachdathan.r1y.util.AudioDiag.streamType(this));
        mediaPlayer.setOnPreparedListener(this);
        mediaPlayer.setOnCompletionListener(this);
        mediaPlayer.setOnErrorListener(this);
        
        isPrepared = false;
    }
    
    /**
     * Phát audio từ URL
     */
    public void playFromUrl(String url) {
        Log.d(TAG, "Playing from URL: " + url);

        // Loa có thể bị vặn âm lượng về 0 (bấm nút trên loa / remote / app gốc).
        // Khi đó mọi thứ khác đúng vẫn KHÔNG ra tiếng -> tự nâng lên mức nghe được.
        try {
            com.bachdathan.r1y.util.AudioDiag.ensureVolume(this);
        } catch (Throwable ignored) {
        }

        nowPlayingUrl = url;
        paused = false;
        decodeFailed = false;

        try {
            // Request audio focus
            int result = audioManager.requestAudioFocus(
                audioFocusChangeListener,
                com.bachdathan.r1y.util.AudioDiag.streamType(this),
                AudioManager.AUDIOFOCUS_GAIN
            );
            
            if (result != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
                Log.w(TAG, "Audio focus not granted");
            }
            
            // Notify LED service
            Intent ledIntent = new Intent(this, LEDControlService.class);
            ledIntent.setAction(LEDControlService.ACTION_SET_SPEAKING);
            startService(ledIntent);
            
            // Stop current playback if any
            if (mediaPlayer.isPlaying()) {
                mediaPlayer.stop();
            }
            
            initMediaPlayer();
            // Gắn User-Agent/Referer: nhiều CDN YouTube trả 403 khi UA rỗng —
            // đây là một trong các nguyên nhân "báo đang phát mà im".
            // API 22 KHÔNG có setDataSource(String, Map<String,String>) (chỉ từ API 23),
            // nên tự tải về file tạm rồi phát từ file. Việc này còn chặn được
            // trường hợp CDN trả 403/HTML mà MediaPlayer báo "đang phát mà im".
            String playable = url;
            if (url.startsWith("http://") || url.startsWith("https://")) {
                playable = com.bachdathan.r1y.util.UrlProbe.download(this, url);
                if (playable == null) {
                    // Không tải được KHÔNG có nghĩa là không phát được: đưa URL
                    // thẳng cho MediaPlayer (nó tự xin từng đoạn). Trước v1.9.3
                    // chỗ này dừng luôn với "Tải link thất bại".
                    Log.w(TAG, "Không tải được link, thử phát trực tiếp: " + url);
                    lastError = "Không tải được cả bài — đang thử phát trực tiếp";
                    playable = url;
                }
            }
            playLocal(playable);

        } catch (Exception e) {
            // playLocal() đã tự bắt lỗi MediaPlayer; đây chỉ còn lỗi hàng đợi âm
            // thanh/Intent. Bắt rộng vì javac không cho catch IOException khi
            // trong try không còn lời gọi nào ném IOException.
            Log.e(TAG, "Error playing from URL", e);
            if (callback != null) {
                callback.onPlaybackError(e.getMessage());
            }
            onPlaybackCompleted();
        }
    }
    
    /**
     * Chọn đường phát cho một file đã có trên máy: tự giải mã trước, MediaPlayer sau.
     *
     * Vì sao mặc định tự giải mã: trên loa R1 tiếng bíp (AudioTrack) KÊU, nhưng
     * MediaPlayer im hoặc báo "Tải link thất bại" với file MP4/AAC — MediaPlayer
     * của ROM cũ phải đi qua OMX/AudioSink, còn AudioTrack ghi PCM thô là đường
     * ngắn nhất tới DAC. Nếu tự giải mã không ra tiếng thì vẫn quay lại
     * MediaPlayer (không mất tính năng nào).
     */
    private void playLocal(String playable) {
        File f = new File(playable);
        if (f.exists() && !decodeFailed && StreamDecoder.canTry(f) && decodeEnabled()) {
            if (startDecoder(f)) return;
        }
        try {
            mediaPlayer.setDataSource(playable);
            prepareStartedAt = System.currentTimeMillis();
            mediaPlayer.prepareAsync();
        } catch (Throwable t) {
            Log.e(TAG, "MediaPlayer setDataSource lỗi: " + t);
            lastError = "Không mở được file: " + t.getMessage();
            if (callback != null) callback.onPlaybackError(lastError);
            onPlaybackCompleted();
        }
    }

    /** Người dùng có bật "tự giải mã" trên web không (mặc định BẬT). */
    private boolean decodeEnabled() {
        try {
            return new R1YConfig(this).isDecodeAudio();
        } catch (Throwable ignored) {
            return true;
        }
    }

    /** Chạy bộ tự giải mã ở luồng nền. true = đã nhận việc (đừng dùng MediaPlayer nữa). */
    private boolean startDecoder(final File f) {
        usingDecoder = true;
        decodeFailed = false;
        new Thread(new Runnable() {
            @Override
            public void run() {
                org.json.JSONObject r = StreamDecoder.decodeToSpeaker(
                    AudioPlaybackService.this, f,
                    com.bachdathan.r1y.util.AudioDiag.streamType(AudioPlaybackService.this), 0);
                boolean ok = r.optBoolean("ok", false);
                boolean cancelled = r.optBoolean("cancelled", false);
                usingDecoder = false;
                if (cancelled) return;
                if (ok) {
                    lastError = "";
                    Log.i(TAG, "Tự giải mã phát xong: " + r.optInt("seconds") + "s, peak="
                        + r.optInt("peak"));
                    if (repeatOne && nowPlayingUrl != null) {
                        playFromUrl(nowPlayingUrl);
                        return;
                    }
                    nowPlayingUrl = null;
                    onPlaybackCompleted();
                } else {
                    // Không ra tiếng bằng bộ giải mã -> quay lại MediaPlayer cho bài này.
                    decodeFailed = true;
                    lastError = "Tự giải mã: " + r.optString("error", "không ra tiếng")
                        + " — đã chuyển sang MediaPlayer";
                    Log.w(TAG, lastError);
                    playingFile = f;
                    playLocal(f.getAbsolutePath());
                }
            }
        }, "R1Y-Decode").start();
        return true;
    }

    /** File đang phát bằng bộ giải mã (để pause/resume/stop đúng chỗ). */
    private volatile File playingFile = null;

    /**
     * Phát audio từ raw data (PCM)
     */
    public void playFromData(byte[] audioData) {
        Log.d(TAG, "Playing from data: " + audioData.length + " bytes");
        
        try {
            // Save to temp file
            File tempFile = File.createTempFile("xiaozhi_audio_", ".pcm", getCacheDir());
            FileOutputStream fos = new FileOutputStream(tempFile);
            fos.write(audioData);
            fos.close();
            
            // Play from file
            playFromUrl(tempFile.getAbsolutePath());
            
            // Delete temp file after playback
            tempFile.deleteOnExit();
            
        } catch (IOException e) {
            Log.e(TAG, "Error playing from data", e);
            if (callback != null) {
                callback.onPlaybackError(e.getMessage());
            }
        }
    }
    
    /**
     * Dừng phát
     */
    /** Ngưng phát hẳn bài đang phát (khác Tạm dừng: không phát tiếp được nữa). */
    public void stop() {
        if (usingDecoder) StreamDecoder.stopPlayback();
        usingDecoder = false;
        try {
            if (mediaPlayer != null) {
                if (mediaPlayer.isPlaying()) mediaPlayer.stop();
                mediaPlayer.reset();
            }
        } catch (Exception e) {
            Log.w(TAG, "stop lỗi: " + e.getMessage());
        }
        nowPlayingUrl = null;
        paused = false;
        Log.d(TAG, "Playback stopped");
        onPlaybackCompleted();
    }
    
    /**
     * Tạm dừng
     */
    public void pause() {
        if (usingDecoder) {
            StreamDecoder.pausePlayback();
            paused = true;
            return;
        }
        if (mediaPlayer != null && isPrepared) {
            try {
                if (mediaPlayer.isPlaying()) {
                    mediaPlayer.pause();
                    paused = true;
                    Log.d(TAG, "Playback paused");
                }
            } catch (Exception e) {
                Log.w(TAG, "pause lỗi: " + e.getMessage());
            }
        }
    }
    
    /**
     * Tiếp tục phát
     */
    public void resume() {
        if (usingDecoder) {
            StreamDecoder.resumePlayback();
            paused = false;
            return;
        }
        if (mediaPlayer != null && isPrepared) {
            try {
                mediaPlayer.start();
                paused = false;
                Log.d(TAG, "Playback resumed");
            } catch (Exception e) {
                Log.w(TAG, "resume lỗi: " + e.getMessage());
            }
        } else if (nowPlayingUrl != null) {
            // đã stop/reset -> phát lại từ đầu bài đang nhớ
            playFromUrl(nowPlayingUrl);
        }
    }

    public boolean isPaused() {
        return paused;
    }
    
    /**
     * MediaPlayer callbacks
     */
    @Override
    public void onPrepared(MediaPlayer mp) {
        isPrepared = true;
        try {
            // chắc chắn không ở mức 0 rồi mới start
            com.bachdathan.r1y.util.AudioDiag.ensureVolume(this);
            mp.setVolume(1.0f, 1.0f);
        } catch (Throwable ignored) {
        }
        mp.start();
        lastPreparedAt = System.currentTimeMillis();
        lastError = "";
        Log.d(TAG, "Playback started");
        
        if (callback != null) {
            callback.onPlaybackStarted();
        }
    }
    
    @Override
    public void onCompletion(MediaPlayer mp) {
        Log.d(TAG, "Playback completed");
        if (repeatOne && nowPlayingUrl != null) {
            Log.i(TAG, "Lặp lại bài đang phát");
            playFromUrl(nowPlayingUrl);
            return;
        }
        nowPlayingUrl = null;
        onPlaybackCompleted();
    }
    
    @Override
    public boolean onError(MediaPlayer mp, int what, int extra) {
        Log.e(TAG, "MediaPlayer error: what=" + what + ", extra=" + extra);
        lastError = "what=" + what + " extra=" + extra
            + (what == 1 ? " (MEDIA_ERROR_UNKNOWN — thường là codec/định dạng)" : "")
            + (extra == -1004 ? " (IO — link chết/403)" : "")
            + (extra == -110 ? " (timeout)" : "")
            + (extra == -2147483648 ? " (lỗi hệ thống)" : "");

        // 1) Còn bậc thấp hơn trong cùng máy chủ -> tự hạ chất lượng (Opus -> AAC -> video)
        if (!retrying && ladderIndex + 1 < ladder.size()) {
            retrying = true;
            ladderIndex++;
            AudioFallback.Candidate c = ladder.get(ladderIndex);
            Log.w(TAG, "Hạ chất lượng xuống bậc " + (ladderIndex + 1) + ": " + c.label);
            playFromUrl(c.url);
            return true;
        }

        // 2) Hết bậc -> chuyển sang máy chủ kế tiếp
        if (!retrying && currentVideoId != null) {
            retrying = true;
            resolveAndPlayAsync(currentQuery, currentVideoId);
            return true;
        }

        if (callback != null) {
            callback.onPlaybackError("MediaPlayer error: " + what);
        }
        
        onPlaybackCompleted();
        return true;
    }

    /**
     * Tìm + lấy âm thanh + phát, trong luồng nền.
     * Có videoId -> lấy trực tiếp; chỉ có từ khóa -> tìm trước (mặc định máy chủ công cộng).
     */
    private void resolveAndPlayAsync(final String query, final String videoId) {
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    R1YConfig config = new R1YConfig(AudioPlaybackService.this);
                    ResolverManager rm = new ResolverManager(config);
                    String vid = videoId;
                    if (vid == null) {
                        com.bachdathan.r1y.youtube.SearchManager sm =
                            new com.bachdathan.r1y.youtube.SearchManager(config);
                        java.util.List<com.bachdathan.r1y.youtube.SearchManager.Result> found =
                            sm.search(query, 1);
                        if (found.isEmpty()) throw new Exception("không tìm thấy bài");
                        vid = found.get(0).videoId;
                    }
                    ResolverManager.Resolved r = rm.resolveNext(vid, triedSources);
                    currentVideoId = vid;
                    currentSource = r.source;
                    nowPlayingTitle = r.title == null ? "" : r.title;
                    nowPlayingId = vid;
                    if (r.source != null) triedSources.add(r.source);
                    // Giữ cả thang chất lượng để tự hạ bậc nếu MediaPlayer lỗi
                    ladder.clear();
                    if (r.ladder != null && !r.ladder.isEmpty()) ladder.addAll(r.ladder);
                    ladderIndex = 0;
                    Log.i(TAG, "Phát qua " + r.source + ": " + r.title
                        + " | bậc " + r.tier + "/" + Math.max(1, ladder.size())
                        + (ladder.isEmpty() ? "" : " (" + ladder.get(0).label + ")"));
                    retrying = false;
                    playFromUrl(r.audioUrl);
                } catch (Exception e) {
                    Log.e(TAG, "Không lấy được âm thanh: " + e.getMessage());
                    retrying = false;
                    if (callback != null) callback.onPlaybackError(e.getMessage());
                }
            }
        }, "R1Y-Resolver").start();
    }
    
    /**
     * Cleanup sau khi phát xong
     */
    private void onPlaybackCompleted() {
        isPrepared = false;
        
        // Release audio focus
        audioManager.abandonAudioFocus(audioFocusChangeListener);
        
        // Reset LED
        Intent ledIntent = new Intent(this, LEDControlService.class);
        ledIntent.setAction(LEDControlService.ACTION_SET_IDLE);
        startService(ledIntent);
        
        if (callback != null) {
            callback.onPlaybackCompleted();
        }
    }
    
    /**
     * Audio Focus Change Listener
     */
    private AudioManager.OnAudioFocusChangeListener audioFocusChangeListener = 
        new AudioManager.OnAudioFocusChangeListener() {
            @Override
            public void onAudioFocusChange(int focusChange) {
                switch (focusChange) {
                    case AudioManager.AUDIOFOCUS_GAIN:
                        // Gained focus
                        if (mediaPlayer != null && !mediaPlayer.isPlaying() && isPrepared) {
                            mediaPlayer.start();
                            mediaPlayer.setVolume(1.0f, 1.0f);
                        }
                        break;
                        
                    case AudioManager.AUDIOFOCUS_LOSS:
                        // Lost focus permanently
                        stop();
                        break;
                        
                    case AudioManager.AUDIOFOCUS_LOSS_TRANSIENT:
                        // Lost focus temporarily
                        if (mediaPlayer != null && mediaPlayer.isPlaying()) {
                            mediaPlayer.pause();
                        }
                        break;
                        
                    case AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK:
                        // Lost focus temporarily but can duck
                        if (mediaPlayer != null && mediaPlayer.isPlaying()) {
                            mediaPlayer.setVolume(0.3f, 0.3f);
                        }
                        break;
                }
            }
        };
    
    /**
     * Release MediaPlayer resources
     */
    private void releaseMediaPlayer() {
        if (mediaPlayer != null) {
            try {
                if (mediaPlayer.isPlaying()) {
                    mediaPlayer.stop();
                }
                mediaPlayer.release();
            } catch (Exception e) {
                Log.e(TAG, "Error releasing MediaPlayer", e);
            }
            mediaPlayer = null;
        }
    }
    
    public boolean isPlaying() {
        if (usingDecoder) return StreamDecoder.busy && !paused;
        return mediaPlayer != null && mediaPlayer.isPlaying();
    }
    
    @Override
    public void onDestroy() {
        releaseMediaPlayer();
        audioManager.abandonAudioFocus(audioFocusChangeListener);
        super.onDestroy();
        Log.d(TAG, "AudioPlaybackService destroyed");
    }
}