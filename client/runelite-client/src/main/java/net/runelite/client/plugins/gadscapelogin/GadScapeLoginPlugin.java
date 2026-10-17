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
import net.runelite.client.callback.LoginScreenPainter;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.input.KeyManager;
import net.runelite.client.input.MouseManager;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;

/**
 * GadScape's login screen: full-window design, Remember me, saved accounts with face pictures, error messages.
 * Always on and hidden from the plugin list, so every build has it.
 */
@PluginDescriptor(name = "GadScape Login", hidden = true)
public class GadScapeLoginPlugin extends Plugin
{
	@Inject
	private Client client;
	@Inject
	private MouseManager mouseManager;
	@Inject
	private KeyManager keyManager;

	private LoginScreen screen;
	private HeadCapture faces;

	@Override
	protected void startUp()
	{
		File heads = new File(RuneLite.RUNELITE_DIR, "gadscape-heads");
		faces = new HeadCapture(client, heads);
		screen = new LoginScreen(client, new SavedAccounts(RuneLite.RUNELITE_DIR), image("background_hd.jpg"), image("shield.png"),
			heads, font("CinzelDecorative-Bold.ttf"), font("Cinzel.ttf"));
		// first in line, so the game's own login screen underneath never sees our clicks
		mouseManager.registerMouseListener(0, screen);
		keyManager.registerKeyListener(screen);
		LoginScreenPainter.set(screen::paint);
		client.setShouldRenderLoginScreenFire(false);
	}

	@Override
	protected void shutDown()
	{
		LoginScreenPainter.set(null);
		mouseManager.unregisterMouseListener(screen);
		keyManager.unregisterKeyListener(screen);
		client.setShouldRenderLoginScreenFire(true);
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
