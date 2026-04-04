@echo off
setlocal

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

echo ==============================
echo BUILD SUCCESS
echo ==============================
pause