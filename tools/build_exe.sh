#!/usr/bin/env bash
# ==========================================================
#  R1-Y — Đóng gói tool thành 1 file chạy được (Linux/macOS)
#  Chạy:  bash tools/build_exe.sh
#  Kết quả: dist/R1Y-Tool
#
#  Muốn ra .exe cho Windows thì chạy trên Windows:
#    tools\build_exe.bat
#  (PyInstaller không build chéo hệ điều hành.)
# ==========================================================
set -euo pipefail
cd "$(dirname "$0")/.."

G='\033[92m'; Y='\033[93m'; R='\033[91m'; B='\033[94m'; N='\033[0m'
ok()   { printf "  ${G}✔${N} %s\n" "$1"; }
info() { printf "  ${B}•${N} %s\n" "$1"; }
warn() { printf "  ${Y}!${N} %s\n" "$1"; }
err()  { printf "  ${R}✘${N} %s\n" "$1"; }

printf "\n${B}══ R1-Y · Build tool thành 1 file ══${N}\n\n"

PY=""
for c in python3 python; do command -v "$c" >/dev/null 2>&1 && { PY="$c"; break; }; done
[ -n "$PY" ] || { err "Chưa có Python. Cài: sudo apt install python3"; exit 1; }
ok "Python: $($PY --version 2>&1 | head -1)"

info "Cài PyInstaller…"
"$PY" -m pip install --quiet --upgrade pip >/dev/null 2>&1 || true
if ! "$PY" -m pip install --quiet pyinstaller; then
    warn "Không cài được bằng pip — thử --user / --break-system-packages"
    "$PY" -m pip install --quiet --user pyinstaller || \
    "$PY" -m pip install --quiet --break-system-packages pyinstaller || {
        err "Không cài được PyInstaller. Thử: $PY -m pip install pyinstaller"
        exit 1
    }
fi
ok "PyInstaller sẵn sàng"

info "Đóng gói (1-2 phút)…"
"$PY" -m PyInstaller tools/r1y.spec --noconfirm --clean >/tmp/r1y-build.log 2>&1 || {
    err "Đóng gói thất bại. 3 dòng cuối log:"
    tail -3 /tmp/r1y-build.log | sed 's/^/      /'
    exit 1
}

if [ -f dist/R1Y-Tool ]; then
    chmod +x dist/R1Y-Tool
    ok "Xong: dist/R1Y-Tool ($(du -h dist/R1Y-Tool | cut -f1))"
    printf "\n  Chạy thử: ${B}./dist/R1Y-Tool${N}\n"
    printf "  Muốn ra .exe Windows: chạy ${B}tools\\\\build_exe.bat${N} trên máy Windows.\n\n"
else
    err "Không thấy dist/R1Y-Tool — xem /tmp/r1y-build.log"
    exit 1
fi
