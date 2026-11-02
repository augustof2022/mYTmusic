@echo off
setlocal
cd /d "%~dp0"
title Segue signing setup
echo Segue signing setup
echo Keep this window open until setup reports success or an error.
echo.
if not exist "%~dp0setup-release-secrets.ps1" goto missing_files
if not exist "%~dp0signing.json" goto missing_files
if not exist "%~dp0segue-release.jks" goto missing_files
echo Starting the setup tool...
powershell.exe -NoProfile -ExecutionPolicy Bypass -File "%~dp0setup-release-secrets.ps1"
set "SETUP_EXIT=%ERRORLEVEL%"
echo.
if "%SETUP_EXIT%"=="0" (
    echo Setup finished successfully.
) else (
    echo Setup did not finish. Exit code: %SETUP_EXIT%
    echo Read SETUP-STATUS.txt in this folder for the next step.
    if not exist "%~dp0SETUP-STATUS.txt" echo Setup could not start. Check the error shown above.> "%~dp0SETUP-STATUS.txt"
)
goto finish
:missing_files
echo The setup files are not together.
echo Right-click the downloaded ZIP and choose Extract All.
echo Open the extracted folder, then run START-WINDOWS.cmd there.
echo Setup stopped: extract ALL files from the private ZIP into one folder.> "%~dp0SETUP-STATUS.txt"
:finish
echo.
if exist "%~dp0SETUP-STATUS.txt" start "" notepad.exe "%~dp0SETUP-STATUS.txt"
echo Press any key ONLY TO CLOSE this window.
pause >nul
endlocal
