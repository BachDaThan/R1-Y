# R1-Y — Firmware sạch cho loa Phicomm R1

Biến loa **Phicomm R1** (Android 5.1 / API 22, RK3229) thành trợ lý giọng nói
Xiaozhi + loa phát nhạc YouTube. Viết lại sạch 100% từ
[r1-xiaozhi](https://github.com/xuan2261/r1-xiaozhi), vẫn chạy tốt trên phần cứng
thật, không còn hàng chục file ghi chú thừa.

- 🎵 **Bộ lấy tiếng YouTube** đúng như ảnh: mảng JSON
  `[{"type":"piped|invidious|custom","url":"..."}]`, thử đúng thứ tự.
  **Mặc định để `[]`** → loa tự dò máy chủ Piped/Invidious công cộng đang sống
  (danh sách chính thức, kiểm tra + cache 6 giờ) để tìm kiếm & lấy âm thanh
  giống Tube/LiteTube. Chỉ khi có máy chủ riêng mới thay `[]` bằng địa chỉ đó.
- 🔍 **Tìm kiếm chính xác** kế thừa từ [Tube](https://github.com/BachDaThan/Tube)
  (yt-dlp `ytsearch`, nhận diện link, resolver Invidious) và
  [LiteTube](https://github.com/BachDaThan/LiteTube) (Piped API, luồng audio
  xếp bitrate cao nhất).
- 📶 **Cài qua cùng WiFi** bằng ADB (`scripts/install.sh`), không cần USB.
- 📡 **Chế độ cài đặt WiFi** (giữ nút tròn trên loa): loa phát WiFi `R1-Y` →
  điện thoại nối vào → **quét + chọn WiFi nhà + nhập mật khẩu** → loa vào WiFi nhà
  → giờ mới cài firmware qua mạng nội bộ.
- 📱 **Tool trên điện thoại (Termux)**: `bash tools/r1y_termux.sh` — tự cài thư viện
  thiếu, lỗi được dịch thành gợi ý dễ hiểu, không cần mò.
- 🤖 **Chọn máy chủ AI**: mặc định `xiaozhi.me`, đổi được sang Xiaozhi tự dựng
  (esp32-server) hoặc URL riêng + token trong tab **Trợ lý**.
- 🎧 **Dán link YouTube là phát** — dán link vào ô **Phát nhạc**, loa tự lấy tiếng.
- 🔊 **Âm thanh chất lượng cao + tự hạ cấp**: thang 6 bậc
  *Opus cao → Opus thấp → AAC cao → AAC thấp → codec khác → âm thanh từ video*.
  Bậc nào lỗi thì tự hạ xuống bậc dưới, nên máy chủ công cộng nào cũng lấy được tiếng.
- 🔄 **Nút Cập nhật trong giao diện** (`http://IP_LOA:8088` → Thêm),
  nguồn duy nhất là **GitHub Releases của repo này** (`BachDaThan/R1-Y`).
- 🤖 **Bot GitHub kiểm tra đàng hoàng**: CI build APK + tạo Release,
  lint/test/quét secret, bot PR/issue, Dependabot.

## Cài nhanh — tool A→Z (khuyên dùng)

Chỉ cần **Python 3**, không cần Android Studio, không cần tải APK thủ công:

```sh
python3 tools/r1y.py          # Windows: bấm đúp tools\r1y.bat
```

Chọn **1 – Cài mới từ đầu (A→Z)**: tool tự tìm loa trong WiFi, tải firmware từ
GitHub Releases của repo này, cài, cấp quyền, bật tự khởi động, cấu hình và mở
giao diện. Mục **2** là cập nhật firmware, mục **6** là gỡ/khôi phục app gốc.

Chạy không hỏi gì:
```sh
python3 tools/r1y.py install --ip 192.168.2.20 --yes
python3 tools/r1y.py update  --ip 192.168.2.20 --yes
```

Xem [tools/README.md](tools/README.md).

## Cài bằng tay (script cũ / ADB)

```sh
adb connect <IP_LOA>:5555
./scripts/install.sh <IP_LOA>
# cập nhật:
./scripts/update.sh <IP_LOA>
```

Chi tiết: [HUONG_DAN_CAI_DAT.md](HUONG_DAN_CAI_DAT.md)

## Cấu trúc

```
R1-Y/
├── app/                    # Firmware Android (package com.bachdathan.r1y)
│   ├── hardware/           # LED + HotspotManager (phát WiFi từ loa)
│   ├── service/            # Xiaozhi, Voice, Audio, LED, WebServer (8088)
│   ├── youtube/            # ResolverManager, SearchManager
│   ├── update/             # UpdateManager (GitHub BachDaThan/R1-Y)
│   ├── web/                # Giao diện R1-Y + REST API
│   └── config/             # XiaozhiConfig, R1YConfig
├── server/                 # Máy chủ nhạc cho PC (Tube/LiteTube style)
│   └── r1y_server.py       # Invidious + Piped + custom, yt-dlp
├── tools/                  # r1y.py — tool A→Z (cài, cập nhật, cấu hình, hotspot, gỡ)
├── scripts/                # install.sh/.bat, update.sh (ADB WiFi)
├── docs/                   # Kiến trúc, hướng dẫn resolver
└── .github/                # CI build/test, bot PR/issue, Dependabot
```

## Bộ lấy tiếng YouTube

Mở `http://IP_LOA:8088` → **Nhạc** → **Bộ lấy tiếng YouTube**, dán:

```json
[{"type":"custom","url":"http://192.168.2.130:8091"}]
```

Để `[]` (mặc định) là xong — loa tự dò máy chủ công cộng. Muốn dùng máy chủ riêng
thì thay bằng `[{"type":"custom","url":"http://IP_MAY:8091"}]`.

Thứ tự thử: mảng của bạn → máy chủ công cộng tự dò (`piped`: `/streams/{id}`,
`invidious`: `/api/v1/videos/{id}`) → máy chủ nhạc cuối cùng.
Xem [docs/YOUTUBE_RESOLVER.md](docs/YOUTUBE_RESOLVER.md).

## Điều khiển loa qua web

Mở: **`http://<IP_LOA>:8088`** — cổng cố định **8088**, không đổi.

- Khi loa ở chế độ cài đặt (phát WiFi): **`http://192.168.43.1:8088`**
- Khi loa đã vào WiFi nhà: `http://<IP_LOA>:8088` (IP xem ở tab **Trạng thái**,
  hoặc tool in ra sau khi cài: `python3 tools/r1y.py status --ip <IP>`)
- Xem nhanh IP: `adb shell ip -f inet addr show wlan0` hoặc `python3 tools/r1y.py`

## Khởi động lại sau khi cài

Cài/cập nhật xong, tool **hỏi** có khởi động lại loa không (mặc định **Có**):

| Cách | Hành vi |
|---|---|
| `python3 tools/r1y.py install --ip <IP>` | hỏi → mặc định khởi động lại loa, chờ loa lên lại rồi in IP mới |
| `... --reboot` | khởi động lại luôn, không hỏi |
| `... --no-reboot` | chỉ khởi động lại app (nhanh, ~10 giây) |
| `./scripts/install.sh <IP>` | hỏi (Enter = Có); bỏ qua bằng `REBOOT=no ./scripts/install.sh` |
| Trên web | tab **Trạng thái** → *Khởi động lại app* hoặc *Khởi động lại loa* |

*Khởi động lại app* = tắt/mở lại R1-Y (~10s, web lên lại ngay).
*Khởi động lại loa* = reboot cả máy (~40-60s, **cần root**; không root thì tool
tự hạ xuống chỉ khởi động lại app).

## Tính năng trên loa (mở `http://<IP_LOA>:8088`)

| Tab | Việc |
|---|---|
| **Trạng thái** | Phiên bản, chế độ YouTube, máy chủ AI, chất lượng âm thanh |
| **Trợ lý** | Chọn máy chủ AI: `xiaozhi.me` (mặc định) / Xiaozhi tự dựng / URL riêng + token |
| **Nhạc** | Dán link YouTube hoặc gõ tên bài → **Phát**; chọn chất lượng (Opus/AAC); bộ lấy tiếng YouTube |
| **Nhà** | **Phát WiFi từ loa** (SSID/mật khẩu), tự bật khi khởi động |
| **Thêm** | Cập nhật firmware từ GitHub Releases |

### Chế độ cài đặt WiFi (giữ nút tròn)

1. **Giữ nút tròn trên loa** (hoặc bật ở tab **Nhà**, hoặc `tools/r1y.py hotspot`).
2. Loa phát WiFi tên **R1-Y** (mật khẩu mặc định `r1y12345678`).
3. Điện thoại nối WiFi `R1-Y` → mở `http://192.168.43.1:8088`.
4. Mục **Cài đặt WiFi** → **Quét WiFi** → chọn WiFi nhà → nhập mật khẩu → **Kết nối**.
5. Loa vào WiFi nhà, tự tắt hotspot, trả về IP dải nhà → lúc này mới cài firmware qua LAN.
   ```sh
   python3 tools/r1y.py wifi --ip 192.168.43.1     # làm cả bước 4-5 từ terminal
   python3 tools/r1y.py install --ip <IP_MOI>      # cài firmware qua mạng nội bộ
   ```

### Bật phát WiFi thủ công

Bật ở tab **Nhà** hoặc:
```sh
python3 tools/r1y.py hotspot --ip <IP_LOA> --ssid R1-Y --password r1y12345678
```
Điện thoại nối WiFi `R1-Y` rồi mở `http://192.168.43.1:8088`.
Loa R1 chạy Android 5.1 nên cần root/quyền hệ thống; nếu ROM chặn, tool tự thử
lệnh root `cmd wifi start-softap` và in hướng dẫn.

### Chọn máy chủ AI

Tab **Trợ lý** → chọn → **Lưu** → khởi động lại app.
```sh
python3 tools/r1y.py config --ip <IP_LOA> --ai-server custom \
  --ai-url ws://192.168.1.50:8000/xiaozhi/v1/ --ai-token <token>
```

## Dùng trên điện thoại (Termux)

```sh
# 1. Cài Termux (từ F-Droid), mở lên rồi dán:
pkg update -y && pkg install -y git python

# 2. Tải firmware repo
git clone https://github.com/BachDaThan/R1-Y
cd R1-Y

# 3. Tự chuẩn bị (cài adb/curl nếu thiếu, cấp quyền bộ nhớ)
bash tools/termux_setup.sh

# 4. Chạy tool
bash tools/r1y_termux.sh
```

Chọn **1** để cài (cần adb), hoặc **2b** để cập nhật qua web (không cần adb —
loa tự tải và cài), hoặc **3** để cấu hình WiFi nhà cho loa.

📖 Hướng dẫn chi tiết từng bước: **[docs/TERMUX.md](docs/TERMUX.md)**

Tool tự cài `python`, `android-tools` (adb), `curl` nếu thiếu — chỉ dùng `pkg`
chính thức của Termux, không tải script lạ. Lỗi kỹ thuật được dịch thành gợi ý
(ví dụ "Loa chưa phản hồi — kiểm tra cùng WiFi") thay vì đổ traceback.

## Cập nhật

3 cách, chọn cách nào cũng được:

| Cách | Lệnh |
|---|---|
| Tool A→Z trên PC | `python3 tools/r1y.py update --ip <IP_LOA>` |
| Ngay trên loa | `http://IP_LOA:8088` → **Thêm** → **Cập nhật firmware** → **Cập nhật ngay** |
| Script ngắn | `./scripts/update.sh <IP_LOA>`

Nguồn duy nhất: **GitHub Releases của `BachDaThan/R1-Y`**.
Loa kiểm tra `GET /repos/BachDaThan/R1-Y/releases/latest`, tải APK, cài
(qua root `pm install -r` nếu có, ngược lại trình cài đặt).

## Phát hành bản mới (cho chủ repo)

```sh
# Sửa versionName/versionCode trong app/app/build.gradle
git tag v1.4.1
git push origin v1.4.1
# CI tự build APK và tạo Release trên GitHub
```

## Tài liệu

- [Tool cài đặt A→Z](tools/README.md)
- [Cài bằng điện thoại (Termux)](docs/TERMUX.md)
- [Hướng dẫn cài đặt](HUONG_DAN_CAI_DAT.md)
- [Kiến trúc](docs/ARCHITECTURE.md)
- [Bộ lấy tiếng YouTube](docs/YOUTUBE_RESOLVER.md)
- [Máy chủ nhạc](server/README.md)

## Giấy phép

MIT — xem [LICENSE](LICENSE).
