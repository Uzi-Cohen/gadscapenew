@echo off
rem @TITLE@ client for Windows. Needs Java 17 or newer: java\ next to this file, or java on PATH.
rem Server: first line of server.txt next to this file (default @DEFAULT_HOST@).
rem RuneLite settings and the game cache go to %LOCALAPPDATA%\@WIN_DIR@\home, never to your normal RuneLite.
rem This window shows the client log while the game runs; if the client stops with an error, it stays open.
setlocal
cd /d "%~dp0"
set JAVA=java
if exist "%~dp0java\bin\java.exe" set JAVA="%~dp0java\bin\java.exe"
set "GSHOME=%LOCALAPPDATA%\@WIN_DIR@\home"
if not exist "%GSHOME%" mkdir "%GSHOME%"
%JAVA% @jvm-args.txt "-Duser.home=%GSHOME%" -cp "lib\*;lib-win\*" gadscape.launcher.Launcher --host-file server.txt --home "%GSHOME%" %*
if errorlevel 1 (
  echo.
  echo The @TITLE@ client stopped with an error ^(see above^).
  pause
)
endlocal
