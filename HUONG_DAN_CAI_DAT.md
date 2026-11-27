# Hướng dẫn cài đặt Lily R1 (R1-Y) cho loa Phicomm R1

Firmware **Lily R1** biến loa Phicomm R1 (Android 5.1) thành trợ lý giọng nói
Xiaozhi + loa phát nhạc YouTube. Cài qua **cùng WiFi** bằng ADB, cập nhật
ngay trong giao diện từ GitHub Releases của repo này.

## 1. Chuẩn bị

- Loa Phicomm R1, máy tính (Windows/Linux) **cùng WiFi** với loa.
- Cài [Android SDK Platform Tools](https://developer.android.com/tools/adb) (có `adb`).
- (Tùy chọn) Máy chủ nhạc: PC Debian/Ubuntu chạy `server/r1y_server.py`.
  Không có máy chủ vẫn dùng được (loa tự tìm máy chủ công khai).

## 2. Bật ADB qua WiFi trên loa

1. Trên loa, mở **Cài đặt → Tùy chọn nhà phát triển → Gỡ lỗi USB** (bật).
2. Ghi lại **IP của loa** (ví dụ `192.168.2.20`) trong Cài đặt → WiFi.
3. Trên máy tính, kết nối:
   ```sh
   adb connect 192.168.2.20:5555
   adb devices   # phải thấy "device"
   ```
   Nếu chưa thấy, cắm USB một lần rồi chạy lại `adb connect`.

## 3. Tải APK

- Cách 1 (khuyên dùng): vào [Releases](../../releases) của repo này,
  tải `LilyR1-vX.Y.Z-release.apk` mới nhất.
- Cách 2: build từ mã nguồn:
  ```sh
  cd app
  ./gradlew assembleRelease
  # APK: app/build/outputs/apk/release/app-release.apk
  ```

## 4. Cài đặt

### Cách A: script tự động (khuyên dùng)

```sh
# Linux/Mac
./scripts/install.sh 192.168.2.20

# Windows
scripts\install.bat 192.168.2.20
```

Script sẽ: tắt app Phicomm gốc (giữ lại, không xóa), gỡ bản cũ, cài APK,
cấp quyền, kiểm tra root (cho LED), khởi động app và bật tự chạy khi bật loa.

### Cách B: thủ công

```sh
adb connect 192.168.2.20:5555
adb push LilyR1-v1.4.0-release.apk /data/local/tmp/lilyr1.apk
adb shell pm install -t -r /data/local/tmp/lilyr1.apk
adb shell pm grant com.bachdathan.r1y android.permission.RECORD_AUDIO
adb shell am start -n com.bachdathan.r1y/.ui.MainActivity
```

## 5. Cấu hình lần đầu

1. Mở giao diện loa trên trình duyệt: `http://192.168.2.20:8088`
2. **Trợ lý**: bấm **Kết nối** trên loa (hoặc trong app), nhập mã kích hoạt
   tại https://xiaozhi.me/activate (hoặc máy chủ Xiaozhi tự đặt).
3. **Nhạc → Bộ lấy tiếng YouTube**: để nguyên `[]` (mặc định) là loa tự dò
   máy chủ YouTube công cộng đang sống, không cần cài gì thêm.
   Chỉ khi có máy chủ riêng mới thay `[]` bằng địa chỉ của bạn, ví dụ:
   ```json
   [{"type":"custom","url":"http://192.168.2.130:8091"}]
   ```
   Bấm **Lưu** (hoặc **Tự dò lại máy chủ công cộng** nếu muốn dò mới).
4. Thử phát nhạc: gọi API hoặc dùng Tube/LiteTube để phát bài lên loa.

## 6. Cập nhật firmware

### Trên loa (khuyên dùng)

Mở `http://192.168.2.20:8088` → **Thêm** → **Cập nhật firmware** →
**Kiểm tra** → **Cập nhật ngay**. Loa tự kiểm tra GitHub Releases của
`BachDaThan/R1-Y`, tải APK và cài.

### Trên máy tính

```sh
./scripts/update.sh 192.168.2.20
```

## 7. Máy chủ nhạc (tùy chọn — chỉ khi muốn dùng máy chủ riêng)

Chạy trên PC trong cùng LAN:

```sh
cd server
cp r1y.env.example r1y.env
python3 r1y_server.py
```

Kiểm tra: `curl http://IP_MAY:8091/health`

Rồi cấu hình loa như bước 5.3 (thay `[]` bằng máy chủ này).
Không có máy chủ riêng thì cứ để `[]`, loa tự dùng máy chủ công cộng.
Xem [server/README.md](server/README.md).

## 8. Xử lý sự cố

| Triệu chứng | Cách xử lý |
|---|---|
| `adb connect` không thấy loa | Kiểm tra cùng WiFi, bật Gỡ lỗi USB, thử cắm USB một lần |
| Cài APK báo lỗi chữ ký | Gỡ bản cũ trước: `adb uninstall com.bachdathan.r1y` |
| Loa không tự chạy khi bật | Chạy lại bước 6 của `install.sh` (bật BootReceiver) |
| Không phát được nhạc | Xem [docs/YOUTUBE_RESOLVER.md](docs/YOUTUBE_RESOLVER.md) |
| Cập nhật báo lỗi GitHub | Kiểm tra loa có Internet không, thử nút **Kiểm tra** lại |
| Xem log | `adb logcat \| grep -E 'Xiaozhi\|Resolver\|WebServer\|Update'` |

## 9. Gỡ cài đặt / khôi phục

```sh
adb uninstall com.bachdathan.r1y
adb shell pm unhide com.phicomm.speaker.player
adb shell pm unhide com.phicomm.speaker.device
adb shell pm unhide com.phicomm.speaker.airskill
adb shell pm unhide com.phicomm.speaker.exceptionreporter
```
