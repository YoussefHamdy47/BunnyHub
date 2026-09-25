@echo off
setlocal EnableDelayedExpansion
title BunnyNexus Telemetry Engine

set DELAY=5
set MAX_DELAY=60
set ATTEMPT=0

:CHECK_INTERNET
cls
echo  BunnyNexus V2 - Waiting for Internet Connection...
echo.

:: Check DNS resolution by curling discord.com 
curl -s --max-time 5 --head https://discord.com >nul 2>&1

if errorlevel 1 (
    set /a ATTEMPT+=1
    echo  Attempt #!ATTEMPT! - No connection yet.
    echo  Retrying in %DELAY% seconds...
    echo.

    if %DELAY% GEQ %MAX_DELAY% (
        echo  Max wait time reached
        echo  Press any key to reset and try again...
        pause >nul
        set DELAY=5
        set ATTEMPT=0
        goto CHECK_INTERNET
    )

    timeout /t %DELAY% >nul
    set /a DELAY+=5
    goto CHECK_INTERNET
)

cls
echo  Discord reachable. Initializing Telemetry Engine...
cd /d "%~dp0"

java -jar target\BunnyHub-4.00.jar

pause
