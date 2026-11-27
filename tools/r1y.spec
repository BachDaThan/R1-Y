# -*- mode: python ; coding: utf-8 -*-
"""
Đóng gói tool R1-Y thành MỘT file .exe (Windows) / binary (Linux/macOS).

Dùng:  python -m PyInstaller tools/r1y.spec --noconfirm
Kết quả: dist/R1Y-Tool.exe  (Windows)  hoặc  dist/R1Y-Tool  (Linux/macOS)

Chạy .exe không cần cài Python, không cần mở CMD gõ lệnh — chỉ bấm đúp là menu hiện ra.
"""
import os
import sys

# Thư mục chứa spec này = tools/ (SPECPATH do PyInstaller truyền vào, không có thì suy ra)
_here = globals().get('SPECPATH') or os.path.dirname(os.path.abspath(__file__))
if os.path.basename(_here) != 'tools':
    _here = os.path.join(_here, 'tools')
HERE = _here
SCRIPT = os.path.join(HERE, 'r1y.py')
if not os.path.isfile(SCRIPT):
    raise SystemExit('Không tìm thấy tools/r1y.py (đang tìm ở: %s)' % SCRIPT)

a = Analysis(
    [SCRIPT],
    pathex=[HERE],
    binaries=[],
    datas=[],
    hiddenimports=[
        'ssl', '_ssl', 'hashlib', 'http.client', 'urllib.request',
        'json', 'zipfile', 'subprocess', 'shutil', 'platform',
        'argparse', 'socket', 'threading',
    ],
    hookspath=[],
    hooksconfig={},
    runtime_hooks=[],
    # KHÔNG loại 'email' — urllib/http.client cần nó để đọc header mạng
    excludes=['tkinter', 'unittest', 'pydoc', 'test', 'pdb'],
    noarchive=False,
)

pyz = PYZ(a.pure)

exe = EXE(
    pyz,
    a.scripts,
    a.binaries,
    a.datas,
    [],
    name='R1Y-Tool',
    debug=False,
    bootloader_ignore_signals=False,
    strip=False,
    upx=True,
    upx_exclude=[],
    runtime_tmpdir=None,
    console=True,          # cần console để hiện menu
    disable_windowed_traceback=False,
    argv_emulation=False,
    target_arch=None,
    codesign_identity=None,
    entitlements_file=None,
)
