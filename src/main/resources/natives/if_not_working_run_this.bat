@echo off
setlocal

set "HAS_PS=0"
set "HAS_REG=0"

where powershell >nul 2>&1
if not errorlevel 1 set "HAS_PS=1"

where regsvr32 >nul 2>&1
if not errorlevel 1 set "HAS_REG=1"

if "%HAS_PS%"=="1" if "%HAS_REG%"=="1" goto :all_good
if "%HAS_PS%"=="0" if "%HAS_REG%"=="0" goto :none_found
if "%HAS_REG%"=="0" goto :no_regsvr

REM Only PowerShell missing — offer the manual_install workaround
mshta vbscript:msgbox("PowerShell is not on your PATH variable. If you don't want to deal with putting PowerShell back to PATH, right-click manual_install.bat and choose 'Run as administrator' to register the virtual camera without PowerShell.",48,"Minecraft Virtual Camera")(window.close)
exit /b 1

:all_good
mshta vbscript:msgbox("PowerShell and regsvr32 are both on your PATH variable. If the virtual camera still isn't working, right-click manual_install.bat and choose 'Run as administrator' to re-register the driver.",64,"Minecraft Virtual Camera")(window.close)
exit /b 0

:none_found
mshta vbscript:msgbox("Neither PowerShell nor regsvr32 are on your PATH variable. Both are required for the virtual camera. Please add them back via System Properties > Environment Variables. They normally live in C:\Windows\System32 and C:\Windows\System32\WindowsPowerShell\v1.0.",16,"Minecraft Virtual Camera")(window.close)
exit /b 1

:no_regsvr
mshta vbscript:msgbox("regsvr32 is not on your PATH variable. It is required to register the virtual camera driver. Please add C:\Windows\System32 back to PATH via System Properties > Environment Variables.",16,"Minecraft Virtual Camera")(window.close)
exit /b 1
