@echo off
setlocal
cd /d "%~dp0"

where mvn >nul 2>nul
if errorlevel 1 (
    echo Maven was not found. Install Maven and make sure "mvn" is on PATH.
    pause
    exit /b 1
)

call mvn javafx:run
set "exitCode=%ERRORLEVEL%"
if not "%exitCode%"=="0" (
    echo.
    echo QR Attendance did not start successfully.
    pause
)
exit /b %exitCode%
