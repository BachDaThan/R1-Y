# Web báo “đang phát” mà loa im — tìm ra lỗi trong 1 phút

Bản v1.9.0 thêm nút **🔍 Kiểm tra vì sao im** ở tab **Nhạc** (và kết luận tự động
ở tab **Trợ lý**). Tài liệu này giải thích nó kiểm tra cái gì, và cách sửa từng ca.

## Vì sao “báo đang phát” mà vẫn im

`MediaPlayer` của Android chỉ báo “đang phát” khi nó đã **nhận** được dữ liệu —
nó không biết loa có kêu hay không. Có ba tầng riêng biệt có thể hỏng:

| Tầng | Triệu chứng | Kiểm tra |
|---|---|---|
| **A. Link / nguồn** | MediaPlayer lỗi ngay, hoặc “phát” 0 giây | `/api/audio/probe` (nút Kiểm tra) |
| **B. Giải mã** | prepare xong, vị trí (position) đứng ở 0 | `/api/audio/playing` |
| **C. Đường ra / âm lượng** | position nhích lên mà tai không nghe | bíp 880 Hz |

## A. Link — ba kiểu hỏng hay gặp

Nút Kiểm tra mở link đúng cách một trình phát thật mở: gắn
`User-Agent` giả Chrome, kèm `Referer: https://www.youtube.com/`, đọc 16 byte đầu.

| Kết quả trên web | Nghĩa | Cách sửa |
|---|---|---|
| `HTTP 403` | Link hết hạn **hoặc khoá theo IP** — link do máy chủ công cộng sinh ra thường chỉ dùng được từ chính máy chủ đó | Đổi máy chủ YouTube (Tải lại → Lưu) hoặc tìm bài khác |
| `contentType: text/html` | Nhận về **trang web** (trang lỗi / bắt đăng nhập), không phải âm thanh | Đổi máy chủ |
| `magic: webm/matroska` | WebM/Opus — Android 5.1 **không có** bộ giải mã | Đổi chất lượng sang **Tương thích** (AAC/m4a) |
| `magic: mp4/m4a` | Định dạng R1 giải mã được | Nếu vẫn im → sang mục C |
| `magic: mpeg-ts` | MPEG-TS — R1 thường không phát được | Đổi sang Tương thích |

Từ v1.9.0 app **tải trọn bài về máy** (tối đa 60 MB) rồi phát từ file. Việc này
vừa gắn được header, vừa biết ngay khi nguồn trả trang lỗi, vừa phát mượt hơn
trên R1 và lặp lại bài không phải tải lại. Tắt được ở `/api/settings` (`cache_audio`).

## B. Giải mã
`positionMs` = 0 sau khi đã prepare = bộ phát chưa chạy được khung nào → gần như
luôn là codec (Opus/WebM/MPEG-TS) hoặc file hỏng. Xem `lastError` (`what=1`
= MEDIA_ERROR_UNKNOWN → codec).

## C. Đường ra / âm lượng
`positionMs` tăng đều mà im → thử **Phát thử tiếng bíp**. Bíp kêu nghĩa là đường
ra OK. Trên R1, `STREAM_SYSTEM` và `STREAM_MUSIC` là **hai mức âm lượng riêng** —
v1.9.0 nâng cả hai (xem `docs/LOA_KHONG_RA_TIENG.md`).

> Lưu ý riêng của R1 (ghi nhận từ các dự án firmware chạy trên máy thật): các app
> thay thế firmware gốc đều phát qua **`AudioTrack` + Opus** chứ không dùng
> `MediaPlayer` cho tiếng của trợ lý. R1-Y dùng `AudioTrack` cho TTS/bíp và
> `MediaPlayer` cho nhạc YouTube (định dạng AAC/MP4) — hai đường độc lập, nên bíp
> kêu mà nhạc im là chuyện bình thường và khoanh vùng được lỗi.

## Dùng bằng dòng lệnh

```
python3 tools/r1y.py silent --ip 192.168.2.20
```

In ra kết luận + nội dung link + lời khuyên, giống nút trên web.
