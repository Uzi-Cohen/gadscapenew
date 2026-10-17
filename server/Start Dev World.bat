@echo off
setlocal

set "SERVER_DIR=C:\Users\Ezz_C\Downloads\RSPS-GADSCAPE-main\RSPS-GADSCAPE-main\gadscape-server-main"
set "CLIENT_EXE=C:\Users\Ezz_C\Downloads\RSPS-GADSCAPE-main\RSPS-GADSCAPE-main\gadscape-client-main\dist\appimage-out\GadScape\GadScape.exe"
set "JAVA_HOME=C:\Program Files\Eclipse Adoptium\jdk-21.0.12.101-hotspot"
set "PATH=%JAVA_HOME%\bin;%PATH%"

echo Starting GadScape dev server (localhost, no database, fully isolated from the live server)...
start "GadScape Dev Server" cmd /k "cd /d "%SERVER_DIR%" && gradlew.bat runDev"

echo Waiting for the server to come up...
timeout /t 25 /nobreak >nul

echo Launching client against 127.0.0.1...
start "" "%CLIENT_EXE%" --local

endlocal
