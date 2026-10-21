GadScape build 241 TEST client
==============================

This is RuneLite 1.13.3 (the official RuneLite for Old School build 241) with one file changed:
the game client (injected-client) is patched so it uses the GadScape TEST server's login key and
accepts any server address. It is for testing the build-241 server only. Not affiliated with Jagex or RuneLite.

Server address: put it on the first line of server.txt (default 127.0.0.1 = this computer).
A host name or IP address; "http://", a path and "# comments" are ignored.
The game always connects to that address on port 43594 (a different port in the file is ignored).

Start: run.bat (Windows) or ./run.sh (Linux). Java 17 or newer is needed (the .exe build has it inside).
run.bat keeps a window open with the client log; if the client stops with an error, the window stays open.

Your normal RuneLite and OSRS are not touched: this client keeps its own settings and game cache in
  Windows: %LOCALAPPDATA%\GadScape241Test\home\.runelite
  Linux:   ~/.gadscape-241-test/home/.runelite
Logs: that .runelite\logs\client.log

What happens on this test server stays out of public services: the launcher blocks the OSRS Wiki
crowdsourcing and XP tracker uploads and RuneLite's chat-command (!kc, !pb, ...) and loot uploads,
and turns RuneLite's Discord integration off.

Licenses: see the licenses folder (licenses/THIRD-PARTY.txt lists every part and its license).
