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
        mediaPlayer.setAudioStreamType(AudioManager.STREAM_MUSIC);
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
        
        try {
            // Request audio focus
            int result = audioManager.requestAudioFocus(
                audioFocusChangeListener,
                AudioManager.STREAM_MUSIC,
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
            mediaPlayer.setDataSource(url);
            mediaPlayer.prepareAsync();
            
        } catch (IOException e) {
            Log.e(TAG, "Error playing from URL", e);
            if (callback != null) {
                callback.onPlaybackError(e.getMessage());
            }
            onPlaybackCompleted();
        }
    }
    
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
    public void stop() {
        if (mediaPlayer != null && mediaPlayer.isPlaying()) {
            mediaPlayer.stop();
            Log.d(TAG, "Playback stopped");
        }
        onPlaybackCompleted();
    }
    
    /**
     * Tạm dừng
     */
    public void pause() {
        if (mediaPlayer != null && mediaPlayer.isPlaying()) {
            mediaPlayer.pause();
            Log.d(TAG, "Playback paused");
        }
    }
    
    /**
     * Tiếp tục phát
     */
    public void resume() {
        if (mediaPlayer != null && isPrepared) {
            mediaPlayer.start();
            Log.d(TAG, "Playback resumed");
        }
    }
    
    /**
     * MediaPlayer callbacks
     */
    @Override
    public void onPrepared(MediaPlayer mp) {
        isPrepared = true;
        mp.start();
        Log.d(TAG, "Playback started");
        
        if (callback != null) {
            callback.onPlaybackStarted();
        }
    }
    
    @Override
    public void onCompletion(MediaPlayer mp) {
        Log.d(TAG, "Playback completed");
        onPlaybackCompleted();
    }
    
    @Override
    public boolean onError(MediaPlayer mp, int what, int extra) {
        Log.e(TAG, "MediaPlayer error: what=" + what + ", extra=" + extra);

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