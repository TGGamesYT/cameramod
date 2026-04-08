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

echo ==============================
echo Building installer (x64)...
echo ==============================
call "C:\Program Files\Microsoft Visual Studio\2022\Community\VC\Auxiliary\Build\vcvarsall.bat" x64
msbuild softcam\examples\softcam_installer\softcam_installer.sln /p:Configuration=Release

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
copy /y softcam\examples\softcam_installer\x64\Release\softcam_installer.exe src\main\resources\natives\windows-x64\

:: x86
copy /y softcam\src\softcam\Win32\Release\softcam.dll src\main\resources\natives\windows-x86\
copy /y softcam\examples\softcam_installer\x64\Release\softcam_installer.exe src\main\resources\natives\windows-x86\

:: ==============================
:: Generate uninstall_camera.bat
:: ==============================
echo Generating uninstall_camera.bat...

> src\main\resources\natives\uninstall_camera.bat (
echo @echo off
echo REM Uninstalls the Minecraft Virtualcam COM driver and cleans up
echo REM Run this script as Administrator if unregister fails.
echo setlocal
echo.
echo set "DIR=%%~dp0"
echo set "DLL=%%DIR%%softcam.dll"
echo set "INSTALLER=%%DIR%%softcam_installer.exe"
echo set "MARKER=%%DIR%%.softcam_registered"
echo.
echo echo Minecraft Virtualcam Uninstaller
echo echo =================================
echo echo.
echo.
echo if exist "%%INSTALLER%%" ^(
echo     echo Unregistering COM driver...
echo     "%%INSTALLER%%" unregister "%%DLL%%"
echo     if %%errorlevel%% neq 0 ^(
echo         echo WARNING: Installer unregister failed, trying regsvr32...
echo         regsvr32 /u /s "%%DLL%%"
echo     ^)
echo ^) else ^(
echo     echo Installer not found, trying regsvr32...
echo     regsvr32 /u /s "%%DLL%%"
echo ^)
echo.
echo echo.
echo.
echo if exist "%%MARKER%%" ^(
echo     del /f "%%MARKER%%" 2^>nul
echo     echo Deleted registration marker.
echo ^)
echo.
echo if exist "%%DLL%%" ^(
echo     del /f "%%DLL%%" 2^>nul
echo     if exist "%%DLL%%" ^(
echo         echo WARNING: Could not delete softcam.dll - it may still be in use.
echo     ^) else ^(
echo         echo Deleted softcam.dll
echo     ^)
echo ^)
echo.
echo if exist "%%INSTALLER%%" ^(
echo     del /f "%%INSTALLER%%" 2^>nul
echo     echo Deleted softcam_installer.exe
echo ^)
echo.
echo echo.
echo echo Uninstall complete.
echo echo You can delete this folder: %%DIR%%
echo pause
)

echo ==============================
echo BUILD SUCCESS
echo ==============================
pause
