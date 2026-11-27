# Bộ lấy tiếng YouTube — hướng dẫn cấu hình

Giao diện: mở `http://IP_LOA:8088` → **Nhạc** → **Bộ lấy tiếng YouTube**.

## Cách dùng (2 chế độ)

### Chế độ 1 — `[]` (mặc định): dùng máy chủ công cộng

```json
[]
```

Loa tự dò **máy chủ Piped/Invidious công cộng đang sống** (lấy từ danh sách chính thức
`piped-instances.kavin.rocks` và `api.invidious.io`, có kiểm tra sống/chết, giữ tối đa
3 máy chủ mỗi loại, cache 6 giờ), rồi tìm kiếm + lấy âm thanh y như Tube/LiteTube.
Không cần cài gì trên PC. Bấm **Tự dò lại máy chủ công cộng** để dò mới ngay.

### Tự chuyển máy chủ khi lỗi (failover)

Nếu đang phát mà máy chủ trả lỗi (403/hết hạn link, mất mạng...), loa **tự lấy lại
âm thanh từ nguồn kế tiếp** và phát tiếp — giống Tube/LiteTube đổi player client khi lỗi.
Không cần bấm gì.

### Chế độ 2 — máy chủ riêng của bạn

Khi bạn có máy chủ riêng (R1-Y server, Tube, LiteTube bridge...), thay `[]` bằng:

```json
[{"type":"custom","url":"http://192.168.2.130:8091"}]
```

## Thứ tự thử

1. Các resolver trong mảng (đúng thứ tự bạn viết).
2. `youtube_autodiscover = true` (mặc định bật) → máy chủ công cộng tự dò
   (kể cả khi mảng có cấu hình nhưng tất cả đều lỗi).
3. Máy chủ nhạc cuối cùng (`music_server`).

## Định dạng mảng

```json
[{"type":"piped|invidious|custom","url":"..."}]
```

- `type`: `piped`, `invidious` hoặc `custom`
- `url`: địa chỉ máy chủ (không cần dấu `/` cuối, có `https://` hoặc `http://`)
- Thử **đúng thứ tự** trong mảng. Lỗi thì chuyển sang phần tử tiếp theo.
- JSON sai → loa bỏ qua và coi như `[]` (không treo loa).

## Ví dụ

### Dùng R1-Y server trong nhà

```json
[{"type":"custom","url":"http://192.168.2.130:8091"}]
```

### Dùng Tube (BachDaThan/Tube, cổng 8090)

```json
[{"type":"invidious","url":"http://192.168.2.130:8090"}]
```

### Dùng LiteTube bridge (cổng 8081)

```json
[{"type":"piped","url":"http://192.168.2.130:8081"}]
```

### Nhiều máy chủ dự phòng

```json
[{"type":"custom","url":"http://192.168.2.130:8091"},
 {"type":"piped","url":"http://192.168.2.130:8081"},
 {"type":"invidious","url":"https://inv.nadeko.net"}]
```

## API của loa (cho Tube/LiteTube gọi)

| Đường dẫn | Mô tả |
|---|---|
| `GET /api/youtube/resolvers` | Đọc mảng JSON hiện tại |
| `POST /api/youtube/resolvers` | Lưu mảng mới (nhận mảng, `{"resolvers":[...]}`, `{"json":"..."}`, `{"value":[...]}`, `{"youtube_resolvers":...}`) |
| `GET /api/youtube/discover` | Xem máy chủ công cộng đang dùng (dò nếu cache hết hạn) |
| `POST /api/youtube/discover` | Xoá cache + dò lại ngay |
| `GET /api/settings` | Toàn bộ cài đặt |
| `POST /api/settings` | Lưu cài đặt |
| `GET /api/youtube/search?q=` | Tìm kiếm YouTube |
| `GET /api/media/play?query=` | Tìm + lấy tiếng + phát (Tube gọi) |
| `POST /api/media/play` | `{"query":"...","source":"youtube"}` |
| `POST /api/media/play` | `{"query":"...","direct":true}` — đưa từ khóa cho loa tự tìm + tự failover |

## Dán link là phát

Ô **Phát nhạc** ở tab Nhạc nhận:

- Link đầy đủ: `https://www.youtube.com/watch?v=...`, `youtu.be/...`,
  `youtube.com/shorts/...`, `music.youtube.com/watch?v=...`
- Hoặc chỉ **video ID** (11 ký tự)
- Hoặc từ khóa bình thường (`sơn tùng m-tp chạy ngay đi`)

Không phải link thì loa tự tìm, lấy kết quả đầu rồi phát.

## Chất lượng âm thanh — thang tự hạ cấp

Loa thử lần lượt từng bậc, bậc nào lỗi thì **tự hạ xuống bậc dưới** (không báo lỗi):

| Bậc | Loại | Ghi chú |
|---|---|---|
| 1 | Opus ≥128kbps | Tốt nhất, YouTube thường 160kbps |
| 2 | Opus <128kbps | Khi mạng yếu / server giới hạn |
| 3 | AAC/m4a ≥128kbps | Tương thích cao |
| 4 | AAC/m4a <128kbps | |
| 5 | Codec khác (vorbis, mp3) | Server lạ |
| 6 | **Âm thanh từ luồng video** | Cứu cánh khi server chỉ trả video có tiếng |

Vì có bậc 6, gần như **mọi server công cộng đều lấy được tiếng**, kể cả video thường.
`AUDIO_FORMAT=high` (mặc định) ưu tiên Opus trước; `compat` đảo AAC lên trước.

Đổi ở tab **Nhạc** → *Chất lượng âm thanh*, hoặc:
```sh
python3 tools/r1y.py config --ip <IP_LOA> --audio-quality high
```

## Xử lý sự cố

| Triệu chứng | Cách xử lý |
|---|---|
| Loa báo "Không lấy được âm thanh" | Bấm **Tự dò lại máy chủ công cộng**, thử lại; nếu vẫn lỗi thì cấu hình máy chủ riêng |
| Tìm không ra bài | Thử lại (máy chủ công cộng đôi khi quá tải), hoặc để `[]` và bấm dò lại |
| Video giới hạn tuổi / riêng tư | Máy chủ công cộng thường không lấy được — cần máy chủ riêng (R1-Y server với `COOKIES_FILE`) |
| Loa không gọi máy chủ riêng | Kiểm tra cùng WiFi, firewall cổng tương ứng, và JSON đúng định dạng |
| Muốn chắc chắn ổn định | Chạy `server/r1y_server.py` trên PC rồi dùng chế độ 2 |
