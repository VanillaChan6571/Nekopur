@echo off
setlocal EnableExtensions

rem ==========================================================
rem Java configuration
rem ==========================================================
set "JAVA_HOME=C:\Java\jdk-25.0.1"
set "PATH=%JAVA_HOME%\bin;%PATH%"

rem ==========================================================
rem Paths
rem ==========================================================
set "PROJECT_DIR=%~dp0"
set "GRADLEW=%PROJECT_DIR%gradlew.bat"
set "LOGFILE=%PROJECT_DIR%applyAllPatches.log"

rem ==========================================================
rem Init log
rem ==========================================================
if exist "%LOGFILE%" del "%LOGFILE%"

echo ========================================================== >> "%LOGFILE%"
echo Starting applyAllPatches at %date% %time% >> "%LOGFILE%"
echo JAVA_HOME=%JAVA_HOME% >> "%LOGFILE%"
echo GRADLEW=%GRADLEW% >> "%LOGFILE%"
echo PROJECT_DIR=%PROJECT_DIR% >> "%LOGFILE%"
echo ========================================================== >> "%LOGFILE%"

echo.
echo === Starting applyAllPatches ===
echo.

rem ==========================================================
rem Run Gradle
rem ==========================================================
pushd "%PROJECT_DIR%"
call "%GRADLEW%" applyAllPatches >> "%LOGFILE%" 2>&1
popd

set "EXITCODE=%ERRORLEVEL%"

echo.
echo === applyAllPatches finished with exit code %EXITCODE% ===
echo.

if NOT "%EXITCODE%"=="0" goto :failed

rem ==========================================================
rem Detect warnings in output
rem ==========================================================
set "HAS_WARNINGS=0"
findstr /I /C:"WARNING" "%LOGFILE%" >nul && set "HAS_WARNINGS=1"

if "%HAS_WARNINGS%"=="1" (
  echo WARNINGS detected during applyAllPatches >> "%LOGFILE%"
  echo applyAllPatches completed successfully. [There are some warnings. Check logs to see the warnings]
) else (
  echo applyAllPatches completed successfully. >> "%LOGFILE%"
  echo applyAllPatches completed successfully.
)

goto :end

:failed
echo applyAllPatches failed with exit code %EXITCODE% >> "%LOGFILE%"
echo applyAllPatches failed with exit code %EXITCODE%

:end
echo.
echo Log saved to: %LOGFILE%
pause
