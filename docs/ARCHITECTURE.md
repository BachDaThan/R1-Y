# Kiến trúc R1-Y

```
┌─────────────────────────────────────────────────────────────┐
│  Loa Phicomm R1 (Android 5.1 / API 22, RK3229, armeabi-v7a) │
│                                                             │
│  app/  (package com.bachdathan.r1y)                         │
│  ├─ service/   Xiaozhi, Voice, Audio, LED, HTTP(8088)      │
│  ├─ youtube/   ResolverManager, SearchManager              │
│  ├─ update/    UpdateManager (GitHub BachDaThan/R1-Y)      │
│  ├─ web/       WebServer (giao diện như ảnh + REST API)    │
│  └─ config/    XiaozhiConfig, R1YConfig                    │
└──────────────┬──────────────────────────────┬───────────────┘
               │ HTTP 8088                    │ WebSocket
               │ (giao diện + API)            │ (xiaozhi.me)
               ▼                              ▼
     Điện thoại / PC (trình duyệt)      Xiaozhi Cloud
               │
               │ HTTP 8091 (resolver)
               ▼
┌─────────────────────────────────────────────────────────────┐
│  server/r1y_server.py  (PC Debian/Ubuntu, Python stdlib)    │
│  - Invidious: /api/v1/search, /api/v1/videos/<id>  (Tube)   │
│  - Piped:     /search, /streams/<id>               (LiteTube)│
│  - Custom:    /api/search, /stream/<id>            (Tube)    │
│  - yt-dlp:    ytsearch/scsearch, -J, luồng audio            │
└─────────────────────────────────────────────────────────────┘
```

## Luồng lấy tiếng YouTube (đúng như ảnh)

1. Người dùng mở `http://IP_LOA:8088` → **Nhạc** → **Bộ lấy tiếng YouTube**.
2. Ô JSON chứa mảng resolver, ví dụ:
   ```json
   [{"type":"piped","url":"http://192.168.2.130:8081"},
    {"type":"invidious","url":"http://192.168.2.130:8091"}]
   ```
2b. Để `[]` (mặc định) → loa tự dò máy chủ Piped/Invidious công cộng đang sống
   (danh sách chính thức + kiểm tra sống/chết + cache 6 giờ), rồi thử như dưới.
3. Loa thử **đúng thứ tự** trong mảng:
   - `piped` → `GET {url}/streams/{id}` → `audioStreams[0].url`
   - `invidious` → `GET {url}/api/v1/videos/{id}` → `adaptiveFormats` (audio)
   - `custom` → thử Piped trước, lỗi thì thử Invidious
4. Mảng rỗng (`[]`) hoặc mọi resolver đều lỗi + `youtube_autodiscover = true`
   → loa dùng máy chủ công cộng tự dò (cache 6 giờ, `GET/POST /api/youtube/discover`).
5. Cuối cùng là **máy chủ nhạc** đã cấu hình (`music_server`).

## Tìm kiếm chính xác (Tube + LiteTube)

- **Tube**: `ytsearch`/`scsearch` qua yt-dlp, nhận diện link YouTube
  (`watch?v=`, `youtu.be/`, `shorts/`), resolver giả kiểu Invidious.
- **LiteTube**: Piped API (`/search`, `/streams/<id>`), luồng âm thanh do
  yt-dlp bóc tách, xếp bitrate cao nhất (Opus 251 / AAC 140), proxy Range.

Loa (`SearchManager`) thử từng resolver theo thứ tự, lỗi thì chuyển tiếp theo —
giống cách Tube/LiteTube tự đổi player client khi lỗi.

## Phát WiFi từ loa (hotspot)

`hardware/HotspotManager.java`:

1. Thử `WifiManager.setWifiApEnabled()` bằng reflection (API ≤ 25, ROM Android TV).
2. Thử `setWifiApConfiguration()` (một số ROM khác).
3. Fallback có root: `su -c "cmd wifi start-softap <SSID> wpa2 <pass>"`.
4. Bật tự động khi khởi động nếu `hotspot_auto_start = true` (BootReceiver).

Điện thoại nối WiFi `R1-Y` → mở `http://192.168.43.1:8088`.
Web: tab **Nhà** (`GET/POST /api/hotspot`).

## Máy chủ AI (trợ lý)

`config/R1YConfig` giữ preset: `xiaozhi` (mặc định, `wss://xiaozhi.me/v1/ws`),
`xiaozhi_self` (esp32-server), `custom` (URL riêng + token).
`XiaozhiConnectionService` lấy URL động từ `getAiUrl()`. Nếu chọn server tùy chỉnh
và có token dự phòng thì kết nối luôn, **không cần kích hoạt** qua xiaozhi.me.
Web: tab **Trợ lý** (`GET /api/ai/servers`).

## Âm thanh chất lượng cao

`audio_quality = high` (mặc định) → `ResolverManager` chọn Opus bitrate cao nhất
(`pickPipedAudio`, Invidious sort theo `bitrate`); `compat` → AAC/m4a.
Máy chủ PC: `AUDIO_FORMAT=high|compat`.

## Cập nhật firmware

- Nguồn duy nhất: **GitHub Releases của `BachDaThan/R1-Y`** (repo này).
- Trên loa: `http://IP_LOA:8088` → **Thêm** → **Cập nhật firmware**
  → **Kiểm tra** / **Cập nhật ngay**.
- `UpdateManager` gọi `GET /repos/BachDaThan/R1-Y/releases/latest`,
  so sánh tag với `versionName`, tải APK, cài qua root `pm install -r`
  (nếu có) hoặc trình cài đặt hệ thống.
- Trên PC: `./scripts/update.sh <IP_LOA>` làm tương tự qua ADB WiFi.

## Tool cài đặt A→Z (PC)

`tools/r1y.py` (Python 3, stdlib, chạy Win/macOS/Linux) lo trọn quy trình:

| Bước | Việc |
|---|---|
| 1 | Tìm adb (tự tải Platform Tools nếu thiếu) |
| 2 | `adb connect <IP>:5555` hoặc tự quét LAN tìm cổng 5555 |
| 3 | Nhận diện loa (Phicomm R1, API 22) |
| 4 | `pm hide` app Phicomm gốc (giữ để khôi phục) |
| 5 | Tải APK từ GitHub Releases của repo này |
| 6 | `pm install -r` (fallback `adb install`) |
| 7 | Cấp quyền, kiểm tra root (LED), bật BootReceiver + whitelist pin |
| 8 | Khởi động app, chờ `http://IP_LOA:8088/api/status` |
| 9 | Cấu hình resolver YouTube qua `POST /api/settings` |

Cùng tool đó có mục **cập nhật** (so `versionName` với tag GitHub) và **gỡ/khôi phục**.

## Cài đặt qua cùng WiFi

Loa và máy tính cùng WiFi, dùng ADB over WiFi:

```sh
adb connect <IP_LOA>:5555
./scripts/install.sh <IP_LOA>
```

Xem [HUONG_DAN_CAI_DAT.md](../HUONG_DAN_CAI_DAT.md).

## CI / Bot

- `build.yml`: build APK (debug + release), tạo Release khi push tag `v*`.
- `test.yml`: lint, unit test, kiểm tra cú pháp Python/shell, quét secret.
- `bot.yml`: bot kiểm tra PR (tiêu đề, checklist, nhãn), phân loại issue,
  tự tạo ghi chú release.
- `dependabot.yml`: tự mở PR cập nhật phụ thuộc mỗi tuần.
