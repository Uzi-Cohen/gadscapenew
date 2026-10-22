package net.runelite.client.plugins.gadscapelogin;

import java.awt.Font;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.InputStream;
import javax.imageio.ImageIO;
import javax.inject.Inject;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.events.GameStateChanged;
import net.runelite.api.events.GameTick;
import net.runelite.client.RuneLite;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.callback.GadScapePaintHook;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.input.KeyManager;
import net.runelite.client.input.MouseManager;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * GadScape's login screen: full-window design, Remember me, saved accounts with face pictures, error messages.
 * Ported from the GadScape 211 client to stock RuneLite 1.13.3: it is loaded as a core plugin from
 * gadscape-plugins.jar (RuneLite scans net.runelite.client.plugins on its classpath), draws through the paint hook
 * that client241/patcher adds to RuneLite's Hooks.draw ({@link GadScapePaintHook}), and drives the game's login
 * through {@link LoginAccess}. If either is missing it does nothing and the game's own login screen shows.
 * Turning the plugin off in RuneLite's plugin list also brings back the game's own login screen.
 */
@PluginDescriptor(
	name = "GadScape Login",
	description = "GadScape's login screen: saved accounts with faces, Remember me",
	tags = {"gadscape", "login", "accounts"}
)
public class GadScapeLoginPlugin extends Plugin
{
	private static final Logger log = LoggerFactory.getLogger(GadScapeLoginPlugin.class);

	@Inject
	private Client client;
	@Inject
	private ClientThread clientThread;
	@Inject
	private MouseManager mouseManager;
	@Inject
	private KeyManager keyManager;

	private LoginScreen screen;
	private HeadCapture faces;

	@Override
	protected void startUp()
	{
		LoginAccess access = LoginAccess.load(client.getClass().getClassLoader());
		if (access == null)
		{
			return;
		}
		File heads = new File(RuneLite.RUNELITE_DIR, "gadscape-heads");
		faces = new HeadCapture(client, heads);
		screen = new LoginScreen(client, clientThread, access, new SavedAccounts(RuneLite.RUNELITE_DIR), image("background_hd.jpg"),
			image("shield.png"), heads, font("CinzelDecorative-Bold.ttf"), font("Cinzel.ttf"));
		// first in line, so the game's own login screen underneath never sees our clicks
		mouseManager.registerMouseListener(0, screen);
		keyManager.registerKeyListener(screen);
		GadScapePaintHook.set(screen::paint);
		clientThread.invoke(() -> client.setShouldRenderLoginScreenFire(false));
		log.info("GadScape login screen on");
	}

	@Override
	protected void shutDown()
	{
		GadScapePaintHook.set(null);
		if (screen != null)
		{
			mouseManager.unregisterMouseListener(screen);
			keyManager.unregisterKeyListener(screen);
			clientThread.invoke(() -> client.setShouldRenderLoginScreenFire(true));
		}
		screen = null;
		faces = null;
	}

	@Subscribe
	public void onGameStateChanged(GameStateChanged e)
	{
		if (screen == null)
		{
			return;
		}
		if (e.getGameState() == GameState.LOGGED_IN)
		{
			String user = screen.lastAttemptUser();
			screen.onLoggedIn();
			if (user != null)
			{
				faces.start(user);
			}
		}
		else if (e.getGameState() == GameState.LOGIN_SCREEN)
		{
			client.setShouldRenderLoginScreenFire(false);
			faces.stop();
		}
	}

	@Subscribe
	public void onGameTick(GameTick e)
	{
		if (faces != null)
		{
			faces.onGameTick();
		}
	}

	private static BufferedImage image(String name)
	{
		try (InputStream in = GadScapeLoginPlugin.class.getResourceAsStream(name))
		{
			return in == null ? null : ImageIO.read(in);
		}
		catch (Exception e)
		{
			return null;
		}
	}

	private static Font font(String name)
	{
		try (InputStream in = GadScapeLoginPlugin.class.getResourceAsStream(name))
		{
			return in == null ? null : Font.createFont(Font.TRUETYPE_FONT, in);
		}
		catch (Exception e)
		{
			return null;
		}
	}
}
