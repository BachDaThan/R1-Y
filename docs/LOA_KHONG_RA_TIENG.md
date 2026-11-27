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
