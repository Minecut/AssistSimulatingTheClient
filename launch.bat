@echo off
setlocal enabledelayedexpansion
title Eagle - Minecraft 1.21.4

rem ---------------------------------------------------------------------------
rem  Eagle one-click launcher
rem
rem    launch.bat        install the jar into .minecraft\mods, then open the
rem                      official launcher
rem    launch.bat dev    launch the game through the dev environment instead
rem
rem  Set EAGLE_MC to point at a custom .minecraft folder.
rem
rem  Everything here is plain ASCII on purpose: batch files are read by byte
rem  offset, and mixing UTF-8 text with a "chcp" call makes cmd lose its place
rem  mid-file and split lines apart. ASCII sidesteps the whole problem.
rem ---------------------------------------------------------------------------

set "ROOT=%~dp0"
if "%ROOT:~-1%"=="\" set "ROOT=%ROOT:~0,-1%"

set "MC=%APPDATA%\.minecraft"
if defined EAGLE_MC set "MC=%EAGLE_MC%"
set "MODS=%MC%\mods"

echo ============================================================
echo   Eagle - Fabric 1.21.4
echo ============================================================
echo.

if /i "%~1"=="dev" goto :dev

rem ---------------------------------------------------------------- 1. find jar
set "JAR="
if exist "%ROOT%\build\libs" (
    for /f "delims=" %%f in ('dir /b /o-n "%ROOT%\build\libs\eagle-*.jar" 2^>nul ^| findstr /v /i "sources"') do (
        if not defined JAR set "JAR=%ROOT%\build\libs\%%f"
    )
)

if not defined JAR (
    echo [1/3] no jar found, building...
    call "%ROOT%\gradlew.bat" build --console=plain
    if errorlevel 1 goto :buildfail
    for /f "delims=" %%f in ('dir /b /o-n "%ROOT%\build\libs\eagle-*.jar" 2^>nul ^| findstr /v /i "sources"') do (
        if not defined JAR set "JAR=%ROOT%\build\libs\%%f"
    )
)

if not defined JAR goto :buildfail
echo [1/3] jar: %JAR%

rem ------------------------------------------------------------- 2. install it
if not exist "%MC%" (
    echo [X] .minecraft not found:
    echo     %MC%
    echo     Run the official launcher once, or set EAGLE_MC.
    goto :fail
)

if not exist "%MODS%" mkdir "%MODS%" >nul 2>&1

del /q "%MODS%\eagle-*.jar" >nul 2>&1
copy /y "%JAR%" "%MODS%\" >nul
if errorlevel 1 (
    echo [X] copy failed - the mods folder may be locked or read-only.
    goto :fail
)
echo [2/3] installed to %MODS%

if not exist "%MODS%\fabric-api-*.jar" (
    echo     WARN: no Fabric API in mods - drop in the 1.21.4 build first
)

if exist "%MC%\launcher_profiles.json" (
    findstr /i "fabric" "%MC%\launcher_profiles.json" >nul 2>&1
    if errorlevel 1 echo     WARN: no Fabric profile in the launcher - install Fabric Loader 1.21.4
)

rem -------------------------------------------------------------- 3. start it
set "LAUNCHER="
for %%p in (
    "%ProgramFiles%\Minecraft Launcher\MinecraftLauncher.exe"
    "%ProgramFiles(x86)%\Minecraft Launcher\MinecraftLauncher.exe"
    "%LOCALAPPDATA%\Programs\Minecraft Launcher\MinecraftLauncher.exe"
) do (
    if exist %%p if not defined LAUNCHER set "LAUNCHER=%%~p"
)

if defined LAUNCHER (
    echo [3/3] launcher: !LAUNCHER!
    start "" "!LAUNCHER!"
) else (
    echo [3/3] launcher not found at a known path, using minecraft:// instead
    rem explorer always hands off and returns; "start" can block if nothing handles the scheme
    explorer "minecraft://"
)

echo.
echo Pick the Fabric 1.21.4 profile and press Play.
echo In game: Right Shift opens the GUI. Hotkeys are listed in README.md.
echo.
pause
exit /b 0

:dev
echo [dev] launching through the dev environment...
call "%ROOT%\gradlew.bat" runClient --console=plain
exit /b %errorlevel%

:buildfail
echo.
echo [X] build failed - see the Gradle output above.
pause
exit /b 1

:fail
echo.
pause
exit /b 1
