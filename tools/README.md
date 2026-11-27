# Tool cài firmware Lily R1 (A → Z)

Một tool duy nhất, chạy trên **Windows / macOS / Linux**, lo hết mọi bước:
tìm loa trong WiFi → tải firmware từ **GitHub Releases của BachDaThan/R1-Y**
→ cài → cấp quyền → bật tự khởi động → cấu hình máy chủ YouTube → báo trạng thái.
Có luôn **tùy chọn cập nhật firmware** và **gỡ/khôi phục**.

## Chạy

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
6  Gỡ cài đặt / khôi phục app gốc
7  Thoát
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
python3 tools/r1y.py install --ip 192.168.2.20 --apk ./LilyR1-v1.4.0-release.apk

# Cài bản vừa build trong repo
python3 tools/r1y.py install --ip 192.168.2.20 --source local
```

### Tuỳ chọn

| Tham số | Ý nghĩa |
|---|---|
| `--ip <IP>` | IP loa. Bỏ trống thì tool tự quét WiFi tìm máy mở cổng 5555 |
| `--apk <file>` | Dùng APK chỉ định thay vì tải GitHub |
| `--source github\|local` | Nguồn APK: bản phát hành GitHub (mặc định) hoặc bản build trong máy |
| `--resolvers '<json>'` | Mảng JSON resolver lưu vào loa |
| `--music-server <url>` | Máy chủ nhạc cuối cùng |
| `--pre` | Cho phép bản thử nghiệm (prerelease) |
| `--force` | Cài đè dù đã ở bản mới nhất |
| `--yes` / `-y` | Không hỏi lại |
| `--adb <path>` | Dùng adb ở đường dẫn khác |

## Tool làm gì ở mục 1 (A→Z)

1. **Tìm loa** — `adb connect <IP>:5555`, hoặc tự quét dải WiFi nhà tìm cổng 5555.
2. **Nhận diện** loa Phicomm R1 (model/brand/API), cảnh báo nếu không đúng.
3. **Tắt app Phicomm gốc** (`pm hide` — vẫn giữ để khôi phục sau).
4. **Gỡ bản cũ** của Lily R1 nếu có.
5. **Tải firmware** từ `github.com/BachDaThan/R1-Y/releases` (bản mới nhất kèm APK).
6. **Cài** bằng `pm install` (tương thích Android 5.1), fallback `adb install`.
7. **Cấp quyền** thu âm + bộ nhớ, kiểm tra root (cho đèn LED).
8. **Bật tự khởi động** (BootReceiver + whitelist pin).
9. **Khởi động** app và chờ giao diện `http://IP_LOA:8088` lên.
10. **Cấu hình** máy chủ YouTube (`[]` = tự dò máy chủ công cộng).

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

Gỡ Lily R1 và bật lại 4 app Phicomm gốc (`pm unhide`).

## Xử lý sự cố

| Triệu chứng | Cách xử lý |
|---|---|
| "Chưa thấy loa nào" | Bật Gỡ lỗi ADB trên loa; loa và máy tính cùng WiFi; thử cắm USB một lần |
| `pm install` báo `INSTALL_FAILED_UPDATE_INCOMPATIBLE` | Tool đã tự gỡ bản cũ; nếu vẫn lỗi: `adb uninstall com.bachdathan.r1y` |
| GitHub báo không có Release | Chủ repo cần push tag `v1.4.0` để CI tạo Release, hoặc dùng `--apk`/`--source local` |
| Không mở được giao diện 8088 | Đợi 30 giây; kiểm tra IP loa (có thể đổi); xem log `adb logcat \| grep WebServer` |
| Máy không có Python (Windows) | Tải python.org, tick "Add python.exe to PATH" |
