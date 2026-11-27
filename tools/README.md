# Tool cài firmware R1-Y (A → Z)

Một tool duy nhất, chạy trên **Windows / macOS / Linux**, lo hết mọi bước:
tìm loa trong WiFi → tải firmware từ **GitHub Releases của BachDaThan/R1-Y**
→ cài → cấp quyền → bật tự khởi động → cấu hình máy chủ YouTube → báo trạng thái.
Có luôn **tùy chọn cập nhật firmware** và **gỡ/khôi phục**.

## Đang ở WiFi của loa thì KHÔNG có internet

WiFi loa phát (`192.168.43.x`) chỉ để cài đặt, không ra internet. Tool tự nhận ra và:
- **có** bản firmware đã tải trong `~/.r1y/firmware/` → dùng luôn, không cần mạng;
- **chưa có** → nói rõ và chỉ cách (nối WiFi nhà/4G tải trước, hoặc `--apk /sdcard/...`).

Menu **8b — Tải firmware về trước**: tải sẵn khi còn internet. Menu **3b — Cập nhật qua web**:
cài không cần adb (loa ở WiFi nhà).

Nếu bản tool đang chạy cũ hơn bản trên repo, khi có internet tool sẽ **tự cập nhật chính nó**
rồi bảo chạy lại — vì bản cũ không sửa được lỗi nó đang gặp.

## Chạy

**Điện thoại Android (Termux)**
```sh
pkg update -y && pkg install -y git python
git clone https://github.com/BachDaThan/R1-Y && cd R1-Y
bash tools/termux_setup.sh     # tự cài python/adb/curl nếu thiếu (an toàn)
bash tools/r1y_termux.sh       # menu riêng: cài fw, cấu hình WiFi, hotspot, AI
```
Chưa cài được adb? Dùng **2b — Cập nhật qua web** (loa tự tải + cài APK).
Hướng dẫn đầy đủ: [../docs/TERMUX.md](../docs/TERMUX.md)

**Windows** — bấm đúp `tools/r1y.bat`

**macOS / Linux**
```sh
./tools/r1y.sh
```

**Bằng Python trực tiếp**
```sh
python3 tools/r1y.py
```

Menu hiện ra:

```
1  Cài mới từ đầu (A→Z) — tải APK từ GitHub rồi cài
2  Cập nhật firmware     — so với bản mới nhất trên GitHub
3  Cài bản build trong máy (APK dev)
4  Cấu hình máy chủ YouTube cho loa
5  Kiểm tra trạng thái loa
3b Cập nhật qua web — không cần adb
6  Cấu hình WiFi nhà cho loa (quét + nối)
7  Bật phát WiFi (hotspot) từ loa để điện thoại kết nối
8  Gỡ cài đặt / khôi phục app gốc
0  Bật ADB qua WiFi (khi adb báo offline / cổng 5555 chưa mở)
9  Thoát
```

Cần: **Python 3** và **mạng Internet**. Tool tự tải **adb (Android Platform Tools)**
nếu máy chưa có — không phải cài Android Studio.

## Chạy không hỏi (script / CI)

```sh
# Cài từ đầu, không hỏi gì
python3 tools/r1y.py install --ip 192.168.2.20 --yes

# Cập nhật lên bản mới nhất
python3 tools/r1y.py update --ip 192.168.2.20 --yes

# Kiểm tra trạng thái
python3 tools/r1y.py status --ip 192.168.2.20

# Cấu hình máy chủ YouTube (để "[]" = tự dò máy chủ công cộng)
python3 tools/r1y.py config --ip 192.168.2.20 --resolvers '[]'

# Dùng máy chủ riêng
python3 tools/r1y.py config --ip 192.168.2.20 \
  --resolvers '[{"type":"custom","url":"http://192.168.2.130:8091"}]'

# Cài APK có sẵn (dev)
python3 tools/r1y.py install --ip 192.168.2.20 --apk ./R1Y-v1.5.0-release.apk

# Cài bản vừa build trong repo
python3 tools/r1y.py install --ip 192.168.2.20 --source local
```

### Cấu hình WiFi nhà cho loa

Khi loa đang ở chế độ cài đặt (giữ nút tròn), điện thoại đã nối WiFi `R1-Y`:

```sh
python3 tools/r1y.py wifi --ip 192.168.43.1
```
Tool in danh sách WiFi quét được → chọn số → nhập mật khẩu → loa vào WiFi nhà,
tool in ra **IP mới** để cài firmware tiếp.

### Phát WiFi từ loa

```sh
python3 tools/r1y.py hotspot --ip 192.168.2.20 --ssid R1-Y --password r1y12345678
python3 tools/r1y.py hotspot --ip 192.168.2.20 --off
```
Nối WiFi `R1-Y` trên điện thoại → mở `http://192.168.43.1:8088`.

### Chọn máy chủ AI / chất lượng âm thanh

```sh
# giữ xiaozhi.me (mặc định)
python3 tools/r1y.py config --ip 192.168.2.20 --ai-server xiaozhi

# Xiaozhi tự dựng (esp32-server) hoặc URL riêng + token
python3 tools/r1y.py config --ip 192.168.2.20 --ai-server custom \
  --ai-url ws://192.168.1.50:8000/xiaozhi/v1/ --ai-token abc123

# âm thanh: high = Opus (mặc định, hay nhất) | compat = AAC/m4a
python3 tools/r1y.py config --ip 192.168.2.20 --audio-quality high
```

### Tuỳ chọn

| Tham số | Ý nghĩa |
|---|---|
| `--ip <IP>` | IP loa. Bỏ trống thì tool tự quét WiFi tìm máy mở cổng 5555 |
| `--apk <file>` | Dùng APK chỉ định thay vì tải GitHub |
| `--source github\|local` | Nguồn APK: bản phát hành GitHub (mặc định) hoặc bản build trong máy |
| `--resolvers '<json>'` | Mảng JSON resolver lưu vào loa |
| `--music-server <url>` | Máy chủ nhạc cuối cùng |
| `--ai-server xiaozhi\|xiaozhi_self\|custom` | Máy chủ AI cho trợ lý |
| `--ai-url <url>` | URL WebSocket máy chủ AI |
| `--ai-token <token>` | Token/API key cho máy chủ AI tự dựng |
| `--audio-quality high\|compat` | `high` = Opus (mặc định), `compat` = AAC |
| `--ssid` / `--password` / `--on` / `--off` | Cấu hình phát WiFi từ loa |
| `--pre` | Cho phép bản thử nghiệm (prerelease) |
| `--force` | Cài đè dù đã ở bản mới nhất |
| `--reboot` / `--no-reboot` | Khởi động lại loa sau khi cài / chỉ khởi động lại app |
| `--yes` / `-y` | Không hỏi lại |
| `--adb <path>` | Dùng adb ở đường dẫn khác |

## Tool làm gì ở mục 1 (A→Z)

1. **Tìm loa** — `adb connect <IP>:5555`, hoặc tự quét dải WiFi nhà tìm cổng 5555.
2. **Nhận diện** loa Phicomm R1 (model/brand/API), cảnh báo nếu không đúng.
3. **Tắt app Phicomm gốc** (`pm hide` — vẫn giữ để khôi phục sau).
4. **Gỡ bản cũ** của R1-Y nếu có.
5. **Tải firmware** từ `github.com/BachDaThan/R1-Y/releases` (bản mới nhất kèm APK).
6. **Cài** bằng `pm install` (tương thích Android 5.1), fallback `adb install`.
7. **Cấp quyền** thu âm + bộ nhớ, kiểm tra root (cho đèn LED).
8. **Bật tự khởi động** (BootReceiver + whitelist pin).
9. **Khởi động** app và chờ giao diện `http://IP_LOA:8088` lên.
10. **Cấu hình** máy chủ YouTube (`[]` = tự dò máy chủ công cộng).

## Khởi động lại sau khi cài

Cài xong tool hỏi *"Khởi động lại loa ngay?"* (mặc định **Có**), rồi chờ loa lên
lại và in IP mới + link web. Muốn bỏ qua: `--no-reboot` hoặc `--reboot` để làm luôn
không hỏi.

## Cập nhật firmware

```sh
python3 tools/r1y.py update --ip 192.168.2.20
```

Tool so `versionName` trên loa với tag mới nhất trên GitHub; nếu đã mới nhất thì
không cài lại (trừ khi thêm `--force`). Cài xong tự khởi động lại app.

Có 3 cách cập nhật, chọn cách nào cũng được:

| Cách | Lệnh |
|---|---|
| Tool này (PC) | `python3 tools/r1y.py update --ip <IP>` |
| Trên loa | `http://<IP>:8088` → **Thêm** → **Cập nhật firmware** |
| Script ngắn | `./scripts/update.sh <IP>` |

## Gỡ / khôi phục

```sh
python3 tools/r1y.py uninstall --ip 192.168.2.20
```

Gỡ R1-Y và bật lại 4 app Phicomm gốc (`pm unhide`).

## Xử lý sự cố

| Triệu chứng | Cách xử lý |
|---|---|
| "Chưa thấy loa nào" | Bật Gỡ lỗi ADB trên loa; loa và máy tính cùng WiFi; thử cắm USB một lần |
| `pm install` báo `INSTALL_FAILED_UPDATE_INCOMPATIBLE` | Tool đã tự gỡ bản cũ; nếu vẫn lỗi: `adb uninstall com.bachdathan.r1y` |
| GitHub báo không có Release | Chủ repo cần push tag `v1.5.0` để CI tạo Release, hoặc dùng `--apk`/`--source local` |
| Không mở được giao diện 8088 | Đợi 30 giây; kiểm tra IP loa (có thể đổi); xem log `adb logcat \| grep WebServer` |
| Máy không có Python (Windows) | Tải python.org, tick "Add python.exe to PATH" |

**Bản .exe (không cần Python)**

Tải `R1Y-Tool.exe` từ **Releases** hoặc **Actions → Build Tool EXE → Artifacts**,
rồi bấm đúp. Tự build trên Windows: bấm đúp `tools\build_exe.bat` → `dist\R1Y-Tool.exe`.
Trên Linux/macOS: `bash tools/build_exe.sh` → `dist/R1Y-Tool`.

**Windows (CMD/PowerShell)**

## Nói chuyện với trợ lý AI (v1.8.0)

```
python3 tools/r1y.py voice --ip 192.168.2.20                     # menu
python3 tools/r1y.py voice --ip 192.168.2.20 --action talk       # bấm "nói"
python3 tools/r1y.py voice --ip 192.168.2.20 --action abort      # ngắt lời
python3 tools/r1y.py voice --ip 192.168.2.20 --action text --text "xin chào"
python3 tools/r1y.py voice --ip 192.168.2.20 --action key --code 66   # dò mã phím
```

Lệnh này in ra: kênh audio đã mở chưa, số khung Opus đã gửi/nhận, số lần mic tự
khởi động lại, và tên phím cuối cùng loa gửi. Đây là cách nhanh nhất để biết lỗi
nằm ở giao thức, ở codec, ở micro hay ở nút bấm. Xem `docs/TRO_LY_KHONG_NOI.md`.
