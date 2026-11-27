# Loa không ra tiếng / trợ lý không nói — kiểm tra theo thứ tự

Từ **v1.7.4** bạn không cần cắm cáp hay đọc logcat: mở `http://IP_LOA:8088`
→ **Nhạc** → mục **🔊 Kiểm tra loa có ra tiếng không**, hoặc chạy
`python3 tools/r1y.py audio --ip IP_LOA`.

## 1. Bấm “Phát thử tiếng bíp” (không cần mạng)

Tiếng bíp do app tự sinh, không qua YouTube, không qua mạng.

| Kết quả | Nghĩa là | Làm gì |
|---|---|---|
| Loa **kêu** | Loa, âm lượng, đường ra âm thanh đều ổn | Lỗi nằm ở **link nhạc / máy chủ**, xem mục 2 |
| Loa **im** | Vấn đề ở âm lượng hoặc phần cứng | Xem mục 1b |

### 1b. Loa im cả khi bíp

- Dòng `Âm lượng: 0/15` → **âm lượng nhạc đang bằng 0**. Bấm **Bật âm lượng**
  (hoặc bấm nút tăng âm lượng trên loa / remote). Đây là nguyên nhân phổ biến nhất.
- `đang nhận tai nghe` / `đang phát qua Bluetooth` → âm thanh bị dồn sang đường khác.
- Cả hai đều đúng mà vẫn im → loa/mạch khuếch đại có vấn đề (phần cứng).

## 2. Bíp kêu nhưng phát nhạc không ra tiếng

- Xem dòng **“Đang phát: … (nguồn)”** có hiện không → tức là **đã lấy được link**
  (giai đoạn mạng OK), lỗi ở bước phát.
- Bấm lại **Phát** rồi xem `/api/audio/status`: `musicActive` = `false` nghĩa là
  MediaPlayer không chạy được luồng đó.
- Đổi **Chất lượng âm thanh** sang **Tương thích (AAC/m4a)** — Android 5.1 đọc
  AAC tốt hơn Opus/WebM.
- Link `invidious` công cộng hay trả 403 sau vài phút (YouTube chặn IP máy chủ) →
  bấm **Tự dò lại máy chủ công cộng** rồi phát lại.

## 3. Trợ lý không trả lời

Hai đầu, phải kiểm tra cả hai:

**a) Loa có nghe không** — mục **🎙 Kiểm tra mic** (tab Trợ lý): nói to gần loa rồi bấm,
số phải nhảy lên.

- Luôn `~0` → cấp quyền ghi âm:
  `adb shell pm grant com.bachdathan.r1y android.permission.RECORD_AUDIO`
- Có quyền mà vẫn ~0 → mic hỏng/bị ROM chặn.

**b) Loa có nói không** — server xiaozhi gửi tiếng trả lời dưới dạng **opus**. Android 5.1
không có bộ giải mã opus. Từ v1.7.4 app nhận được khung nhị phân và:
- nhận ra Ogg/MP3/WAV/AAC → phát luôn;
- opus trần → ghi rõ trong log `TTS binary: … nhận dạng: null` (không im lặng nữa).
  Khi đó chọn máy chủ AI tự dựng trả **PCM/mp3** (tab **Trợ lý** → máy chủ AI).

> Bản này **chưa có** bộ phát hiện wake word thật: nó dùng ngưỡng năng lượng âm thanh,
> nên tiếng ồn to cũng kích hoạt, còn nói nhỏ thì không. Đây là hạn chế đã biết,
> không phải lỗi cài đặt.

## 4. “Có phải firmware không giải mã được stream máy chủ gửi về?”

Đúng một phần, và đó là điểm quan trọng nhất:

- **Android 5.1 (API 22) không bắt buộc có bộ giải mã Opus/WebM.** ROM của loa R1
  cũng không kèm. Trước v1.7.5 app xếp **Opus lên bậc 1** của thang chất lượng →
  MediaPlayer báo `-1010 UNSUPPORTED`, app mới hạ xuống AAC. Trang web vẫn kịp hiện
  “Đang phát … (nguồn)” nên rất dễ tưởng là “web nói đang phát mà loa không giải mã”.
- Từ **v1.7.5**: app đọc danh sách codec của máy (`MediaCodecList`) ngay khi lấy link,
  **nếu không có Opus thì tự chọn AAC/m4a trước** thay vì thử Opus rồi lỗi.
- Muốn biết chắc: `http://IP_LOA:8088` → **Nhạc** → xem dòng `Codec:` (hoặc
  `python3 tools/r1y.py audio --ip IP_LOA`) → in ra đúng những codec loa đọc được.

Nếu máy chủ công cộng **chỉ** trả Opus (một số instance cấu hình vậy):
- đổi **Chất lượng âm thanh → Tương thích (AAC/m4a)**, hoặc
- bấm **Tự dò lại máy chủ công cộng** để chọn instance có AAC, hoặc
- dùng máy chủ nhạc trong nhà (`server/r1y_server.py`) — nó tự bóc cả AAC và Opus.

## 5. Điều khiển phát nhạc (từ v1.7.5)

Tab **Nhạc** có: **Tạm dừng** · **Phát tiếp** · **Ngưng hẳn** · **Lặp lại: BẬT/TẮT**
(kèm dòng “Đang phát: <tên bài> | âm lượng”). Trong terminal:

```sh
python3 tools/r1y.py player --ip IP_LOA                        # menu điều khiển
python3 tools/r1y.py player --ip IP_LOA --action pause
python3 tools/r1y.py player --ip IP_LOA --action stop
python3 tools/r1y.py player --ip IP_LOA --action repeat-on
```

- **Tạm dừng** giữ nguyên bài, **Phát tiếp** chạy tiếp đúng chỗ dừng.
- **Ngưng hẳn** dừng và quên bài (bấm **Phát tiếp** sau đó sẽ phát lại từ đầu).
- **Lặp lại** phát lại đúng bài đang phát khi hết (như YouTube “lặp 1 bài”).

## 6. QUAN TRỌNG: R1 không phát STREAM_MUSIC ra loa ngoài

Đây là nguyên nhân gốc rễ hay bị bỏ qua nhất. Loa Phicomm R1 có cấu hình route
âm thanh bất thường: **`STREAM_MUSIC` không đi ra loa ngoài**, trong khi mọi thứ
vẫn báo "đang phát". Các dự án firmware R1 đã kiểm chứng trên phần cứng thật và
đều chuyển sang `STREAM_SYSTEM`.

Từ **v1.7.6** R1-Y mặc định phát bằng **STREAM_SYSTEM**:

- Tab **Nhạc** → ô **“Đường ra: Hệ thống (STREAM_SYSTEM)”** (mặc định). Đổi sang
  `music` / `ring` nếu ROM của bạn khác, rồi bấm Phát lại.
- Khối chẩn đoán hiện **đường ra đang dùng + âm lượng của đúng stream đó**
  (âm lượng STREAM_SYSTEM và STREAM_MUSIC trên R1 là hai mức riêng biệt).
- `python3 tools/r1y.py audio --ip IP_LOA` cũng in ra đường ra + âm lượng.

Nếu tiếng bíp **vẫn im** dù đã ở `system` và âm lượng > 0: thử lần lượt `music`, `ring`.
Ba đường ra này là toàn bộ những gì Android 5.1 trên R1 có.
