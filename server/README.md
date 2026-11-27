# R1-Y server — máy chủ nhạc cho loa Lily R1

Máy chủ nhỏ (chỉ dùng thư viện chuẩn Python + `yt-dlp`) chạy trên PC Debian/Ubuntu
trong cùng mạng LAN với loa Phicomm R1. Loa gọi về máy chủ này để lấy tiếng YouTube
với độ chính xác cao, kế thừa từ [Tube](https://github.com/BachDaThan/Tube) và
[LiteTube](https://github.com/BachDaThan/LiteTube).

## Chạy nhanh

```sh
cd server
cp r1y.env.example r1y.env   # sửa PORT nếu cần
python3 r1y_server.py        # tự tải yt-dlp nếu thiếu
```

Mở `http://IP-MAY:8091/health` để kiểm tra.

## Loa dùng máy chủ này thế nào

Trên loa, mở `http://IP-LOA:8088` → mục **Nhạc** → **Bộ lấy tiếng YouTube**,
dán mảng JSON (thử đúng thứ tự):

```json
[{"type":"custom","url":"http://192.168.2.130:8091"}]
```

Hoặc dùng luôn kiểu Invidious/Piped (máy chủ hỗ trợ cả ba):

```json
[{"type":"invidious","url":"http://192.168.2.130:8091"}]
[{"type":"piped","url":"http://192.168.2.130:8091"}]
```

Không cần máy chủ riêng thì **để `[]`** — loa tự dò máy chủ YouTube công cộng
đang sống. Máy chủ R1-Y này chỉ dùng khi bạn muốn ổn định hơn / lấy được video
giới hạn tuổi (nhờ cookie) / không phụ thuộc máy chủ công cộng.

## Endpoint

| Đường dẫn | Kiểu | Mô tả |
|---|---|---|
| `GET /api/v1/search?q=` | Invidious (Tube) | Tìm kiếm bằng `ytsearch` |
| `GET /api/v1/videos/<id>` | Invidious (Tube) | Thông tin + luồng audio |
| `GET /search?q=` | Piped (LiteTube) | Tìm kiếm kiểu Piped |
| `GET /streams/<id>` | Piped (LiteTube) | `audioStreams` xếp bitrate cao nhất |
| `GET /api/search?q=` | Custom (Tube) | Tìm kiếm custom |
| `GET /stream/<id>` | Custom | Proxy luồng audio (có Range) |
| `GET /health` | — | Trạng thái máy chủ + phiên bản yt-dlp |

## Bảo mật

Máy chủ tự chặn IP ngoài mạng nội bộ/Tailscale (trừ khi `ALLOW_PUBLIC=1`).
Đừng port-forward cổng này trên router.
