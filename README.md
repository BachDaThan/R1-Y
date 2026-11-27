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
│   ├── service/            # Xiaozhi, Voice, Audio, LED, WebServer (8088)
│   ├── youtube/            # ResolverManager, SearchManager
│   ├── update/             # UpdateManager (GitHub BachDaThan/R1-Y)
│   ├── web/                # Giao diện R1-Y + REST API
│   └── config/             # XiaozhiConfig, R1YConfig
├── server/                 # Máy chủ nhạc cho PC (Tube/LiteTube style)
│   └── r1y_server.py       # Invidious + Piped + custom, yt-dlp
├── tools/                  # r1y.py — tool A→Z (cài, cập nhật, cấu hình, gỡ)
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
- [Hướng dẫn cài đặt](HUONG_DAN_CAI_DAT.md)
- [Kiến trúc](docs/ARCHITECTURE.md)
- [Bộ lấy tiếng YouTube](docs/YOUTUBE_RESOLVER.md)
- [Máy chủ nhạc](server/README.md)

## Giấy phép

MIT — xem [LICENSE](LICENSE).
