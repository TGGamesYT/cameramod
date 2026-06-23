@echo off
setlocal

echo ==============================
echo Resetting softcam source to unmodified...
echo ==============================
:: Revert any prior device-name patch so re-running (e.g. after the name
:: changed) starts from the original "Directshow Softcam" strings. softcam is a
:: git submodule, so checkout restores its committed source. Best-effort.
git -C softcam checkout -- . 2>nul
if errorlevel 1 echo NOTE: could not reset softcam (git missing or submodule not initialized) - continuing.

echo ==============================
echo Patching device name...
echo ==============================

powershell -Command "Get-ChildItem -Path 'softcam\src' -Recurse -Include *.cpp,*.h,*.rc | ForEach-Object { (Get-Content $_.FullName) -replace '\"Directshow Softcam\"', '\"Minecraft Virtual Camera\"' | Set-Content $_.FullName }"

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
echo where powershell ^>nul 2^>^&1
echo if %%errorlevel%% neq 0 goto :no_powershell
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
echo     powershell -Command "Add-Type -AssemblyName PresentationFramework; [System.Windows.MessageBox]::Show('Unregistration failed or was cancelled. No changes made.', 'Minecraft Virtual Camera', 'OK', 'Error')"
echo     exit /b 1
echo ^)
echo.
echo if exist "%%MARKER%%" del /f "%%MARKER%%" 2^>nul
echo if exist "%%DLL%%" del /f "%%DLL%%" 2^>nul
echo if exist "%%INSTALLER%%" del /f "%%INSTALLER%%" 2^>nul
echo.
echo powershell -Command "Add-Type -AssemblyName PresentationFramework; $r = [System.Windows.MessageBox]::Show('Virtual camera unregistered. Restart your computer to apply changes. Restart now?', 'Minecraft Virtual Camera', 'YesNo', 'Information'); if ($r -eq 'Yes') { Restart-Computer -Force }"
echo exit /b 0
echo.
echo :no_powershell
echo mshta vbscript:msgbox^("This script requires PowerShell, which was not found on PATH. Please install PowerShell to uninstall the virtual camera.",16,"Minecraft Virtual Camera"^)^(window.close^)
echo exit /b 1
)

:: ==============================
:: Generate manual_install.bat (only if missing)
:: ==============================
if exist src\main\resources\natives\manual_install.bat goto :skip_manual_install
echo Generating manual_install.bat...

> src\main\resources\natives\manual_install.bat (
echo @echo off
echo setlocal
echo.
echo net session ^>nul 2^>^&1
echo if errorlevel 1 goto :no_admin
echo.
echo set "DIR=%%~dp0"
echo set "DLL=%%DIR%%softcam.dll"
echo set "MARKER=%%DIR%%.softcam_registered"
echo.
echo if not exist "%%DLL%%" goto :no_dll
echo.
echo regsvr32 /s "%%DLL%%"
echo if errorlevel 1 goto :reg_failed
echo.
echo type nul ^> "%%MARKER%%"
echo.
echo mshta vbscript:msgbox^("Virtual camera registered. Restart your computer to apply changes.",64,"Minecraft Virtual Camera"^)^(window.close^)
echo exit /b 0
echo.
echo :no_admin
echo mshta vbscript:msgbox^("This script needs administrator privileges. Right-click manual_install.bat and choose 'Run as administrator'.",16,"Minecraft Virtual Camera"^)^(window.close^)
echo exit /b 1
echo.
echo :no_dll
echo mshta vbscript:msgbox^("softcam.dll was not found next to this script. Launch Minecraft with the cameramod once so the DLL gets extracted, then run this script again.",16,"Minecraft Virtual Camera"^)^(window.close^)
echo exit /b 1
echo.
echo :reg_failed
echo mshta vbscript:msgbox^("Registration failed. Make sure you are running as administrator and that regsvr32 is available.",16,"Minecraft Virtual Camera"^)^(window.close^)
echo exit /b 1
)
:skip_manual_install

:: ==============================
:: Generate if_not_working_run_this.bat (only if missing)
:: ==============================
if exist src\main\resources\natives\if_not_working_run_this.bat goto :skip_if_not_working
echo Generating if_not_working_run_this.bat...

> src\main\resources\natives\if_not_working_run_this.bat (
echo @echo off
echo setlocal
echo.
echo set "HAS_PS=0"
echo set "HAS_REG=0"
echo.
echo where powershell ^>nul 2^>^&1
echo if not errorlevel 1 set "HAS_PS=1"
echo.
echo where regsvr32 ^>nul 2^>^&1
echo if not errorlevel 1 set "HAS_REG=1"
echo.
echo if "%%HAS_PS%%"=="1" if "%%HAS_REG%%"=="1" goto :all_good
echo if "%%HAS_PS%%"=="0" if "%%HAS_REG%%"=="0" goto :none_found
echo if "%%HAS_REG%%"=="0" goto :no_regsvr
echo.
echo mshta vbscript:msgbox^("PowerShell is not on your PATH variable. If you don't want to deal with putting PowerShell back to PATH, right-click manual_install.bat and choose 'Run as administrator' to register the virtual camera without PowerShell.",48,"Minecraft Virtual Camera"^)^(window.close^)
echo exit /b 1
echo.
echo :all_good
echo mshta vbscript:msgbox^("PowerShell and regsvr32 are both on your PATH variable. If the virtual camera still isn't working, right-click manual_install.bat and choose 'Run as administrator' to re-register the driver.",64,"Minecraft Virtual Camera"^)^(window.close^)
echo exit /b 0
echo.
echo :none_found
echo mshta vbscript:msgbox^("Neither PowerShell nor regsvr32 are on your PATH variable. Both are required for the virtual camera. Please add them back via System Properties ^> Environment Variables. They normally live in C:\Windows\System32 and C:\Windows\System32\WindowsPowerShell\v1.0.",16,"Minecraft Virtual Camera"^)^(window.close^)
echo exit /b 1
echo.
echo :no_regsvr
echo mshta vbscript:msgbox^("regsvr32 is not on your PATH variable. It is required to register the virtual camera driver. Please add C:\Windows\System32 back to PATH via System Properties ^> Environment Variables.",16,"Minecraft Virtual Camera"^)^(window.close^)
echo exit /b 1
)
:skip_if_not_working

echo ==============================
echo BUILD SUCCESS
echo ==============================
pause
