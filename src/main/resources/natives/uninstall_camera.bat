@echo off
REM Uninstalls the Minecraft Virtualcam COM driver and cleans up
REM Run this script as Administrator if unregister fails.
setlocal

set "DIR=%~dp0"
set "DLL=%DIR%softcam.dll"
set "INSTALLER=%DIR%softcam_installer.exe"
set "MARKER=%DIR%.softcam_registered"

echo Minecraft Virtualcam Uninstaller
echo =================================
echo.

if exist "%INSTALLER%" (
    echo Unregistering COM driver...
    "%INSTALLER%" unregister "%DLL%"
    if %errorlevel% neq 0 (
        echo WARNING: Installer unregister failed, trying regsvr32...
        regsvr32 /u /s "%DLL%"
    )
) else (
    echo Installer not found, trying regsvr32...
    regsvr32 /u /s "%DLL%"
)

echo.

if exist "%MARKER%" (
    del /f "%MARKER%" 2>nul
    echo Deleted registration marker.
)

if exist "%DLL%" (
    del /f "%DLL%" 2>nul
    if exist "%DLL%" (
        echo WARNING: Could not delete softcam.dll - it may still be in use.
    ) else (
        echo Deleted softcam.dll
    )
)

if exist "%INSTALLER%" (
    del /f "%INSTALLER%" 2>nul
    echo Deleted softcam_installer.exe
)

echo.
echo Uninstall complete.
echo You can delete this folder: %DIR%
pause
