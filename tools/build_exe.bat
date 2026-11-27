@echo off
REM ==========================================================
REM  R1-Y — Đóng gói tool thành MỘT file .exe (Windows)
REM  Chạy:  build_exe.bat
REM  Kết quả: dist\R1Y-Tool.exe
REM ==========================================================
setlocal
cd /d "%~dp0"

echo.
echo === R1-Y - Build tool thanh file .exe ===
echo.

where python >nul 2>&1
if errorlevel 1 (
    echo [LOI] Chua co Python. Tai tai: https://www.python.org/downloads/
    echo        Nho tick "Add Python to PATH" khi cai.
    pause
    exit /b 1
)

echo [1/3] Cai PyInstaller...
python -m pip install --upgrade pip >nul 2>&1
python -m pip install pyinstaller >nul 2>&1
if errorlevel 1 (
    echo [LOI] Khong cai duoc pyinstaller. Thu chay lai bang quyen Admin.
    pause
    exit /b 1
)

echo [2/3] Dong goi (mat 1-2 phut)...
python -m PyInstaller tools\r1y.spec --noconfirm --clean >build_exe.log 2>&1
if errorlevel 1 (
    echo [LOI] Dong goi that bai. Xem file build_exe.log
    pause
    exit /b 1
)

echo [3/3] Xong!
if exist dist\R1Y-Tool.exe (
    echo    File: dist\R1Y-Tool.exe
    for %%A in (dist\R1Y-Tool.exe) do echo    Kich thuoc: %%~zA bytes
    echo.
    echo    Bam dup vao file do la menu hien ra, khong can go lenh.
) else (
    echo    [CANH BAO] Khong thay dist\R1Y-Tool.exe - xem build_exe.log
)
echo.
pause
