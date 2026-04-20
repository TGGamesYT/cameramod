@echo off
setlocal

echo ==============================
echo Patching device name...
echo ==============================

powershell -Command "Get-ChildItem -Path 'softcam\src' -Recurse -Include *.cpp,*.h,*.rc | ForEach-Object { (Get-Content $_.FullName) -replace '\"Directshow Softcam\"', '\"Minecraft Virtualcam\"' | Set-Content $_.FullName }"

:: ==============================
:: Check for Windows SDK
:: ==============================
if not exist "C:\Program Files (x86)\Windows Kits\10\Include" (
    echo ERROR: Windows 10 SDK not found.
    echo Please install the Windows 10 SDK from https://learn.microsoft.com/en-us/windows/apps/windows-sdk/downloads
    pause
    exit /b 1
)

echo ==============================
echo Setting up Visual Studio...
echo ==============================

:: x64
call "C:\Program Files\Microsoft Visual Studio\2022\Community\VC\Auxiliary\Build\vcvarsall.bat" x64

echo ==============================
echo Building softcam DLL (x64)...
echo ==============================
msbuild softcam\src\softcam\softcam.vcxproj /p:Configuration=Release /p:Platform=x64

:: x86
call "C:\Program Files\Microsoft Visual Studio\2022\Community\VC\Auxiliary\Build\vcvarsall.bat" x86

echo ==============================
echo Building softcam DLL (x86)...
echo ==============================
msbuild softcam\src\softcam\softcam.vcxproj /p:Configuration=Release /p:Platform=Win32

:: ==============================
:: Prepare output folders
:: ==============================
mkdir src\main\resources\natives\windows-x64 2>nul
mkdir src\main\resources\natives\windows-x86 2>nul

:: ==============================
:: Copy files
:: ==============================

:: x64
copy /y softcam\src\softcam\x64\Release\softcam.dll src\main\resources\natives\windows-x64\

:: x86
copy /y softcam\src\softcam\Win32\Release\softcam.dll src\main\resources\natives\windows-x86\

:: ==============================
:: Generate uninstall_camera.bat
:: ==============================
echo Generating uninstall_camera.bat...

> src\main\resources\natives\uninstall_camera.bat (
echo @echo off
echo if "%%1"=="hidden" goto :main
echo powershell -WindowStyle Hidden -Command "Start-Process '%%~f0' 'hidden' -Wait"
echo exit /b
echo :main
echo setlocal
echo.
echo set "DIR=%%~dp0"
echo set "DLL=%%DIR%%softcam.dll"
echo set "INSTALLER=%%DIR%%softcam_installer.exe"
echo set "MARKER=%%DIR%%.softcam_registered"
echo.
echo powershell -Command "try { Start-Process regsvr32 -ArgumentList '/u /s \"%%DLL%%\"' -Verb runAs -Wait } catch { exit 1 }"
echo if %%errorlevel%% neq 0 ^(
echo     powershell -Command "Add-Type -AssemblyName PresentationFramework; [System.Windows.MessageBox]::Show('Unregistration failed or was cancelled. No changes made.', 'Minecraft Virtualcam', 'OK', 'Error')"
echo     exit /b 1
echo ^)
echo.
echo if exist "%%MARKER%%" del /f "%%MARKER%%" 2^>nul
echo if exist "%%DLL%%" del /f "%%DLL%%" 2^>nul
echo if exist "%%INSTALLER%%" del /f "%%INSTALLER%%" 2^>nul
echo.
echo powershell -Command "Add-Type -AssemblyName PresentationFramework; [System.Windows.MessageBox]::Show('Virtual camera unregistered. Please restart your computer to apply the changes.', 'Minecraft Virtualcam', 'OK', 'Information')"
)

echo ==============================
echo BUILD SUCCESS
echo ==============================
pause
