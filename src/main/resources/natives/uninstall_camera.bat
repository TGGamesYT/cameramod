@echo off
where powershell >nul 2>&1
if %errorlevel% neq 0 goto :no_powershell
if "%1"=="hidden" goto :main
powershell -WindowStyle Hidden -Command "Start-Process '%~f0' 'hidden' -Wait"
exit /b
:main
setlocal

set "DIR=%~dp0"
set "DLL=%DIR%softcam.dll"
set "INSTALLER=%DIR%softcam_installer.exe"
set "MARKER=%DIR%.softcam_registered"

powershell -Command "try { Start-Process regsvr32 -ArgumentList '/u /s \"%DLL%\"' -Verb runAs -Wait } catch { exit 1 }"
if %errorlevel% neq 0 (
    powershell -Command "Add-Type -AssemblyName PresentationFramework; [System.Windows.MessageBox]::Show('Unregistration failed or was cancelled. No changes made.', 'Minecraft Virtual Camera', 'OK', 'Error')"
    exit /b 1
)

if exist "%MARKER%" del /f "%MARKER%" 2>nul
if exist "%DLL%" del /f "%DLL%" 2>nul
if exist "%INSTALLER%" del /f "%INSTALLER%" 2>nul

powershell -Command "Add-Type -AssemblyName PresentationFramework; $r = [System.Windows.MessageBox]::Show('Virtual camera unregistered. Restart your computer to apply changes. Restart now?', 'Minecraft Virtual Camera', 'YesNo', 'Information'); if ($r -eq 'Yes') { Restart-Computer -Force }"
exit /b 0

:no_powershell
mshta vbscript:msgbox("This script requires PowerShell, which was not found on PATH. Please install PowerShell to uninstall the virtual camera.",16,"Minecraft Virtual Camera")(window.close)
exit /b 1
