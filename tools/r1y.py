#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
r1y.py — Tool cài đặt firmware R1-Y cho loa Phicomm R1, từ A đến Z.

Một file Python, chỉ dùng thư viện chuẩn. Chạy được trên Windows / macOS / Linux.

    python3 tools/r1y.py

Menu:
  1) Cài mới từ đầu (A→Z)         : tìm loa, tải APK từ GitHub, cài, cấp quyền, cấu hình
  2) Cập nhật firmware            : so bản trên loa với GitHub Releases mới nhất
  3) Cài bản build trong máy      : dùng file APK có sẵn (dev)
  4) Cấu hình máy chủ YouTube     : nhập mảng resolver [] hoặc máy chủ riêng
  5) Kiểm tra loa                 : phiên bản, trạng thái, máy chủ đang dùng
  6) Gỡ cài đặt / khôi phục       : gỡ R1-Y, bật lại app Phicomm gốc
  8) Thoát

Chạy không cần hỏi (dùng cho CI / script khác):
  python3 tools/r1y.py install --ip 192.168.2.20 --yes
  python3 tools/r1y.py update  --ip 192.168.2.20 --yes
  python3 tools/r1y.py status  --ip 192.168.2.20
  python3 tools/r1y.py config  --ip 192.168.2.20 --resolvers '[{"type":"custom","url":"http://192.168.2.130:8091"}]'

Nguồn firmware: GitHub Releases của BachDaThan/R1-Y (repo này). Không nguồn nào khác.
"""

import argparse
import json
import os
import platform
import re
import sys


def _setup_utf8_console():
    """
    Windows: console mặc định dùng cp1252/cp437 → in tiếng Việt là crash
    (UnicodeEncodeError: 'charmap' codec...). Chuyển sang UTF-8 và bật
    chế độ thay thế ký tự để KHÔNG BAO GIỜ crash vì chữ.
    """
    def _safe(stream):
        """Đổi sang UTF-8 nếu bảng mã hiện tại không in được tiếng Việt."""
        try:
            enc = (getattr(stream, "encoding", "") or "").lower()
            if enc.replace("-", "") in ("utf8", "utf_8"):
                return
            if hasattr(stream, "reconfigure"):
                stream.reconfigure(encoding="utf-8", errors="replace")
        except Exception:
            try:  # Python < 3.7
                import io
                stream.flush()
                if hasattr(sys, "stdout"):
                    sys.stdout = io.TextIOWrapper(sys.stdout.buffer, encoding="utf-8", errors="replace")
            except Exception:
                pass

    _safe(sys.stdout)
    _safe(sys.stderr)

    if os.name != "nt":
        return
    # đặt code page console sang 65001 (UTF-8)
    try:
        import ctypes
        ctypes.windll.kernel32.SetConsoleOutputCP(65001)
        ctypes.windll.kernel32.SetConsoleCP(65001)
    except Exception:
        pass


_setup_utf8_console()
import shutil
import socket
import subprocess
import sys
import tarfile
import tempfile
import time
import zipfile
import io
import urllib.error
import urllib.request

# ----------------------------------------------------------------- cấu hình chung
REPO = "BachDaThan/R1-Y"
GITHUB_API = "https://api.github.com/repos/%s/releases" % REPO
PACKAGE = "com.bachdathan.r1y"
APP_NAME = "R1-Y"
ACTIVITY = PACKAGE + "/.ui.MainActivity"
WEB_PORT = 8088
ADB_PORT = 5555
PHICOMM_APPS = [
    "com.phicomm.speaker.player",
    "com.phicomm.speaker.device",
    "com.phicomm.speaker.airskill",
    "com.phicomm.speaker.exceptionreporter",
]
PLATFORM_TOOLS_URL = {
    "Windows": "https://dl.google.com/android/repository/platform-tools-latest-windows.zip",
    "Darwin": "https://dl.google.com/android/repository/platform-tools-latest-darwin.zip",
    "Linux": "https://dl.google.com/android/repository/platform-tools-latest-linux.zip",
}
IS_WIN = platform.system() == "Windows"

# ----------------------------------------------------------------- trang trí
if IS_WIN:
    try:
        import ctypes
        ctypes.windll.kernel32.SetConsoleMode(ctypes.windll.kernel32.GetStdHandle(-11), 7)
    except Exception:
        pass


class C:
    RESET = "\033[0m"
    BOLD = "\033[1m"
    DIM = "\033[2m"
    RED = "\033[91m"
    GREEN = "\033[92m"
    YELLOW = "\033[93m"
    BLUE = "\033[94m"
    CYAN = "\033[96m"
    GRAY = "\033[90m"


def supports_color():
    if os.environ.get("NO_COLOR"):
        return False
    if IS_WIN and not os.environ.get("WT_SESSION") and not os.environ.get("TERM"):
        return False
    return sys.stdout.isatty()


if not supports_color():
    for _k in list(vars(C)):
        if _k.isupper():
            setattr(C, _k, "")


def title(text):
    print("\n%s%s══ %s ══%s" % (C.BOLD, C.CYAN, text, C.RESET))


def step(n, total, text):
    print("\n%s[%s/%s]%s %s%s%s" % (C.BOLD, n, total, C.RESET, C.BOLD, text, C.RESET))


def ok(text):
    print("  %s✔%s %s" % (C.GREEN, C.RESET, text))


def info(text):
    print("  %s•%s %s" % (C.BLUE, C.RESET, text))


def warn(text):
    print("  %s!%s %s" % (C.YELLOW, C.RESET, text))


def err(text):
    print("  %s✘%s %s" % (C.RED, C.RESET, text))


def die(text, code=1):
    err(text)
    print()
    sys.exit(code)


def is_termux():
    return ("com.termux" in os.environ.get("PREFIX", "")
            or os.path.isdir("/data/data/com.termux"))


def explain_error(msg):
    """Dịch lỗi kỹ thuật thành gợi ý dễ hiểu (không bắt người dùng mò)."""
    m = (msg or "").lower()
    if "command not found" in m or "not found" in m:
        return "Thiếu công cụ cần thiết — tool sẽ thử tự cài lại."
    if "no such file" in m:
        return "Không thấy file cần dùng — kiểm tra lại đường dẫn."
    if "permission denied" in m:
        return "Không có quyền truy cập — trên Termux chạy: termux-setup-storage"
    if "connection refused" in m or "timed out" in m or "timeout" in m:
        return "Loa chưa phản hồi — kiểm tra loa đã bật và cùng WiFi."
    if "unauthorized" in m:
        return "Loa chưa cho phép máy này — bấm 'Cho phép' trên màn hình loa."
    if "install_failed" in m:
        return "Cài đặt bị từ chối — tool đã thử gỡ bản cũ; nếu vẫn lỗi, chọn bản APK khác."
    if "http 404" in m:
        return "Máy chủ không có dữ liệu này (có thể repo chưa có bản phát hành)."
    if "ssl" in m or "certificate" in m or "eof" in m or "tls" in m:
        return ("Kết nối bị ngắt khi tải (SSL/TLS) — mạng chặn hoặc yếu. "
                "Thử lại, hoặc đổi WiFi/4G. Trên Termux có thể cài: pkg install openssl-tool")
    return msg


def ensure_env(quiet=False):
    """
    Kiểm tra môi trường và tự cài thư viện còn thiếu một cách an toàn.
    - Chỉ dùng trình quản lý gói chính thức của hệ điều hành / Termux.
    - Không tải và chạy script từ mạng.
    - Thiếu gì không cài được thì vẫn tiếp tục (báo nhẹ, không chặn).
    """
    missing = []
    if not shutil.which("adb") and not ADB:
        missing.append("adb")
    if is_termux():
        pkgs = []
        if missing:
            pkgs.append("android-tools")
        if not shutil.which("curl"):
            pkgs.append("curl")
        if not pkgs:
            return True
        if not quiet:
            info("Đang tự cài thêm: %s" % ", ".join(pkgs))
        try:
            run(["pkg", "install", "-y"] + pkgs, timeout=600, quiet=True)
        except Exception:
            try:
                run(["pkg", "update", "-y"], timeout=300, quiet=True)
                run(["pkg", "install", "-y"] + pkgs, timeout=600, quiet=True)
            except Exception as e:
                if not quiet:
                    warn("Không cài được tự động (%s) — vẫn tiếp tục." % explain_error(str(e)))
                return False
        if not quiet:
            ok("Đã chuẩn bị xong công cụ cần thiết.")
        return True

    # PC: chỉ nhắc bằng lệnh cài chuẩn, không tự chạy apt (cần sudo)
    if missing and not quiet:
        if platform.system() == "Linux":
            info("Nếu cần adb, cài bằng: sudo apt install android-tools-adb")
        elif platform.system() == "Darwin":
            info("Nếu cần adb: brew install android-platform-tools")
        else:
            info("Nếu cần adb: tải Android Platform Tools từ developer.android.com")
    return True


def ask(prompt, default=""):
    """Hỏi người dùng; trả về default khi nhập rỗng."""
    suffix = (" %s[%s]%s" % (C.GRAY, default, C.RESET)) if default else ""
    try:
        v = input("%s?%s %s%s: " % (C.YELLOW, C.RESET, prompt, suffix)).strip()
    except (EOFError, KeyboardInterrupt):
        print()
        die("Đã huỷ.")
    return v if v else default


def confirm(prompt, default=True):
    d = "Có" if default else "Không"
    v = ask("%s (%s/không)" % (prompt, d), d).lower()
    return v in ("c", "có", "co", "y", "yes", "1", "true", "đồng ý", "dong y")


def banner():
    print("%s" % C.CYAN)
    print("  ╔══════════════════════════════════════════════╗")
    print("  ║   %sR1-Y%s%s — Tool cài đặt firmware A→Z      ║" % (C.BOLD + C.RESET + C.CYAN, C.RESET, C.CYAN))
    print("  ║   %sLoa Phicomm R1 · nguồn: %s%s" % (C.DIM, REPO, C.RESET + C.CYAN))
    print("  ╚══════════════════════════════════════════════╝")
    print(C.RESET)


# ----------------------------------------------------------------- gọi lệnh
def run(cmd, timeout=600, check=False, quiet=False, input_text=None):
    """Chạy lệnh, trả về (returncode, stdout+stderr)."""
    if not quiet:
        print("    %s$ %s%s" % (C.GRAY, " ".join(cmd), C.RESET))
    try:
        p = subprocess.run(cmd, capture_output=True, text=True, timeout=timeout,
                           input=input_text,
                           encoding="utf-8", errors="replace")
        out = ((p.stdout or "") + (p.stderr or "")).strip()
        if check and p.returncode != 0:
            raise RuntimeError(out or ("lệnh lỗi: " + " ".join(cmd)))
        return p.returncode, out
    except FileNotFoundError:
        raise RuntimeError("Không tìm thấy lệnh: %s" % cmd[0])
    except subprocess.TimeoutExpired:
        raise RuntimeError("Lệnh quá thời gian chờ: %s" % " ".join(cmd))


# ----------------------------------------------------------------- adb
ADB = None


def find_adb():
    """Tìm adb: PATH -> biến môi trường SDK -> vị trí quen thuộc -> tải platform-tools."""
    global ADB
    if ADB:
        return ADB
    exe = "adb.exe" if IS_WIN else "adb"
    found = shutil.which("adb")
    if found:
        ADB = found
        return ADB

    bases = []
    for env in ("ANDROID_SDK_ROOT", "ANDROID_HOME"):
        if os.environ.get(env):
            bases.append(os.path.join(os.environ[env], "platform-tools"))
    home = os.path.expanduser("~")
    if IS_WIN:
        bases += [os.path.join(os.environ.get("LOCALAPPDATA", home), "Android", "Sdk", "platform-tools"),
                  os.path.join(home, "AppData", "Local", "Android", "Sdk", "platform-tools")]
    elif platform.system() == "Darwin":
        bases += [os.path.join(home, "Library", "Android", "sdk", "platform-tools"),
                  "/usr/local/share/android-sdk/platform-tools"]
    else:
        bases += [os.path.join(home, "Android", "Sdk", "platform-tools"),
                  "/usr/lib/android-sdk/platform-tools"]
    for b in bases:
        cand = os.path.join(b, exe)
        if os.path.isfile(cand):
            ADB = cand
            return ADB

    # Tải platform-tools vào thư mục tool
    if confirm("Chưa có adb trên máy. Tự tải Android Platform Tools (~15MB)?", True):
        ADB = download_platform_tools()
        if ADB:
            return ADB
    die("Cần adb. Tải thủ công: https://developer.android.com/tools/releases/platform-tools")


def download_platform_tools():
    sysname = platform.system()
    url = PLATFORM_TOOLS_URL.get(sysname)
    if not url:
        warn("Hệ điều hành %s chưa hỗ trợ tự tải, hãy cài adb thủ công." % sysname)
        return None
    dest = os.path.join(os.path.dirname(os.path.abspath(__file__)), "_tools")
    os.makedirs(dest, exist_ok=True)
    info("Đang tải platform-tools từ Google…")
    try:
        tmp = download_bytes(url, "platform-tools.zip")
    except Exception as e:
        warn("Tải thất bại: %s" % e)
        return None
    try:
        if url.endswith(".zip"):
            with zipfile.ZipFile(tmp) as z:
                z.extractall(dest)
        else:
            with tarfile.open(tmp) as t:
                t.extractall(dest)
    except Exception as e:
        warn("Giải nén thất bại: %s" % e)
        return None
    finally:
        try:
            os.unlink(tmp)
        except Exception:
            pass
    exe = "adb.exe" if IS_WIN else "adb"
    adb = os.path.join(dest, "platform-tools", exe)
    if os.path.isfile(adb):
        if not IS_WIN:
            os.chmod(adb, 0o755)
        ok("Đã có adb: %s" % adb)
        return adb
    warn("Không tìm thấy adb sau khi giải nén.")
    return None


def adb(*args, **kw):
    """Gọi adb. Luôn đặt thời gian chờ để không bị treo trên điện thoại."""
    kw.setdefault("timeout", 60)
    kw.pop("allow_fail", None)
    exe = find_adb()
    if not exe:
        raise RuntimeError("Chưa có adb — chạy: bash tools/termux_setup.sh")
    return run([exe] + list(args), **kw)


def adb_out(*args, **kw):
    """Chạy adb, trả về stdout. Không nuốt lỗi — ném RuntimeError kèm thông báo thật."""
    rc, out = adb(*args, **kw)
    if rc != 0 and not kw.get("allow_fail"):
        low = out.lower()
        if "unauthorized" in low:
            raise RuntimeError("Loa chưa cho phép máy này — bấm Cho phép trên màn hình loa")
        if "no devices" in low or "device not found" in low:
            raise RuntimeError("Chưa kết nối được loa qua ADB WiFi")
        if "offline" in low:
            raise RuntimeError("Loa đang offline — tắt/bật lại Gỡ lỗi ADB trên loa")
        if "more than one device" in low:
            raise RuntimeError("Có nhiều thiết bị ADB — chỉ bật 1 loa rồi chạy lại")
        raise RuntimeError(out.strip() or ("adb lỗi: " + " ".join(args)))
    return out


# ----------------------------------------------------------------- mạng / tải
def download_bytes(url, name_hint="file.bin", progress=True, retries=3):
    """Tải URL về file tạm, trả về đường dẫn. Tự thử lại khi mạng chập chờn."""
    last = None
    for attempt in range(1, retries + 1):
        try:
            return _download_once(url, name_hint, progress)
        except Exception as e:
            last = e
            msg = str(e).lower()
            fatal = any(k in msg for k in ("http 404", "http 403", "http 401"))
            if fatal or attempt == retries:
                break
            warn("Tải lỗi (lần %d/%d): %s — thử lại…" % (attempt, retries, explain_error(str(e))))
            time.sleep(2 * attempt)
    raise RuntimeError(explain_error(str(last)))


def _download_once(url, name_hint="file.bin", progress=True):
    """Một lần tải."""
    req = urllib.request.Request(url, headers={
        "User-Agent": "r1y-tool/1.4",
        "Accept": "application/vnd.github+json",
    })
    fd, path = tempfile.mkstemp(prefix="r1y-", suffix=os.path.splitext(name_hint)[1] or ".bin")
    total = 0
    try:
        with urllib.request.urlopen(req, timeout=60) as r, os.fdopen(fd, "wb") as f:
            total = int(r.headers.get("Content-Length") or 0)
            done = 0
            while True:
                chunk = r.read(262144)
                if not chunk:
                    break
                f.write(chunk)
                done += len(chunk)
                if progress and total > 0 and sys.stdout.isatty():
                    pct = done * 100 // total
                    sys.stdout.write("\r    %s[%3d%%] %5.1f / %5.1f MB%s" %
                                     (C.GRAY, pct, done / 1048576.0, total / 1048576.0, C.RESET))
                    sys.stdout.flush()
        if progress and total > 0 and sys.stdout.isatty():
            sys.stdout.write("\r" + " " * 60 + "\r")
        return path
    except urllib.error.HTTPError as e:
        try:
            os.unlink(path)
        except Exception:
            pass
        raise RuntimeError("HTTP %s (%s)" % (e.code, url))
    except Exception as e:
        try:
            os.unlink(path)
        except Exception:
            pass
        raise RuntimeError(str(e))


def http_json(url, timeout=25):
    req = urllib.request.Request(url, headers={
        "User-Agent": "r1y-tool/1.4",
        "Accept": "application/vnd.github+json",
    })
    with urllib.request.urlopen(req, timeout=timeout) as r:
        return json.loads(r.read().decode("utf-8", "replace"))


def http_post_json(url, payload, timeout=25):
    data = json.dumps(payload).encode("utf-8")
    req = urllib.request.Request(url, data=data, headers={
        "Content-Type": "application/json",
        "User-Agent": "r1y-tool/1.4",
    })
    with urllib.request.urlopen(req, timeout=timeout) as r:
        body = r.read().decode("utf-8", "replace")
        try:
            return json.loads(body)
        except Exception:
            return {"raw": body}


def local_ip():
    s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    try:
        s.connect(("8.8.8.8", 9))
        return s.getsockname()[0]
    except Exception:
        return "127.0.0.1"
    finally:
        s.close()


def scan_lan_for_adb(port=ADB_PORT, timeout=0.25, max_workers=64):
    """Quét nhanh LAN tìm cổng 5555 (ADB over WiFi) mở."""
    ip = local_ip()
    if not ip.startswith(("192.168.", "10.", "172.")):
        return []
    base = ip.rsplit(".", 1)[0] + "."
    from concurrent.futures import ThreadPoolExecutor

    def probe(i):
        host = base + str(i)
        try:
            s = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
            s.settimeout(timeout)
            if s.connect_ex((host, port)) == 0:
                return host
        except Exception:
            pass
        finally:
            try:
                s.close()
            except Exception:
                pass
        return None

    found = []
    with ThreadPoolExecutor(max_workers=max_workers) as ex:
        for r in ex.map(probe, range(1, 255)):
            if r:
                found.append(r)
    return found


# ----------------------------------------------------------------- chọn loa
def devices_raw():
    """Trả về list (serial, trạng thái) từ `adb devices` — gồm cả unauthorized/offline."""
    out = adb_out("devices", allow_fail=True)
    rows = []
    for line in out.splitlines()[1:]:
        parts = line.split()
        if len(parts) >= 2:
            rows.append((parts[0], parts[1]))
    return rows


def list_devices():
    return [serial for serial, state in devices_raw() if state == "device"]


def explain_device_states(rows):
    """Dịch trạng thái adb sang việc người dùng cần làm."""
    hints = []
    for serial, state in rows:
        if state == "unauthorized":
            hints.append("%s: chưa cho phép → bấm “Cho phép” trên màn hình loa" % serial)
        elif state == "offline":
            hints.append("%s: offline → tắt/bật lại Gỡ lỗi ADB trên loa" % serial)
        elif state == "connecting":
            hints.append("%s: đang kết nối → chờ thêm vài giây" % serial)
        elif state == "device":
            hints.append("%s: sẵn sàng" % serial)
        else:
            hints.append("%s: %s" % (serial, state))
    return hints


def connect_device(ip=None, interactive=True):
    """Bảo đảm có 1 loa đang kết nối, trả về (serial, ip)."""
    find_adb()
    title("Tìm loa Phicomm R1")

    if ip:
        ip = re.sub(r":\d+$", "", ip)
        info("Kết nối ADB WiFi tới %s…" % ip)
        adb("start-server", quiet=True, allow_fail=True)
        rc, out = adb("connect", "%s:%d" % (ip, ADB_PORT), timeout=30)
        txt = out.strip()
        low = txt.lower()
        if "failed to authenticate" in low or "authentication" in low:
            warn("Loa chưa cho phép máy này (authentication failed).")
            print("  → Nhìn màn hình loa: bấm %sCho phép%s (tick “luôn cho phép”)." % (C.BOLD, C.RESET))
            print("  → Không thấy hộp thoại? Cắm cáp USB một lần rồi chạy lại.")
        elif "refused" in low or "cannot connect" in low or "unable" in low:
            warn("Loa không mở cổng 5555: %s" % (txt or "không rõ"))
            print("  • Loa phải %sbật Gỡ lỗi ADB%s (Tùy chọn nhà phát triển)" % (C.BOLD, C.RESET))
            print("  • Sau khi bật ADB, loa thường cần %skhởi động lại 1 lần%s" % (C.BOLD, C.RESET))
        elif "connected" in low or "already" in low:
            ok("Đã kết nối %s" % ip)
        else:
            warn("adb trả lời: %s" % (txt or "(không có gì)"))

    rows = devices_raw()
    devs = [d for d, st in rows if st == "device"]
    if not devs and rows:
        warn("Có kết nối TCP nhưng chưa dùng được:")
        for h in explain_device_states(rows):
            print("  • %s" % h)
    if devs:
        ok("Thấy %d thiết bị: %s" % (len(devs), ", ".join(devs)))
        # Ưu tiên serial dạng ip:5555
        serial = sorted(devs, key=lambda d: (0 if ":" in d else 1))[0]
        return serial, (serial.split(":")[0] if ":" in serial else None)

    if not interactive:
        rows = devices_raw()
        msg = ["Chưa kết nối được loa qua ADB."]
        if ip:
            msg.append("  • Đã thử %s:%d nhưng chưa dùng được" % (ip, ADB_PORT))
        for h in explain_device_states(rows):
            msg.append("  • %s" % h)
        if not rows:
            msg += ["  • Loa chưa bật Gỡ lỗi ADB, hoặc chưa cho phép máy này",
                    "  • Bật ADB rồi khởi động lại loa, sau đó chạy lại",
                    "  • Kiểm tra điện thoại và loa cùng 1 WiFi"]
        if ip:
            msg.append("  • Không cần adb: python3 tools/r1y.py webinstall --ip %s" % ip)
        die("\n".join(msg))

    print()
    warn("Chưa thấy loa nào.")
    print("  Cách bật ADB trên loa Phicomm R1:")
    print("    1. Cài đặt → Giới thiệu / Tùy chọn nhà phát triển → bật %sGỡ lỗi ADB/USB%s" % (C.BOLD, C.RESET))
    print("    2. Ghi lại IP loa ở Cài đặt → WiFi (hoặc xem trên router)")
    print("    3. Loa và máy tính phải %scùng một WiFi%s" % (C.BOLD, C.RESET))
    print()

    if confirm("Tự quét WiFi nhà để tìm loa (cổng 5555)?", True):
        info("Đang quét %s…" % (local_ip().rsplit(".", 1)[0] + ".0/24"))
        hosts = scan_lan_for_adb()
        if hosts:
            ok("Thấy %d máy mở cổng 5555: %s" % (len(hosts), ", ".join(hosts)))
            host = hosts[0] if len(hosts) == 1 else ask("Chọn IP loa", hosts[0])
            adb("connect", "%s:%d" % (host, ADB_PORT), timeout=30)
            devs = list_devices()
            if devs:
                ok("Đã kết nối loa %s" % host)
                return devs[0], host
        else:
            warn("Không quét thấy máy nào mở cổng 5555.")

    manual = ask("Nhập IP loa (Enter để dừng)")
    if not manual:
        die("Không có loa. Hãy bật ADB WiFi rồi chạy lại tool.")
    manual = re.sub(r":\d+$", "", manual)
    devs = []
    for attempt in range(1, 4):
        rc, out = adb("connect", "%s:%d" % (manual, ADB_PORT), timeout=30)
        time.sleep(1)
        rows = devices_raw()
        devs = [d for d, st in rows if st == "device"]
        if devs:
            break
        if rows:
            warn("Lần %d/%d: %s" % (attempt, 3, ", ".join(explain_device_states(rows))))
            if any(st == "unauthorized" for _, st in rows):
                print("  → Giờ hãy nhìn màn hình loa và bấm %sCho phép%s, tool sẽ thử lại."
                      % (C.BOLD, C.RESET))
                for _ in range(30):
                    time.sleep(2)
                    rows = devices_raw()
                    devs = [d for d, st in rows if st == "device"]
                    if devs:
                        break
                if devs:
                    break
        elif attempt < 3:
            time.sleep(2)
    if not devs:
        detail = out.strip()
        print()
        rows = devices_raw()
        if rows:
            for h in explain_device_states(rows):
                print("  • %s" % h)
        warn("Chưa kết nối được %s:%d" % (manual, ADB_PORT))
        if detail:
            print("  adb trả lời: %s%s%s" % (C.DIM, detail, C.RESET))
        low = detail.lower()
        if "authenticate" in low or "unauthorized" in low:
            print("  → Trên loa có hộp thoại %s“Cho phép gỡ lỗi USB”%s — bấm Cho phép." % (C.BOLD, C.RESET))
        elif "refused" in low or "unable" in low or rc != 0:
            print("  → Cổng 5555 chưa mở: bật %sGỡ lỗi ADB%s trên loa, rồi %skhởi động lại loa%s." % (C.BOLD, C.RESET, C.BOLD, C.RESET))
        print("  • Kiểm tra điện thoại và loa cùng 1 WiFi (thử mở %shttp://%s:8088%s)" % (C.BOLD, manual, C.RESET))
        print("  • Thử tay: adb connect %s:%d" % (manual, ADB_PORT))
        print("  • Không dùng được adb? Vẫn cài được bằng: %spython3 tools/r1y.py webinstall --ip %s%s"
              % (C.BOLD, manual, C.RESET))
        die("Dừng ở bước kết nối ADB.")
    return devs[0], manual


def check_is_r1(serial):
    """Kiểm tra thiết bị có phải loa Phicomm R1 không (cảnh báo, không chặn)."""
    model = adb_out("-s", serial, "shell", "getprop", "ro.product.model").strip()
    brand = adb_out("-s", serial, "shell", "getprop", "ro.product.brand").strip()
    sdk = adb_out("-s", serial, "shell", "getprop", "ro.build.version.sdk").strip()
    info("Thiết bị: %s %s (Android API %s)" % (brand, model, sdk))
    low = (brand + " " + model).lower()
    if "phicomm" in low or "r1" in low:
        ok("Đúng là loa Phicomm R1.")
        return True
    warn("Không nhận ra Phicomm R1 (vẫn tiếp tục).")
    return False


def device_version(serial):
    out = adb_out("-s", serial, "shell", "dumpsys", "package", PACKAGE)
    if "Unable" in out or "Unknown package" in out or not out.strip():
        return None, None
    m_name = re.search(r"versionName=(\S+)", out)
    m_code = re.search(r"versionCode=(\d+)", out)
    if not m_name and not m_code:
        return None, None
    return (m_name.group(1) if m_name else "?"), (int(m_code.group(1)) if m_code else 0)


def device_ip(serial, fallback=None):
    out = adb_out("-s", serial, "shell", "ip", "-f", "inet", "addr", "show", "wlan0")
    m = re.search(r"inet\s+(\d+\.\d+\.\d+\.\d+)", out)
    if m:
        return m.group(1)
    if fallback:
        return fallback
    out = adb_out("-s", serial, "shell", "getprop", "dhcp.wlan0.ipaddress").strip()
    return out or None


# ----------------------------------------------------------------- GitHub releases
def gh_release(include_prerelease=False):
    """Lấy bản release mới nhất từ repo này."""
    rels = http_json(GITHUB_API + "?per_page=20")
    for r in rels:
        if r.get("draft"):
            continue
        if r.get("prerelease") and not include_prerelease:
            continue
        assets = r.get("assets") or []
        apk = None
        for a in assets:
            name = a.get("name", "").lower()
            if not name.endswith(".apk") or "debug" in name or "unsigned" in name:
                continue
            apk = a
            break
        if apk is None:
            for a in assets:
                if a.get("name", "").lower().endswith(".apk"):
                    apk = a
                    break
        if apk is None:
            continue
        return {
            "tag": (r.get("tag_name") or "").lstrip("vV"),
            "name": r.get("name") or r.get("tag_name"),
            "notes": r.get("body") or "",
            "published": r.get("published_at") or "",
            "apk_url": apk["browser_download_url"],
            "apk_name": apk["name"],
            "apk_size": apk.get("size") or 0,
            "prerelease": bool(r.get("prerelease")),
        }
    return None


def version_tuple(v):
    parts = re.findall(r"\d+", v or "")
    return tuple(int(x) for x in parts[:4]) if parts else (0,)


# ----------------------------------------------------------------- tải APK
def local_apk_from_build():
    """Tìm APK vừa build trong repo (dev)."""
    here = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
    cands = [
        os.path.join(here, "app", "app", "build", "outputs", "apk", "release", "app-release.apk"),
        os.path.join(here, "app", "app", "build", "outputs", "apk", "debug", "app-debug.apk"),
    ]
    # Release có thể bị đổi tên bởi CI
    rel_dir = os.path.join(here, "app", "app", "build", "outputs", "apk", "release")
    if os.path.isdir(rel_dir):
        for f in sorted(os.listdir(rel_dir)):
            if f.endswith(".apk"):
                cands.insert(0, os.path.join(rel_dir, f))
    for c in cands:
        if os.path.isfile(c):
            return c
    return None


def gh_debug_asset(tag):
    """Tìm file *debug*.apk trong release (nếu repo có đăng kèm)."""
    url = "https://api.github.com/repos/%s/releases/tags/%s" % (REPO, tag)
    try:
        data = http_json(url, timeout=30)
    except Exception:
        return None
    for a in data.get("assets", []):
        name = a.get("name", "")
        if name.lower().endswith(".apk") and "debug" in name.lower():
            return {"tag": tag, "prerelease": False, "apk_name": name,
                    "apk_url": a["browser_download_url"], "apk_size": a.get("size", 0),
                    "published": data.get("published_at"), "unsigned": False}
    return None


def apk_is_signed(path):
    """APK đã ký chưa? Kiểm tra META-INF có .RSA/.DSA/.EC/.SF (chữ ký v1)."""
    try:
        import zipfile
        with zipfile.ZipFile(path) as z:
            for n in z.namelist():
                if n.startswith("META-INF/") and n.upper().endswith((".RSA", ".DSA", ".EC", ".SF")):
                    return True
    except Exception:
        return True  # không đọc được thì đừng chặn oan
    return False


def warn_if_unsigned(rel):
    """APK chưa ký thì Android không cài được — nói rõ và đề xuất bản debug."""
    if not rel.get("unsigned"):
        return False
    warn("Bản APK này CHƯA ĐƯỢC KÝ (unsigned) — Android sẽ từ chối cài.")
    print("  Lý do: bản release cần keystore để ký, repo chưa cấu hình.")
    print("  Cách xử lý (chọn 1):")
    print("    1. Cài bản debug (có sẵn, ký bằng key debug, cài được ngay):")
    print("       python3 tools/r1y.py install --ip <IP_LOA> --source debug")
    print("    2. Tự ký: xem docs/TERMUX.md mục “Ký APK release”")
    return True


def get_apk(source="github", apk_path=None, include_prerelease=False):
    """
    Trả về (đường dẫn APK, tuple phiên bản, thông tin release|None).
    source: github (bản phát hành) | debug (ưu tiên APK debug nếu có) | local
    """
    prefer_debug = (source == "debug")
    if source == "local":
        p = apk_path or local_apk_from_build()
        if not p or not os.path.isfile(p):
            die("Không thấy APK để cài. Build trước: cd app && ./gradlew assembleRelease")
        ok("Dùng APK có sẵn: %s" % p)
        return p, None, None

    if apk_path:
        if not os.path.isfile(apk_path):
            die("File APK không tồn tại: %s" % apk_path)
        ok("Dùng APK: %s" % apk_path)
        return apk_path, None, None

    title("Lấy firmware từ GitHub")
    info("Nguồn: github.com/%s (bản phát hành)" % REPO)
    try:
        rel = gh_release(include_prerelease)
    except urllib.error.HTTPError as e:
        if e.code == 404:
            die("Repo %s chưa có bản phát hành (Release) nào.\n"
                "  → Chủ repo cần tạo Release: mở Actions, hoặc git tag v1.5.0 && git push origin v1.5.0\n"
                "  → Hoặc chọn mục 3 để cài bản build trong máy." % REPO)
        die("GitHub trả lỗi HTTP %s." % e.code)
    except Exception as e:
        die("Không gọi được GitHub API: %s\n  → Kiểm tra mạng/Internet." % e)
    if not rel:
        die("Repo %s chưa có bản phát hành (Release) nào kèm file .apk.\n"
            "  → Cần tạo Release có APK trước (CI sẽ tự tạo khi gắn tag như v1.6.0).\n"
            "  → Hoặc cài bằng file APK có sẵn trong máy:\n"
            "      python3 tools/r1y.py install --ip <IP_LOA> --apk /sdcard/Download/app-release.apk\n"
            "  → Hoặc nhờ loa tự tải+cài (không cần file):\n"
            "      python3 tools/r1y.py webinstall --ip <IP_LOA>" % REPO)

    ok("Bản mới nhất: v%s%s" % (rel["tag"], " (thử nghiệm)" if rel["prerelease"] else ""))
    # APK tên có chữ "unsigned" → Android sẽ từ chối cài, cảnh báo ngay
    rel["unsigned"] = "unsigned" in (rel["apk_name"] or "").lower()
    if rel["apk_size"]:
        info("APK: %s (%.1f MB)" % (rel["apk_name"], rel["apk_size"] / 1048576.0))
    if rel["published"]:
        info("Phát hành: %s" % rel["published"][:10])

    if prefer_debug:
        dbg = gh_debug_asset(rel["tag"])
        if dbg:
            info("Dùng bản DEBUG (đã ký bằng key debug): %s" % dbg["apk_name"])
            rel = dbg
        else:
            warn("Bản phát hành không có APK debug — dùng bản release.")

    if warn_if_unsigned(rel) and not confirm("Vẫn tải và thử cài bản chưa ký?", False):
        die("Đã dừng. Dùng: --source debug  hoặc  --apk <file.apk>")

    info("Đang tải APK…")
    try:
        path = download_bytes(rel["apk_url"], rel["apk_name"])
    except Exception as e:
        die("Tải APK thất bại: %s" % e)
    size = os.path.getsize(path)
    if size < 100 * 1024:
        die("File tải về quá nhỏ (%d bytes) — có thể lỗi mạng." % size)
    ok("Đã tải %.1f MB" % (size / 1048576.0))
    if not apk_is_signed(path):
        warn("APK tải về CHƯA ĐƯỢC KÝ — Android sẽ báo “ứng dụng chưa được cài đặt”.")
        print("  Cách xử lý:")
        print("    • Nhờ chủ repo bật ký release (xem docs/TERMUX.md → “Ký APK release”)")
        print("    • Hoặc cài bản debug: python3 tools/r1y.py install --ip <IP> --source debug")
        if not confirm("Vẫn thử cài bản chưa ký?", False):
            die("Đã dừng.")
    return path, version_tuple(rel["tag"]), rel


# ----------------------------------------------------------------- cài đặt
def adb_install(serial, apk_path):
    """Cài APK: push + pm install (tương thích Android 5.1), fallback adb install."""
    remote = "/data/local/tmp/r1y-install.apk"
    info("Đang chuyển APK lên loa…")
    adb("push", apk_path, remote, timeout=900)
    info("Đang cài…")
    rc, out = adb("-s", serial, "shell", "pm", "install", "-t", "-r", remote, timeout=900)
    if rc != 0 or "Success" not in out:
        warn("pm install lỗi, thử adb install: %s" % out.splitlines()[-1] if out else "")
        rc2, out2 = adb("-s", serial, "install", "-t", "-r", apk_path, timeout=900)
        if rc2 != 0 or "Success" not in out2:
            adb("-s", serial, "shell", "rm", remote)
            die("Cài APK thất bại:\n%s" % (out2 or out))
    adb("-s", serial, "shell", "rm", remote)
    ok("Đã cài %s" % APP_NAME)


def grant_permissions(serial):
    perms = [
        "android.permission.RECORD_AUDIO",
        "android.permission.WRITE_EXTERNAL_STORAGE",
        "android.permission.READ_EXTERNAL_STORAGE",
    ]
    for p in perms:
        adb("-s", serial, "shell", "pm", "grant", PACKAGE, p, quiet=True)
    ok("Đã cấp quyền thu âm + bộ nhớ")


def hide_phicomm(serial):
    hidden = 0
    for app in PHICOMM_APPS:
        rc, out = adb("-s", serial, "shell", "pm", "hide", app, quiet=True)
        if "new hidden state: true" in out or rc == 0:
            hidden += 1
    if hidden:
        ok("Đã tắt %d app Phicomm gốc (vẫn giữ để khôi phục)" % hidden)
    else:
        info("Không có app Phicomm gốc cần tắt.")


def check_root(serial):
    out = adb_out("-s", serial, "shell", "su", "-c", "id")
    if "uid=0" in out:
        ok("Có root — đèn LED sẽ hoạt động.")
        return True
    warn("Chưa root — đèn LED sẽ tắt, các tính năng khác vẫn chạy.")
    return False


def enable_autostart(serial):
    adb("-s", serial, "shell", "pm", "enable", PACKAGE + "/.receiver.BootReceiver", quiet=True)
    adb("-s", serial, "shell", "dumpsys", "deviceidle", "whitelist", "+" + PACKAGE, quiet=True)
    ok("Đã bật tự khởi động khi cắm điện / bật loa")


def start_app(serial):
    adb("-s", serial, "shell", "am", "start", "-n", ACTIVITY, quiet=True)
    time.sleep(3)
    ok("Đã khởi động %s" % APP_NAME)


def reboot_and_wait(serial, old_ip=None, timeout=120):
    """Khởi động lại loa rồi chờ nó lên lại. Trả về IP mới (nếu lấy được)."""
    title("Khởi động lại loa")
    info("Đang khởi động lại (mất khoảng 30-60 giây)…")
    try:
        adb("-s", serial, "reboot", quiet=True)
    except Exception as e:
        warn("Không ra lệnh khởi động lại được: %s" % explain_error(str(e)))
        return None

    # chờ mất kết nối rồi có lại
    t0 = time.time()
    gone = False
    while time.time() - t0 < timeout:
        devs = []
        try:
            devs = list_devices()
        except Exception:
            pass
        if not devs:
            gone = True
        elif gone:
            ok("Loa đã lên lại")
            break
        time.sleep(3)

    # chờ boot xong (tối đa 60 giây, dừng sớm khi loa phản hồi bình thường)
    t1 = time.time()
    while time.time() - t1 < 60:
        try:
            out = adb_out("-s", serial, "shell", "getprop", "sys.boot_completed").strip()
            ping = adb_out("-s", serial, "shell", "echo", "r1y-ok").strip()
        except Exception:
            out, ping = "", ""
        if out == "1" or "r1y-ok" in ping:
            break
        time.sleep(3)

    # nếu mất kết nối ADB WiFi thì thử nối lại
    if serial and ":" in serial:
        adb("connect", serial, timeout=20, quiet=True)
    time.sleep(2)

    new_ip = None
    try:
        new_ip = device_ip(serial, old_ip)
    except Exception:
        pass
    ok("Khởi động lại xong")
    if new_ip:
        info("IP loa: %s" % new_ip)
        info("Giao diện: http://%s:%d" % (new_ip, WEB_PORT))
    return new_ip


def wait_web(ip, seconds=15):
    """Chờ giao diện web loa (cổng 8088) lên; trả về JSON /api/status hoặc None."""
    if not ip:
        return None
    sys.stdout.write("  %s•%s Chờ giao diện loa ở http://%s:%d " % (C.BLUE, C.RESET, ip, WEB_PORT))
    sys.stdout.flush()
    t0 = time.time()
    while time.time() - t0 < seconds:
        try:
            st = http_json("http://%s:%d/api/status" % (ip, WEB_PORT), timeout=4)
            print("%s✔%s" % (C.GREEN, C.RESET))
            return st
        except Exception:
            sys.stdout.write(".")
            sys.stdout.flush()
            time.sleep(1.5)
    print("%s!%s" % (C.YELLOW, C.RESET))
    warn("Chưa mở được giao diện web (loa có thể đang khởi động, thử lại sau).")
    return None


# ----------------------------------------------------------------- cấu hình web
def configure_loa(ip, resolvers=None, music_server=None, autodiscover=None, interactive=True,
                  ai_server=None, ai_url=None, ai_token=None, audio_quality=None):
    """Cấu hình loa qua API 8088 (YouTube, máy chủ AI, chất lượng âm thanh)."""
    if not ip:
        warn("Không biết IP loa — bỏ qua cấu hình web.")
        return False

    if resolvers is None and interactive:
        title("Cấu hình máy chủ YouTube")
        print("  %s[] = loa tự dò máy chủ công cộng (mặc định, khuyên dùng)%s" % (C.DIM, C.RESET))
        print("  %sMáy chủ riêng: [{\"type\":\"custom\",\"url\":\"http://192.168.2.130:8091\"}]%s\n"
              % (C.DIM, C.RESET))
        cur = None
        try:
            cur = http_json("http://%s:%d/api/youtube/resolvers" % (ip, WEB_PORT), timeout=5)
        except Exception:
            pass
        if cur is not None:
            info("Hiện tại: %s" % json.dumps(cur, ensure_ascii=False))
        raw = ask("Mảng JSON resolver", "[]")
        try:
            resolvers = json.loads(raw) if raw.strip().startswith("[") else raw
        except Exception:
            warn("JSON không hợp lệ — giữ nguyên cấu hình cũ.")
            return False
        if interactive and not isinstance(resolvers, list):
            resolvers = []

    payload = {}
    if resolvers is not None:
        payload["youtube_resolvers"] = resolvers
    if ai_server is not None:
        payload["ai_server"] = ai_server
    if ai_url is not None:
        payload["ai_server_url"] = ai_url
    if ai_token is not None:
        payload["ai_token"] = ai_token
    if audio_quality is not None:
        payload["audio_quality"] = audio_quality

    # Hỏi chọn máy chủ AI (mặc định giữ xiaozhi.me)
    if interactive and ai_server is None:
        if confirm("Đổi máy chủ AI (mặc định là xiaozhi.me)?", False):
            title("Máy chủ AI")
            print("   1  xiaozhi.me (chính thức) — mặc định")
            print("   2  Xiaozhi tự dựng (esp32-server)")
            print("   3  Tự nhập URL khác")
            c = ask("Chọn", "1")
            if c == "2":
                payload["ai_server"] = "xiaozhi_self"
                payload["ai_server_url"] = ask("URL WebSocket", "ws://192.168.1.100:8000/xiaozhi/v1/")
            elif c == "3":
                payload["ai_server"] = "custom"
                payload["ai_server_url"] = ask("URL WebSocket (ws:// hoặc wss://)")
            t = ask("Token/API key (Enter bỏ qua, dùng token kích hoạt)")
            if t:
                payload["ai_token"] = t

    # Hỏi chất lượng âm thanh
    if interactive and audio_quality is None:
        if confirm("Dùng âm thanh chất lượng cao (Opus, nghe hay hơn)?", True):
            payload["audio_quality"] = "high"
        else:
            payload["audio_quality"] = "compat"
    if music_server is not None:
        payload["music_server"] = music_server
    if autodiscover is not None:
        payload["youtube_autodiscover"] = bool(autodiscover)

    if interactive and confirm("Đặt máy chủ nhạc riêng (music_server)?", False):
        payload["music_server"] = ask("URL máy chủ nhạc (Enter bỏ trống)")

    if not payload:
        return False
    try:
        r = http_post_json("http://%s:%d/api/settings" % (ip, WEB_PORT), payload, timeout=15)
        if isinstance(r, dict) and r.get("ok"):
            ok("Đã lưu cấu hình vào loa.")
            return True
        warn("Loa trả: %s" % r)
    except Exception as e:
        warn("Không lưu được cấu hình: %s" % e)
    return False


def show_status(ip, serial=None):
    title("Trạng thái loa")
    if serial:
        ver, code = device_version(serial)
        if ver:
            ok("Bản trên loa: v%s (code %s)" % (ver, code))
        else:
            warn("Chưa cài %s" % APP_NAME)
    if not ip:
        warn("Không biết IP loa.")
        return
    try:
        st = http_json("http://%s:%d/api/status" % (ip, WEB_PORT), timeout=6)
    except Exception:
        warn("Không mở được giao diện web http://%s:%d" % (ip, WEB_PORT))
        info("Loa có thể đang khởi động, hoặc IP đã đổi. Thử lại sau 30 giây.")
        return
    ok("Giao diện: http://%s:%d" % (ip, WEB_PORT))
    info("Phiên bản: v%s" % st.get("version", "?"))
    mode = "máy chủ công cộng (tự dò)" if st.get("mode") == "public" else "máy chủ riêng của bạn"
    info("Chế độ YouTube: %s" % mode)
    if st.get("discoveredPiped"):
        info("Piped đang dùng: %s" % ", ".join(st["discoveredPiped"].split()))
    if st.get("discoveredInvidious"):
        info("Invidious đang dùng: %s" % ", ".join(st["discoveredInvidious"].split()))
    if st.get("musicServer"):
        info("Máy chủ nhạc: %s" % st["musicServer"])
    info("Máy chủ AI: %s (%s)" % (st.get("aiServer", "?"), st.get("aiUrl", "")))
    info("Chất lượng âm thanh: %s" % ("cao (Opus)" if st.get("audioQuality") == "high" else "tương thích (AAC)"))
    if st.get("hotspotEnabled"):
        info("Phát WiFi: bật (SSID %s)" % st.get("hotspotSsid", "R1-Y"))
    info("Nguồn cập nhật: github.com/%s" % (st.get("updateRepo") or REPO))
    try:
        rd = http_json("http://%s:%d/api/youtube/discover" % (ip, WEB_PORT), timeout=60)
        if rd.get("found"):
            ok("Dò được %d máy chủ công cộng sẵn sàng." % rd["found"])
    except Exception:
        pass


# ----------------------------------------------------------------- luồng chính
def flow_install(args):
    banner()
    serial, ip = connect_device(args.ip, interactive=not args.yes)
    check_is_r1(serial)
    apk_path = None
    if args.apk:
        apk_path = args.apk
    elif args.source == "local":
        apk_path = local_apk_from_build()
        if not apk_path:
            die("Không thấy APK build trong máy (app/app/build/outputs/apk/...).")
    apk, want_ver, rel = get_apk(args.source, apk_path, getattr(args, "pre", False))
    ip = device_ip(serial, ip)

    total = 7
    step(1, total, "Tắt app Phicomm gốc (giữ lại, không xoá)")
    hide_phicomm(serial)

    step(2, total, "Gỡ bản cũ (nếu có)")
    old_ver, _ = device_version(serial)
    if old_ver:
        adb("-s", serial, "uninstall", PACKAGE, quiet=True)
        ok("Đã gỡ v%s" % old_ver)
    else:
        info("Chưa có bản cũ.")

    step(3, total, "Cài firmware %s" % APP_NAME)
    adb_install(serial, apk)

    step(4, total, "Cấp quyền + kiểm tra root")
    grant_permissions(serial)
    check_root(serial)

    step(5, total, "Bật tự khởi động")
    enable_autostart(serial)

    step(6, total, "Khởi động và chờ giao diện")
    start_app(serial)
    ip = device_ip(serial, ip)
    st = wait_web(ip)

    step(7, total, "Cấu hình máy chủ YouTube")
    if args.yes and not args.resolvers and not getattr(args, "music_server", None):
        info("Bỏ qua (đang để [] = loa tự dò máy chủ công cộng).")
    if ip:
        configure_loa(ip,
                      resolvers=json.loads(args.resolvers) if args.resolvers else None,
                      music_server=getattr(args, "music_server", None),
                      autodiscover=None,
                      interactive=not args.yes,
                      ai_server=getattr(args, "ai_server", None),
                      ai_url=getattr(args, "ai_url", None),
                      ai_token=getattr(args, "ai_token", None),
                      audio_quality=getattr(args, "audio_quality", None))
    else:
        warn("Không lấy được IP loa — mở giao diện bằng tay để cấu hình.")

    # Bước 8: khởi động lại loa (mặc định hỏi; --yes thì theo --reboot/--no-reboot)
    want_reboot = getattr(args, "reboot", None)
    if want_reboot is None:
        if args.yes:
            want_reboot = True
        else:
            step(8, 8, "Khởi động lại loa")
            want_reboot = confirm("Khởi động lại loa ngay để áp dụng sạch?", True)
    if want_reboot:
        new_ip = reboot_and_wait(serial, ip)
        if new_ip:
            ip = new_ip
        st2 = wait_web(ip, 20)
        if st2:
            st = st2

    finish(ip, st)


def flow_update(args):
    banner()
    serial, ip = connect_device(args.ip, interactive=not args.yes)
    check_is_r1(serial)
    ip = device_ip(serial, ip)
    cur_ver, cur_code = device_version(serial)
    if cur_ver:
        info("Bản trên loa: v%s (code %s)" % (cur_ver, cur_code))
    else:
        warn("%s chưa được cài — nên dùng mục 1." % APP_NAME)

    if args.apk:
        apk, want_ver, rel = get_apk("local", args.apk)
    else:
        apk, want_ver, rel = get_apk("github", None, getattr(args, "pre", False))

    if cur_ver and want_ver and version_tuple(cur_ver) >= want_ver and not args.force:
        ok("Loa đã ở bản mới nhất (v%s). Không cần cập nhật." % cur_ver)
        if not confirm("Cài lại (ghi đè) anyway?", False):
            show_status(ip, serial)
            return

    title("Cập nhật firmware")
    step(1, 3, "Cài bản mới")
    adb_install(serial, apk)
    step(2, 3, "Cấp quyền + tự khởi động")
    grant_permissions(serial)
    enable_autostart(serial)
    step(3, 3, "Khởi động lại")
    adb("-s", serial, "shell", "am", "force-stop", PACKAGE, quiet=True)
    start_app(serial)
    st = wait_web(ip)

    want_reboot = getattr(args, "reboot", None)
    if want_reboot is None and not args.yes:
        want_reboot = confirm("Khởi động lại loa để áp dụng bản mới?", True)
    if want_reboot:
        new_ip = reboot_and_wait(serial, ip)
        if new_ip:
            ip = new_ip
        st2 = wait_web(ip, 20)
        if st2:
            st = st2
    finish(ip, st)


def flow_hotspot(args):
    """Bật/tắt phát WiFi từ loa (để điện thoại nối trực tiếp vào cấu hình)."""
    serial, ip = connect_device(args.ip, interactive=not args.yes)
    ip = device_ip(serial, ip)
    on = True if args.on is None else args.on
    ssid = args.ssid or "R1-Y"
    pw = args.password or "r1y12345678"
    title("Phát WiFi từ loa")
    if ip:
        try:
            r = http_post_json("http://%s:%d/api/hotspot" % (ip, WEB_PORT),
                               {"on": on, "ssid": ssid, "password": pw}, timeout=20)
            if r.get("ok"):
                ok("Đã bật phát WiFi: %s" % r.get("ssid", ssid))
                info("Điện thoại kết nối WiFi '%s' (mật khẩu %s)" % (ssid, pw))
                info("Rồi mở: %s" % r.get("url", "http://192.168.43.1:8088"))
                return
            warn("Loa không bật được hotspot: %s" % r.get("warning", r.get("hint", "")))
        except Exception as e:
            warn("Không gọi được API loa: %s" % e)
    # Dự phòng: bật bằng ADB (cần root)
    if args.yes or confirm("Thử bật bằng ADB (cần root)?", True):
        adb("-s", serial, "shell", "su", "-c",
            'cmd wifi start-softap "%s" wpa2 "%s"' % (ssid, pw), quiet=True)
        hid = adb_out("-s", serial, "shell", "ip", "-f", "inet", "addr", "show", "wlan0")
        m = re.search(r"inet\s+(\d+\.\d+\.\d+\.\d+)", hid)
        info("Kiểm tra WiFi loa bằng tay: adb shell ip -f inet addr show wlan0")
        if m:
            ok("IP hiện tại của loa: %s" % m.group(1))


def flow_wifi(args):
    """Cấu hình WiFi nhà cho loa qua giao diện web (chế độ cài đặt)."""
    ip = args.ip or "192.168.43.1"
    title("Cấu hình WiFi cho loa")
    print("  %sLoa phải đang ở chế độ cài đặt (giữ nút tròn) — WiFi: %s%s"
          % (C.DIM, "R1-Y", C.RESET))
    info("Giao diện: http://%s:%d" % (ip, WEB_PORT))

    # 1) xem trạng thái
    st = None
    try:
        st = http_json("http://%s:%d/api/setup" % (ip, WEB_PORT), timeout=8)
        w = st.get("wifi") or {}
        if w.get("connected"):
            ok("Loa đang ở WiFi: %s (IP %s)" % (w.get("ssid", "?"), w.get("ip", "?")))
        else:
            warn("Loa chưa vào WiFi nhà.")
        if st.get("hotspotOn"):
            info("Loa đang phát WiFi: %s" % st.get("hotspotSsid", "R1-Y"))
    except Exception:
        warn("Không gọi được loa ở %s — kiểm tra WiFi đã nối vào loa chưa." % ip)

    # 2) quét
    info("Đang quét WiFi xung quanh loa…")
    networks = []
    try:
        r = http_json("http://%s:%d/api/wifi/scan" % (ip, WEB_PORT), timeout=40)
        networks = r.get("networks") or []
    except Exception as e:
        warn("Không quét được: %s" % e)

    if networks:
        ok("Thấy %d WiFi:" % len(networks))
        for i, n in enumerate(networks, 1):
            lock = "khoá" if n.get("secured") else "mở"
            saved = " (đã lưu)" if n.get("saved") else ""
            print("    %2d. %-32s %s%s" % (i, n.get("ssid", "?"), lock, saved))
        sel = ask("Chọn số (Enter để nhập tay)", "1")
        ssid = ""
        try:
            idx = int(sel) - 1
            if 0 <= idx < len(networks):
                ssid = networks[idx].get("ssid", "")
        except ValueError:
            ssid = sel
        if not ssid:
            ssid = ask("Tên WiFi (SSID)")
    else:
        ssid = ask("Tên WiFi nhà (SSID)")

    if not ssid:
        die("Không có tên WiFi — huỷ.")

    passwd = ""
    for n in networks:
        if n.get("ssid") == ssid and not n.get("secured"):
            info("WiFi này không có mật khẩu.")
            break
    if not any(n.get("ssid") == ssid and not n.get("secured") for n in networks):
        passwd = ask("Mật khẩu WiFi (Enter nếu mở)")

    info("Đang yêu cầu loa kết nối (chờ tới ~30 giây)…")
    try:
        r = http_post_json("http://%s:%d/api/wifi/connect" % (ip, WEB_PORT),
                           {"ssid": ssid, "password": passwd}, timeout=60)
    except Exception as e:
        die("Không gửi được yêu cầu: %s" % e)

    if r.get("ok"):
        ok("Loa đã vào WiFi: %s" % ssid)
        if r.get("ip"):
            print("\n  %sGiao diện loa:%s %s%s%s"
                  % (C.BOLD, C.RESET, C.CYAN, r.get("webUrl"), C.RESET))
            info("Giờ loa chung mạng với máy — cài firmware:")
            print("     %spython3 tools/r1y.py install --ip %s%s"
                  % (C.BOLD, r.get("ip"), C.RESET))
    else:
        err(r.get("message") or "Không kết nối được")
        if "mật khẩu" in (r.get("message") or "").lower():
            if confirm("Thử quên mạng cũ rồi nối lại?", True):
                try:
                    http_post_json("http://%s:%d/api/wifi/forget" % (ip, WEB_PORT),
                                   {"ssid": ssid}, timeout=15)
                except Exception:
                    pass
                flow_wifi(args)


def flow_webinstall(args):
    """
    Cài/cập nhật firmware KHÔNG cần adb: nhờ chính loa tự tải APK từ GitHub
    và tự cài (loa có quyền root thì cài im lặng, không thì hiện trình cài đặt).
    Dùng được trên điện thoại khi chưa cài được android-tools.
    """
    ip = args.ip or ""
    if not ip:
        ip = "192.168.43.1"
    base = "http://%s:%d" % (ip, WEB_PORT)
    title("Cập nhật firmware qua web (không cần adb)")
    info("Loa: %s" % base)

    try:
        st = http_json(base + "/api/status", timeout=8)
    except Exception:
        die("Không mở được %s\n"
            "  • Điện thoại đã nối cùng WiFi với loa chưa?\n"
            "  • IP loa đúng chưa? (xem trong Cài đặt WiFi của điện thoại)\n"
            "  • Loa đang phát WiFi riêng thì dùng 192.168.43.1" % base)

    ok("Loa đang chạy %s v%s" % (st.get("app", "R1-Y"), st.get("version", "?")))

    info("Đang kiểm tra bản mới trên GitHub…")
    try:
        chk = http_json(base + "/api/update/check", timeout=40)
    except Exception as e:
        die("Loa không kiểm tra được cập nhật: %s" % explain_error(str(e)))

    if not chk.get("hasUpdate"):
        ok("Đã là bản mới nhất (v%s)." % chk.get("currentVersion", "?"))
        if not confirm("Cài lại (ghi đè) bản mới nhất?", False):
            return
    else:
        ok("Có bản mới: v%s (đang dùng v%s)"
           % (chk.get("latestTag", "?"), chk.get("currentVersion", "?")))
        notes = (chk.get("releaseNotes") or "").strip()
        if notes:
            print("  %s%s%s" % (C.DIM, notes[:400].replace("\n", "\n  "), C.RESET))

    if not confirm("Tải và cài ngay trên loa?", True):
        return

    info("Loa đang tải APK và cài (1-3 phút, đừng tắt loa)…")
    try:
        r = http_post_json(base + "/api/update/install", {}, timeout=600)
    except Exception as e:
        warn("Yêu cầu bị ngắt giữa chừng (loa có thể đang cài): %s" % explain_error(str(e)))
        r = {}

    if r.get("ok"):
        ok(r.get("message") or "Đã cài xong")
        info("Chờ loa khởi động lại rồi mở: %s" % base)
        if not confirm("Đợi loa lên lại và kiểm tra?", True):
            return
        for i in range(20):
            time.sleep(6)
            try:
                st2 = http_json(base + "/api/status", timeout=5)
                ok("Loa đã lên lại: v%s" % st2.get("version", "?"))
                info("Giao diện: %s" % base)
                return
            except Exception:
                sys.stdout.write(".")
                sys.stdout.flush()
        print()
        warn("Loa chưa phản hồi — mở lại %s sau 1 phút." % base)
    else:
        err(r.get("error") or "Loa chưa cài được. Thử lại hoặc dùng cách có adb.")


def flow_status(args):
    serial, ip = None, args.ip
    try:
        devs = list_devices() if shutil.which("adb") or ADB else []
        if devs:
            serial = devs[0]
            ip = ip or device_ip(serial)
    except Exception:
        pass
    show_status(ip, serial)


def flow_config(args):
    ip = args.ip
    if not ip:
        try:
            serial, ip = connect_device(None, interactive=False)
            ip = device_ip(serial, ip)
        except SystemExit:
            die("Cần IP loa: --ip 192.168.2.20")
    resolvers = json.loads(args.resolvers) if args.resolvers else None
    configure_loa(ip, resolvers=resolvers,
                  music_server=getattr(args, "music_server", None),
                  interactive=(resolvers is None and not args.yes),
                  ai_server=getattr(args, "ai_server", None),
                  ai_url=getattr(args, "ai_url", None),
                  ai_token=getattr(args, "ai_token", None),
                  audio_quality=getattr(args, "audio_quality", None))
    show_status(ip)


def flow_uninstall(args):
    serial, ip = connect_device(args.ip, interactive=not args.yes)
    title("Gỡ cài đặt / khôi phục")
    if not confirm("Gỡ %s và bật lại app Phicomm gốc?" % APP_NAME, True):
        return
    adb("-s", serial, "uninstall", PACKAGE, quiet=True)
    ok("Đã gỡ %s" % APP_NAME)
    for app in PHICOMM_APPS:
        adb("-s", serial, "shell", "pm", "unhide", app, quiet=True)
    ok("Đã bật lại app Phicomm gốc")
    warn("Loa nên được khởi động lại.")


def finish(ip, status_info):
    title("Xong!")
    if status_info:
        ok("Phiên bản trên loa: v%s" % status_info.get("version", "?"))
    if ip:
        print("\n  %sGiao diện loa:%s %shttp://%s:%d%s" % (C.BOLD, C.RESET, C.CYAN, ip, WEB_PORT, C.RESET))
    print("\n  Việc tiếp theo:")
    print("    • Mở giao diện trên để vào %sTrợ lý%s (kích hoạt Xiaozhi) và %sNhạc%s" % (C.BOLD, C.RESET, C.BOLD, C.RESET))
    print("    • Để trống %s[]%s = loa tự dò máy chủ YouTube công cộng" % (C.BOLD, C.RESET))
    print("    • Cập nhật sau này: %spython3 tools/r1y.py update%s" % (C.BOLD, C.RESET))
    print("    • Xem log: %sadb logcat | grep -E 'Resolver|WebServer'%s" % (C.DIM, C.RESET))
    print()


def menu():
    """Menu tương tác — chạy xong 1 việc thì quay lại menu."""
    banner()
    while True:
        try:
            _menu_once()
        except KeyboardInterrupt:
            print("\nTạm biệt!")
            return
        except SystemExit as e:
            # lỗi trong flow (die) → quay lại menu; người dùng chọn thoát (code 0) → thoát hẳn
            if e.code in (0, None):
                return
        except Exception as e:
            warn("Có lỗi: %s" % explain_error(str(e)))
        print()
        if not confirm("Quay lại menu chính?", True):
            print("Tạm biệt!")
            return


def _menu_once():
    print("  %sChọn việc cần làm:%s\n" % (C.BOLD, C.RESET))
    items = [
        ("1", "Cài mới từ đầu (A→Z) — tải APK từ GitHub rồi cài"),
        ("2", "Cập nhật firmware — so với bản mới nhất trên GitHub"),
        ("3", "Cài bản build trong máy (APK dev)"),
        ("3b", "Cập nhật qua web — không cần adb (loa tự tải + cài)"),
        ("4", "Cấu hình máy chủ YouTube cho loa"),
        ("5", "Kiểm tra trạng thái loa"),
        ("6", "Cấu hình WiFi nhà cho loa (quét + nối)"),
        ("7", "Bật phát WiFi (hotspot) từ loa để điện thoại kết nối"),
        ("8", "Gỡ cài đặt / khôi phục app gốc"),
        ("9", "Thoát"),
    ]
    for k, t in items:
        print("   %s%-2s%s %s" % (C.CYAN, k, C.RESET, t))
    print()
    choice = ask("Nhập số", "1")

    if choice == "1":
        flow_install(argparse.Namespace(ip=None, apk=None, source="github", resolvers=None,
                                        music_server=None, yes=False, pre=False,
                                        ai_server=None, ai_url=None, ai_token=None,
                                        audio_quality=None))
    elif choice == "2":
        flow_update(argparse.Namespace(ip=None, apk=None, yes=False, pre=False, force=False))
    elif choice == "3":
        flow_install(argparse.Namespace(ip=None, apk=None, source="local", resolvers=None,
                                        music_server=None, yes=False, pre=False,
                                        ai_server=None, ai_url=None, ai_token=None,
                                        audio_quality=None))
    elif choice in ("3b", "web"):
        flow_webinstall(argparse.Namespace(ip=None))
    elif choice == "4":
        args = argparse.Namespace(ip=None, resolvers=None, music_server=None, yes=False,
                                  ai_server=None, ai_url=None, ai_token=None, audio_quality=None)
        flow_config(args)
    elif choice == "5":
        flow_status(argparse.Namespace(ip=None))
    elif choice == "6":
        flow_wifi(argparse.Namespace(ip=None))
    elif choice == "7":
        flow_hotspot(argparse.Namespace(ip=None, yes=False, on=None, ssid=None, password=None))
    elif choice == "8":
        flow_uninstall(argparse.Namespace(ip=None, yes=False))
    elif choice in ("9", "q", "quit", "exit", ""):
        print("Tạm biệt!")
        sys.exit(0)
    else:
        warn("Không có mục '%s' — chọn số trong danh sách." % choice)


def main():
    global ADB
    ap = argparse.ArgumentParser(
        description="Tool cài đặt / cập nhật firmware %s cho loa Phicomm R1." % APP_NAME,
        epilog="Nguồn firmware: github.com/%s" % REPO)
    ap.add_argument("command", nargs="?", default="menu",
                    choices=["install", "update", "status", "config", "uninstall",
                             "hotspot", "wifi", "webinstall", "menu"],
                    help="Việc cần làm (bỏ trống = mở menu)")
    ap.add_argument("--ip", help="IP loa trong WiFi, ví dụ 192.168.2.20")
    ap.add_argument("--apk", help="Dùng file APK chỉ định thay vì tải GitHub")
    ap.add_argument("--source", choices=["github", "debug", "local"], default="github",
                    help="Nguồn APK: github (release) hoặc local (build trong máy)")
    ap.add_argument("--resolvers", help='Mảng JSON resolver, ví dụ \'[{"type":"custom","url":"http://x:8091"}]\'')
    ap.add_argument("--music-server", dest="music_server", help="URL máy chủ nhạc cuối cùng")
    ap.add_argument("--ai-server", dest="ai_server",
                    choices=["xiaozhi", "xiaozhi_self", "custom"],
                    help="Máy chủ AI: xiaozhi (mặc định xiaozhi.me) | xiaozhi_self | custom")
    ap.add_argument("--ai-url", dest="ai_url", help="URL WebSocket của máy chủ AI")
    ap.add_argument("--ai-token", dest="ai_token", help="Token/API key cho máy chủ AI tự dựng")
    ap.add_argument("--audio-quality", dest="audio_quality", choices=["high", "compat"],
                    help="high = Opus chất lượng cao (mặc định), compat = AAC/m4a")
    ap.add_argument("--on", dest="on", action="store_true", default=None,
                    help="(hotspot) bật phát WiFi")
    ap.add_argument("--off", dest="on", action="store_false",
                    help="(hotspot) tắt phát WiFi")
    ap.add_argument("--ssid", help="(hotspot) tên WiFi, mặc định R1-Y")
    ap.add_argument("--password", help="(hotspot) mật khẩu WiFi, mặc định r1y12345678")
    ap.add_argument("--pre", action="store_true", help="Cho phép bản thử nghiệm (prerelease)")
    ap.add_argument("--force", action="store_true", help="Cài đè dù đã ở bản mới nhất")
    ap.add_argument("--reboot", dest="reboot", action="store_true", default=None,
                    help="Khởi động lại loa sau khi cài")
    ap.add_argument("--no-reboot", dest="reboot", action="store_false",
                    help="Không khởi động lại loa sau khi cài")
    ap.add_argument("--yes", "-y", action="store_true", help="Không hỏi lại (dùng cho script/CI)")
    ap.add_argument("--adb", help="Đường dẫn adb nếu không có trong PATH")
    args = ap.parse_args()

    if args.adb:
        ADB = args.adb

    if IS_WIN and hasattr(sys.stdout, "reconfigure"):
        try:
            sys.stdout.reconfigure(encoding="utf-8")
        except Exception:
            pass

    try:
        cmd = args.command
        # Tự chuẩn bị môi trường (cài thư viện thiếu) — không hiện lỗi khó hiểu
        if cmd in ("install", "update", "status", "hotspot", "uninstall") or cmd is None:
            try:
                ensure_env()
            except Exception:
                pass
        if cmd is None or cmd == "menu":
            menu()
        elif cmd == "install":
            flow_install(args)
        elif cmd == "update":
            flow_update(args)
        elif cmd == "status":
            flow_status(args)
        elif cmd == "config":
            flow_config(args)
        elif cmd == "hotspot":
            flow_hotspot(args)
        elif cmd == "wifi":
            flow_wifi(args)
        elif cmd == "webinstall":
            flow_webinstall(args)
        elif cmd == "uninstall":
            flow_uninstall(args)
    except RuntimeError as e:
        die(explain_error(str(e)))
    except OSError as e:
        die(explain_error(str(e)))
    except KeyboardInterrupt:
        print()
        die("Đã huỷ.")


if __name__ == "__main__":
    main()
