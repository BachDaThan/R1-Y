# Bấm nút trên loa mà trợ lý AI không nói chuyện

Tài liệu này giải thích **hai lỗi thật trong app** (đã sửa ở v1.8.0) khiến loa R1
không dùng được với trợ lý AI, và cách tự kiểm tra trên trang 8088.

---

## Lỗi 1: app gửi SAI giao thức nên máy chủ AI không bao giờ trả lời

Đặc tả giao thức của Xiaozhi (`xiaozhi-esp32/docs/websocket.md`) quy định:

- Sau khi mở WebSocket, thiết bị gửi gói **hello** dạng:

```json
{ "type": "hello", "version": 1, "transport": "websocket",
  "audio_params": { "format": "opus", "sample_rate": 16000,
                    "channels": 1, "frame_duration": 60 } }
```

- Máy chủ trả về hello (`transport` = `websocket`, kèm `session_id` và
  `audio_params`) — **chỉ khi đó kênh audio mới được coi là mở**.
- Muốn nói thì gửi: `{"type":"listen","state":"start","mode":"manual"}`
- Micro gửi lên bằng **khung NHỊ PHÂN Opus** (không phải JSON base64).

Trước v1.8.0 app gửi:

```json
{ "header": { "name": "hello", "namespace": "ai.xiaoai.common" }, "payload": {…} }
```

Đó là định dạng của một giao thức khác. Máy chủ Xiaozhi không hiểu, **không mở
kênh audio**, và mọi thứ sau đó (gửi PCM base64, nghe TTS…) đều không có hiệu lực
— đúng hiện tượng “bấm nút không nói chuyện được”.

### Đã sửa ở v1.8.0
- Gói `hello` đúng đặc tả, chờ máy chủ trả hello mới coi là kết nối xong.
- `listen/start`, `listen/stop`, `listen/detect` (wake word), `abort` đúng dạng.
- Micro mã hoá **Opus 16 kHz mono, khung 60 ms** rồi gửi khung nhị phân.
- `stt` / `tts` / `llm` xử lý theo `type` như đặc tả.

## Lỗi 2: Android 5.1 không có bộ giải mã Opus

Máy chủ trả lời bằng **khung Opus trần**. API 22 không có codec Opus trong
MediaCodec, nên bản cũ đưa khung đó cho MediaPlayer → luôn lỗi → trợ lý “trả lời”
mà loa im.

### Đã sửa ở v1.8.0
Tích hợp **Concentus** (bản port Java thuần của libopus, giấy phép BSD — xem
`app/app/src/main/assets/licenses/concentus-LICENSE.txt`):

- giải mã từng khung Opus 24 kHz (hoặc tần số máy chủ khai báo) → PCM;
- ghi vào **một** AudioTrack `MODE_STREAM` duy nhất cho cả câu trả lời.

Vì sao phải dùng một AudioTrack: tầng audio HAL của R1 có thể **deadlock vĩnh
viễn** nếu tạo/giải phóng AudioTrack/AudioRecord quá nhanh — sau đó loa câm cho
tới khi rút điện. Vì vậy luồng phát được giữ liên tục và chỉ đóng khi hết câu
(1,5 giây không có khung mới).

## Lỗi 3: nút trên loa không được nối vào đâu

Trước v1.8.0 app **không hề đọc phím** — không có `onKeyDown`, không có
`MediaButtonReceiver`. Bấm nút trên đỉnh loa không có phản ứng gì.

### Đã sửa ở v1.8.0 — `VoiceTrigger`
- `MainActivity.onKeyDown` (app đang mở) và `MediaButtonReceiver` (app ở nền).
- Bấm nút = `abort` (nếu trợ lý đang nói) → `listen/start` → mở cổng mic →
  bấm lần nữa (hoặc hết 15 giây) → `listen/stop`.
- Bíp ngắn 880 Hz để biết đã vào chế độ nghe.

## Lỗi 4: mic R1 quá nhỏ + bị Unisound giành

- Tín hiệu mic R1 rất nhỏ; các dự án chạy trên máy thật phải dùng gain lớn
  (tới 30×). v1.8.0 thêm **gain mặc định 12×** (đổi được trên web).
- Trợ lý gốc `com.phicomm.speaker.device` (Unisound) chiếm micro mỗi lần khởi
  động lại → ẩn bằng `python3 tools/r1y.py` → `1` (bước 1 tự hỏi) hoặc
  `adb shell pm hide com.phicomm.speaker.device`.
- AudioFlinger của R1 có thể kẹt → **watchdog mic**: quá 6 giây không có dữ liệu
  thì tự tạo lại `AudioRecord` (có nghỉ 500 ms). Số lần khởi động lại hiện trên web.

---

## Tự kiểm tra trên web 8088 (không cần logcat)

Tab **Trợ lý** → mục **Nói chuyện với trợ lý**:

| Dòng hiện trên web | Nghĩa | Cần làm |
|---|---|---|
| `Kênh: chưa mở (chưa nhận hello của máy chủ)` | Sai URL/token, hoặc máy chủ từ chối giao thức | Kiểm tra URL `wss://…`, lấy lại mã kích hoạt |
| `Kết nối: không` | Chưa nối được WebSocket | Xem WiFi, thử `adb shell am force-stop com.bachdathan.r1y` rồi mở lại |
| `Kênh: OK` + `Khung gửi: 0` | Đã nối nhưng chưa bấm nói | Bấm **Nói** |
| `Kênh: OK`, bấm Nói, `Khung gửi` tăng | Micro OK, đang gửi lên | Chờ `Bạn nói:` xuất hiện |
| `Khung nhận: 0` dù máy chủ đã trả lời | Lỗi giải mã Opus | Xem `recvBad`, báo lại kèm số này |
| `Phím cuối: KEYCODE_xxx` | Đã nhận được nút trên loa | Nếu vẫn không nói: báo mã phím đó |
| `Mic tự khởi động lại: N lần` | Tầng âm thanh R1 bị kẹt | Rút điện cắm lại, rồi ẩn Unisound |
| Mic ~0 dù nói to | Mic bị chặn/chết | `adb shell pm grant com.bachdathan.r1y android.permission.RECORD_AUDIO`; ẩn Unisound |

### Dò mã phím của nút trên loa
Bấm nút trên loa rồi xem dòng **Phím cuối**. Nếu không hiện gì, thử giả lập:

```
python3 tools/r1y.py voice --ip 192.168.2.20 --action key --code 66
```

Các mã hay gặp: `66` ENTER, `23` DPAD_CENTER, `85` MEDIA_PLAY_PAUSE,
`79` HEADSETHOOK, `84` SEARCH, `231` VOICE_ASSIST. Mã nào làm trợ lý bíp và
vào chế độ nghe thì đó là mã của nút.

### Gõ tay để thử đường trả lời (không cần micro)
```
python3 tools/r1y.py voice --ip 192.168.2.20 --action text --text "xin chào"
```
Nếu cách này mà loa **nói được**, thì micro/mã phím là phần còn sai.
Nếu vẫn im → xem mục `docs/LOA_KHONG_RA_TIENG.md` (đường ra âm thanh).

## Máy chủ chính thức: `wss://api.tenclass.net/xiaozhi/v1/`

Đây là điểm sai đã sửa ở v1.9.0. Máy chủ thật của Xiaozhi **không phải**
`wss://xiaozhi.me/v1/ws` (đó là API web, không nhận WebSocket thiết bị).
Thiết bị lấy cấu hình từ OTA `https://api.tenclass.net/xiaozhi/ota/`, và bắt tay
bằng **4 header bắt buộc**:

```
Authorization: Bearer <token>
Protocol-Version: 1
Device-Id: <MAC của loa>
Client-Id: <UUID ổn định>
```

App cũ chỉ gửi `Authorization` → máy chủ đóng kết nối ngay ở bước bắt tay, đúng
hiện tượng “bấm nói mà AI không trả lời”. Từ v1.9.0 gửi đủ cả bốn.

### Đọc bảng kết luận ở tab Trợ lý
| Kết luận trên web | Nghĩa |
|---|---|
| `Đã nối WebSocket nhưng máy chủ CHƯA trả hello` | Sai URL/token/header |
| `Kênh OK — chưa gửi khung micro nào` | Chưa bấm **Nói** |
| `Đã gửi N khung nhưng máy chủ KHÔNG trả gì` | Mic gần như im lặng → tăng độ nhạy mic |
| `Máy chủ có trả lời (JSON: …) nhưng KHÔNG gửi khung tiếng` | Máy chủ trả định dạng khác Opus |
| `Đã nhận N khung tiếng` | Đường truyền OK → kiểm tra đường ra/bíp |

## “Bấm Lấy mã kích hoạt mà không thấy gì” (sửa ở v1.9.1)

Hai lỗi:
1. `OTAConfigManager` coi **mọi** mã HTTP khác 200 là lỗi. Nhưng theo đặc tả OTA
   của Xiaozhi, **202/203 nghĩa là “thiết bị cần kích hoạt”** và body vẫn là JSON
   hợp lệ (có `activation.code`). Vì thế mã kích hoạt không bao giờ hiện ra.
2. Bấm “Lấy mã” lần thứ hai khi lần trước còn đang chạy thì app `return` im lặng
   → trên web không có gì thay đổi.

Bây giờ trang Trợ lý hiện đủ: đang hỏi URL nào, **HTTP mấy**, lỗi gì, và nguyên
văn phần đầu câu trả lời của máy chủ. Gửi mình đúng mấy dòng đó là biết ngay
nguyên nhân (hết hạn chứng chỉ, chặn mạng, hay máy chủ đổi API).

## “OTA đã trả token mà app vẫn báo Kết nối: không” (sửa ở v1.9.2)

Khi in câu trả lời OTA lên trang 8088, thấy rõ:

```json
{"server_time":…,
 "websocket":{"url":"wss://api.tenclass.net/xiaozhi/v1/","token":"test-token"}}
```

HTTP **200** + có `websocket.token` nghĩa là **thiết bị đã được kích hoạt** cho
đúng MAC đó, và máy chủ đã cấp token. Nhưng app cũ:

1. **bỏ qua** token đó (`handleOTAResponse` chỉ lưu cờ “đã kích hoạt”);
2. khi nối thì lấy `getValidAccessToken()`; nếu trống, `DeviceActivator` **tự sinh**
   một “token” = `Base64(MAC:SN:thời_gian)` rồi gửi lên → máy chủ từ chối;
3. `connect()` **không hề hỏi OTA** trước khi bắt tay.

Từ v1.9.2, `connect()` hỏi OTA trước, dùng đúng `websocket.token` và
`websocket.url` máy chủ trả về; chỉ khi OTA không cấp token mới dùng token dự phòng
đã lưu; cuối cùng mới chuyển sang luồng kích hoạt. Có thêm nút **🔄 Kết nối lại AI**
ở tab Trợ lý.

> **Về câu hỏi “trên xiaozhi.me mình đã nhập mã OTA cho bản fw khác”:** máy chủ
> gắn token theo **MAC thiết bị**, không theo tên firmware. Nếu bạn đã nhập mã cho
> đúng MAC của loa này thì máy chủ coi là đã kích hoạt và cấp token — đó chính là
> cái `websocket.token` trong ảnh. Vì vậy **không cần lấy mã lại**; chỉ cần app
> dùng đúng token đó (đã sửa ở v1.9.2). Muốn kiểm tra: bấm **🔄 Kết nối lại AI**,
> rồi xem dòng `Kênh:` — phải thành `OK`.

> Ghi chú bảo mật: từ v1.9.2 trang 8088 **che** token/mật khẩu trong câu trả lời
> OTA (`test-t***`). Trang này không có mật khẩu, ai vào được WiFi nhà cũng mở được.
