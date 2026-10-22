@TITLE@
@TITLE_RULE@

The @TITLE@ client: RuneLite 1.13.3 (the official RuneLite for Old School build 241) with GadScape's
login screen, icons and settings. The game client (injected-client) is patched so it uses GadScape's login
key and accepts GadScape's server address. Not affiliated with Jagex or RuneLite.

Server address: the first line of server.txt (default @DEFAULT_HOST@).
A host name or IP address; "http://", a path and "# comments" are ignored.
The game always connects to that address on port 43594 (a different port in the file is ignored).

Start: run.bat (Windows) or ./run.sh (Linux). Java 17 or newer is needed (the .exe build has it inside).
run.bat keeps a window open with the client log; if the client stops with an error, the window stays open.

Your normal RuneLite and OSRS are not touched: this client keeps its own settings and game cache in
  Windows: %LOCALAPPDATA%\@WIN_DIR@\home\.runelite
  Linux:   ~/@UNIX_DIR@/home/.runelite
Logs: that .runelite\logs\client.log
Saved logins (Remember me) are in that .runelite folder too (gadscape-accounts.properties, passwords encrypted
for this computer and user).

RuneLite plugins that cannot work on GadScape start switched off (HiScore, Chat Commands, World Hopper,
Default World, XP Updater, OSRS Wiki Crowdsourcing, Discord, Login Screen); you can turn them back on in
RuneLite's plugin list. What happens on GadScape stays out of public services: the launcher blocks the OSRS
Wiki crowdsourcing and XP tracker uploads and RuneLite's chat-command (!kc, !pb, ...) and loot uploads.

Licenses: see the licenses folder (licenses/THIRD-PARTY.txt lists every part and its license).
