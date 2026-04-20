@echo off
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
    powershell -Command "Add-Type -AssemblyName PresentationFramework; [System.Windows.MessageBox]::Show('Unregistration failed or was cancelled. No changes made.', 'Minecraft Virtualcam', 'OK', 'Error')"
    exit /b 1
)

if exist "%MARKER%" del /f "%MARKER%" 2>nul
if exist "%DLL%" del /f "%DLL%" 2>nul
if exist "%INSTALLER%" del /f "%INSTALLER%" 2>nul

powershell -Command "Add-Type -AssemblyName PresentationFramework; [System.Windows.MessageBox]::Show('Virtual camera unregistered. Please restart your computer to apply the changes.', 'Minecraft Virtualcam', 'OK', 'Information')"
