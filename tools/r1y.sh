#!/usr/bin/env bash
# ==========================================================
#  Lily R1 — Tool cài firmware cho loa Phicomm R1 (Linux/macOS)
#  Chỉ cần chạy: ./tools/r1y.sh      (hoặc bash tools/r1y.sh)
#  Nguồn: github.com/BachDaThan/R1-Y
# ==========================================================
set -e
DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

if command -v python3 &>/dev/null; then
    exec python3 "$DIR/r1y.py" "$@"
elif command -v python &>/dev/null; then
    exec python "$DIR/r1y.py" "$@"
else
    cat <<'EOF'

[LỖI] Chưa có Python 3 trên máy.

  • Debian/Ubuntu : sudo apt install python3
  • Fedora        : sudo dnf install python3
  • macOS         : brew install python3  (hoặc tải python.org)

Sau đó chạy lại: ./tools/r1y.sh

EOF
    exit 1
fi
