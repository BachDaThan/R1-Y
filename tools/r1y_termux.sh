#!/data/data/com.termux/files/usr/bin/bash
# ==========================================================
#  R1-Y cho Termux (điện thoại Android)
#  Chạy:  bash tools/r1y_termux.sh
#
#  - Tự cài thư viện còn thiếu (python, adb, curl...) một cách an toàn
#  - Không hiện lỗi kỹ thuật khó hiểu; mọi lỗi được dịch thành gợi ý
#  - Có sẵn mục cấu hình WiFi cho loa + cài firmware qua WiFi
# ==========================================================
set -u

# Termux không có /tmp — dùng TMPDIR
LOGF="${TMPDIR:-${PREFIX:-/tmp}/tmp}/r1y-install.log"
mkdir -p "$(dirname "$LOGF")" 2>/dev/null || LOGF="/dev/null"
DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_DIR="$(cd "$DIR/.." && pwd)"

G='\033[92m'; Y='\033[93m'; R='\033[91m'; B='\033[94m'; C='\033[96m'; N='\033[0m'; D='\033[2m'; BOLD='\033[1m'
ok()   { printf "  ${G}✔${N} %s\n" "$1"; }
info() { printf "  ${B}•${N} %s\n" "$1"; }
warn() { printf "  ${Y}!${N} %s\n" "$1"; }
err()  { printf "  ${R}✘${N} %s\n" "$1"; }

# ---------------------------------------------------------------- dịch lỗi
# Biến lỗi kỹ thuật thành câu người dùng hiểu được
explain() {
    local t="$1"
    case "$t" in
        *"command not found"*|*"not found"*)      echo "Thiếu công cụ — tool sẽ tự cài lại." ;;
        *"Permission denied"*)                    echo "Termux chưa có quyền — chạy lại: termux-setup-storage" ;;
        *"No such file"*)                         echo "Không thấy file — kiểm tra đường dẫn hoặc tải lại APK." ;;
        *"Connection refused"*|*"timed out"*)     echo "Loa chưa phản hồi — kiểm tra loa đã bật và cùng WiFi." ;;
        *"unauthorized"*|*"device unauthorized"*) echo "Loa chưa cho phép máy này — bấm Cho phép trên loa." ;;
        *"INSTALL_FAILED"*)                       echo "Cài đặt bị từ chối — tool sẽ thử gỡ bản cũ rồi cài lại." ;;
        *)                                        echo "$t" ;;
    esac
}

need_python() {
    if command -v python3 >/dev/null 2>&1; then PY=python3
    elif command -v python >/dev/null 2>&1; then PY=python
    else
        warn "Chưa có Python — đang tự cài…"
        pkg install -y python >"$LOGF" 2>&1 || {
            err "Không tự cài được Python."
            info "Mở Termux và chạy: ${BOLD}pkg install python${N}"
            exit 1
        }
        command -v python3 >/dev/null 2>&1 && PY=python3 || PY=python
    fi
}

header() {
    printf "\n${C}  ╔══════════════════════════════════════════════╗\n"
    printf "  ║   ${BOLD}R1-Y${N}${C} — Tool trên điện thoại (Termux)     ║\n"
    printf "  ║   ${D}Loa Phicomm R1 · github.com/BachDaThan/R1-Y${N}${C}\n"
    printf "  ╚══════════════════════════════════════════════╝${N}\n\n"
}

# ---------------------------------------------------------------- chuẩn bị
header
need_python

if ! command -v adb >/dev/null 2>&1; then
    if command -v pkg >/dev/null 2>&1; then
        info "Chưa có adb — đang tự cài (android-tools)…"
        pkg install -y android-tools >"$LOGF" 2>&1 || true
    fi
fi
if command -v adb >/dev/null 2>&1; then
    ok "adb sẵn sàng"
else
    warn "Chưa có adb — vẫn cập nhật được firmware qua web (mục 4)."
fi

# python tool tự lo phần còn lại
if [ ! -f "$DIR/r1y.py" ]; then
    err "Không thấy tools/r1y.py — hãy chạy trong thư mục repo đã tải."
    exit 1
fi

# Menu nhanh riêng cho điện thoại (dùng lại r1y.py với tham số)
while true; do
    printf "\n  ${BOLD}Chọn việc:${N}\n"
    printf "   1  Cài firmware cho loa (tải APK từ GitHub, cần adb)\n"
    printf "   2  Cập nhật firmware (cần adb)\n"
    printf "   2b Cập nhật qua web — KHÔNG cần adb (loa tự tải + cài)\n"
    printf "   3  Cấu hình WiFi cho loa (quét + nối WiFi nhà)\n"
    printf "   4  Bật chế độ cài đặt (loa phát WiFi)\n"
    printf "   5  Xem trạng thái loa\n"
    printf "   6  Cấu hình máy chủ AI / chất lượng âm thanh\n"
    printf "   7  Thoát\n\n"
    printf "  ${Y}?${N} Nhập số [1]: "
    read -r c || c=7
    [ -z "$c" ] && c=1

    case "$c" in
        1)
            printf "  ${Y}?${N} IP loa (Enter để tự quét): "
            read -r IP
            if [ -n "$IP" ]; then
                "$PY" "$DIR/r1y.py" install --ip "$IP" || warn "$(explain "$?")"
            else
                "$PY" "$DIR/r1y.py" install || true
            fi
            ;;
        2)
            printf "  ${Y}?${N} IP loa (Enter để tự quét): "
            read -r IP
            if [ -n "$IP" ]; then
                "$PY" "$DIR/r1y.py" update --ip "$IP"
            else
                "$PY" "$DIR/r1y.py" update
            fi
            ;;
        2b|b|B)
            printf "  ${Y}?${N} IP loa (xem trong WiFi điện thoại): "
            read -r IP
            if [ -z "$IP" ]; then
                warn "Cần IP loa. Mở Cài đặt WiFi của điện thoại để xem IP."
            else
                "$PY" "$DIR/r1y.py" webinstall --ip "$IP"
            fi
            ;;
        3)
            printf "  ${Y}?${N} IP loa (đang ở chế độ cài đặt): "
            read -r IP
            IP="${IP:-192.168.43.1}"
            info "Mở giao diện: http://$IP:8088 → mục 'Cài đặt WiFi'"
            info "Quét WiFi, chọn WiFi nhà, nhập mật khẩu, bấm Kết nối."
            printf "\n  ${Y}?${N} Tải danh sách WiFi về xem tại đây? (c/k) [k]: "
            read -r D
            if [ "$D" = "c" ] || [ "$D" = "C" ]; then
                if command -v curl >/dev/null 2>&1; then
                    curl -s "http://$IP:8088/api/wifi/scan" | "$PY" -c "
import json,sys
try:
    d=json.load(sys.stdin)
except Exception:
    print('  Không đọc được danh sách WiFi (loa có thể chưa bật chế độ cài đặt).'); sys.exit()
if not d.get('ok'):
    print('  Lỗi:', d.get('error','?')); sys.exit()
ns=d.get('networks') or []
print('  Tìm thấy %d WiFi:' % len(ns))
for i,n in enumerate(ns,1):
    print('   %2d. %s %s' % (i, n.get('ssid'), '(có mật khẩu)' if n.get('secured') else '(mở)'))
" 2>/dev/null || warn "Không tải được danh sách (kiểm tra kết nối WiFi tới loa)."
                else
                    warn "Thiếu curl — mở trình duyệt điện thoại tới http://$IP:8088"
                fi
            fi
            ;;
        4)
            printf "  ${Y}?${N} IP loa (đang nối WiFi của loa hoặc LAN) [192.168.43.1]: "
            read -r IP
            IP="${IP:-192.168.43.1}"
            if command -v curl >/dev/null 2>&1; then
                info "Đang bật chế độ cài đặt trên loa…"
                curl -s -X POST "http://$IP:8088/api/setup" -d '{"on":true}' | "$PY" -c "
import json,sys
try: d=json.load(sys.stdin)
except Exception: print('  Không bật được (loa chưa phản hồi).'); sys.exit()
print('  ' + ('OK: ' if d.get('ok') else 'Lỗi: ') + str(d.get('hint') or d.get('error') or ''))
if d.get('warning'): print('  Lưu ý:', d['warning'])
" 2>/dev/null || warn "Không gọi được loa."
            else
                warn "Thiếu curl — mở http://$IP:8088 → tab Nhà → Bật WiFi"
            fi
            ;;
        5)
            printf "  ${Y}?${N} IP loa: "
            read -r IP
            [ -n "$IP" ] && "$PY" "$DIR/r1y.py" status --ip "$IP" || "$PY" "$DIR/r1y.py" status
            ;;
        6)
            "$PY" "$DIR/r1y.py" config || true
            ;;
        7|q|Q)
            printf "\n  Tạm biệt!\n\n"
            exit 0
            ;;
        *)
            warn "Chọn từ 1 đến 7."
            ;;
    esac
done
