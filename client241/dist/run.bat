@echo off
rem GadScape build 241 TEST client for Windows. Needs Java 17 or newer: java\ next to this file, or javaw on PATH.
rem Server host: first line of server.txt next to this file (default 127.0.0.1).
rem RuneLite settings and the game cache go to %LOCALAPPDATA%\GadScape241Test\home, never to your normal RuneLite.
rem To see the log in this window, replace "start "" %JAVAW%" with "%JAVA%" below.
cd /d "%~dp0"
set JAVAW=javaw
set JAVA=java
if exist "%~dp0java\bin\javaw.exe" set JAVAW="%~dp0java\bin\javaw.exe"
if exist "%~dp0java\bin\java.exe" set JAVA="%~dp0java\bin\java.exe"
start "" %JAVAW% @jvm-args.txt -cp "lib\*;lib-win\*" gadscape.launcher.Launcher --host-file server.txt %*
