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
- 📡 **Chế độ cài đặt WiFi** (giữ nút trên đỉnh loa ~5 giây): loa phát WiFi `R1-Y` →
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
- 🔐 **Cài đè được giữa các bản** — CI giữ một khoá ký cố định, tool tự xử lý
  trường hợp khác chữ ký (xem [Vì sao bản mới không cài đè được](#vì-sao-bản-mới-không-cài-đè-được-bản-cũ-signatures-do-not-match)).
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

## Vì sao bản mới không cài đè được bản cũ (signatures do not match)

APK của mỗi bản phải được ký bằng **cùng một khoá** thì Android mới cho cài đè.
Bản trước đây do CI build nên **mỗi lần chạy sinh một `debug.keystore` mới** →
bản sau khác chữ ký bản trước → Android báo:

```
INSTALL_FAILED_UPDATE_INCOMPATIBLE: Package com.bachdathan.r1y signatures do not match
```

`pm install` gặp lỗi rồi đóng shell giữa lúc cài, nên Termux chỉ thấy
`error: closed` — nhìn như lỗi mạng, thật ra là lỗi chữ ký.

Từ **v1.7.1** đã sửa hai đầu:

- **CI** giữ lại khoá ký giữa các lần build (`~/.r1y-signing/r1y-release.jks`,
  lưu bằng `actions/cache`; hoặc bền hơn: secret `R1Y_KEYSTORE_B64` = base64
  của file `.jks`). Bản **debug** cũng ký bằng đúng khoá đó.
- **Tool** (`tools/r1y.py`) đọc chữ ký bản đang cài, và nếu bản mới khác chữ ký
  thì **tự gỡ bản cũ rồi cài lại** (hỏi trước), thay vì cài rồi lỗi giữa đường.

Cài đè không gỡ được chỉ xảy ra với các bản **đã phát hành trước v1.7.1** —
gỡ một lần, từ đó về sau cài đè bình thường và **giữ nguyên cài đặt WiFi/AI**.

```sh
# cách 1 — để tool tự làm (khuyến nghị)
python3 tools/r1y.py install --ip 192.168.43.1

# cách 2 — gỡ tay rồi cài
adb -s 192.168.43.1:5555 shell pm uninstall com.bachdathan.r1y
python3 tools/r1y.py install --ip 192.168.43.1
```

Giữ lại cài đặt khi gỡ (nếu ROM cho phép): `pm uninstall -k com.bachdathan.r1y`.

## Ghi chú: bộ tài liệu cộng đồng Phicomm R1

Trong nhóm “AI Plus Box” có bộ video + bộ cài All-in-One cho loa R1 (trang cấu hình
**8081**, `R1connectwifi.apk`, AI tiếng Việt/Unisound/DLNA). R1-Y **không** phụ thuộc
vào bộ đó và **không** dùng chung cổng: R1-Y luôn ở **8088**.
Xem [docs/PHICOMM_COMMUNITY.md](docs/PHICOMM_COMMUNITY.md) — chỗ nào dùng được thì đã
áp dụng (khoá ký cố định, kiểm tra IP, chống treo `yt-dlp`), chỗ nào không nên trộn
thì nói rõ. Chọn **một** bộ firmware cho một loa.

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

## Tự build APK bằng bot GitHub

APK do bot build, không cần cài Android SDK trên máy:

1. Vào tab **Actions** → chọn **Build APK** → **Run workflow** (nhánh `main`).
2. Chờ ~2 phút → mở run vừa chạy → mục **Artifacts**:
   - `R1Y-Debug-APK` — bản debug (cài được ngay, tiện thử)
   - `R1Y-Release-APK` — bản release
   - `Checksums` — mã SHA256
3. Tải `.zip` về, giải nén ra file `.apk`.

### Bản phát hành hiện có

**`v1.6.2`** → https://github.com/BachDaThan/R1-Y/releases/tag/v1.6.2
(`R1Y-v1.6.2-release.apk`, 1.28 MB, **đã ký** — cài được ngay trên loa)

### Tạo bản phát hành (để tool tự tải)

Gắn tag dạng `v*` là bot tự build + tự tạo Release kèm APK:

```bash
git tag v1.6.1
git push origin v1.6.1
```

Sau ~3 phút, APK có ở mục **Releases**. Từ đó:

```bash
python3 tools/r1y.py install --ip <IP_LOA>   # tool tự tải APK từ Releases
```

> Bot cũng tự chạy khi có push/PR vào `main`: Build APK, Lint, Unit Tests,
> Python Server Check, quét secret, PR Check Bot.

## Loa đang chạy firmware gốc (Phicomm/Lily) — cài R1-Y thế nào

Trên firmware gốc, loa **chỉ mở ADB khi đang phát WiFi riêng**. Đừng cố nối
ADB qua WiFi nhà — sẽ luôn `offline`.

> ⚠️ **WiFi do loa phát KHÔNG CÓ INTERNET.** Vì vậy tool tải firmware về điện thoại
> **trước**, rồi mới nối vào WiFi của loa. APK đã tải được lưu ở `~/.r1y/firmware/`
> để lần sau cài lại **không cần internet**.

**Đúng thứ tự (cài TRƯỚC, nối WiFi nhà SAU):**

| Bước | Làm gì |
|---|---|
| 1 | Chạy `r1y` khi máy còn internet → chọn **1**: tool tải firmware về máy trước |
| 2 | Cắm điện loa, chờ ~1 phút, giữ **nút trên đỉnh loa** ~5 giây → loa phát WiFi `Phicomm R1` |
| 3 | Điện thoại/PC nối vào WiFi `Phicomm R1` rồi bấm Enter: tool tự nối ADB `192.168.43.1:5555` và cài firmware |
| 4 | Tool hỏi "Cho loa vào WiFi nhà?" → chọn WiFi nhà, nhập mật khẩu |
| 5 | Nối lại điện thoại/PC vào WiFi nhà → tool tự dò IP loa → mở `http://IP_LOA:8088` |

> Cách này giống DB-Robot, Bánh Mì và script Vietbot: cài qua `192.168.43.1` khi loa
> đang phát WiFi, rồi mới chuyển loa sang WiFi nhà. Nếu nối WiFi nhà **trước** khi cài,
> ADB trên firmware gốc không còn mở (`offline`) và không cài được.
>
> Loa chỉ nhận **một** kết nối ADB cùng lúc: tắt tool/adb khác đang nối loa.
> Cấu hình WiFi dùng API của R1-Y (`:8088`, có quét WiFi), lỗi thì tự thử API gốc
> `http://192.168.43.1:8989/api/configwifi`.

**Gặp lỗi:**

| Hiện tượng | Cách xử lý |
|---|---|
| Giữ nút mà không thấy WiFi `Phicomm R1` | Chờ loa khởi động xong (~1 phút), giữ lại, đứng gần loa, làm mới danh sách WiFi |
| `offline` / "Không kết nối được" | Rút điện loa 10 giây, cắm lại, chờ 1 phút, giữ nút 5 giây, chạy lại tool |
| `unauthorized` | Tool chờ ~20 giây; chạy lại, hoặc rút điện cắm lại rồi nối lại |
| Đã vào WiFi nhà mà chưa cài | Giữ nút 5 giây cho loa phát WiFi lại, rồi cài qua `192.168.43.1` |

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

1. **Giữ nút trên đỉnh loa ~5 giây** (hoặc bật ở tab **Nhà**, hoặc `tools/r1y.py hotspot`).
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

## Bản .exe — chỉ bấm đúp, không cần cài gì

Không muốn gõ lệnh? Tải **`R1Y-Tool.exe`** là chạy được ngay trên Windows
(không cần cài Python, không cần mở CMD).

**Lấy file .exe:**

| Cách | Ở đâu |
|---|---|
| **Releases** (khuyến nghị) | tab **Releases** → bản mới nhất → tải `R1Y-Tool-vX.Y.Z.exe` |
| **Actions** (bản mới nhất, chưa cần tag) | tab **Actions** → **Build Tool EXE** → **Run workflow** → mở run → mục **Artifacts** → `R1Y-Tool-EXE` |
| **Tự build trên máy** | Windows: bấm đúp `tools\build_exe.bat` → file ra ở `dist\R1Y-Tool.exe` |

Bấm đúp vào `.exe` → menu hiện ra:

```
   1  Cài mới từ đầu (A→Z) — tải APK từ GitHub rồi cài
   2  Cập nhật firmware — so với bản mới nhất trên GitHub
   3  Cài bản build trong máy (APK dev)
   3b Cập nhật qua web — không cần adb (loa tự tải + cài)
   ...
```

Làm xong một việc, tool hỏi *“Quay lại menu chính?”* — chọn **Có** để làm tiếp,
**Không** để thoát.

> Windows SmartScreen có thể cảnh báo *“Windows protected your PC”* vì file chưa
> mua chứng chỉ ký số. Bấm **More info** → **Run anyway**.
> Hoặc tự build bằng `tools\build_exe.bat` để có file của chính bạn.

## Dùng trên điện thoại (Android) — chạm 1 cái là chạy

Mở **Termux**, dán **một dòng**:

```bash
curl -sL https://raw.githubusercontent.com/BachDaThan/R1-Y/main/tools/android/install.sh | bash
```

Script tự cài python/git/adb, tải firmware về, tạo **lệnh ngắn `r1y`** và gắn
**phím `r1y` lên thanh phím Termux**. Sau đó:

- **Chạm phím `r1y` → Enter** (nhanh)
- Hoặc **chạm 1 lần** ngoài màn hình chính bằng app **Termux:Widget**
- Hoặc gõ `r1y`

Menu hiện ra:

```
   1  Cài mới từ đầu (A→Z) — tải APK từ GitHub rồi cài
   2  Cập nhật firmware
   3b Cập nhật qua web — không cần adb
   ...
```

> Termux không cho gắn *lệnh* vào phím thanh phím (phím chỉ gõ chữ), nên phím
> `r1y` chỉ gõ sẵn chữ — bấm Enter để chạy. Muốn **1 chạm chạy luôn** thì dùng
> **Termux:Widget** như trên.

📖 Chi tiết: **[docs/TERMUX.md](docs/TERMUX.md)**

## Dùng trên điện thoại (Termux) — cách thủ công

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
