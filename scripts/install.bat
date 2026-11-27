@echo off
REM Cai firmware R1-Y cho loa Phicomm R1 qua cung WiFi (ADB over WiFi).
REM Dung: install.bat [IP_LOA]
REM Vi du: install.bat 192.168.2.20
REM Can: adb (Android SDK Platform Tools), file APK trong app\app\build\outputs\apk\release\

setlocal enabledelayedexpansion

set PACKAGE_NAME=com.bachdathan.r1y
set APK_PATH=app\app\build\outputs\apk\release\app-release.apk
set R1_IP=%1

echo === Cai R1-Y qua WiFi ===
echo.

where adb >nul 2>nul
if %errorlevel% neq 0 (
    echo [ERROR] Chua co adb. Cai Android SDK Platform Tools truoc.
    exit /b 1
)
echo [INFO] Da co adb

if not "%R1_IP%"=="" (
    echo [INFO] Ket noi loa %R1_IP% qua WiFi...
    adb connect %R1_IP%:5555
    timeout /t 2 /nobreak >nul
)

adb devices | findstr /C:"device" >nul
if %errorlevel% neq 0 (
    echo [ERROR] Chua thay loa. Bat ADB qua WiFi tren loa roi chay lai:
    echo         adb connect ^<IP_LOA^>:5555
    exit /b 1
)
echo [INFO] Da thay loa

if not exist "%APK_PATH%" (
    echo [ERROR] Chua co APK tai %APK_PATH%
    echo         Tai tu GitHub Releases (BachDaThan/R1-Y) hoac build:
    echo         cd app ^& gradlew.bat assembleRelease
    exit /b 1
)
echo [INFO] APK: %APK_PATH%

echo.
echo === 1/6 Tat app Phicomm goc (giu lai, khong xoa) ===
adb shell pm hide com.phicomm.speaker.player 2>nul
adb shell pm hide com.phicomm.speaker.device 2>nul
adb shell pm hide com.phicomm.speaker.airskill 2>nul
adb shell pm hide com.phicomm.speaker.exceptionreporter 2>nul

echo.
echo === 2/6 Go ban cu (neu co) ===
adb shell pm list packages | findstr /C:"%PACKAGE_NAME%" >nul
if %errorlevel% equ 0 (
    adb uninstall %PACKAGE_NAME%
) else (
    echo [INFO] Chua co ban cu.
)

echo.
echo === 3/6 Cai APK ===
adb push "%APK_PATH%" /data/local/tmp/lilyr1.apk
adb shell pm install -t -r /data/local/tmp/lilyr1.apk

echo.
echo === 4/6 Cap quyen ===
adb shell pm grant %PACKAGE_NAME% android.permission.RECORD_AUDIO 2>nul
adb shell pm grant %PACKAGE_NAME% android.permission.WRITE_EXTERNAL_STORAGE 2>nul
adb shell pm grant %PACKAGE_NAME% android.permission.READ_EXTERNAL_STORAGE 2>nul

echo.
echo === 5/6 Kiem tra root (de dieu khien LED) ===
adb shell su -c "id" 2>nul | findstr /C:"uid=0" >nul
if %errorlevel% equ 0 (
    echo [INFO] Co root - LED se hoat dong.
) else (
    echo [WARN] Chua root - LED se tat, cac tinh nang khac van chay.
)

echo.
echo === 6/6 Khoi dong + tu chay khi bat loa ===
adb shell am start -n %PACKAGE_NAME%/.ui.MainActivity
timeout /t 2 /nobreak >nul
adb shell pm enable %PACKAGE_NAME%/.receiver.BootReceiver 2>nul
adb shell dumpsys deviceidle whitelist +%PACKAGE_NAME% 2>nul
adb shell rm /data/local/tmp/lilyr1.apk 2>nul

echo.
if not "%REBOOT%"=="no" (
    set /p ANS="Khoi dong lai loa ngay? (C/khong) [C]: "
    if "!ANS!"=="" set ANS=C
    if /I "!ANS!"=="C" (
        echo [INFO] Dang khoi dong lai loa, cho khoang 60 giay...
        adb reboot
        timeout /t 45 /nobreak >nul
        adb wait-for-device
        echo [INFO] Loa da len lai.
    )
)

echo.
echo === Xong! ===
echo Mo giao dien loa: http://^<IP_LOA^>:8088
echo Kiem tra log: adb logcat ^| findstr Xiaozhi

endlocal
