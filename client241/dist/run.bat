@echo off
rem GadScape build 241 TEST client for Windows. Needs Java 17 or newer: java\ next to this file, or java on PATH.
rem Server host: first line of server.txt next to this file (default 127.0.0.1).
rem RuneLite settings and the game cache go to %LOCALAPPDATA%\GadScape241Test\home, never to your normal RuneLite.
rem This window shows the client log while the game runs; if the client stops with an error, it stays open.
setlocal
cd /d "%~dp0"
set JAVA=java
if exist "%~dp0java\bin\java.exe" set JAVA="%~dp0java\bin\java.exe"
set "GSHOME=%LOCALAPPDATA%\GadScape241Test\home"
if not exist "%GSHOME%" mkdir "%GSHOME%"
%JAVA% @jvm-args.txt "-Duser.home=%GSHOME%" -cp "lib\*;lib-win\*" gadscape.launcher.Launcher --host-file server.txt --home "%GSHOME%" %*
if errorlevel 1 (
  echo.
  echo The GadScape 241 test client stopped with an error ^(see above^).
  pause
)
endlocal
