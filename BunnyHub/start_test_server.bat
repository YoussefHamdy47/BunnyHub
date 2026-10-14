@echo off
setlocal EnableDelayedExpansion
title BunnyNexus TEST instance
:: Starts a separate TEST instance for live verification (see "Test instance" in README.md).
:: Reads settings from .env.test (git-ignored, never published), never from .env.
:: Refuses to start when the test database is missing or is the production database GBF, or when the
:: test token is the production token. The Java side checks the database again (-Dbunny.test.instance=true).
:: Nothing here prints a token or any other value from the env files.

cd /d "%~dp0"
set "ENV_FILE=.env.test"

if not exist "%ENV_FILE%" (
    echo  REFUSED: %ENV_FILE% not found. Create it as described under "Test instance" in README.md.
    goto :REFUSED
)

set "TEST_DB="
set "TEST_TOKEN="
for /f "usebackq eol=# tokens=1,* delims==" %%A in ("%ENV_FILE%") do (
    if /I "%%A"=="MONGO_DB_NAME" set "TEST_DB=%%~B"
    if /I "%%A"=="TOKEN" set "TEST_TOKEN=%%~B"
)

if "!TEST_DB!"=="" (
    echo  REFUSED: MONGO_DB_NAME is not set in %ENV_FILE%. Without it the bot would use the production database GBF.
    goto :REFUSED
)
if /I "!TEST_DB!"=="GBF" (
    echo  REFUSED: MONGO_DB_NAME in %ENV_FILE% is GBF, the production database. Use a test name such as GBF_test.
    goto :REFUSED
)
if "!TEST_TOKEN!"=="" (
    echo  REFUSED: TOKEN is not set in %ENV_FILE%. Use the separate TEST bot application's token.
    goto :REFUSED
)

:: The test bot must be a different application: the production bot is in real servers.
if exist ".env" (
    set "PROD_TOKEN="
    for /f "usebackq eol=# tokens=1,* delims==" %%A in (".env") do (
        if /I "%%A"=="TOKEN" set "PROD_TOKEN=%%~B"
    )
    if "!PROD_TOKEN!"=="!TEST_TOKEN!" (
        echo  REFUSED: %ENV_FILE% uses the PRODUCTION bot token. Create a separate test application in the Discord developer portal.
        goto :REFUSED
    )
)

if not exist "target\BunnyHub-4.00.jar" (
    echo  REFUSED: target\BunnyHub-4.00.jar not found. Build it first with mvnw.cmd clean verify.
    goto :REFUSED
)

echo  Starting the TEST instance on database !TEST_DB!.
echo  Before approving anything, check the "Database: ... | TEST INSTANCE in: ..." line lists only your test server.
echo  Type "stop" and press Enter to shut it down.
echo.
java -Dbunny.env.file=%ENV_FILE% -Dbunny.test.instance=true -jar target\BunnyHub-4.00.jar
echo.
echo  Test instance stopped (exit code %ERRORLEVEL%).
pause
goto :EOF

:REFUSED
echo.
pause
exit /b 1
