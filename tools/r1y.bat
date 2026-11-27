@echo off
REM ==========================================================
REM  Lily R1 - Tool cai firmware cho loa Phicomm R1 (Windows)
REM  Chi can bam dup file nay. Nguon: github.com/BachDaThan/R1-Y
REM ==========================================================
setlocal
chcp 65001 >nul
title Lily R1 - Tool cai firmware

where python >nul 2>nul
if %errorlevel%==0 (
    python "%~dp0r1y.py" %*
    goto :end
)

where py >nul 2>nul
if %errorlevel%==0 (
    py -3 "%~dp0r1y.py" %*
    goto :end
)

echo.
echo [LOI] Chua co Python tren may.
echo.
echo  1) Tai Python tai: https://www.python.org/downloads/
echo     Khi cai nho tick "Add python.exe to PATH"
echo  2) Chay lai file nay.
echo.
pause
exit /b 1

:end
echo.
pause
