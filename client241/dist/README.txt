GadScape build 241 TEST client
==============================

This is RuneLite 1.13.3 (the official RuneLite for Old School build 241) with one file changed:
the game client (injected-client) is patched so it uses the GadScape TEST server's login key and
accepts any server address. It is for testing the build-241 server only.

Server address: put it on the first line of server.txt (default 127.0.0.1 = this computer).
The game connects to that address on port 43594.

Start: run.bat (Windows) or ./run.sh (Linux). Java 17 or newer is needed (the .exe build has it inside).

Your normal RuneLite and OSRS are not touched: this client keeps its own settings and game cache in
  Windows: %LOCALAPPDATA%\GadScape241Test\home\.runelite
  Linux:   ~/.gadscape-241-test/home/.runelite
Logs: that .runelite\logs\client.log
