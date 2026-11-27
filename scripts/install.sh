#!/bin/bash
# Cài firmware Lily R1 (R1-Y) cho loa Phicomm R1 qua cùng WiFi (ADB over WiFi).
# Dùng:  ./install.sh [IP_LOA]
# Ví dụ: ./install.sh 192.168.2.20
# Cần: adb (Android SDK Platform Tools), file APK trong app/build/outputs/apk/release/
#      hoặc tải từ GitHub Releases của BachDaThan/R1-Y.

set -e

RED='\033[0;31m'; GREEN='\033[0;32m'; YELLOW='\033[1;33m'; NC='\033[0m'
PACKAGE_NAME="com.bachdathan.r1y"
APK_PATH="app/app/build/outputs/apk/release/app-release.apk"
R1_IP="${1:-}"

info()  { echo -e "${GREEN}[INFO]${NC} $1"; }
warn()  { echo -e "${YELLOW}[WARN]${NC} $1"; }
error() { echo -e "${RED}[ERROR]${NC} $1"; }

if ! command -v adb &> /dev/null; then
    error "Chưa có adb. Cài Android SDK Platform Tools trước."
    exit 1
fi
info "adb: $(adb version | head -1)"

if [ -n "$R1_IP" ]; then
    info "Kết nối loa $R1_IP qua WiFi (adb connect $R1_IP:5555)..."
    adb connect "$R1_IP:5555" || true
    sleep 2
fi

if ! adb devices | grep -q "device$"; then
    error "Chưa thấy loa. Bật ADB qua WiFi trên loa rồi chạy lại:"
    error "  adb connect <IP_LOA>:5555"
    error "Hoặc cắm USB rồi chạy lại script."
    exit 1
fi
info "Đã thấy loa: $(adb devices | grep device$ | awk '{print $1}')"

if [ ! -f "$APK_PATH" ]; then
    warn "Chưa có APK tại $APK_PATH"
    warn "Tải từ GitHub Releases (BachDaThan/R1-Y) rồi đặt vào thư mục này, hoặc build:"
    warn "  cd app && ./gradlew assembleRelease"
    exit 1
fi
info "APK: $APK_PATH"

info "=== 1/6 Tắt app Phicomm gốc (giữ lại, không xóa) ==="
for app in com.phicomm.speaker.player com.phicomm.speaker.device \
           com.phicomm.speaker.airskill com.phicomm.speaker.exceptionreporter; do
    adb shell pm hide "$app" 2>/dev/null || warn "Không tắt được $app (có thể không có)"
done

info "=== 2/6 Gỡ bản cũ (nếu có) ==="
if adb shell pm list packages | grep -q "$PACKAGE_NAME"; then
    adb uninstall "$PACKAGE_NAME" || warn "Không gỡ được bản cũ"
else
    info "Chưa có bản cũ."
fi

info "=== 3/6 Cài APK ==="
adb push "$APK_PATH" /data/local/tmp/lilyr1.apk
adb shell pm install -t -r /data/local/tmp/lilyr1.apk

info "=== 4/6 Cấp quyền ==="
for perm in android.permission.RECORD_AUDIO android.permission.WRITE_EXTERNAL_STORAGE \
            android.permission.READ_EXTERNAL_STORAGE android.permission.ACCESS_NETWORK_STATE \
            android.permission.INTERNET; do
    adb shell pm grant "$PACKAGE_NAME" "$perm" 2>/dev/null || warn "Không cấp được $perm"
done

info "=== 5/6 Kiểm tra root (để điều khiển LED) ==="
if adb shell su -c "id" 2>/dev/null | grep -q "uid=0"; then
    info "Có root — LED sẽ hoạt động."
else
    warn "Chưa root — LED sẽ tắt, các tính năng khác vẫn chạy."
fi

info "=== 6/6 Khởi động + tự chạy khi bật loa ==="
adb shell am start -n "$PACKAGE_NAME/.ui.MainActivity"
sleep 2
adb shell pm enable "$PACKAGE_NAME/.receiver.BootReceiver" 2>/dev/null || true
adb shell dumpsys deviceidle whitelist +"$PACKAGE_NAME" 2>/dev/null || true
adb shell rm /data/local/tmp/lilyr1.apk 2>/dev/null || true

info "=== Xong! ==="
info "Mở giao diện loa: http://<IP_LOA>:8088"
info "Kiểm tra log: adb logcat | grep -E 'Xiaozhi|Resolver|WebServer'"
