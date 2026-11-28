package com.bachdathan.r1y.service;

import android.app.Notification;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.os.Binder;
import android.os.Handler;
import android.os.IBinder;
import android.util.Log;

import com.bachdathan.r1y.activation.DeviceActivator;
import com.bachdathan.r1y.activation.DeviceFingerprint;
import com.bachdathan.r1y.config.XiaozhiConfig;
import com.bachdathan.r1y.config.R1YConfig;
import com.bachdathan.r1y.util.OpusEncoderWrapper;
import com.bachdathan.r1y.core.DeviceState;
import com.bachdathan.r1y.core.EventBus;
import com.bachdathan.r1y.core.ListeningMode;
import com.bachdathan.r1y.core.XiaozhiCore;
import com.bachdathan.r1y.events.ConnectionEvent;
import com.bachdathan.r1y.events.MessageReceivedEvent;
import com.bachdathan.r1y.util.ErrorCodes;
import com.bachdathan.r1y.util.TrustAllCertificates;

import org.java_websocket.client.WebSocketClient;
import org.java_websocket.handshake.ServerHandshake;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.net.Socket;
import java.net.URI;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;

/**
 * Service quản lý kết nối WebSocket với Xiaozhi Cloud
 *
 * UPDATED: Sử dụng py-xiaozhi authentication method
 * - Token-based authentication (Bearer token trong WebSocket header)
 * - Device activation flow với HMAC challenge-response
 * - Hello message thay vì Authorize handshake
 *
 * Refactored để sử dụng XiaozhiCore và EventBus
 */
public class XiaozhiConnectionService extends Service {

    private static final String TAG = "XiaozhiConnection";
    private static final int MAX_RETRIES = 3;
    private static final int NOTIFICATION_ID = 1001;
    private static final String CHANNEL_ID = "xiaozhi_service_channel";

    private WebSocketClient webSocketClient;
    private final IBinder binder = new LocalBinder();
    private ConnectionListener connectionListener;
    
    // XiaozhiCore và EventBus
    private XiaozhiCore core;
    private EventBus eventBus;
    
    // Device activation
    private DeviceActivator deviceActivator;
    private DeviceFingerprint deviceFingerprint;
    
    // Retry logic
    private Handler retryHandler;
    private int retryCount = 0;
    private boolean isRetrying = false;

    // ---- Giao thức Xiaozhi (v1.8.0) ----
    /** Đã nhận gói hello của máy chủ -> kênh audio mở. */
    private volatile boolean channelOpen = false;
    private volatile int serverOpusRate = 24000;
    private volatile int serverFrameMs = 60;
    private volatile String serverSessionId = "";
    /** Giải mã + phát Opus nhận từ máy chủ. */
    public final com.bachdathan.r1y.util.OpusStreamPlayer opusPlayer =
        new com.bachdathan.r1y.util.OpusStreamPlayer();
    /** Mã hoá micro thành Opus để gửi lên máy chủ. */
    private com.bachdathan.r1y.util.OpusEncoderWrapper opusEncoder;
    /** Chẩn đoán hiển thị trên trang 8088. */
    public static volatile String lastStt = "";
    public static volatile String lastTts = "";
    public static volatile String lastListenState = "";
    public static volatile long lastTtsAt = 0;
    public static volatile int sentFrames = 0;
    /** Loại tin JSON cuối cùng máy chủ gửi (rỗng = máy chủ chưa nói gì). */
    public static volatile String lastJsonType = "";
    public static volatile long lastJsonAt = 0;
    /** Chuỗi loại tin gần đây, ví dụ "hello stt llm tts:sentence_start". */
    public static volatile String lastTypes = "";
    /** Số khung nhị phân đã nhận (0 = máy chủ không gửi tiếng nào). */
    public static volatile int recvBinary = 0;
    
    public class LocalBinder extends Binder {
        public XiaozhiConnectionService getService() {
            return XiaozhiConnectionService.this;
        }
    }
    
    public interface ConnectionListener {
        void onConnected();
        void onDisconnected();
        void onActivationRequired(String verificationCode);
        void onActivationProgress(int attempt, int maxAttempts);
        void onPairingSuccess();
        void onPairingFailed(String error);
        void onMessage(String message);
        void onError(String error);
    }

    /**
     * Start service as foreground to prevent being killed by system
     * FIX: This ensures WebSocket connection stays alive even when MainActivity is destroyed
     *
     * NOTE: Simplified for API 22 compatibility - no NotificationChannel needed
     */
    private void startForegroundService() {
        // Create notification intent
        Intent notificationIntent = new Intent(this, com.bachdathan.r1y.ui.MainActivity.class);
        PendingIntent pendingIntent = PendingIntent.getActivity(
            this,
            0,
            notificationIntent,
            0
        );

        // Create notification (API 22 compatible)
        Notification notification = new Notification.Builder(this)
            .setContentTitle("Xiaozhi Voice Assistant")
            .setContentText("Listening for wake word...")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build();

        startForeground(NOTIFICATION_ID, notification);
        Log.i(TAG, "Service started as foreground - will not be killed by system");
    }

    @Override
    public void onCreate() {
        super.onCreate();

        // FIX: Start as foreground service to prevent being killed
        startForegroundService();

        // Get XiaozhiCore instance
        core = XiaozhiCore.getInstance();
        eventBus = core.getEventBus();

        // Initialize device activation
        deviceFingerprint = DeviceFingerprint.getInstance(this);
        deviceActivator = new DeviceActivator(this);

        // Setup activation listener
        deviceActivator.setListener(new DeviceActivator.ActivationListener() {
            @Override
            public void onActivationStarted(String verificationCode) {
                Log.i(TAG, "Activation started - code: " + verificationCode);
                if (connectionListener != null) {
                    connectionListener.onActivationRequired(verificationCode);
                }
            }

            @Override
            public void onActivationProgress(int attempt, int maxAttempts) {
                Log.d(TAG, "Activation progress: " + attempt + "/" + maxAttempts);
                if (connectionListener != null) {
                    connectionListener.onActivationProgress(attempt, maxAttempts);
                }
            }

            @Override
            public void onActivationSuccess(String accessToken) {
                Log.i(TAG, "=== ACTIVATION SUCCESS ===");
                Log.i(TAG, "Access token received, auto-connecting WebSocket...");

                // FIX #1: Auto-connect WebSocket immediately after activation
                connectWithToken(accessToken);

                // Notify UI after connection attempt
                if (connectionListener != null) {
                    connectionListener.onPairingSuccess();
                }

                Log.i(TAG, "==========================");
            }

            @Override
            public void onActivationFailed(String error) {
                Log.e(TAG, "Activation failed: " + error);
                if (connectionListener != null) {
                    connectionListener.onPairingFailed(error);
                }
            }
        });

        // Register this service với core
        core.setConnectionService(this);

        Log.i(TAG, "Service created and registered with XiaozhiCore");
    }
    
    @Override
    public IBinder onBind(Intent intent) {
        return binder;
    }
    
    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        Log.i(TAG, "=== SERVICE STARTED ===");
        retryHandler = new Handler();

        // FIX: Handle SEND_AUDIO action from VoiceRecognitionService
        if (intent != null && "SEND_AUDIO".equals(intent.getAction())) {
            byte[] audioData = intent.getByteArrayExtra("audio_data");
            int sampleRate = intent.getIntExtra("sample_rate", 16000);
            int channels = intent.getIntExtra("channels", 1);

            if (audioData != null && audioData.length > 0) {
                Log.i(TAG, "Received audio data: " + audioData.length + " bytes");
                sendAudioToServer(audioData, sampleRate, channels);
            } else {
                Log.w(TAG, "Received SEND_AUDIO action but no audio data");
            }

            return START_STICKY;
        }

        // FIX #3: Auto-connect if device is activated but not connected
        // This handles boot/restart scenarios
        if (deviceActivator != null && deviceActivator.isActivated()) {
            if (!isConnected()) {
                Log.i(TAG, "Device is activated but not connected");
                Log.i(TAG, "Starting auto-connect on service startup...");

                // Delay connect to ensure service is fully initialized
                retryHandler.postDelayed(new Runnable() {
                    @Override
                    public void run() {
                        connect();
                    }
                }, 1000); // 1 second delay
            } else {
                Log.i(TAG, "Device is already connected");
            }
        } else {
            Log.i(TAG, "Device not activated - waiting for user action");
        }

        Log.i(TAG, "=======================");
        return START_STICKY;
    }
    
    public void setConnectionListener(ConnectionListener listener) {
        this.connectionListener = listener;
    }
    
    /**
     * Connect to Xiaozhi Cloud
     * Sử dụng py-xiaozhi method:
     * 1. Check if device is activated
     * 2. If not activated -> start activation flow
     * 3. If activated -> connect with token
     */
    public void connect() {
        // Check if already connected
        if (webSocketClient != null && webSocketClient.isOpen()) {
            Log.w(TAG, "Already connected");
            return;
        }

        R1YConfig r1y = new R1YConfig(this);
        Log.i(TAG, "Máy chủ AI: " + r1y.getAiServer() + " -> " + r1y.getAiUrl());

        // Máy chủ tự dựng/tùy chỉnh: dùng token dự phòng nếu có, không cần kích hoạt Xiaozhi
        String fallbackToken = r1y.getAiToken();
        if (!"xiaozhi".equals(r1y.getAiServer())
                && fallbackToken != null && !fallbackToken.trim().isEmpty()) {
            Log.i(TAG, "Dùng token dự phòng cho máy chủ AI tùy chỉnh");
            connectWithToken(fallbackToken.trim());
            return;
        }

        // Bước 1: hỏi OTA. OTA là NGUỒN CHUẨN: nó trả về đúng WebSocket URL + token
        // mà máy chủ đã cấp cho MAC này. Trước v1.9.2 app bỏ qua bước này và dùng
        // "token" sinh từ MAC -> máy chủ từ chối, đúng hiện tượng "Kết nối: không"
        // dù OTA đã trả HTTP 200 kèm token.
        Log.i(TAG, "Hỏi OTA trước khi bắt tay WebSocket…");
        final String fallback2 = r1y.getAiToken();
        new com.bachdathan.r1y.activation.OTAConfigManager(this)
            .fetchOTAConfig(new com.bachdathan.r1y.activation.OTAConfigManager.OTACallback() {
                @Override
                public void onSuccess(com.bachdathan.r1y.activation.OTAConfigManager.OTAResponse resp) {
                    String token = null;
                    if (resp != null && resp.websocket != null) {
                        token = resp.websocket.token;
                        if (resp.websocket.url != null && !resp.websocket.url.isEmpty()) {
                            R1YConfig cfg = new R1YConfig(XiaozhiConnectionService.this);
                            if (!resp.websocket.url.equals(cfg.getAiUrl())) {
                                Log.i(TAG, "OTA đổi máy chủ WebSocket: " + resp.websocket.url);
                                cfg.setAiUrl(resp.websocket.url);
                                if (!"custom".equals(cfg.getAiServer())) cfg.setAiServer("custom");
                            }
                        }
                    }
                    if (token == null || token.trim().isEmpty()) {
                        token = fallback2;
                    }
                    if (token == null || token.trim().isEmpty()) {
                        token = deviceFingerprint.getAccessToken();
                    }
                    if (token != null && !token.trim().isEmpty()) {
                        deviceFingerprint.setAccessToken(token.trim());
                        deviceFingerprint.setActivationStatus(true);
                        connectWithToken(token.trim());
                        return;
                    }
                    // OTA không cấp token -> cần người dùng kích hoạt
                    Log.i(TAG, "OTA không cấp token — bắt đầu kích hoạt");
                    deviceActivator.startActivation();
                }

                @Override
                public void onError(String error) {
                    Log.w(TAG, "OTA lỗi (" + error + ") — dùng token đã lưu nếu có");
                    String token = fallback2 != null && !fallback2.trim().isEmpty()
                        ? fallback2.trim() : deviceFingerprint.getAccessToken();
                    if (token != null && !token.trim().isEmpty()) {
                        connectWithToken(token);
                    } else if (connectionListener != null) {
                        connectionListener.onError("Không lấy được cấu hình máy chủ AI: " + error);
                    }
                }
            });
        return;
    }

    /** Đường cũ (giữ lại cho nơi khác gọi): kiểm tra kích hoạt rồi nối. */
    private void connectLegacy() {
        // Check activation status
        if (!deviceActivator.isActivated()) {
            Log.i(TAG, "Device not activated - starting activation flow");
            deviceActivator.startActivation();
            return;
        }
        
        // Get access token and check expiration
        String accessToken = deviceFingerprint.getValidAccessToken();
        if (accessToken == null) {
            // Token expired or not found
            if (deviceFingerprint.isTokenExpired()) {
                Log.w(TAG, "Access token expired - need re-activation");
                if (connectionListener != null) {
                    connectionListener.onError("Token expired - please re-activate device");
                }
                // Auto start re-activation
                deviceActivator.startActivation();
            } else {
                Log.e(TAG, "No access token available");
                if (connectionListener != null) {
                    connectionListener.onError("No access token - please activate device");
                }
            }
            return;
        }

        // Connect with token
        connectWithToken(accessToken);
    }
    
    /**
     * Connect to WebSocket with Bearer token
     * py-xiaozhi method: Token trong WebSocket header
     */
    private void connectWithToken(final String accessToken) {
        if (webSocketClient != null && webSocketClient.isOpen()) {
            Log.w(TAG, "Already connected");
            return;
        }
        
        // Validate token
        if (accessToken == null || accessToken.isEmpty()) {
            Log.e(TAG, "Cannot connect - access token is null or empty");
            if (connectionListener != null) {
                connectionListener.onError("No access token available. Please activate device first.");
            }
            return;
        }
        
        try {
            String serverUrl = new R1YConfig(this).getAiUrl();
            URI serverUri = new URI(serverUrl);

            // Enhanced logging for debugging
            Log.i(TAG, "=== WEBSOCKET CONNECTION ===");
            Log.i(TAG, "URL: " + serverUrl);
            Log.i(TAG, "Token (first 30 chars): " + (accessToken.length() > 30 ? accessToken.substring(0, 30) + "..." : accessToken));
            Log.i(TAG, "Token length: " + accessToken.length());
            Log.i(TAG, "============================");
            
            // 4 header BẮT BUỘC của giao thức Xiaozhi (xiaozhi-esp32/docs/websocket.md):
            //   Authorization: Bearer <token>, Protocol-Version: 1,
            //   Device-Id: MAC, Client-Id: UUID ổn định
            // Thiếu Protocol-Version/Device-Id/Client-Id thì máy chủ từ chối ngay
            // ở bước bắt tay — đây là lý do "không kết nối được với AI".
            Map<String, String> headers = new HashMap<>();
            headers.put("Authorization", "Bearer " + accessToken);
            headers.put("Protocol-Version", "1");
            String mac = deviceFingerprint.getMacAddress();
            if (mac != null && !mac.isEmpty()) headers.put("Device-Id", mac);
            headers.put("Client-Id", new R1YConfig(this).getAiClientId());
            Log.i(TAG, "Headers: " + headers.toString());
            
            webSocketClient = new WebSocketClient(serverUri, headers) {
                @Override
                public void onOpen(ServerHandshake handshakedata) {
                    Log.i(TAG, "=== WEBSOCKET CONNECTED ===");
                    Log.i(TAG, "HTTP Status: " + handshakedata.getHttpStatus());
                    Log.i(TAG, "HTTP Status Message: " + handshakedata.getHttpStatusMessage());
                    Log.i(TAG, "Server handshake: " + handshakedata.toString());
                    Log.i(TAG, "============================");

                    channelOpen = false;
                    if (connectionListener != null) {
                        connectionListener.onConnected();
                    }

                    // Gói hello đúng giao thức Xiaozhi; kênh chỉ mở khi máy chủ trả hello.
                    sendHelloMessage();
                }

                @Override
                public void onSetSSLParameters(SSLParameters sslParameters) {
                    // Called before SSL handshake - customize SSL parameters if needed
                    Log.d(TAG, "SSL parameters set");
                }
                
                @Override
                public void onMessage(String message) {
                    Log.d(TAG, "Message received: " + message);
                    handleMessage(message);
                    
                    if (connectionListener != null) {
                        connectionListener.onMessage(message);
                    }
                }
                
                @Override
                public void onMessage(java.nio.ByteBuffer bytes) {
                    // Server xiaozhi gửi TIẾNG NÓI (opus) bằng khung nhị phân.
                    // Trước đây app không hề nhận khung nhị phân -> trợ lý trả lời
                    // nhưng loa im lặng. Giờ ghi ra file .opus và phát.
                    try {
                        byte[] data = new byte[bytes.remaining()];
                        bytes.get(data);
                        handleTtsBinary(data);
                    } catch (Throwable t) {
                        Log.e(TAG, "Lỗi xử lý khung nhị phân: " + t);
                    }
                }

                @Override
                public void onClose(int code, String reason, boolean remote) {
                    // Enhanced logging
                    Log.w(TAG, "=== WEBSOCKET CLOSED ===");
                    Log.w(TAG, "Code: " + code);
                    Log.w(TAG, "Reason: " + reason);
                    Log.w(TAG, "Remote: " + remote);
                    Log.w(TAG, "========================");
                    
                    channelOpen = false;
                    opusPlayer.close();
                    if (connectionListener != null) {
                        connectionListener.onDisconnected();
                    }
                    
                    // Auto retry if not manually disconnected
                    if (remote && !isRetrying) {
                        scheduleReconnect(ErrorCodes.WEBSOCKET_ERROR);
                    }
                }
                
                @Override
                public void onError(Exception ex) {
                    // Enhanced error logging with full details
                    Log.e(TAG, "=== WEBSOCKET ERROR DETAIL ===");
                    Log.e(TAG, "Error class: " + ex.getClass().getName());
                    Log.e(TAG, "Error message: " + ex.getMessage());

                    // Log cause if available
                    Throwable cause = ex.getCause();
                    if (cause != null) {
                        Log.e(TAG, "Cause class: " + cause.getClass().getName());
                        Log.e(TAG, "Cause message: " + cause.getMessage());
                    }

                    // Print full stack trace to string
                    StringWriter sw = new StringWriter();
                    PrintWriter pw = new PrintWriter(sw);
                    ex.printStackTrace(pw);
                    Log.e(TAG, "Full stack trace:\n" + sw.toString());
                    Log.e(TAG, "==============================");

                    String errorMsg = ErrorCodes.getMessage(ErrorCodes.WEBSOCKET_ERROR);
                    if (connectionListener != null) {
                        connectionListener.onError(errorMsg + ": " + ex.getMessage());
                    }

                    // Retry on error
                    scheduleReconnect(ErrorCodes.WEBSOCKET_ERROR);
                }
            };

            // Apply SSL trust manager if bypass is enabled
            if (XiaozhiConfig.BYPASS_SSL_VALIDATION) {
                try {
                    Log.i(TAG, "Applying SSL trust manager (bypass validation)");
                    webSocketClient.setSocketFactory(TrustAllCertificates.getSSLSocketFactory());
                } catch (Exception e) {
                    Log.e(TAG, "Failed to set SSL socket factory", e);
                }
            }

            Log.i(TAG, "=== INITIATING WEBSOCKET CONNECTION ===");
            Log.i(TAG, "Calling webSocketClient.connect()...");
            webSocketClient.connect();
            Log.i(TAG, "connect() method returned - waiting for onOpen/onError callback");
            Log.i(TAG, "========================================");
            
        } catch (Exception e) {
            Log.e(TAG, "Failed to connect: " + e.getMessage(), e);
            if (connectionListener != null) {
                connectionListener.onError("Connection failed: " + e.getMessage());
            }
        }
    }
    /**
     * Gói hello ĐÚNG giao thức Xiaozhi (xiaozhi-esp32/docs/websocket.md).
     *
     * Bản cũ gửi {"header":{"name":"hello"}} — máy chủ Xiaozhi không hiểu,
     * không coi là đã mở kênh audio, nên không bao giờ mời nói chuyện.
     *
     * Bản này khai báo thẳng thứ R1 làm được: gửi lên Opus 16 kHz mono
     * 60 ms/khung, và xin về Opus 24 kHz mono (máy chủ mặc định).
     */
    private void sendHelloMessage() {
        try {
            String deviceId = deviceFingerprint.getMacAddress();
            if (deviceId == null || deviceId.isEmpty()) {
                Log.e(TAG, "Không gửi được hello - thiếu MAC");
                if (connectionListener != null) {
                    connectionListener.onError("Device ID not found");
                }
                return;
            }

            JSONObject audioParams = new JSONObject();
            audioParams.put("format", "opus");
            audioParams.put("sample_rate", 16000);
            audioParams.put("channels", 1);
            audioParams.put("frame_duration", 60);

            JSONObject message = new JSONObject();
            message.put("type", "hello");
            message.put("version", 1);
            message.put("transport", "websocket");
            message.put("audio_params", audioParams);

            JSONObject features = new JSONObject();
            features.put("mcp", false);
            features.put("aec", false);
            message.put("features", features);

            String json = message.toString();
            Log.i(TAG, "=== GỬI HELLO (giao thức Xiaozhi) === " + json);
            webSocketClient.send(json);
            // Chưa đánh dấu kết nối xong: phải chờ máy chủ trả hello.
        } catch (Exception e) {
            Log.e(TAG, "Gửi hello lỗi: " + e.getMessage(), e);
            if (connectionListener != null) {
                connectionListener.onError("Hello message failed: " + e.getMessage());
            }
        }
    }

    /** Máy chủ đã trả hello -> kênh audio mở, giờ mới coi là kết nối xong. */
    private void onServerHello(JSONObject json) {
        serverOpusRate = 24000;
        serverFrameMs = 60;
        serverSessionId = json.optString("session_id", "");
        JSONObject ap = json.optJSONObject("audio_params");
        if (ap != null) {
            String fmt = ap.optString("format", "opus");
            serverOpusRate = ap.optInt("sample_rate", 24000);
            serverFrameMs = ap.optInt("frame_duration", 60);
            if (!"opus".equalsIgnoreCase(fmt)) {
                Log.w(TAG, "Máy chủ trả format=" + fmt + " — chỉ hỗ trợ opus");
            }
        }
        Log.i(TAG, "=== MÁY CHỦ ĐÃ MỞ KÊNH: " + serverOpusRate + "Hz, "
            + serverFrameMs + "ms, session=" + serverSessionId + " ===");
        channelOpen = true;
        core.setDeviceState(DeviceState.IDLE);
        eventBus.post(new ConnectionEvent(true, "Xiaozhi: kênh audio đã mở"));
        if (connectionListener != null) {
            connectionListener.onPairingSuccess();
        }
    }

    /** Xử lý tin nhắn JSON từ máy chủ (giao thức Xiaozhi). */
    private void handleMessage(String message) {
        try {
            JSONObject json = new JSONObject(message);
            eventBus.post(new MessageReceivedEvent(json));

            String type = json.optString("type");
            Log.d(TAG, "Nhận JSON type=" + type);
            // Ghi lại loại tin gần nhất — để trả lời "AI có trả lời không?"
            lastJsonType = type == null ? "" : type;
            lastJsonAt = System.currentTimeMillis();
            if ("hello".equals(type) || "tts".equals(type) || "stt".equals(type)
                    || "llm".equals(type) || "mcp".equals(type)) {
                lastTypes = (lastTypes + " " + type
                    + ("tts".equals(type) ? ":" + json.optString("state") : "")).trim();
                if (lastTypes.length() > 160) {
                    lastTypes = lastTypes.substring(lastTypes.length() - 160);
                }
            }

            if ("hello".equals(type)) {
                onServerHello(json);
            } else if ("stt".equals(type)) {
                lastStt = json.optString("text", "");
            } else if ("tts".equals(type)) {
                String state = json.optString("state", "");
                if ("start".equals(state)) {
                    if (core.isKeepListening()
                            && core.getListeningMode() == ListeningMode.REALTIME) {
                        core.setDeviceState(DeviceState.SPEAKING);
                    } else {
                        core.setDeviceState(DeviceState.SPEAKING);
                    }
                } else if ("sentence_start".equals(state)) {
                    lastTts = json.optString("text", "");
                } else if ("stop".equals(state)) {
                    lastTts = "";
                    core.setDeviceState(core.isKeepListening()
                            ? DeviceState.LISTENING : DeviceState.IDLE);
                }
            } else if ("llm".equals(type)) {
                String emo = json.optString("emotion", "");
                Log.d(TAG, "LLM emotion=" + emo);
            }
        } catch (JSONException e) {
            Log.w(TAG, "Không phân tích được JSON: " + e.getMessage());
        }
    }

    /**
     * Nhận KHUNG NHỊ PHÂN từ máy chủ = một khung Opus (60 ms) của câu trả lời.
     *
     * Trước v1.8.0 app coi khung này là file hoàn chỉnh và đưa cho MediaPlayer;
     * MediaPlayer không đọc được Opus trần, nên tiếng trả lời luôn mất. Giờ giải
     * mã bằng Concentus rồi phát liên tục qua một AudioTrack duy nhất.
     *
     * Nếu máy chủ lại gửi cả file (Ogg/MP3/WAV) thì vẫn phát được như cũ.
     */
    private void handleTtsBinary(byte[] data) {
        if (data == null || data.length < 2) {
            return;
        }
        recvBinary++;
        try {
            String kind = sniff(data);
            if (kind != null) {
                // Là file hoàn chỉnh: giao cho MediaPlayer.
                com.bachdathan.r1y.util.AudioDiag.ensureVolume(this);
                java.io.File f = new java.io.File(getCacheDir(), "tts_part.bin");
                java.io.FileOutputStream fos = new java.io.FileOutputStream(f);
                fos.write(data);
                fos.close();
                android.content.Intent i =
                    new android.content.Intent(this, AudioPlaybackService.class);
                i.setAction(AudioPlaybackService.ACTION_PLAY_URL);
                i.putExtra("audio_url", f.getAbsolutePath());
                startService(i);
                return;
            }
            // Khung Opus trần -> giải mã và phát.
            opusPlayer.feed(this, data, serverOpusRate, 1);
            lastTtsAt = System.currentTimeMillis();
        } catch (Throwable t) {
            Log.e(TAG, "Không phát được khung nhị phân: " + t);
        }
    }

    /** Nhận dạng file âm thanh hoàn chỉnh bằng magic bytes (null = không phải file). */
    private static String sniff(byte[] d) {
        if (d.length > 4 && d[0] == 'O' && d[1] == 'g' && d[2] == 'g' && d[3] == 'S') return "ogg";
        if (d.length > 3 && d[0] == 'I' && d[1] == 'D' && d[2] == '3') return "mp3";
        if (d.length > 2 && (d[0] & 0xFF) == 0xFF && (d[1] & 0xE0) == 0xE0) return "mp3/aac";
        if (d.length > 12 && d[0] == 'R' && d[1] == 'I' && d[2] == 'F' && d[3] == 'F') return "wav";
        if (d.length > 8 && d[4] == 'f' && d[5] == 't' && d[6] == 'y' && d[7] == 'p') {
            return "mp4/m4a";
        }
        return null;
    }

    /** Bắt đầu nghe: {"type":"listen","state":"start","mode":"manual"}. */
    public void sendStartListening(ListeningMode mode) {
        sendListen("start", mode == null ? "manual" : ("realtime".equals(mode.getValue())
                ? "realtime" : "manual"), null);
    }

    /** Dừng nghe. */
    public void sendStopListening() {
        sendListen("stop", null, null);
    }

    /** Báo máy chủ là đã nghe thấy từ khoá đánh thức. */
    public void sendWakeWordDetected(String text) {
        sendListen("detect", null, text);
    }

    /** Ngắt câu trả lời đang phát (khi người dùng bấm nút giữa lúc trợ lý nói). */
    public void sendAbort() {
        if (!isChannelReady()) return;
        try {
            JSONObject m = new JSONObject();
            m.put("type", "abort");
            if (serverSessionId != null && !serverSessionId.isEmpty()) {
                m.put("session_id", serverSessionId);
            }
            m.put("reason", "wake_word_detected");
            webSocketClient.send(m.toString());
            opusPlayer.close();
        } catch (Exception e) {
            Log.w(TAG, "Gửi abort lỗi: " + e.getMessage());
        }
    }

    private void sendListen(String state, String mode, String text) {
        if (!isChannelReady()) {
            Log.w(TAG, "Chưa mở kênh audio, không gửi được listen/" + state);
            return;
        }
        try {
            JSONObject m = new JSONObject();
            m.put("type", "listen");
            m.put("state", state);
            if (serverSessionId != null && !serverSessionId.isEmpty()) {
                m.put("session_id", serverSessionId);
            }
            if (mode != null) m.put("mode", mode);
            if (text != null) m.put("text", text);
            String json = m.toString();
            Log.d(TAG, "Gửi listen: " + json);
            webSocketClient.send(json);
            lastListenState = state;
        } catch (Exception e) {
            Log.w(TAG, "Gửi listen lỗi: " + e.getMessage());
        }
    }

    /** Gửi câu người dùng gõ tay (tab Trợ lý trên web): {"type":"listen","state":"detect","text":...}. */
    public void sendTextMessage(String text) {
        sendListen("detect", null, text);
    }

    /**
     * Gửi micro lên máy chủ.
     *
     * Chế độ Opus (mặc định): mã hoá 16 kHz mono thành khung 60 ms rồi gửi
     * NHỊ PHÂN — đúng giao thức Xiaozhi.
     * Chế độ PCM: gửi JSON base64 (chỉ cho máy chủ tự dựng hiểu kiểu cũ).
     */
    private void sendAudioToServer(byte[] audioData, int sampleRate, int channels) {
        if (!isChannelReady()) {
            Log.w(TAG, "Chưa kết nối, không gửi được audio");
            Intent ledIntent = new Intent(this, LEDControlService.class);
            ledIntent.setAction(LEDControlService.ACTION_SET_ERROR);
            startService(ledIntent);
            return;
        }

        if (!new R1YConfig(this).isAiUseOpus()) {
            sendAudioAsPcm(audioData, sampleRate, channels);
            return;
        }

        try {
            if (sampleRate != OpusEncoderWrapper.SAMPLE_RATE || channels != 1) {
                Log.w(TAG, "Audio " + sampleRate + "Hz/" + channels + "ch không khớp 16kHz mono"
                    + " — chuyển sang gửi PCM");
                sendAudioAsPcm(audioData, sampleRate, channels);
                return;
            }
            if (opusEncoder == null) {
                opusEncoder = new OpusEncoderWrapper();
            }
            short[] pcm = new short[audioData.length / 2];
            for (int i = 0; i < pcm.length; i++) {
                pcm[i] = (short) ((audioData[i * 2] & 0xFF) | (audioData[i * 2 + 1] << 8));
            }
            java.util.List<byte[]> frames = opusEncoder.feed(pcm, pcm.length);
            for (byte[] f : frames) {
                webSocketClient.send(f);
            }
            sentFrames += frames.size();
            Log.i(TAG, "Gửi " + frames.size() + " khung Opus (" + audioData.length + " byte PCM)");
            Intent ledIntent = new Intent(this, LEDControlService.class);
            ledIntent.setAction(LEDControlService.ACTION_SET_SPEAKING);
            startService(ledIntent);
        } catch (Throwable t) {
            Log.e(TAG, "Mã hoá/gửi Opus lỗi: " + t);
        }
    }

    /** Đường lui: PCM base64 (máy chủ tự dựng kiểu cũ). */
    private void sendAudioAsPcm(byte[] audioData, int sampleRate, int channels) {
        try {
            String audioBase64 = android.util.Base64.encodeToString(
                    audioData, android.util.Base64.NO_WRAP);
            JSONObject message = new JSONObject();
            JSONObject header = new JSONObject();
            header.put("name", "Recognize");
            header.put("namespace", "ai.xiaoai.recognizer");
            header.put("message_id", UUID.randomUUID().toString());
            JSONObject payload = new JSONObject();
            payload.put("audio", audioBase64);
            payload.put("format", "pcm");
            payload.put("sample_rate", sampleRate);
            payload.put("channels", channels);
            payload.put("bits_per_sample", 16);
            message.put("header", header);
            message.put("payload", payload);
            webSocketClient.send(message.toString());
        } catch (Exception e) {
            Log.e(TAG, "Gửi PCM lỗi: " + e.getMessage(), e);
        }
    }

    /**
     * Gửi trực tiếp một khung PCM 16 kHz mono cho lượt nói hiện tại.
     * Được VoiceRecognitionService gọi 50 lần/giây — không dựng Intent.
     */
    public void sendMicFrame(short[] pcm, int length) {
        if (!isChannelReady()) return;
        try {
            if (opusEncoder == null) opusEncoder = new OpusEncoderWrapper();
            java.util.List<byte[]> frames = opusEncoder.feed(pcm, length);
            for (byte[] f : frames) {
                webSocketClient.send(f);
                sentFrames++;
            }
        } catch (Throwable t) {
            Log.w(TAG, "Gửi khung mic lỗi: " + t);
        }
    }

    /** Kênh audio đã sẵn sàng chưa (đã nhận hello của máy chủ). */
    private boolean isChannelReady() {
        return channelOpen && webSocketClient != null && webSocketClient.isOpen();
    }

    /** Trạng thái giao thức để hiện trên tab Trợ lý của trang 8088. */
    public JSONObject protocolStatus() {
        JSONObject o = new JSONObject();
        try {
            o.put("connected", isConnected());
            o.put("channelOpen", channelOpen);
            o.put("serverRate", serverOpusRate);
            o.put("frameMs", serverFrameMs);
            o.put("sessionId", serverSessionId == null ? "" : serverSessionId);
            o.put("lastStt", lastStt);
            o.put("lastTts", lastTts);
            o.put("listenState", lastListenState);
            o.put("sentFrames", sentFrames);
            o.put("recvOk", opusPlayer.framesOk);
            o.put("recvBad", opusPlayer.framesBad);
            o.put("recvStatus", opusPlayer.status());
            o.put("recvBinary", recvBinary);
            o.put("lastJsonType", lastJsonType);
            o.put("lastTypes", lastTypes);
            o.put("lastJsonAgoMs", lastJsonAt == 0 ? -1
                    : System.currentTimeMillis() - lastJsonAt);
            o.put("useOpus", new R1YConfig(this).isAiUseOpus());
            o.put("url", new R1YConfig(this).getAiUrl());
            o.put("lastTtsAgoMs", lastTtsAt == 0 ? -1
                    : System.currentTimeMillis() - lastTtsAt);
        } catch (Exception ignored) {}
        return o;
    }
    
    /**
     * Schedule reconnect với exponential backoff
     */
    private void scheduleReconnect(final int errorCode) {
        if (retryCount >= MAX_RETRIES) {
            Log.e(TAG, "Max retries reached. Giving up.");
            isRetrying = false;
            retryCount = 0;
            
            String errorMsg = ErrorCodes.getMessage(ErrorCodes.SERVER_UNAVAILABLE);
            if (connectionListener != null) {
                connectionListener.onError(errorMsg);
            }
            return;
        }
        
        isRetrying = true;
        int delay = ErrorCodes.getRetryDelay(errorCode, retryCount);
        retryCount++;
        
        Log.i(TAG, "Scheduling reconnect #" + retryCount + " in " + delay + "ms");
        
        if (retryHandler != null) {
            retryHandler.postDelayed(new Runnable() {
                @Override
                public void run() {
                    Log.i(TAG, "Retrying connection...");
                    connect();
                }
            }, delay);
        }
    }
    
    /**
     * Cancel scheduled retries
     */
    private void cancelRetries() {
        isRetrying = false;
        retryCount = 0;
        if (retryHandler != null) {
            retryHandler.removeCallbacksAndMessages(null);
        }
    }
    
    /**
     * Disconnect (manual - no auto retry)
     */
    public void disconnect() {
        cancelRetries();
        
        if (webSocketClient != null) {
            webSocketClient.close();
            webSocketClient = null;
            Log.i(TAG, "Disconnected");
        }
    }
    
    /**
     * Check connection status
     */
    public boolean isConnected() {
        return webSocketClient != null && webSocketClient.isOpen();
    }
    
    /**
     * Check if device is activated
     */
    public boolean isActivated() {
        return deviceActivator != null && deviceActivator.isActivated();
    }
    
    /**
     * Start device activation manually
     */
    public void startActivation() {
        if (deviceActivator != null) {
            deviceActivator.startActivation();
        }
    }
    
    /**
     * Cancel device activation
     */
    public void cancelActivation() {
        if (deviceActivator != null) {
            deviceActivator.cancelActivation();
        }
    }
    
    /**
     * Reset activation (for testing)
     */
    public void resetActivation() {
        if (deviceActivator != null) {
            deviceActivator.resetActivation();
        }
    }
    
    @Override
    public void onDestroy() {
        Log.w(TAG, "=== SERVICE ONDESTROY CALLED ===");
        Log.w(TAG, "This should NOT happen if service is properly configured!");
        Log.w(TAG, "Check AndroidManifest.xml - stopWithTask should be false");

        // FIX: Do NOT disconnect if we have an active connection
        // Service should stay alive even when MainActivity is destroyed
        if (isConnected()) {
            Log.w(TAG, "WebSocket is connected - keeping connection alive");
            Log.w(TAG, "Service will be restarted by system (START_STICKY)");
            // Do NOT call disconnect() - let the connection stay alive
        } else {
            Log.i(TAG, "No active connection - safe to cleanup");
            cancelRetries();
        }

        // Unregister from core
        if (core != null) {
            core.setConnectionService(null);
        }

        super.onDestroy();
        Log.i(TAG, "Service destroyed");
    }
}