@echo off
setlocal

REM Admin check — net session is the standard zero-prompt admin test
net session >nul 2>&1
if errorlevel 1 goto :no_admin

set "DIR=%~dp0"
set "DLL=%DIR%softcam.dll"
set "MARKER=%DIR%.softcam_registered"

if not exist "%DLL%" goto :no_dll

regsvr32 /s "%DLL%"
if errorlevel 1 goto :reg_failed

REM Mark as registered so the in-game initializer skips its own prompt next launch
type nul > "%MARKER%"

mshta vbscript:msgbox("Virtual camera registered. Restart your computer to apply changes.",64,"Minecraft Virtual Camera")(window.close)
exit /b 0

:no_admin
mshta vbscript:msgbox("This script needs administrator privileges. Right-click manual_install.bat and choose 'Run as administrator'.",16,"Minecraft Virtual Camera")(window.close)
exit /b 1

:no_dll
mshta vbscript:msgbox("softcam.dll was not found next to this script. Launch Minecraft with the cameramod once so the DLL gets extracted, then run this script again.",16,"Minecraft Virtual Camera")(window.close)
exit /b 1

:reg_failed
mshta vbscript:msgbox("Registration failed. Make sure you are running as administrator and that regsvr32 is available.",16,"Minecraft Virtual Camera")(window.close)
exit /b 1
